package com.watchuu.musicbox

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.json.JSONObject
import java.net.URL
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * ZARZ-HMAC-V1 signed session client.
 *
 * Reverse-engineered from the Mornye Rust backend (rust_backend/crates/extensions/src/signed_session/).
 * Implements the full bootstrap → challenge → exchange → refresh → signedFetch lifecycle.
 *
 * Updated appVersion strings match the registry.json versions as of 2026-11-08:
 *   amazon  → amzn@2.3.19   (was 2.3.12)
 *   deezer  → deezer@1.3.11 (was 1.3.5)
 *   tidal   → tidal-web@1.2.14 (was 1.2.5)
 */
class ZarzSession(
    private val namespace: String = "zarz-v2",
    private val baseUrl: String = "https://api.zarz.moe/v2",
    private val appVersion: String = APP_VERSION_DEEZER,
    private val platform: String = "extension",
    private val schemeLabel: String = "ZARZ-HMAC-V1",
    private val headerPrefix: String = "X-Zarz-",
    private val timeWindowSeconds: Long = 300,
    private val bootstrapPath: String = "/bootstrap",
    private val challengePath: String = "/challenge",
    private val exchangePath: String = "/session/exchange",
    private val refreshPath: String = "/session/refresh",
    private val resolverDownloadPath: String = "/dl/dzr",
) {
    companion object {
        private const val TAG = "ZarzSession"
        private const val USER_AGENT = "Mornye/ext-1.0"

        // Per-provider appVersion strings — keep in sync with registry.json versions
        const val APP_VERSION_DEEZER  = "deezer@1.3.11"
        const val APP_VERSION_AMAZON  = "amzn@2.3.19"
        const val APP_VERSION_TIDAL   = "tidal-web@1.2.14"
        const val APP_VERSION_QOBUZ   = "qobuz-web@1.2.27"
        const val APP_VERSION_YTMUSIC = "ytmusic-spotiflac@2.4.6"
        const val APP_VERSION_SC      = "soundcloud@1.0.14"
        const val APP_VERSION_BEATPORT = "beatport@1.0.1"
    }

    // Session record - persisted
    data class SessionRecord(
        var installId: String = "",
        var sessionId: String = "",
        var sessionSecret: String = "",
        var expiresAt: String = "",
    ) {
        fun isUsable(): Boolean {
            if (sessionId.isBlank() || sessionSecret.isBlank()) return false
            val expires = parseTimestamp(expiresAt) ?: return true
            return System.currentTimeMillis() < expires
        }

        fun clear() {
            sessionId = ""
            sessionSecret = ""
            expiresAt = ""
        }
    }

    // Mutable session state
    @Volatile
    var record: SessionRecord = SessionRecord()
        private set

    // Whether we need verification (challenge URL pending)
    @Volatile
    var verificationUrl: String = ""
        private set

    @Volatile
    var needsVerification: Boolean = false
        private set

    /**
     * Load session state from persisted JSON string.
     */
    fun loadState(json: String?) {
        if (json.isNullOrBlank()) {
            record = SessionRecord(installId = randomHex(16))
            return
        }
        try {
            val obj = JSONObject(json)
            record = SessionRecord(
                installId = obj.optString("install_id", randomHex(16)),
                sessionId = obj.optString("session_id", ""),
                sessionSecret = obj.optString("session_secret", ""),
                expiresAt = obj.optString("expires_at", ""),
            )
        } catch (e: Exception) {
            record = SessionRecord(installId = randomHex(16))
        }
    }

    /**
     * Serialize session state for persistence.
     */
    fun saveState(): String {
        return JSONObject().apply {
            put("install_id", record.installId)
            put("session_id", record.sessionId)
            put("session_secret", record.sessionSecret)
            put("expires_at", record.expiresAt)
        }.toString()
    }

    /**
     * Bootstrap - GET /bootstrap to obtain a session or a verification challenge.
     * Returns empty string if session obtained, or a challenge URL if verification needed.
     */
    suspend fun bootstrap(): String {
        val url = resolveEndpoint(bootstrapPath)
        val fullUrl = "$url?app_version=${appVersion.encodeUrl()}&install_id=${record.installId.encodeUrl()}"

        val response = app.get(
            fullUrl,
            headers = mapOf(
                "Accept" to "application/json",
                "User-Agent" to USER_AGENT,
            )
        )

        if (response.code !in 200..299) {
            throw Exception("Bootstrap failed: HTTP ${response.code}")
        }

        val body = response.text
        val obj = JSONObject(body)

        // Check if we got a direct session
        val sid = obj.optString("session_id", "")
        val secret = obj.optString("session_secret", "")
        val expires = obj.optString("expires_at", "")

        if (sid.isNotBlank() && secret.isNotBlank() && expires.isNotBlank()) {
            record.sessionId = sid
            record.sessionSecret = secret
            record.expiresAt = expires
            needsVerification = false
            verificationUrl = ""
            Log.d(TAG, "Bootstrap: obtained session directly")
            return ""
        }

        // Otherwise we got a challenge
        var authUrl = obj.optString("auth_url", "")
        if (authUrl.isBlank()) authUrl = obj.optString("challenge_url", "")

        val challengeId = obj.optString("challenge_id", "")
        if (authUrl.isBlank() && challengeId.isNotBlank()) {
            val challengeEndpoint = resolveEndpoint(challengePath)
            val state = randomHex(16)
            authUrl = "$challengeEndpoint?id=${challengeId.encodeUrl()}&cb=${"mornye://session-grant?cb_version=v2grant&state=$state".encodeUrl()}"
        }

        if (authUrl.isBlank()) {
            throw Exception("Bootstrap returned neither session nor challenge")
        }

        needsVerification = true
        verificationUrl = authUrl
        Log.d(TAG, "Bootstrap: verification required at $authUrl")
        return authUrl
    }

    /**
     * Exchange a verification grant code for a session.
     */
    suspend fun exchange(grant: String) {
        val url = resolveEndpoint(exchangePath)
        val payload = JSONObject().apply {
            put("grant", grant)
            put("install_id", record.installId)
            put("app_version", appVersion)
            put("platform", platform)
        }

        var response: com.lagradost.nicehttp.NiceResponse? = null
        for (attempt in 0..2) {
            response = app.post(
                url,
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/json",
                    "User-Agent" to USER_AGENT,
                ),
                requestBody = payload.toString()
                    .toRequestBody("application/json".toMediaType())
            )

            if (response.code == 429 && attempt < 2) {
                val retryAfter = response.headers["Retry-After"]?.toLongOrNull() ?: 2L
                kotlinx.coroutines.delay(retryAfter.coerceIn(1, 300) * 1000)
                continue
            }
            break
        }

        val resp = response ?: throw Exception("Exchange failed: no response")
        if (resp.code !in 200..299) {
            throw Exception("Exchange failed: HTTP ${resp.code}")
        }

        val obj = JSONObject(resp.text)
        val sid = obj.optString("session_id", "")
        val secret = obj.optString("session_secret", "")
        val expires = obj.optString("expires_at", "")

        if (sid.isBlank() || secret.isBlank() || expires.isBlank()) {
            throw Exception("Exchange response missing session fields")
        }

        record.sessionId = sid
        record.sessionSecret = secret
        record.expiresAt = expires
        needsVerification = false
        verificationUrl = ""
        Log.d(TAG, "Exchange: obtained session, expires $expires")
    }

    /**
     * Refresh the session token if it's expiring soon (within 1 hour).
     */
    suspend fun refreshIfNeeded() {
        if (refreshPath.isBlank() || !record.isUsable()) return
        val expires = parseTimestamp(record.expiresAt) ?: return
        val now = System.currentTimeMillis()
        if (now >= expires || expires - now > 3_600_000) return // not due

        try {
            val payload = JSONObject().apply { put("install_id", record.installId) }
            val response = signedRequest(
                "POST", refreshPath, payload.toString(),
                mapOf("Content-Type" to "application/json")
            )
            if (response.code in 200..299) {
                val obj = JSONObject(response.text)
                val sid = obj.optString("session_id", "")
                val secret = obj.optString("session_secret", "")
                val exp = obj.optString("expires_at", "")
                if (sid.isNotBlank()) record.sessionId = sid
                if (secret.isNotBlank()) record.sessionSecret = secret
                if (exp.isNotBlank()) record.expiresAt = exp
                Log.d(TAG, "Refresh: session updated")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Refresh failed: ${e.message}")
        }
    }

    /** Public accessor so MusicBoxProvider can read the header prefix. */
    val headerPrefixPublic: String get() = headerPrefix

    suspend fun signedFetch(
        method: String,
        path: String,
        body: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        appVersionOverride: String? = null,
    ): SignedResponse {
        if (!record.isUsable()) {
            val url = bootstrap()
            if (url.isNotEmpty()) {
                return SignedResponse(
                    ok = false,
                    needsVerification = true,
                    error = "VERIFY_REQUIRED",
                    authUrl = url,
                )
            }
            if (!record.isUsable()) {
                throw Exception("Signed session not authenticated after bootstrap")
            }
        }

        refreshIfNeeded()

        val response = signedRequest(method, path, body ?: "", extraHeaders, appVersionOverride)
        val responseBody = response.text

        val contract = try { JSONObject(responseBody) } catch (_: Exception) { null }
        val errorCode = contract?.optString("code", "")?.uppercase() ?: ""
        val origin = contract?.optString("origin", "")?.lowercase() ?: ""
        val action = contract?.optString("action", "")?.lowercase() ?: ""

        if (response.code == 401 && origin == "gateway" && errorCode == "SESSION_INVALID" && action == "bootstrap_session") {
            record.clear()
            val url = bootstrap()
            if (url.isNotEmpty()) {
                return SignedResponse(ok = false, needsVerification = true, error = "VERIFY_REQUIRED", authUrl = url)
            }
            if (record.isUsable()) {
                val retryResp = signedRequest(method, path, body ?: "", extraHeaders, appVersionOverride)
                return SignedResponse(
                    ok = retryResp.code in 200..299,
                    statusCode = retryResp.code,
                    body = retryResp.text,
                )
            }
        }

        if (response.code == 428 && origin == "gateway" && errorCode == "VERIFY_REQUIRED" && action == "verify") {
            val url = bootstrap()
            if (url.isNotEmpty()) {
                return SignedResponse(ok = false, needsVerification = true, error = "VERIFY_REQUIRED", authUrl = url)
            }
        }

        return SignedResponse(
            ok = response.code in 200..299,
            statusCode = response.code,
            body = responseBody,
            error = if (response.code !in 200..299) errorCode else "",
        )
    }

    suspend fun signedJson(
        method: String,
        path: String,
        body: Any? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        appVersionOverride: String? = null,
    ): JSONObject {
        val bodyStr = when (body) {
            null -> ""
            is String -> body
            is JSONObject -> body.toString()
            else -> body.toJson()
        }
        val response = signedFetch(method, path, bodyStr, extraHeaders, appVersionOverride)
        if (response.needsVerification) throw VerificationRequiredException(response.authUrl)
        if (!response.ok) throw Exception("Signed request failed: HTTP ${response.statusCode} ${response.error}")
        return JSONObject(response.body ?: "{}")
    }

    /**
     * Get a download ticket.
     * resource_hash = sha256(provider + ":" + type + ":" + id.toLowerCase())
     */
    suspend fun signedTicket(provider: String, type: String = "track", id: String, appVersionOverride: String? = null): String {
        val resourceHash = sha256Hex("$provider:$type:${id.lowercase()}")
        val payload = JSONObject().apply {
            put("capability", "download_ticket")
            put("provider", provider)
            put("resource_hash", resourceHash)
        }
        val result = signedJson("POST", "/tickets", payload, appVersionOverride = appVersionOverride)
        val ticketId = (result.optString("ticket_id", "") ?: result.optString("ticket", "")).trim()
        if (ticketId.isBlank()) throw Exception("Signed ticket response missing ticket_id")
        return ticketId
    }

    // ── Deezer ─────────────────────────────────────────────────────────────────
    suspend fun resolveDownloadDescriptor(trackId: String): JSONObject {
        val trackUrl = "https://www.deezer.com/track/$trackId"
        val ticket = signedTicket("dzr", "track", trackUrl, appVersionOverride = APP_VERSION_DEEZER)
        return signedJson(
            "POST", resolverDownloadPath,
            JSONObject().apply {
                put("id", trackId)
                put("type", "track")
                put("platform", "deezer")
                put("url", trackUrl)
            },
            mapOf("${headerPrefix}Ticket" to ticket),
            appVersionOverride = APP_VERSION_DEEZER,
        )
    }

    // ── Amazon Music ───────────────────────────────────────────────────────────
    suspend fun resolveAmazonDownload(asin: String, codec: String = "flac"): JSONObject {
        val ticket = signedTicket("amazeamazeamaze", "track", asin, appVersionOverride = APP_VERSION_AMAZON)
        return signedJson(
            "POST", "/dl/amazeamazeamaze",
            JSONObject().apply {
                put("asin", asin)
                put("codec", codec)
            },
            mapOf("${headerPrefix}Ticket" to ticket),
            appVersionOverride = APP_VERSION_AMAZON,
        )
    }

    // ── Tidal ──────────────────────────────────────────────────────────────────
    suspend fun resolveTidalDownload(trackId: String, quality: String = "LOSSLESS"): JSONObject {
        val ticket = signedTicket("tid", "track", trackId, appVersionOverride = APP_VERSION_TIDAL)
        return signedJson(
            "POST", "/dl/tid",
            JSONObject().apply {
                put("id", trackId)
                put("quality", quality)
            },
            mapOf("${headerPrefix}Ticket" to ticket),
            appVersionOverride = APP_VERSION_TIDAL,
        )
    }

    // ── Qobuz ─────────────────────────────────────────────────────────────────
    /**
     * Resolve a Qobuz track download via ZARZ.
     * Provider code: "qbz", download path: "/dl/qbz"
     */
    suspend fun resolveQobuzDownload(trackId: String, quality: Int = 27): JSONObject {
        val ticket = signedTicket("qbz", "track", trackId, appVersionOverride = APP_VERSION_QOBUZ)
        return signedJson(
            "POST", "/dl/qbz",
            JSONObject().apply {
                put("id", trackId)
                put("quality", quality) // 5=MP3, 6=CD, 7=24/96, 27=24/192
            },
            mapOf("${headerPrefix}Ticket" to ticket),
            appVersionOverride = APP_VERSION_QOBUZ,
        )
    }

    // ── SoundCloud ────────────────────────────────────────────────────────────
    /**
     * Resolve a SoundCloud track download via ZARZ.
     * Provider code: "sc", download path: "/dl/sc"
     * Falls back to direct SoundCloud stream if ZARZ is unavailable.
     */
    suspend fun resolveSoundCloudDownload(trackId: String): JSONObject {
        val ticket = signedTicket("sc", "track", trackId, appVersionOverride = APP_VERSION_SC)
        return signedJson(
            "POST", "/dl/sc",
            JSONObject().apply {
                put("id", trackId)
            },
            mapOf("${headerPrefix}Ticket" to ticket),
            appVersionOverride = APP_VERSION_SC,
        )
    }

    // ── YouTube Music ──────────────────────────────────────────────────────────
    /**
     * Resolve a YouTube Music download via Cobalt.
     * Uses api.zarz.moe/v1/dl/cobalt (NOTE: v1, not v2).
     * This does NOT use signed session — it's a plain POST.
     */
    suspend fun resolveYouTubeDownload(youtubeUrl: String): JSONObject {
        val response = app.post(
            "https://api.zarz.moe/v1/dl/cobalt",
            headers = mapOf(
                "Content-Type" to "application/json",
                "Accept" to "application/json",
                "User-Agent" to USER_AGENT,
            ),
            requestBody = JSONObject().apply {
                put("url", youtubeUrl)
                put("downloadMode", "audio")
                put("audioFormat", "best")
            }.toString().toRequestBody("application/json".toMediaType())
        )
        if (response.code !in 200..299) {
            throw Exception("Cobalt audio failed: HTTP ${response.code}")
        }
        return JSONObject(response.text)
    }

    // ── Beatport ───────────────────────────────────────────────────────────────
    /**
     * Resolve a Beatport track download via ZARZ.
     * Provider code: "bp", download path: "/dl/bp"
     */
    suspend fun resolveBeatportDownload(trackId: String, format: String = "flac"): JSONObject {
        val ticket = signedTicket("bp", "track", trackId, appVersionOverride = APP_VERSION_BEATPORT)
        return signedJson(
            "POST", "/dl/bp",
            JSONObject().apply {
                put("id", trackId)
                put("format", format) // "flac" or "aac"
            },
            mapOf("${headerPrefix}Ticket" to ticket),
            appVersionOverride = APP_VERSION_BEATPORT,
        )
    }

    // ── JioSaavn (direct, no ZARZ ticket) ─────────────────────────────────────
    // JioSaavn still uses direct DES-decrypted URL — no ZARZ ticket needed.

    // ========== Internal signing mechanics ==========

    private suspend fun signedRequest(
        method: String,
        path: String,
        body: String,
        extraHeaders: Map<String, String>,
        appVersionOverride: String? = null,
    ): com.lagradost.nicehttp.NiceResponse {
        val url = resolveEndpoint(path)
        val headers = buildSignedHeaders(method.uppercase(), url, body.toByteArray(), appVersionOverride)
            .toMutableMap()
        headers["Accept"] = "application/json"
        headers["User-Agent"] = USER_AGENT
        if (body.isNotEmpty()) headers["Content-Type"] = "application/json"
        headers.putAll(extraHeaders.map { (k, v) -> k.lowercase() to v })

        return when (method.uppercase()) {
            "GET"    -> app.get(url, headers = headers)
            "POST"   -> app.post(url, headers = headers, requestBody = body.toRequestBody("application/json".toMediaType()))
            "PUT"    -> app.put(url, headers = headers, requestBody = body.toRequestBody("application/json".toMediaType()))
            "DELETE" -> app.delete(url, headers = headers)
            else     -> app.get(url, headers = headers)
        }
    }

    private fun buildSignedHeaders(
        method: String,
        fullUrl: String,
        body: ByteArray,
        appVersionOverride: String? = null,
    ): Map<String, String> {
        val effectiveAppVersion = appVersionOverride ?: appVersion
        val now = System.currentTimeMillis()
        val nowSeconds = now / 1000

        val timestamp = java.time.Instant.ofEpochMilli(now)
            .atZone(java.time.ZoneOffset.UTC)
            .let { zdt ->
                String.format(
                    "%04d-%02d-%02dT%02d:%02d:%02d.%03dZ",
                    zdt.year, zdt.monthValue, zdt.dayOfMonth,
                    zdt.hour, zdt.minute, zdt.second, zdt.nano / 1_000_000
                )
            }

        val bodySha256 = sha256Hex(body)
        val nonce = randomHex(12)
        val urlObj = URL(fullUrl)
        val escapedPath = urlObj.path ?: "/"

        val window = nowSeconds / timeWindowSeconds
        val rollingMessage = "$window:${record.sessionId}"
        val rollingKeyBase64 = hmacSha256Base64(record.sessionSecret.toByteArray(), rollingMessage.toByteArray())

        val stringToSign = listOf(
            schemeLabel,
            method,
            escapedPath,
            "",
            bodySha256,
            timestamp,
            nonce,
            record.sessionId,
            effectiveAppVersion,
            platform,
        ).joinToString("\n")

        val signature = hmacSha256Base64(rollingKeyBase64.toByteArray(), stringToSign.toByteArray())

        return mapOf(
            "${headerPrefix}Session"     to record.sessionId,
            "${headerPrefix}Timestamp"   to timestamp,
            "${headerPrefix}Nonce"       to nonce,
            "${headerPrefix}Body-SHA256" to bodySha256,
            "${headerPrefix}Signature"   to signature,
            "${headerPrefix}App-Version" to effectiveAppVersion,
            "${headerPrefix}Platform"    to platform,
        )
    }

    private fun resolveEndpoint(path: String): String {
        val trimmedPath = path.trim()
        if (trimmedPath.startsWith("https://")) return trimmedPath
        return baseUrl.trimEnd('/') + "/" + trimmedPath.trimStart('/')
    }

    private fun hmacSha256Base64(key: ByteArray, message: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val result = mac.doFinal(message)
        return Base64.encodeToString(result, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun sha256Hex(input: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun sha256Hex(input: String): String = sha256Hex(input.toByteArray())

    private fun randomHex(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun String.encodeUrl(): String = java.net.URLEncoder.encode(this, "UTF-8")

    data class SignedResponse(
        val ok: Boolean = false,
        val statusCode: Int = 0,
        val body: String? = null,
        val error: String = "",
        val needsVerification: Boolean = false,
        val authUrl: String = "",
    )

    class VerificationRequiredException(val authUrl: String) : Exception("Verification required")
}

private fun parseTimestamp(value: String): Long? {
    if (value.isBlank()) return null
    return try {
        java.time.Instant.parse(value).toEpochMilli()
    } catch (_: Exception) {
        null
    }
}
