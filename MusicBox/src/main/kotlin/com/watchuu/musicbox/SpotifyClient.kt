package com.watchuu.musicbox

import com.lagradost.cloudstream3.app
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Spotify Web catalogue client using TOTP-based anonymous token authentication.
 * Adapted from the SpotiFLAC Spotify extension.
 */
object SpotifyClient {
    private const val TAG = "SpotifyClient"

    // TOTP config from Spotify extension
    private val TOTP_SECRET = intArrayOf(
        44, 55, 47, 42, 70, 40, 34, 114, 76, 74, 50, 111, 120, 97, 75, 76,
        94, 102, 43, 69, 49, 120, 118, 80, 64, 78
    )
    private const val TOTP_VERSION = 61
    private const val SEARCH_HASH = "fcad5a3e0d5af727fb76966f06971c19cfa2275e6ff7671196753e008611873c"

    private const val BASE_URL = "https://api-partner.spotify.com"
    private const val AUTH_URL = "https://open.spotify.com/get_access_token?reason=transport&productType=web_player"
    private const val CLIENT_TOKEN_URL = "https://clienttoken.spotify.com/v1/clienttoken"
    private const val PATHFINDER_URL = "$BASE_URL/pathfinder/v1/query"

    @Volatile private var accessToken: String = ""
    @Volatile private var clientToken: String = ""
    @Volatile private var tokenExpiry: Long = 0

    // ========== TOTP implementation ==========

    private fun generateTOTP(): String {
        val timeStep = System.currentTimeMillis() / 30000
        val timeBytes = ByteArray(8)
        var t = timeStep
        for (i in 7 downTo 0) {
            timeBytes[i] = (t and 0xFF).toByte()
            t = t shr 8
        }

        val secretBytes = ByteArray(TOTP_SECRET.size) { TOTP_SECRET[it].toByte() }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secretBytes, "HmacSHA1"))
        val hash = mac.doFinal(timeBytes)

        val offset = hash[hash.size - 1].toInt() and 0x0F
        val code = ((hash[offset].toInt() and 0x7F) shl 24) or
                ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                ((hash[offset + 2].toInt() and 0xFF) shl 8) or
                (hash[offset + 3].toInt() and 0xFF)

        return String.format("%06d", code % 1000000)
    }

    // ========== Auth ==========

    private suspend fun ensureTokens() {
        if (accessToken.isNotBlank() && clientToken.isNotBlank() && System.currentTimeMillis() < tokenExpiry) return

        // Get access token
        val authResp = app.get(AUTH_URL, headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "application/json",
            "Referer" to "https://open.spotify.com/",
            "Origin" to "https://open.spotify.com",
        ))
        val authJson = JSONObject(authResp.text)
        accessToken = authJson.optString("accessToken", "")
        val expiresIn = authJson.optLong("accessTokenExpirationTimestampMs", 0)
        if (accessToken.isBlank()) throw Exception("Failed to get Spotify access token")

        // Get client token
        val totp = generateTOTP()
        val clientTokenPayload = JSONObject().apply {
            put("client_data", JSONObject().apply {
                put("client_version", "1.2.52.442.g0f1fed8b")
                put("client_id", "d8a5ed958d274c2e8ee717e6a4b0971d")
                put("js_sdk_data", JSONObject().apply {
                    put("device_brand", "unknown")
                    put("device_model", "unknown")
                    put("os", "windows")
                    put("os_version", "NT 10.0")
                    put("device_id", "")
                    put("device_type", "computer")
                })
            })
            put("totp", JSONObject().apply {
                put("timestamp", System.currentTimeMillis() / 30000)
                put("totp_code", totp)
                put("totp_version", TOTP_VERSION)
            })
        }

        val ctResp = app.post(
            CLIENT_TOKEN_URL,
            headers = mapOf(
                "Content-Type" to "application/json",
                "Accept" to "application/json",
                "User-Agent" to "Mozilla/5.0",
            ),
            requestBody = clientTokenPayload.toString()
                .toRequestBody("application/json".toMediaTypeOrNull())
        )
        val ctJson = JSONObject(ctResp.text)
        clientToken = ctJson.optJSONObject("granted_token")?.optString("token", "") ?: ""
        tokenExpiry = System.currentTimeMillis() + 1800_000 // 30min
    }

    private fun authHeaders(): Map<String, String> = mapOf(
        "Authorization" to "Bearer $accessToken",
        "Client-Token" to clientToken,
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "Accept" to "application/json",
        "Origin" to "https://open.spotify.com",
        "Referer" to "https://open.spotify.com/",
        "App-Platform" to "WebPlayer",
    )

    // ========== Pathfinder API ==========

    private suspend fun pathfinderQuery(hash: String, variables: JSONObject): JSONObject {
        ensureTokens()
        val url = "$PATHFINDER_URL?operationName=searchDesktop" +
                "&variables=${java.net.URLEncoder.encode(variables.toString(), "UTF-8")}" +
                "&extensions=${java.net.URLEncoder.encode(
                    JSONObject().apply {
                        put("persistedQuery", JSONObject().apply {
                            put("version", 1)
                            put("sha256Hash", hash)
                        })
                    }.toString(), "UTF-8"
                )}"

        val resp = app.get(url, headers = authHeaders())
        return JSONObject(resp.text)
    }

    // ========== Public API ==========

    data class SpotifyTrack(
        val id: String,
        val name: String,
        val artist: String,
        val album: String,
        val duration: Int, // ms
        val coverUrl: String,
        val isrc: String = "",
    )

    data class SpotifyAlbum(
        val id: String,
        val name: String,
        val artist: String,
        val coverUrl: String,
        val tracks: List<SpotifyTrack> = emptyList(),
        val year: String = "",
    )

    data class SpotifyArtist(
        val id: String,
        val name: String,
        val imageUrl: String,
    )

    suspend fun search(query: String, limit: Int = 10): Triple<List<SpotifyTrack>, List<SpotifyAlbum>, List<SpotifyArtist>> {
        ensureTokens()

        val variables = JSONObject().apply {
            put("searchTerm", query)
            put("offset", 0)
            put("limit", limit)
            put("numberOfTopResults", 5)
            put("includeAudiobooks", false)
            put("includePreReleases", false)
            put("includeLocalConcerts", false)
        }

        val result = pathfinderQuery(SEARCH_HASH, variables)
        val data = result.optJSONObject("data")?.optJSONObject("searchV2") ?: return Triple(emptyList(), emptyList(), emptyList())

        val tracks = mutableListOf<SpotifyTrack>()
        val albums = mutableListOf<SpotifyAlbum>()
        val artists = mutableListOf<SpotifyArtist>()

        // Parse tracks
        data.optJSONObject("tracksV2")?.optJSONArray("items")?.let { items ->
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i)?.optJSONObject("item")?.optJSONObject("data") ?: continue
                tracks.add(parseTrack(item))
            }
        }

        // Parse albums
        data.optJSONObject("albums")?.optJSONArray("items")?.let { items ->
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i)?.optJSONObject("data") ?: continue
                albums.add(parseAlbum(item))
            }
        }

        // Parse artists
        data.optJSONObject("artists")?.optJSONArray("items")?.let { items ->
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i)?.optJSONObject("data") ?: continue
                artists.add(parseArtist(item))
            }
        }

        return Triple(tracks, albums, artists)
    }

    suspend fun getHomeTracks(): List<SpotifyTrack> {
        // Use search with popular terms as fallback for home page content
        val (tracks, _, _) = search("top hits 2024", 20)
        return tracks
    }

    // ========== Parsers ==========

    private fun parseTrack(data: JSONObject): SpotifyTrack {
        val uri = data.optString("uri", "")
        val id = uri.substringAfterLast(":")
        val name = data.optString("name", "")

        val artists = data.optJSONObject("artists")?.optJSONArray("items")
        val artistName = if (artists != null && artists.length() > 0) {
            (0 until artists.length()).joinToString(", ") {
                artists.optJSONObject(it)?.optJSONObject("profile")?.optString("name", "") ?: ""
            }
        } else ""

        val album = data.optJSONObject("albumOfTrack")
        val albumName = album?.optString("name", "") ?: ""
        val coverUrl = album?.optJSONObject("coverArt")?.optJSONArray("sources")
            ?.optJSONObject(0)?.optString("url", "") ?: ""

        val duration = data.optJSONObject("duration")?.optInt("totalMilliseconds", 0) ?: 0

        val isrc = data.optJSONObject("externalId")?.optString("isrc", "") ?: ""

        return SpotifyTrack(
            id = id, name = name, artist = artistName, album = albumName,
            duration = duration, coverUrl = coverUrl, isrc = isrc
        )
    }

    private fun parseAlbum(data: JSONObject): SpotifyAlbum {
        val uri = data.optString("uri", "")
        val id = uri.substringAfterLast(":")
        val name = data.optString("name", "")

        val artists = data.optJSONObject("artists")?.optJSONArray("items")
        val artistName = if (artists != null && artists.length() > 0) {
            artists.optJSONObject(0)?.optJSONObject("profile")?.optString("name", "") ?: ""
        } else ""

        val coverUrl = data.optJSONObject("coverArt")?.optJSONArray("sources")
            ?.optJSONObject(0)?.optString("url", "") ?: ""

        val year = data.optJSONObject("date")?.optString("year", "") ?: ""

        return SpotifyAlbum(id = id, name = name, artist = artistName, coverUrl = coverUrl, year = year)
    }

    private fun parseArtist(data: JSONObject): SpotifyArtist {
        val uri = data.optString("uri", "")
        val id = uri.substringAfterLast(":")
        val name = data.optJSONObject("profile")?.optString("name", "") ?: ""
        val imageUrl = data.optJSONObject("visuals")?.optJSONObject("avatarImage")
            ?.optJSONArray("sources")?.optJSONObject(0)?.optString("url", "") ?: ""

        return SpotifyArtist(id = id, name = name, imageUrl = imageUrl)
    }
}
