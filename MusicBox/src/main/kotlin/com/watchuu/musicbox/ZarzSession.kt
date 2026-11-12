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
 */
class ZarzSession(
    private val namespace: String = "zarz-v2",
    private val baseUrl: String = "https://api.zarz.moe/v2",
    private val appVersion: String = "deezer@1.3.5",
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

        // Per-provider appVersion strings (part of the HMAC string-to-sign)
        const val APP_VERSION_DEEZER = "deezer@1.3.5"
        const val APP_VERSION_AMAZON = "amzn@2.3.12"
        const val APP_VERSION_TIDAL = "tidal-web@1.2.5"
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
            // Build challenge URL from challenge endpoint
            val challengeEndpoint = resolveEndpoint(challengePath)
            val state = randomHex(16)
            authUrl = "$challengeEndpoint?id=${challengeId.encodeUrl()}&cb=${
                "mornye://session-grant?cb_version=v2grant&state=$state".encodeUrl()
            }"
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

    /**
     * The core signed fetch - equivalent to Mornye's session.signedFetch().
     * Signs the request using ZARZ-HMAC-V1 and handles session lifecycle.
     */
    /** Public accessor so MusicBoxProvider can read the header prefix. */
    val headerPrefixPublic: String get() = headerPrefix

    suspend fun signedFetch(
        method: String,
        path: String,
        body: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        appVersionOverride: String? = null,
    ): SignedResponse {
        // Check session validity
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

        // Refresh if about to expire
        refreshIfNeeded()

        // Make signed request
        val response = signedRequest(method, path, body ?: "", extraHeaders, appVersionOverride)
        val responseBody = response.text

        // Parse error contract
        val contract = try {
            JSONObject(responseBody)
        } catch (_: Exception) {
            null
        }

        val errorCode = contract?.optString("code", "")?.uppercase() ?: ""
        val origin = contract?.optString("origin", "")?.lowercase() ?: ""
        val action = contract?.optString("action", "")?.lowercase() ?: ""

        // Handle gateway actions
        if (response.code == 401 && origin == "gateway" && errorCode == "SESSION_INVALID" && action == "bootstrap_session") {
            // Session invalidated - clear and re-bootstrap
            record.clear()
            val url = bootstrap()
            if (url.isNotEmpty()) {
                return SignedResponse(ok = false, needsVerification = true, error = "VERIFY_REQUIRED", authUrl = url)
            }
            // Retry with new session
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
            // Need verification
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

    /**
     * Convenience: signed JSON request that parses the response body.
     */
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
        if (response.needsVerification) {
            throw VerificationRequiredException(response.authUrl)
        }
        if (!response.ok) {
            throw Exception("Signed request failed: HTTP ${response.statusCode} ${response.error}")
        }
        return JSONObject(response.body ?: "{}")
    }

    /**
     * Get a download ticket for a resource.
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

    /**
     * Resolve a Deezer download descriptor.
     * Provider code: "dzr", download path: "/dl/dzr"
     */
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

    /**
     * Resolve an Amazon Music download descriptor.
     * Provider code: "amazeamazeamaze", download path: "/dl/amazeamazeamaze"
     * resource_hash uses the ASIN as the ID.
     */
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

    /**
     * Resolve a Tidal download descriptor.
     * Provider code: "tid", download path: "/dl/tid"
     */
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
            "GET" -> app.get(url, headers = headers)
            "POST" -> app.post(
                url,
                headers = headers,
                requestBody = body.toRequestBody("application/json".toMediaType())
            )
            "PUT" -> app.put(
                url,
                headers = headers,
                requestBody = body.toRequestBody("application/json".toMediaType())
            )
            "DELETE" -> app.delete(url, headers = headers)
            else -> app.get(url, headers = headers)
        }
    }

    /**
     * Build the ZARZ-HMAC-V1 signed headers.
     *
     * From protocol.rs:
     *   rolling = HMAC-SHA256(session_secret, "{window}:{session_id}")  → base64url_no_pad
     *   signing = join("\n", [scheme_label, method, escaped_path, "", body_sha256, timestamp, nonce, session_id, app_version, platform])
     *   signature = HMAC-SHA256(rolling_bytes, signing)  → base64url_no_pad
     */
    private fun buildSignedHeaders(
        method: String,
        fullUrl: String,
        body: ByteArray,
        appVersionOverride: String? = null,
    ): Map<String, String> {
        val effectiveAppVersion = appVersionOverride ?: appVersion
        val now = System.currentTimeMillis()
        val nowSeconds = now / 1000

        // Timestamp in ISO-8601 with milliseconds (matching Rust format)
        val timestamp = java.time.Instant.ofEpochMilli(now)
            .atZone(java.time.ZoneOffset.UTC)
            .let { zdt ->
                String.format(
                    "%04d-%02d-%02dT%02d:%02d:%02d.%03dZ",
                    zdt.year, zdt.monthValue, zdt.dayOfMonth,
                    zdt.hour, zdt.minute, zdt.second, zdt.nano / 1_000_000
                )
            }

        // Body SHA-256
        val bodySha256 = sha256Hex(body)

        // Nonce
        val nonce = randomHex(12)

        // URL path (escaped)
        val urlObj = URL(fullUrl)
        val escapedPath = urlObj.path ?: "/"

        // Rolling key: HMAC-SHA256(session_secret, "{window}:{session_id}")
        val window = nowSeconds / timeWindowSeconds
        val rollingMessage = "$window:${record.sessionId}"
        val rollingKeyBase64 = hmacSha256Base64(record.sessionSecret.toByteArray(), rollingMessage.toByteArray())

        // String to sign (10 components joined by newline)
        val stringToSign = listOf(
            schemeLabel,
            method,
            escapedPath,
            "",  // empty (reserved for query in the protocol)
            bodySha256,
            timestamp,
            nonce,
            record.sessionId,
            effectiveAppVersion,
            platform,
        ).joinToString("\n")

        // Signature: HMAC-SHA256(rolling_key_base64_bytes, string_to_sign)
        val signature = hmacSha256Base64(rollingKeyBase64.toByteArray(), stringToSign.toByteArray())

        return mapOf(
            "${headerPrefix}Session" to record.sessionId,
            "${headerPrefix}Timestamp" to timestamp,
            "${headerPrefix}Nonce" to nonce,
            "${headerPrefix}Body-SHA256" to bodySha256,
            "${headerPrefix}Signature" to signature,
            "${headerPrefix}App-Version" to effectiveAppVersion,
            "${headerPrefix}Platform" to platform,
        )
    }

    private fun resolveEndpoint(path: String): String {
        val trimmedPath = path.trim()
        if (trimmedPath.startsWith("https://")) return trimmedPath
        return baseUrl.trimEnd('/') + "/" + trimmedPath.trimStart('/')
    }

    // ========== Crypto utilities ==========

    /**
     * HMAC-SHA256, returns base64url with no padding (matching Rust's URL_SAFE_NO_PAD).
     */
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

    // ========== Data classes ==========

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

// Extension to parse timestamps from ISO-8601 format
private fun parseTimestamp(value: String): Long? {
    if (value.isBlank()) return null
    return try {
        java.time.Instant.parse(value).toEpochMilli()
    } catch (_: Exception) {
        null
    }
}

