package com.watchuu.musicbox

import com.lagradost.cloudstream3.app
import java.net.URLEncoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * Spotify's web client, as spotify-web.sflx implements it.
 *
 * The anonymous token is not a plain GET: Spotify wants a TOTP whose secret is built at runtime
 * from a byte array, so the whole chain is reproduced here:
 *
 *   totp()        secret bytes -> xor with (i % 33) + 9 -> decimal string -> hex -> base32
 *                 -> HMAC-SHA1(secret, counter) -> 6 digits
 *   token()       GET open.spotify.com/api/token?reason=init&productType=web-player
 *                 &totp=<code>&totpVer=61&totpServer=<code>
 *   clientToken() POST clienttoken.spotify.com/v1/clienttoken -> granted_token.token
 *   search()      POST api-partner.spotify.com/pathfinder/v2/query, operationName searchDesktop
 *
 * The persisted-query hash is the one spotify-web carries. If Spotify changes it, search stops -
 * that is the risk of any client of theirs, and it is confined to this one file.
 */
object SpotifyClient {
    private const val TOTP_VERSION = "61"
    private val SECRET_61 = intArrayOf(
        44, 55, 47, 42, 70, 40, 34, 114, 76, 74, 50, 111, 120, 97, 75, 76, 94, 102,
        43, 69, 49, 120, 118, 80, 64, 78
    )
    private const val SEARCH_HASH =
        "fcad5a3e0d5af727fb76966f06971c19cfa2275e6ff7671196753e008611873c"

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/156.0.0.0 Safari/537.36"

    @Volatile private var accessToken: String? = null
    @Volatile private var accessExpiry: Long = 0L
    @Volatile private var clientToken: String? = null
    @Volatile private var clientId: String? = null
    @Volatile private var clientVersion: String? = null
    @Volatile private var deviceId: String? = null

    // ── totp ───────────────────────────────────────────────────────────────────

    private fun base32Encode(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val sb = StringBuilder()
        var bits = 0
        var value = 0
        for (b in bytes) {
            value = (value shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                sb.append(alphabet[(value ushr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(alphabet[(value shl (5 - bits)) and 31])
        return sb.toString()
    }

    private fun base32Decode(text: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var bits = 0
        var value = 0
        val out = java.io.ByteArrayOutputStream()
        for (c in text.uppercase().trimEnd('=')) {
            val idx = alphabet.indexOf(c)
            if (idx < 0) continue
            value = (value shl 5) or idx
            bits += 5
            if (bits >= 8) {
                out.write((value ushr (bits - 8)) and 0xff)
                bits -= 8
            }
        }
        return out.toByteArray()
    }

    private fun totp(): String {
        // the secret is assembled at runtime exactly as the extension does it
        val transformed = SECRET_61.mapIndexed { i, v -> v xor ((i % 33) + 9) }
        val joined = transformed.joinToString("") { it.toString() }
        val hex = joined.map { "%02x".format(it.code) }.joinToString("")
        val raw = ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        val key = base32Decode(base32Encode(raw))

        val counter = System.currentTimeMillis() / 1000 / 30
        val msg = ByteArray(8) { i -> ((counter ushr (8 * (7 - i))) and 0xff).toByte() }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        val h = mac.doFinal(msg)

        val off = h[h.size - 1].toInt() and 0x0f
        val code = (((h[off].toInt() and 0x7f) shl 24) or
            ((h[off + 1].toInt() and 0xff) shl 16) or
            ((h[off + 2].toInt() and 0xff) shl 8) or
            (h[off + 3].toInt() and 0xff)) % 1_000_000
        return code.toString().padStart(6, '0')
    }

    // ── session ────────────────────────────────────────────────────────────────

    private fun headers(): Map<String, String> = mapOf(
        "User-Agent" to UA,
        "Accept" to "application/json",
        "Origin" to "https://open.spotify.com",
        "Referer" to "https://open.spotify.com/"
    )

    private suspend fun ensureToken(): Boolean {
        if (accessToken != null && System.currentTimeMillis() < accessExpiry) return true
        return runCatching {
            val code = totp()
            val url = "https://open.spotify.com/api/token?reason=init&productType=web-player" +
                "&totp=$code&totpVer=$TOTP_VERSION&totpServer=$code"
            val json = JSONObject(app.get(url, headers = headers(), timeout = 30L).text)
            val token = json.optString("accessToken").ifBlank {
                json.optString("access_token")
            }.ifBlank { return false }

            accessToken = token
            clientId = json.optString("clientId").ifBlank { json.optString("client_id") }
                .ifBlank { null }
            clientVersion = json.optString("clientVersion")
                .ifBlank { json.optString("runningVersion") }.ifBlank { "1.2.60.1" }
            val ttl = json.optLong("accessTokenExpirationTimestampMs", 0L)
            accessExpiry = if (ttl > 0) ttl - 60_000L else System.currentTimeMillis() + 30 * 60_000L
            deviceId = deviceId ?: java.util.UUID.randomUUID().toString().replace("-", "").take(16)
            true
        }.getOrDefault(false)
    }

    private suspend fun ensureClientToken(): String? {
        clientToken?.let { return it }
        if (!ensureToken()) return null

        val payload = JSONObject().apply {
            put("client_data", JSONObject().apply {
                put("client_version", clientVersion)
                put("client_id", clientId)
                put("js_sdk_data", JSONObject().apply {
                    put("device_brand", "unknown")
                    put("device_model", "unknown")
                    put("os", "windows")
                    put("os_version", "NT 10.0")
                    put("device_id", deviceId)
                    put("device_type", "computer")
                })
            })
        }

        return runCatching {
            val res = app.post(
                "https://clienttoken.spotify.com/v1/clienttoken",
                headers = headers() + mapOf("Content-Type" to "application/json"),
                requestBody = okhttp3.RequestBody.create(
                    okhttp3.MediaType.parse("application/json"), payload.toString()
                ),
                timeout = 30L
            ).text
            val json = JSONObject(res)
            json.optJSONObject("granted_token")?.optString("token")?.ifBlank { null }
                ?.also { clientToken = it }
        }.getOrNull()
    }

    // ── search ─────────────────────────────────────────────────────────────────

    suspend fun search(query: String): List<JSONObject> {
        if (query.isBlank()) return emptyList()
        if (!ensureToken()) return emptyList()
        val cToken = ensureClientToken() ?: return emptyList()

        val payload = JSONObject().apply {
            put("variables", JSONObject().apply {
                put("searchTerm", query)
                put("offset", 0)
                put("limit", 20)
                put("numberOfTopResults", 5)
                put("includeAudiobooks", true)
                put("includeArtistHasConcertsField", false)
                put("includePreReleases", true)
                put("includeAuthors", false)
            })
            put("operationName", "searchDesktop")
            put("extensions", JSONObject().apply {
                put("persistedQuery", JSONObject().apply {
                    put("version", 1)
                    put("sha256Hash", SEARCH_HASH)
                })
            })
        }

        val res = runCatching {
            app.post(
                "https://api-partner.spotify.com/pathfinder/v2/query",
                headers = headers() + mapOf(
                    "Authorization" to "Bearer $accessToken",
                    "Client-Token" to cToken,
                    "Spotify-App-Version" to (clientVersion ?: "1.2.60.1"),
                    "Content-Type" to "application/json",
                    "app-platform" to "WebPlayer"
                ),
                requestBody = okhttp3.RequestBody.create(
                    okhttp3.MediaType.parse("application/json"), payload.toString()
                ),
                timeout = 30L
            ).text
        }.getOrNull() ?: return emptyList()

        // walk the answer for anything that is a track; the shape has changed more than once and
        // this does not care where in the tree it sits
        val out = ArrayList<JSONObject>()
        runCatching { walk(JSONObject(res), out, 0) }
        return out
    }

    private fun walk(node: Any?, into: MutableList<JSONObject>, depth: Int) {
        if (depth > 12 || into.size >= 40) return
        when (node) {
            is JSONObject -> {
                val uri = node.optString("uri")
                if (uri.startsWith("spotify:track:")) into.add(node)
                for (key in node.keys()) walk(node.opt(key), into, depth + 1)
            }
            is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), into, depth + 1)
        }
    }

    fun trackIdOf(node: JSONObject): String? =
        node.optString("uri").removePrefix("spotify:track:").ifBlank { null }

    fun titleOf(node: JSONObject): String =
        node.optString("name").ifBlank { node.optString("title") }

    fun artistsOf(node: JSONObject): String {
        val artists = node.optJSONObject("artists")?.optJSONArray("items")
            ?: node.optJSONArray("artists")
            ?: return ""
        val names = ArrayList<String>()
        for (i in 0 until artists.length()) {
            val o = artists.optJSONObject(i) ?: continue
            val profile = o.optJSONObject("profile")?.optString("name")
            val name = profile?.ifBlank { null } ?: o.optString("name")
            if (name.isNotBlank()) names.add(name)
        }
        return names.joinToString(", ")
    }

    fun coverOf(node: JSONObject): String? {
        val sources = node.optJSONObject("albumOfTrack")?.optJSONObject("coverArt")
            ?.optJSONArray("sources") ?: return null
        // the last entry is the largest
        return (0 until sources.length()).mapNotNull { sources.optJSONObject(it)?.optString("url") }
            .lastOrNull { it.isNotBlank() }
    }
}
