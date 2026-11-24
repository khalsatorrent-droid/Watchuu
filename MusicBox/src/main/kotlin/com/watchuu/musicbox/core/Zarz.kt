package com.watchuu.musicbox.core

import android.util.Base64
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * ZARZ signed session, ported from Mornye's host implementation
 * (Mornye/rust_backend/crates/extensions/src/signed_session/{protocol,exchange,fetch}.rs)
 * and the contract declared by Mornye-Extension's manifests:
 *
 *   namespace zarz-v2, baseUrl https://api.zarz.moe/v2, scheme ZARZ-HMAC-V1,
 *   header prefix X-Zarz-, 300 s signing window, endpoints
 *   /bootstrap, /challenge, /session/exchange, /session/refresh.
 *
 * Signing (protocol.rs :: signed_headers):
 *   window    = unixSeconds / 300
 *   rolling   = b64url(HMAC-SHA256(session_secret, "<window>:<session_id>"))
 *   signing   = scheme \n METHOD \n escapedPath \n "" \n sha256hex(body) \n
 *               timestamp \n nonce \n session_id \n app_version \n platform
 *   signature = b64url(HMAC-SHA256(rolling, signing))
 *
 * One session record per provider app_version, all sharing one install id, exactly as
 * Mornye scopes its records (protocol.rs :: filename / normalize_scope).
 */
object Zarz {
    const val NAMESPACE = "zarz-v2"
    const val BASE = "https://api.zarz.moe/v2"
    const val SCHEME = "ZARZ-HMAC-V1"
    const val PREFIX = "X-Zarz-"
    const val PLATFORM = "extension"
    const val WINDOW_SECONDS = 300L

    const val BOOTSTRAP = "/bootstrap"
    const val CHALLENGE = "/challenge"
    const val EXCHANGE = "/session/exchange"
    const val REFRESH = "/session/refresh"
    const val TICKETS = "/tickets"

    /** Raised when the gateway wants the user to prove who they are before a session is minted. */
    class VerificationRequired(val authUrl: String) :
        Exception("ZARZ verification required: $authUrl")

    class SignedError(
        message: String,
        val status: Int = 0,
        val retryable: Boolean = false,
        val retryAfterMs: Long = 0,
    ) : Exception(message)

    data class Session(
        val appVersion: String,
        val sessionId: String,
        val secret: String,
        val expiresAt: String,
    ) {
        val usable: Boolean
            get() = sessionId.isNotBlank() && secret.isNotBlank() &&
                    (parseRfc3339(expiresAt) ?: Long.MAX_VALUE) > System.currentTimeMillis()

        val expiresInMs: Long
            get() = (parseRfc3339(expiresAt) ?: 0L) - System.currentTimeMillis()

        /** protocol.rs :: refresh_due - refresh inside the last hour. */
        val refreshDue: Boolean
            get() = usable && expiresInMs <= 3_600_000L
    }

    // ---------------------------------------------------------------- install id

    private const val KEY_INSTALL = "zarz.install_id"

    fun installId(): String {
        val existing = Creds.get(KEY_INSTALL)
        if (existing != null && existing.length >= 16) return existing
        val fresh = randomHex(16)
        Creds.set(KEY_INSTALL, fresh)
        return fresh
    }

    // ---------------------------------------------------------------- session store

    private fun sessionKey(appVersion: String) = "zarz.session.$appVersion"

    fun load(appVersion: String): Session? {
        val obj = Creds.getJson(sessionKey(appVersion)) ?: return null
        val id = obj.optString("session_id", "")
        val secret = obj.optString("session_secret", "")
        if (id.isBlank() || secret.isBlank()) return null
        return Session(appVersion, id, secret, obj.optString("expires_at", ""))
    }

    private fun save(session: Session) {
        Creds.setJson(
            sessionKey(session.appVersion),
            JSONObject()
                .put("session_id", session.sessionId)
                .put("session_secret", session.secret)
                .put("expires_at", session.expiresAt)
                .put("app_version", session.appVersion)
                .put("namespace", NAMESPACE)
                .put("base_url", BASE)
                .put("platform", PLATFORM)
        )
    }

    fun clear(appVersion: String) {
        Creds.set(sessionKey(appVersion), null)
        Diag.log("zarz", "cleared session for $appVersion")
    }

    fun clearAll() {
        for (spec in AuthSpecs.all) spec.signedSessionAppVersion?.let { clear(it) }
        Diag.log("zarz", "cleared all sessions")
    }

    fun statusLine(appVersion: String): String {
        val s = load(appVersion) ?: return "not authorized"
        if (!s.usable) return "expired ${s.expiresAt}"
        val mins = s.expiresInMs / 60000
        return "active, ${mins}min left"
    }

    // ---------------------------------------------------------------- crypto

    private fun b64url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun hmac(key: ByteArray, message: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8))
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append(String.format(Locale.US, "%02x", b))
        return sb.toString()
    }

    fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

    fun randomHex(bytes: Int): String {
        val raw = ByteArray(bytes)
        SecureRandom().nextBytes(raw)
        val sb = StringBuilder(bytes * 2)
        for (b in raw) sb.append(String.format(Locale.US, "%02x", b))
        return sb.toString()
    }

    private fun timestamp(nowMs: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(nowMs))
    }

    /** RFC 3339 -> millis. Hand-rolled so the plugin does not need API 26 java.time. */
    fun parseRfc3339(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val re = Regex("""^(\d{4})-(\d{2})-(\d{2})[Tt ](\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?([Zz]|[+-]\d{2}:\d{2})$""")
        val m = re.find(value.trim()) ?: return null
        val g = m.groupValues
        val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(
            g[1].toInt(), g[2].toInt() - 1, g[3].toInt(),
            g[4].toInt(), g[5].toInt(), g[6].toInt()
        )
        var millis = cal.timeInMillis
        val frac = g[7]
        if (frac.isNotEmpty()) millis += frac.padEnd(3, '0').take(3).toLong()
        val zone = g[8]
        if (zone != "Z" && zone != "z") {
            val sign = if (zone.startsWith("-")) -1 else 1
            val hours = zone.substring(1, 3).toInt()
            val minutes = zone.substring(4, 6).toInt()
            millis -= sign * (hours * 3600L + minutes * 60L) * 1000L
        }
        return millis
    }

    // ---------------------------------------------------------------- endpoints

    private fun url(path: String): String =
        if (path.startsWith("http")) path else BASE + (if (path.startsWith("/")) path else "/$path")

    private fun withQuery(base: String, params: List<Pair<String, String>>): String {
        val query = params.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        return if (base.contains('?')) "$base&$query" else "$base?$query"
    }

    private fun escapedPath(fullUrl: String): String = try {
        URI(fullUrl).rawPath ?: "/"
    } catch (e: Throwable) {
        "/"
    }

    // ---------------------------------------------------------------- signing

    fun signedHeaders(
        appVersion: String,
        session: Session,
        method: String,
        fullUrl: String,
        body: ByteArray,
        nonce: String,
        nowMs: Long,
    ): Map<String, String> {
        val ts = timestamp(nowMs)
        val bodyHash = sha256Hex(body)
        val window = nowMs / 1000L / WINDOW_SECONDS
        val rolling = b64url(hmac(session.secret.toByteArray(Charsets.UTF_8), "$window:${session.sessionId}"))
        val signing = listOf(
            SCHEME,
            method.uppercase(Locale.US),
            escapedPath(fullUrl),
            "",
            bodyHash,
            ts,
            nonce,
            session.sessionId,
            appVersion,
            PLATFORM,
        ).joinToString("\n")
        val signature = b64url(hmac(rolling.toByteArray(Charsets.UTF_8), signing))
        return mapOf(
            "${PREFIX}Session" to session.sessionId,
            "${PREFIX}Timestamp" to ts,
            "${PREFIX}Nonce" to nonce,
            "${PREFIX}Body-SHA256" to bodyHash,
            "${PREFIX}Signature" to signature,
            "${PREFIX}App-Version" to appVersion,
            "${PREFIX}Platform" to PLATFORM,
        )
    }

    // ---------------------------------------------------------------- bootstrap / exchange

    private fun readSession(appVersion: String, body: JSONObject): Session? {
        val id = body.optString("session_id", "")
        val secret = body.optString("session_secret", "")
        val expires = body.optString("expires_at", "")
        if (id.isBlank() || secret.isBlank() || expires.isBlank()) return null
        return Session(appVersion, id, secret, expires)
    }

    /**
     * GET /bootstrap?app_version=..&install_id=..
     * Returns a session when the gateway mints one outright, otherwise the verification URL
     * the user has to open (exchange.rs :: perform_bootstrap).
     */
    fun bootstrap(appVersion: String): Pair<Session?, String?> {
        val target = withQuery(
            url(BOOTSTRAP),
            listOf("app_version" to appVersion, "install_id" to installId())
        )
        Diag.log("zarz", "bootstrap $appVersion")
        val res = Http.get(target, mapOf("Accept" to "application/json"))
        if (!res.ok) {
            throw SignedError("bootstrap HTTP ${res.code}", res.code)
        }
        val body = res.json() ?: throw SignedError("bootstrap returned non-JSON")
        readSession(appVersion, body)?.let {
            save(it)
            Diag.log("zarz", "bootstrap gave a session, expires ${it.expiresAt}")
            return it to null
        }
        var authUrl = body.optString("auth_url", "")
        if (authUrl.isBlank()) authUrl = body.optString("challenge_url", "")
        if (authUrl.isBlank()) {
            val challengeId = body.optString("challenge_id", "")
            if (challengeId.isNotBlank()) {
                authUrl = withQuery(
                    url(CHALLENGE),
                    listOf("id" to challengeId, "cb" to "mornye://session-grant?cb_version=v2grant")
                )
            }
        }
        if (authUrl.isBlank()) {
            throw SignedError("bootstrap returned neither a session nor a challenge")
        }
        Diag.log("zarz", "bootstrap needs verification: $authUrl")
        return null to authUrl
    }

    /** POST /session/exchange {grant, install_id, app_version, platform}. */
    fun exchange(appVersion: String, grant: String): Session {
        val target = url(EXCHANGE)
        val payload = JSONObject()
            .put("grant", grant)
            .put("install_id", installId())
            .put("app_version", appVersion)
            .put("platform", PLATFORM)
            .toString()
        Diag.log("zarz", "exchange $appVersion")
        var attempt = 0
        while (true) {
            val res = Http.postJson(target, payload)
            if (res.code == 429 && attempt < 2) {
                attempt++
                val wait = (res.header("Retry-After")?.toLongOrNull() ?: 2L).coerceIn(1, 300)
                Thread.sleep(wait * 1000L)
                continue
            }
            if (!res.ok) throw SignedError("exchange HTTP ${res.code}", res.code)
            val body = res.json() ?: throw SignedError("exchange returned non-JSON")
            val session = readSession(appVersion, body)
                ?: throw SignedError("exchange response missing session fields")
            save(session)
            Diag.log("zarz", "exchange ok, expires ${session.expiresAt}")
            return session
        }
    }

    /** POST /session/refresh {install_id}, signed with the current session. */
    fun refresh(appVersion: String): Session? {
        val session = load(appVersion) ?: return null
        val target = url(REFRESH)
        val payload = JSONObject().put("install_id", installId()).toString().toByteArray(Charsets.UTF_8)
        val headers = signedHeaders(appVersion, session, "POST", target, payload, randomHex(16), System.currentTimeMillis())
            .toMutableMap()
        headers["Content-Type"] = "application/json"
        val res = Http.request("POST", target, headers, payload, "application/json")
        if (!res.ok) {
            Diag.log("zarz", "refresh HTTP ${res.code}")
            return null
        }
        val body = res.json() ?: return null
        val refreshed = readSession(appVersion, body) ?: return null
        save(refreshed)
        Diag.log("zarz", "refresh ok, expires ${refreshed.expiresAt}")
        return refreshed
    }

    /** Make sure a usable session exists, bootstrapping when needed. */
    fun ensure(appVersion: String): Session {
        val current = load(appVersion)
        if (current != null && current.usable) {
            if (current.refreshDue) return refresh(appVersion) ?: current
            return current
        }
        val (minted, authUrl) = bootstrap(appVersion)
        if (minted != null) return minted
        throw VerificationRequired(authUrl ?: "")
    }

    // ---------------------------------------------------------------- signed requests

    fun signedFetch(
        appVersion: String,
        method: String,
        path: String,
        body: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        retryOnSession: Boolean = true,
    ): Http.Res {
        var session = ensure(appVersion)
        val target = url(path)
        val payload = (body ?: "").toByteArray(Charsets.UTF_8)
        val headers = signedHeaders(appVersion, session, method, target, payload, randomHex(16), System.currentTimeMillis())
            .toMutableMap()
        if (body != null) headers["Content-Type"] = "application/json"
        headers.putAll(extraHeaders)
        headers["Accept"] = "application/json"
        val res = Http.request(method, target, headers, if (body != null) payload else null, if (body != null) "application/json" else null)

        if (res.ok) return res

        val error = jsonOrNull(res.body)
        val origin = error?.optString("origin", "")?.lowercase(Locale.US) ?: ""
        val code = error?.optString("code", "")?.uppercase(Locale.US) ?: ""
        val action = error?.optString("action", "")?.lowercase(Locale.US) ?: ""

        // fetch.rs :: gateway_action
        if (res.code == 401 && origin == "gateway" && code == "SESSION_INVALID" && retryOnSession) {
            Diag.log("zarz", "session invalidated by gateway, re-bootstrapping $appVersion")
            clear(appVersion)
            val (minted, authUrl) = bootstrap(appVersion)
            if (minted == null) throw VerificationRequired(authUrl ?: "")
            return signedFetch(appVersion, method, path, body, extraHeaders, retryOnSession = false)
        }
        if (res.code == 428 && origin == "gateway" && code == "VERIFY_REQUIRED") {
            Diag.log("zarz", "gateway requires verification for $appVersion")
            val (minted, authUrl) = bootstrap(appVersion)
            if (minted != null && retryOnSession) {
                return signedFetch(appVersion, method, path, body, extraHeaders, retryOnSession = false)
            }
            throw VerificationRequired(authUrl ?: "")
        }
        if (res.code == 403 && origin == "gateway" && code == "REQUEST_AUTH_INVALID" && retryOnSession) {
            val latest = load(appVersion)
            if (latest != null && latest.usable) {
                return signedFetch(appVersion, method, path, body, extraHeaders, retryOnSession = false)
            }
        }
        val retryable = error?.optBoolean("retryable", false) ?: false
        val retryAfter = error?.optLong("retry_after_seconds", 0L) ?: 0L
        if (res.code == 503 && retryable && action.isEmpty()) {
            throw SignedError("provider unavailable", res.code, retryable = true, retryAfterMs = retryAfter * 1000L)
        }
        throw SignedError("HTTP ${res.code} for $path: ${res.body.take(160)}", res.code)
    }

    fun signedJson(appVersion: String, method: String, path: String, body: JSONObject? = null, extra: Map<String, String> = emptyMap()): JSONObject {
        val res = signedFetch(appVersion, method, path, body?.toString(), extra)
        return res.json() ?: throw SignedError("signed response was not JSON")
    }

    /**
     * POST /tickets - resource_hash is sha256("<provider>:<type>:<id lowercased>").
     * [hashProvider] exists because a few providers name the alias in the header but hash a
     * different word: Beatport sends provider "btp" and hashes "beatport:track:<id>".
     */
    fun ticket(
        appVersion: String,
        provider: String,
        type: String,
        id: String,
        hashProvider: String = provider,
    ): String {
        val resourceHash = sha256Hex("$hashProvider:$type:${id.lowercase(Locale.US)}")
        val payload = JSONObject()
            .put("capability", "download_ticket")
            .put("provider", provider)
            .put("resource_hash", resourceHash)
        val body = signedJson(appVersion, "POST", TICKETS, payload)
        val ticketId = body.optString("ticket_id", "").ifBlank { body.optString("ticket", "") }
        if (ticketId.isBlank()) throw SignedError("ticket response missing ticket_id")
        Diag.log("zarz", "ticket $provider:$type:$id")
        return ticketId
    }
}
