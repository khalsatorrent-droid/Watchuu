package com.watchuu.musicbox

import android.content.Context
import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.app
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URL
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * ZARZ-HMAC-V1 signed session for ONE provider scope.
 *
 * The host app keeps a separate session per extension appVersion (deezer@1.3.5, tidal-web@1.2.5,
 * qobuz-web@1.2.21 ...), all sharing one install_id. The gateway binds a session to the
 * app_version it was issued for, so a single shared session cannot sign for several providers.
 */
class ZarzSession(
    val appVersion: String,
    private val installIdProvider: () -> String,
    private val persist: (String) -> Unit,
) {
    companion object {
        private const val TAG = "ZarzSession"
        const val NAMESPACE_BASE = "https://api.zarz.moe/v2"
        private const val PLATFORM = "extension"
        private const val SCHEME = "ZARZ-HMAC-V1"
        private const val PREFIX = "X-Zarz-"
        private const val WINDOW = 300L
        const val CALLBACK = "mornye://session-grant"
    }

    private val userAgent get() = "Mornye/$appVersion"

    data class Record(
        var sessionId: String = "",
        var sessionSecret: String = "",
        var expiresAt: String = "",
    ) {
        fun usable(): Boolean {
            if (sessionId.isBlank() || sessionSecret.isBlank()) return false
            val exp = zzParseTs(expiresAt) ?: return true
            return System.currentTimeMillis() < exp
        }

        fun clear() {
            sessionId = ""; sessionSecret = ""; expiresAt = ""
        }
    }

    sealed class Bootstrap {
        object Ready : Bootstrap()
        data class Challenge(val url: String, val state: String) : Bootstrap()
    }

    class VerificationRequired(val url: String, val state: String) : Exception("VERIFY_REQUIRED")

    @Volatile var record = Record()
        private set

    /** Challenge we already handed out (reused for 180 s like the host does). */
    @Volatile var pending: Bootstrap.Challenge? = null
        private set
    @Volatile private var pendingAt = 0L

    fun load(json: String?) {
        if (json.isNullOrBlank()) return
        try {
            val o = JSONObject(json)
            record = Record(o.optString("session_id"), o.optString("session_secret"), o.optString("expires_at"))
        } catch (_: Exception) {
        }
    }

    private fun save() = persist(
        JSONObject().put("session_id", record.sessionId).put("session_secret", record.sessionSecret)
            .put("expires_at", record.expiresAt).toString(),
    )

    fun clear() {
        record.clear(); pending = null; save()
    }

    // ------------------------------------------------------------ bootstrap / exchange

    suspend fun bootstrap(): Bootstrap {
        if (record.usable()) return Bootstrap.Ready
        pending?.let { if (System.currentTimeMillis() - pendingAt < 180_000) return it }
        val url = "$NAMESPACE_BASE/bootstrap?app_version=${zzEnc(appVersion)}&install_id=${zzEnc(installIdProvider())}"
        val resp = app.get(url, headers = mapOf("Accept" to "application/json", "User-Agent" to userAgent))
        if (resp.code !in 200..299) throw Exception("bootstrap HTTP ${resp.code}")
        val o = JSONObject(resp.text)
        val sid = o.optString("session_id")
        val secret = o.optString("session_secret")
        val exp = o.optString("expires_at")
        if (sid.isNotBlank() && secret.isNotBlank() && exp.isNotBlank()) {
            record = Record(sid, secret, exp)
            pending = null
            save()
            return Bootstrap.Ready
        }
        var auth = o.optString("auth_url").ifBlank { o.optString("challenge_url") }
        var state = zzRandomHex(16)
        if (auth.isNotBlank()) {
            val serverState = Regex("[?&]state=([^&#]*)").find(auth)?.groupValues?.get(1)
                ?.let { java.net.URLDecoder.decode(it, "UTF-8") }?.trim().orEmpty()
            if (serverState.isNotEmpty()) state = serverState
            else auth += (if (auth.contains("?")) "&" else "?") + "state=" + zzEnc(state)
        } else {
            val id = o.optString("challenge_id")
            if (id.isBlank()) throw Exception("bootstrap returned neither session nor challenge")
            val cb = "$CALLBACK?cb_version=v2grant&state=${zzEnc(state)}"
            auth = "$NAMESPACE_BASE/challenge?id=${zzEnc(id)}&cb=${zzEnc(cb)}"
        }
        val ch = Bootstrap.Challenge(auth, state)
        pending = ch
        pendingAt = System.currentTimeMillis()
        Log.d(TAG, "[$appVersion] verification required")
        return ch
    }

    suspend fun exchange(grant: String) {
        val body = JSONObject().put("grant", grant).put("install_id", installIdProvider())
            .put("app_version", appVersion).put("platform", PLATFORM).toString()
        var resp: com.lagradost.nicehttp.NiceResponse? = null
        for (attempt in 0..2) {
            resp = app.post(
                "$NAMESPACE_BASE/session/exchange",
                headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json", "User-Agent" to userAgent),
                requestBody = body.toRequestBody("application/json".toMediaType()),
            )
            if (resp.code == 429 && attempt < 2) {
                kotlinx.coroutines.delay((resp.headers["Retry-After"]?.toLongOrNull() ?: 2L).coerceIn(1, 300) * 1000)
                continue
            }
            break
        }
        val r = resp ?: throw Exception("exchange: no response")
        if (r.code !in 200..299) throw Exception("session exchange failed: HTTP ${r.code}")
        val o = JSONObject(r.text)
        val sid = o.optString("session_id")
        val secret = o.optString("session_secret")
        val exp = o.optString("expires_at")
        if (sid.isBlank() || secret.isBlank() || exp.isBlank()) throw Exception("exchange response missing session fields")
        record = Record(sid, secret, exp)
        pending = null
        save()
    }

    private suspend fun refreshIfDue() {
        val exp = zzParseTs(record.expiresAt) ?: return
        val now = System.currentTimeMillis()
        if (!record.usable() || exp - now > 3_600_000) return
        try {
            val r = rawSigned("POST", "/session/refresh", JSONObject().put("install_id", installIdProvider()).toString(), emptyMap())
            if (r.code in 200..299) {
                val o = JSONObject(r.text)
                val sid = o.optString("session_id"); val sec = o.optString("session_secret"); val e = o.optString("expires_at")
                if (sid.isNotBlank() && sec.isNotBlank() && e.isNotBlank()) { record = Record(sid, sec, e); save() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "refresh failed: ${e.message}")
        }
    }

    // ------------------------------------------------------------ signed calls

    data class Result(val code: Int, val body: String)

    /** Signed request. Throws [VerificationRequired] when the user must complete the challenge. */
    suspend fun signed(method: String, path: String, body: String = "", extra: Map<String, String> = emptyMap()): Result {
        ensureSession()
        refreshIfDue()
        var r = rawSigned(method, path, body, extra)
        val c = contract(r.text)
        if (r.code == 401 && c.origin == "gateway" && c.code == "SESSION_INVALID" && c.action == "bootstrap_session") {
            record.clear(); save(); ensureSession()
            r = rawSigned(method, path, body, extra)
        } else if (r.code == 428 && c.origin == "gateway" && c.code == "VERIFY_REQUIRED") {
            record.clear(); save(); ensureSession()
            r = rawSigned(method, path, body, extra)
        }
        return Result(r.code, r.text)
    }

    private suspend fun ensureSession() {
        if (record.usable()) return
        when (val b = bootstrap()) {
            is Bootstrap.Challenge -> throw VerificationRequired(b.url, b.state)
            Bootstrap.Ready -> if (!record.usable()) throw Exception("session unusable after bootstrap")
        }
    }

    suspend fun signedJson(method: String, path: String, body: JSONObject? = null, extra: Map<String, String> = emptyMap()): Any {
        val r = signed(method, path, body?.toString() ?: "", extra)
        if (r.code !in 200..299) {
            val c = contract(r.body)
            throw Exception("HTTP ${r.code} ${c.code.ifBlank { r.body.take(120) }}")
        }
        val t = r.body.trim()
        return if (t.startsWith("[")) org.json.JSONArray(t) else JSONObject(t)
    }

    suspend fun ticket(provider: String, resource: String): String {
        val hash = zzSha256Hex("$provider:track:${resource.lowercase()}".toByteArray())
        val o = signedJson(
            "POST", "/tickets",
            JSONObject().put("capability", "download_ticket").put("provider", provider).put("resource_hash", hash),
        ) as? JSONObject ?: throw Exception("ticket: bad response")
        return o.optString("ticket_id").ifBlank { o.optString("ticket") }.trim()
            .ifBlank { throw Exception("ticket response missing ticket_id") }
    }

    /** ticket + download call in one go; the gateway consumes the ticket on /dl/*. */
    suspend fun download(provider: String, ticketResource: String, path: String, body: JSONObject): Any {
        val t = ticket(provider, ticketResource)
        return signedJson("POST", path, body, mapOf("${PREFIX}Ticket" to t))
    }

    private class Contract(val code: String, val origin: String, val action: String)

    private fun contract(text: String): Contract = try {
        val o = JSONObject(text)
        Contract(o.optString("code").uppercase(), o.optString("origin").lowercase(), o.optString("action").lowercase())
    } catch (_: Exception) {
        Contract("", "", "")
    }

    private suspend fun rawSigned(method: String, path: String, body: String, extra: Map<String, String>): com.lagradost.nicehttp.NiceResponse {
        val url = if (path.startsWith("https://")) path else NAMESPACE_BASE + "/" + path.trimStart('/')
        val m = method.uppercase()
        val headers = signedHeaders(m, url, body.toByteArray()).toMutableMap()
        headers["Accept"] = "application/json"
        headers["User-Agent"] = userAgent
        if (body.isNotEmpty()) headers["Content-Type"] = "application/json"
        headers.putAll(extra)
        return if (m == "GET") app.get(url, headers = headers)
        else app.post(url, headers = headers, requestBody = body.toRequestBody("application/json".toMediaType()))
    }

    private fun signedHeaders(method: String, url: String, body: ByteArray): Map<String, String> {
        val now = System.currentTimeMillis()
        val z = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneOffset.UTC)
        val ts = String.format("%04d-%02d-%02dT%02d:%02d:%02d.%03dZ", z.year, z.monthValue, z.dayOfMonth, z.hour, z.minute, z.second, z.nano / 1_000_000)
        val hash = zzSha256Hex(body)
        val nonce = zzRandomHex(12)
        val path = URL(url).path.ifEmpty { "/" }
        val window = now / 1000 / WINDOW
        val rolling = hmac(record.sessionSecret.toByteArray(), "$window:${record.sessionId}".toByteArray())
        val toSign = listOf(SCHEME, method, path, "", hash, ts, nonce, record.sessionId, appVersion, PLATFORM).joinToString("\n")
        val sig = hmac(rolling.toByteArray(), toSign.toByteArray())
        return mapOf(
            "${PREFIX}Session" to record.sessionId, "${PREFIX}Timestamp" to ts, "${PREFIX}Nonce" to nonce,
            "${PREFIX}Body-SHA256" to hash, "${PREFIX}Signature" to sig,
            "${PREFIX}App-Version" to appVersion, "${PREFIX}Platform" to PLATFORM,
        )
    }

    private fun hmac(key: ByteArray, msg: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return Base64.encodeToString(mac.doFinal(msg), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}

private fun zzParseTs(v: String): Long? = if (v.isBlank()) null else try { java.time.Instant.parse(v).toEpochMilli() } catch (_: Exception) { null }
private fun zzEnc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
private fun zzSha256Hex(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
private fun zzRandomHex(n: Int): String {
    val b = ByteArray(n); java.security.SecureRandom().nextBytes(b); return b.joinToString("") { "%02x".format(it) }
}

/**
 * One session per ZARZ-backed provider, persisted in SharedPreferences (shared install_id).
 */
object ZarzHub {
    enum class Provider(val key: String, val appVersion: String, val label: String) {
        DEEZER("dzr", "deezer@1.3.5", "Deezer"),
        TIDAL("tid", "tidal-web@1.2.5", "Tidal"),
        QOBUZ("qbz", "qobuz-web@1.2.21", "Qobuz"),
    }

    private const val PREFS = "musicbox_prefs"
    private val sessions = HashMap<Provider, ZarzSession>()

    private fun prefs(): android.content.SharedPreferences? =
        com.lagradost.cloudstream3.CloudStreamApp.context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    private fun installId(): String {
        val p = prefs()
        val existing = p?.getString("zarz_install_id", null)
        if (!existing.isNullOrBlank()) return existing
        val id = zzRandomHex(16)
        p?.edit()?.putString("zarz_install_id", id)?.apply()
        return id
    }

    @Synchronized
    fun session(p: Provider): ZarzSession = sessions.getOrPut(p) {
        ZarzSession(p.appVersion, { installId() }) { json ->
            prefs()?.edit()?.putString("zarz_session_${p.key}", json)?.apply()
        }.also { it.load(prefs()?.getString("zarz_session_${p.key}", null)) }
    }

    fun status(p: Provider): String {
        val s = session(p)
        return when {
            s.record.usable() -> "verified (until ${s.record.expiresAt.take(16).replace("T", " ")})"
            s.record.sessionId.isNotBlank() -> "session expired"
            else -> "not verified"
        }
    }

    fun clearAll() = Provider.values().forEach { session(it).clear() }

    // ------------------------------------------------------------ resolvers (shapes taken from the SpotiFLAC extensions)

    /** /dl/dzr; ticket resource is the track URL. */
    suspend fun deezer(trackId: String): JSONObject {
        val url = "https://www.deezer.com/track/$trackId"
        val r = session(Provider.DEEZER).download(
            "dzr", url, "/dl/dzr",
            JSONObject().put("id", trackId).put("type", "track").put("platform", "deezer").put("url", url),
        )
        return (r as? JSONObject) ?: (r as org.json.JSONArray).optJSONObject(0) ?: JSONObject()
    }

    /** /dl/tid; ticket resource is the track id. */
    suspend fun tidal(trackId: String, quality: String = "LOSSLESS"): JSONObject {
        val r = session(Provider.TIDAL).download(
            "tid", trackId, "/dl/tid", JSONObject().put("id", trackId).put("quality", quality),
        )
        return (r as? JSONObject) ?: JSONObject()
    }

    /** /dl/qbz; ticket resource is the open.qobuz.com track URL (the body's `url`). */
    suspend fun qobuz(trackId: String, quality: String = "cd"): JSONObject {
        val url = "https://open.qobuz.com/track/$trackId"
        val r = session(Provider.QOBUZ).download(
            "qbz", url, "/dl/qbz",
            JSONObject().put("quality", quality).put("upload_to_r2", false).put("id", trackId).put("type", "track").put("url", url),
        )
        return (r as? JSONObject) ?: JSONObject()
    }

    /** Cobalt lives on /v1 and is not signed. */
    suspend fun cobalt(youtubeUrl: String): JSONObject {
        val resp = app.post(
            "https://api.zarz.moe/v1/dl/cobalt",
            headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json", "User-Agent" to "Mornye/cobalt"),
            requestBody = JSONObject().put("url", youtubeUrl).put("downloadMode", "audio").put("audioFormat", "best")
                .toString().toRequestBody("application/json".toMediaType()),
        )
        if (resp.code !in 200..299) throw Exception("Cobalt HTTP ${resp.code}")
        return JSONObject(resp.text)
    }
}
