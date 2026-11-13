package com.watchuu.musicbox

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URL
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * ZARZ-HMAC-V1 signed session client.
 *
 * Implements the full bootstrap -> challenge -> exchange -> refresh -> signedFetch lifecycle,
 * supporting Deezer, Amazon Music, Tidal, Qobuz, and Cobalt YouTube resolution.
 */
class ZarzSession(
    private val namespace: String = "zarz-v2",
    private val baseUrl: String = "https://api.zarz.moe/v2",
    private val appVersion: String = "deezer@1.2.0",
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
        /** musicdl sends "SpotiFLAC-Mobile/<app_version>" on bootstrap, exchange and every
         *  signed call - the server gates on it, so a different prefix is rejected */
        private const val USER_AGENT_PREFIX = "SpotiFLAC-Mobile"

        private fun userAgent(version: String) = "$USER_AGENT_PREFIX/$version"

        const val APP_VERSION_DEEZER = "deezer@1.2.0"
        const val APP_VERSION_AMAZON = "amzn@2.3.12"
        const val APP_VERSION_TIDAL = "tidal-web@1.1.0"
        const val APP_VERSION_QOBUZ = "qobuz-web@1.1.0"
    }

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

    @Volatile
    var record: SessionRecord = SessionRecord()
        private set

    @Volatile
    var verificationUrl: String = ""
        private set

    @Volatile
    var needsVerification: Boolean = false
        private set

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

    fun saveState(): String {
        return JSONObject().apply {
            put("install_id", record.installId)
            put("session_id", record.sessionId)
            put("session_secret", record.sessionSecret)
            put("expires_at", record.expiresAt)
        }.toString()
    }

    suspend fun bootstrap(): String {
        val url = resolveEndpoint(bootstrapPath)
        val fullUrl = "$url?app_version=${appVersion.encodeUrl()}&install_id=${record.installId.encodeUrl()}"

        val response = app.get(
            fullUrl,
            headers = mapOf(
                "Accept" to "application/json",
                "User-Agent" to userAgent(appVersion),
            ),
            timeout = 12L
        )

        if (response.code !in 200..299) {
            throw Exception("Bootstrap failed: HTTP ${response.code}")
        }

        val body = response.text
        val obj = JSONObject(body)

        val sid = obj.optString("session_id", "")
        val secret = obj.optString("session_secret", "")
        val expires = obj.optString("expires_at", "")

        if (sid.isNotBlank() && secret.isNotBlank() && expires.isNotBlank()) {
            record.sessionId = sid
            record.sessionSecret = secret
            record.expiresAt = expires
            needsVerification = false
            verificationUrl = ""
            MusicBoxLog.d(TAG, "Bootstrap: obtained session directly")
            return ""
        }

        var authUrl = obj.optString("auth_url", "")
        if (authUrl.isBlank()) authUrl = obj.optString("challenge_url", "")

        val challengeId = obj.optString("challenge_id", "")
        if (authUrl.isBlank() && challengeId.isNotBlank()) {
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
        MusicBoxLog.d(TAG, "Bootstrap: verification required at $authUrl")
        return authUrl
    }

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
                    "User-Agent" to userAgent(appVersion),
                ),
                requestBody = payload.toString()
                    .toRequestBody("application/json".toMediaType()),
                timeout = 12L
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
        MusicBoxLog.d(TAG, "Exchange: obtained session, expires $expires")
    }

    suspend fun refreshIfNeeded() {
        if (refreshPath.isBlank() || !record.isUsable()) return
        val expires = parseTimestamp(record.expiresAt) ?: return
        val now = System.currentTimeMillis()
        if (now >= expires || expires - now > 3_600_000) return

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
                MusicBoxLog.d(TAG, "Refresh: session updated")
            }
        } catch (e: Exception) {
            MusicBoxLog.w(TAG, "Refresh failed: ${e.message}")
        }
    }

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

        val contract = try {
            JSONObject(responseBody)
        } catch (_: Exception) {
            null
        }

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
        if (response.needsVerification) {
            throw VerificationRequiredException(response.authUrl)
        }
        if (!response.ok) {
            throw Exception("Signed request failed: HTTP ${response.statusCode} ${response.error}")
        }
        return JSONObject(response.body ?: "{}")
    }

    suspend fun signedTicket(provider: String, type: String = "track", id: String, appVersionOverride: String? = null): String {
        val resourceHash = sha256Hex("$provider:$type:${id.lowercase()}")
        val payload = JSONObject().apply {
            put("capability", "download_ticket")
            put("provider", provider)
            put("resource_hash", resourceHash)
        }
        val result = signedJson("POST", "/tickets", payload, appVersionOverride = appVersionOverride)
        val ticketId = (result.optString("ticket_id", "").ifBlank { result.optString("ticket", "") }).trim()
        if (ticketId.isBlank()) throw Exception("Signed ticket response missing ticket_id")
        return ticketId
    }

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

    suspend fun resolveQobuzDownload(trackId: String, quality: Int = 27): JSONObject {
        // musicdl hashes the *track url*, not the bare id, and sends a string quality plus the url
        val trackUrl = "https://open.qobuz.com/track/$trackId"
        val ticket = signedTicket("qbz", "track", trackUrl, appVersionOverride = APP_VERSION_QOBUZ)
        val qualityStr = when (quality) {
            27 -> "hi-res-max"
            7 -> "hi-res"
            else -> "cd"
        }
        return signedJson(
            "POST", "/dl/qbz",
            JSONObject().apply {
                put("quality", qualityStr)
                put("upload_to_r2", false)
                put("id", trackId)
                put("type", "track")
                put("url", trackUrl)
            },
            mapOf("${headerPrefix}Ticket" to ticket),
            appVersionOverride = APP_VERSION_QOBUZ,
        )
    }

    /** musicdl's descriptorurl(): direct_downloadable first, then download_url, then direct */
    fun descriptorUrl(descriptor: JSONObject): String? {
        val nested = descriptor.optJSONObject("data")
        val direct = (descriptor.optString("direct_download_url", "").ifBlank {
            nested?.optString("direct_download_url", "").orEmpty()
        }).trim()
        val normal = (descriptor.optString("download_url", "").ifBlank {
            nested?.optString("download_url", "").orEmpty()
        }).trim()
        val link = (descriptor.optString("link", "").ifBlank {
            nested?.optString("link", "").orEmpty()
        }).trim()
        val plain = (descriptor.optString("url", "").ifBlank {
            nested?.optString("url", "").orEmpty()
        }).trim()
        if (link.startsWith("http")) return link
        if (plain.startsWith("http") && !plain.contains("manifest")) return plain
        val directOk = if (descriptor.has("direct_downloadable")) {
            descriptor.optBoolean("direct_downloadable")
        } else null
        if (directOk == true && direct.isNotBlank()) return direct
        if (normal.isNotBlank()) return normal
        if (direct.isNotBlank()) return direct
        return null
    }

    /** musicdl's requiresclientdecryption(): a Blowfish-encrypted blob cannot be played as-is */
    fun requiresClientDecryption(descriptor: JSONObject): Boolean {
        if (descriptor.has("requires_client_decryption")) {
            return descriptor.optBoolean("requires_client_decryption")
        }
        if (descriptor.has("direct_downloadable")) {
            return !descriptor.optBoolean("direct_downloadable")
        }
        return descriptor.optBoolean("deezer_encrypted", false)
    }

    /**
     * TIDAL answers a base64 DASH manifest, not a url. musicdl rejects a PREVIEW asset and treats
     * data.manifest as base64; handing that string to a player as if it were a link fails, so the
     * manifest is decoded and its first audio url returned instead.
     */
    fun tidalPlayableUrl(descriptor: JSONObject): String? {
        val data = descriptor.optJSONObject("data") ?: return null
        if (data.optString("assetPresentation").uppercase() == "PREVIEW") return null
        val manifest = data.optString("manifest", "").ifBlank { return null }

        val decoded = runCatching {
            String(android.util.Base64.decode(manifest, android.util.Base64.DEFAULT))
        }.getOrNull() ?: return null

        // a dash manifest lists <BaseURL> per adaptation set; take the first audio one
        return Regex("""<BaseURL>([^<]+)</BaseURL>""").find(decoded)?.groupValues?.get(1)?.trim()
            ?: Regex(""""(https?://[^"]+\.(?:mp3|m4a|flac|aac)[^"]*)"""")
                .find(decoded)?.groupValues?.get(1)
    }

    suspend fun resolveYouTubeDownload(youtubeUrl: String): JSONObject {
        val response = app.post(
            "https://api.zarz.moe/v1/dl/cobalt",
            headers = mapOf(
                "Content-Type" to "application/json",
                "Accept" to "application/json",
                "User-Agent" to userAgent(appVersion),
            ),
            requestBody = JSONObject().apply {
                put("url", youtubeUrl)
                put("downloadMode", "audio")
                put("audioFormat", "best")
            }.toString().toRequestBody("application/json".toMediaType()),
            timeout = 12L
        )
        if (response.code !in 200..299) {
            throw Exception("Cobalt audio failed: HTTP ${response.code}")
        }
        return JSONObject(response.text)
    }

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
        headers["User-Agent"] = userAgent(appVersionOverride ?: appVersion)
        if (body.isNotEmpty()) headers["Content-Type"] = "application/json"
        headers.putAll(extraHeaders.map { (k, v) -> k.lowercase() to v })

        return when (method.uppercase()) {
            "GET" -> app.get(url, headers = headers, timeout = 12L)
            "POST" -> app.post(
                url,
                headers = headers,
                requestBody = body.toRequestBody("application/json".toMediaType()),
                timeout = 15L
            )
            "PUT" -> app.put(
                url,
                headers = headers,
                requestBody = body.toRequestBody("application/json".toMediaType()),
                timeout = 12L
            )
            "DELETE" -> app.delete(url, headers = headers, timeout = 12L)
            else -> app.get(url, headers = headers, timeout = 12L)
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
