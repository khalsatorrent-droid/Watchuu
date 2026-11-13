package com.watchuu.musicbox

import com.lagradost.cloudstream3.app
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Unified music track data.
 */
data class MusicTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val duration: Int = 0,
    val coverUrl: String = "",
    val source: String = "",
    val sourceId: String = "",
    val previewUrl: String = "",
    val isrc: String = "",
)

data class MusicAlbum(
    val id: String,
    val title: String,
    val artist: String,
    val coverUrl: String = "",
    val source: String = "",
    val year: String = "",
    val trackCount: Int = 0,
)

data class MusicArtist(
    val id: String,
    val name: String,
    val imageUrl: String = "",
    val source: String = "",
)

// ========== Deezer Public API (catalogue only) ==========

object DeezerApi {
    private const val BASE = "https://api.deezer.com"

    suspend fun search(query: String, limit: Int = 10): List<MusicTrack> {
        val resp = app.get("$BASE/search?q=${query.encodeUrl()}&limit=$limit")
        val data = JSONObject(resp.text).optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { parseTrack(data.optJSONObject(it)) }
    }

    suspend fun searchAlbums(query: String, limit: Int = 10): List<MusicAlbum> {
        val resp = app.get("$BASE/search/album?q=${query.encodeUrl()}&limit=$limit")
        val data = JSONObject(resp.text).optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { parseAlbum(data.optJSONObject(it)) }
    }

    suspend fun searchArtists(query: String, limit: Int = 10): List<MusicArtist> {
        val resp = app.get("$BASE/search/artist?q=${query.encodeUrl()}&limit=$limit")
        val data = JSONObject(resp.text).optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { parseArtist(data.optJSONObject(it)) }
    }

    suspend fun getTrack(id: String): MusicTrack? {
        val resp = app.get("$BASE/track/$id")
        return parseTrack(JSONObject(resp.text))
    }

    suspend fun getAlbumTracks(albumId: String): List<MusicTrack> {
        val resp = app.get("$BASE/album/$albumId")
        val obj = JSONObject(resp.text)
        val albumTitle = obj.optString("title", "")
        val albumCover = obj.optString("cover_xl", obj.optString("cover_big", ""))
        val tracks = obj.optJSONObject("tracks")?.optJSONArray("data") ?: return emptyList()
        return (0 until tracks.length()).mapNotNull { i ->
            parseTrack(tracks.optJSONObject(i))?.copy(album = albumTitle, coverUrl = albumCover)
        }
    }

    suspend fun getArtistTopTracks(artistId: String, limit: Int = 20): List<MusicTrack> {
        val resp = app.get("$BASE/artist/$artistId/top?limit=$limit")
        val data = JSONObject(resp.text).optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { parseTrack(data.optJSONObject(it)) }
    }

    suspend fun getChart(limit: Int = 20): List<MusicTrack> {
        val resp = app.get("$BASE/chart/0/tracks?limit=$limit")
        val data = JSONObject(resp.text).optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { parseTrack(data.optJSONObject(it)) }
    }

    private fun parseTrack(obj: JSONObject?): MusicTrack? {
        obj ?: return null
        val id = obj.optLong("id", 0).toString()
        if (id == "0") return null
        return MusicTrack(
            id = "deezer:$id",
            title = obj.optString("title", ""),
            artist = obj.optJSONObject("artist")?.optString("name", "") ?: "",
            album = obj.optJSONObject("album")?.optString("title", "") ?: "",
            duration = obj.optInt("duration", 0),
            coverUrl = obj.optJSONObject("album")?.optString("cover_xl",
                obj.optJSONObject("album")?.optString("cover_big", "")) ?: "",
            source = "deezer",
            sourceId = id,
            previewUrl = obj.optString("preview", ""),
            isrc = obj.optString("isrc", ""),
        )
    }

    private fun parseAlbum(obj: JSONObject?): MusicAlbum? {
        obj ?: return null
        val id = obj.optLong("id", 0).toString()
        if (id == "0") return null
        return MusicAlbum(
            id = "deezer-album:$id",
            title = obj.optString("title", ""),
            artist = obj.optJSONObject("artist")?.optString("name", "") ?: "",
            coverUrl = obj.optString("cover_xl", obj.optString("cover_big", "")),
            source = "deezer",
            trackCount = obj.optInt("nb_tracks", 0),
        )
    }

    private fun parseArtist(obj: JSONObject?): MusicArtist? {
        obj ?: return null
        val id = obj.optLong("id", 0).toString()
        if (id == "0") return null
        return MusicArtist(
            id = "deezer-artist:$id",
            name = obj.optString("name", ""),
            imageUrl = obj.optString("picture_xl", obj.optString("picture_big", "")),
            source = "deezer",
        )
    }
}

// ========== Apple Music / iTunes API ==========

object AppleMusicApi {
    private const val BASE = "https://itunes.apple.com"

    suspend fun search(query: String, limit: Int = 10): List<MusicTrack> {
        val resp = app.get("$BASE/search?term=${query.encodeUrl()}&media=music&entity=song&limit=$limit")
        val results = JSONObject(resp.text).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val obj = results.optJSONObject(i) ?: return@mapNotNull null
            val trackId = obj.optLong("trackId", 0).toString()
            MusicTrack(
                id = "apple:$trackId",
                title = obj.optString("trackName", ""),
                artist = obj.optString("artistName", ""),
                album = obj.optString("collectionName", ""),
                duration = (obj.optLong("trackTimeMillis", 0) / 1000).toInt(),
                coverUrl = obj.optString("artworkUrl100", "").replace("100x100", "600x600"),
                source = "apple",
                sourceId = trackId,
                previewUrl = obj.optString("previewUrl", ""),
            )
        }
    }
}

// ========== SoundCloud API ==========

object SoundCloudApi {
    private const val BASE = "https://api-v2.soundcloud.com"

    // Known-good fallback client IDs in priority order; the scraper fills this at runtime.
    // If Soundcloud rotates their client ID these statics will expire, but the scraper
    // below recovers automatically.
    private val FALLBACK_CLIENT_IDS = listOf(
        "iZIs9mchVcX5lhVRyQGGAYlNPVldzAoX",   // common public key (may expire)
    )

    @Volatile private var clientId: String = ""
    @Volatile private var clientIdFetchedAt: Long = 0L
    private const val CLIENT_ID_TTL_MS = 6 * 3600 * 1000L  // 6 hours

    private suspend fun getClientId(): String {
        // Return cached ID if still fresh
        val now = System.currentTimeMillis()
        if (clientId.isNotBlank() && (now - clientIdFetchedAt) < CLIENT_ID_TTL_MS) {
            return clientId
        }

        // Scrape client_id from SoundCloud page scripts
        // Try the page first; if that fails, try the web player directly
        val pageSources = listOf("https://soundcloud.com/", "https://soundcloud.com/discover")
        for (pageUrl in pageSources) {
            try {
                val page = app.get(pageUrl, headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                )).text
                // Find script URLs matching the CDN pattern
                val scriptUrls = Regex("""src="(https://a-v2\.sndcdn\.com/assets/[^"]+\.js)"""")
                    .findAll(page)
                    .map { it.groupValues[1] }
                    .toList()

                // Also look for inline client_id in the page itself
                val inlineMatch = Regex("""[,"']client_id[,"']\s*:\s*[,"']([a-zA-Z0-9]{32})[,"']""").find(page)
                if (inlineMatch != null) {
                    clientId = inlineMatch.groupValues[1]
                    clientIdFetchedAt = now
                    return clientId
                }

                for (scriptUrl in scriptUrls.take(5)) {
                    try {
                        val js = app.get(scriptUrl).text
                        val idMatch = Regex("""client_id\s*[:=]\s*["']([a-zA-Z0-9]{32})["']""").find(js)
                        if (idMatch != null) {
                            clientId = idMatch.groupValues[1]
                            clientIdFetchedAt = now
                            return clientId
                        }
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }

        // Try fallbacks — validate each one before trusting it
        for (fallback in FALLBACK_CLIENT_IDS) {
            try {
                val testResp = app.get(
                    "$BASE/search/tracks?q=test&client_id=$fallback&limit=1",
                    headers = mapOf("User-Agent" to "Mozilla/5.0")
                )
                if (testResp.code == 200) {
                    clientId = fallback
                    clientIdFetchedAt = now
                    return clientId
                }
            } catch (_: Exception) {}
        }

        throw Exception("Failed to get SoundCloud client_id")
    }

    suspend fun search(query: String, limit: Int = 10): List<MusicTrack> {
        val cid = getClientId()
        val resp = app.get(
            "$BASE/search/tracks?q=${query.encodeUrl()}&client_id=$cid&limit=$limit",
            headers = mapOf("User-Agent" to "Mozilla/5.0")
        )
        if (resp.code == 401) {
            // client_id expired — invalidate and throw so caller can retry
            clientId = ""
            throw Exception("SoundCloud client_id expired (401)")
        }
        val collection = JSONObject(resp.text).optJSONArray("collection") ?: return emptyList()
        return (0 until collection.length()).mapNotNull { i ->
            val obj = collection.optJSONObject(i) ?: return@mapNotNull null
            val id = obj.optLong("id", 0).toString()
            MusicTrack(
                id = "soundcloud:$id",
                title = obj.optString("title", ""),
                artist = obj.optJSONObject("user")?.optString("username", "") ?: "",
                duration = (obj.optLong("duration", 0) / 1000).toInt(),
                coverUrl = obj.optString("artwork_url", "").replace("-large", "-t500x500"),
                source = "soundcloud",
                sourceId = id,
            )
        }
    }

    /**
     * Resolve a stream URL for a SoundCloud track.
     * Prefers progressive (direct MP3) over HLS.
     */
    suspend fun getStreamUrl(trackId: String): String {
        val cid = getClientId()
        val resp = app.get(
            "$BASE/tracks/$trackId?client_id=$cid",
            headers = mapOf("User-Agent" to "Mozilla/5.0")
        )
        if (resp.code == 401) { clientId = ""; throw Exception("SoundCloud 401") }
        val track = JSONObject(resp.text)
        val media = track.optJSONObject("media") ?: return ""
        val transcodings = media.optJSONArray("transcodings") ?: return ""

        var progressiveUrl = ""
        var hlsUrl = ""

        for (i in 0 until transcodings.length()) {
            val t = transcodings.optJSONObject(i) ?: continue
            val url = t.optString("url", "")
            val format = t.optJSONObject("format") ?: continue
            val protocol = format.optString("protocol", "")
            val mimeType = format.optString("mime_type", "")

            if (url.isBlank()) continue

            when {
                protocol == "progressive" && progressiveUrl.isBlank() -> progressiveUrl = url
                protocol == "hls" && hlsUrl.isBlank() -> hlsUrl = url
            }
        }

        val bestUrl = if (progressiveUrl.isNotBlank()) progressiveUrl else hlsUrl
        if (bestUrl.isBlank()) return ""

        val streamResp = app.get("$bestUrl?client_id=$cid", headers = mapOf("User-Agent" to "Mozilla/5.0"))
        if (streamResp.code == 401) { clientId = ""; throw Exception("SoundCloud stream 401") }
        val streamJson = JSONObject(streamResp.text)
        return streamJson.optString("url", "")
    }
}

// ========== Tidal API ==========

object TidalApi {
    private const val BASE = "https://api.tidal.com/v1"
    private const val TOKEN = "49YxDN9a2aFV6RTG"

    private fun headers() = mapOf(
        "X-Tidal-Token" to TOKEN,
        "Accept" to "application/json",
    )

    suspend fun search(query: String, limit: Int = 10): List<MusicTrack> {
        val resp = app.get(
            "$BASE/search?query=${query.encodeUrl()}&limit=$limit&offset=0&types=TRACKS&countryCode=US",
            headers = headers()
        )
        val tracks = JSONObject(resp.text).optJSONObject("tracks")?.optJSONArray("items") ?: return emptyList()
        return (0 until tracks.length()).mapNotNull { i ->
            val obj = tracks.optJSONObject(i) ?: return@mapNotNull null
            val id = obj.optLong("id", 0).toString()
            val albumObj = obj.optJSONObject("album")
            val coverId = albumObj?.optString("cover", "")?.replace("-", "/") ?: ""
            val artists = obj.optJSONArray("artists")
            MusicTrack(
                id = "tidal:$id",
                title = obj.optString("title", ""),
                artist = if (artists != null && artists.length() > 0)
                    artists.optJSONObject(0)?.optString("name", "") ?: "" else "",
                album = albumObj?.optString("title", "") ?: "",
                duration = obj.optInt("duration", 0),
                coverUrl = if (coverId.isNotBlank()) "https://resources.tidal.com/images/$coverId/640x640.jpg" else "",
                source = "tidal",
                sourceId = id,
                isrc = obj.optString("isrc", ""),
            )
        }
    }
}

// ========== Qobuz API ==========

object QobuzApi {
    private const val BASE = "https://www.qobuz.com/api.json/0.2"
    private const val APP_ID = "798273057"

    private fun headers() = mapOf(
        "X-App-Id" to APP_ID,
        "Accept" to "application/json",
    )

    suspend fun search(query: String, limit: Int = 10): List<MusicTrack> {
        val resp = app.get(
            "$BASE/track/search?query=${query.encodeUrl()}&limit=$limit",
            headers = headers()
        )
        val tracks = JSONObject(resp.text).optJSONObject("tracks")?.optJSONArray("items") ?: return emptyList()
        return (0 until tracks.length()).mapNotNull { i ->
            val obj = tracks.optJSONObject(i) ?: return@mapNotNull null
            val id = obj.optLong("id", 0).toString()
            val albumObj = obj.optJSONObject("album")
            val imageObj = albumObj?.optJSONObject("image")
            MusicTrack(
                id = "qobuz:$id",
                title = obj.optString("title", ""),
                artist = obj.optJSONObject("performer")?.optString("name", "") ?: "",
                album = albumObj?.optString("title", "") ?: "",
                duration = obj.optInt("duration", 0),
                coverUrl = imageObj?.optString("large", imageObj.optString("small", "")) ?: "",
                source = "qobuz",
                sourceId = id,
                isrc = obj.optString("isrc", ""),
            )
        }
    }
}

// ========== JioSaavn API ==========

object JioSaavnApi {
    private const val BASE = "https://www.jiosaavn.com/api.php"
    private const val DES_KEY = "38346591"

    private val defaultHeaders = mapOf(
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "en-US,en;q=0.9",
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to "https://www.jiosaavn.com/",
        "User-Agent" to "Mozilla/5.0",
        "Cookie" to "DL=english; geo=19.0760%2C72.8777%2CIN%2CMaharashtra%2CMumbai%2C400001",
    )

    suspend fun search(query: String, limit: Int = 10): List<MusicTrack> {
        val resp = app.get(
            "$BASE?__call=search.getResults&_format=json&_marker=0&api_version=4&ctx=web6dot0" +
                    "&q=${query.encodeUrl()}&p=1&n=$limit",
            headers = defaultHeaders
        )
        val results = JSONObject(resp.text).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val obj = results.optJSONObject(i) ?: return@mapNotNull null
            parseSaavnTrack(obj)
        }
    }

    suspend fun getTrackById(id: String): MusicTrack? {
        val resp = app.get(
            "$BASE?__call=song.getDetails&_format=json&_marker=0&pids=$id&ctx=web6dot0",
            headers = defaultHeaders
        )
        val obj = JSONObject(resp.text)
        val songs = obj.optJSONObject("songs") ?: obj.optJSONArray("songs")?.let { arr ->
            if (arr.length() > 0) arr.optJSONObject(0) else null
        }
        return songs?.let { parseSaavnTrack(it) }
    }

    fun decryptMediaUrl(encrypted: String): String {
        if (encrypted.isBlank()) return ""
        return try {
            val keyBytes = DES_KEY.toByteArray(Charsets.UTF_8)
            val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "DES"))
            val decoded = android.util.Base64.decode(encrypted, android.util.Base64.DEFAULT)
            val decrypted = cipher.doFinal(decoded)
            String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    suspend fun getStreamUrl(trackId: String, quality: String = "320"): String {
        val resp = app.get(
            "$BASE?__call=song.getDetails&_format=json&_marker=0&pids=$trackId&ctx=web6dot0",
            headers = defaultHeaders
        )
        val obj = JSONObject(resp.text)
        val song = obj.optJSONObject(trackId)
            ?: obj.optJSONArray("songs")?.optJSONObject(0)
            ?: return ""

        val encryptedUrl = song.optString("encrypted_media_url", "")
        if (encryptedUrl.isBlank()) return ""

        val decrypted = decryptMediaUrl(encryptedUrl)
        if (decrypted.isBlank()) return ""

        return decrypted.replace("_96.", "_${quality}.")
    }

    private fun parseSaavnTrack(obj: JSONObject): MusicTrack? {
        val id = obj.optString("id", "")
        if (id.isBlank()) return null

        val imageUrl = obj.optString("image", "")
            .replace("150x150", "500x500")
            .replace("50x50", "500x500")

        val artistMap = obj.optJSONObject("artistMap")?.optJSONArray("primary_artists")
        val artist = when {
            artistMap != null && artistMap.length() > 0 ->
                (0 until artistMap.length()).joinToString(", ") {
                    artistMap.optJSONObject(it)?.optString("name", "") ?: ""
                }
            else -> obj.optString("primary_artists", obj.optString("subtitle", ""))
        }

        val moreInfo = obj.optJSONObject("more_info")
        val encryptedUrl = obj.optString("encrypted_media_url",
            moreInfo?.optString("encrypted_media_url", "") ?: "")

        return MusicTrack(
            id = "jiosaavn:$id",
            title = obj.optString("title", obj.optString("song", "")),
            artist = artist,
            album = obj.optString("album", ""),
            duration = obj.optString("duration", "0").toIntOrNull() ?: 0,
            coverUrl = imageUrl,
            source = "jiosaavn",
            sourceId = id,
            previewUrl = if (encryptedUrl.isNotBlank()) decryptMediaUrl(encryptedUrl) else "",
        )
    }
}

// ========== Beatport API (public catalogue) ==========

object BeatportApi {
    private const val BASE = "https://www.beatport.com/api/v4"

    suspend fun search(query: String, limit: Int = 10): List<MusicTrack> {
        return try {
            val resp = app.get(
                "$BASE/catalog/search?q=${query.encodeUrl()}&type=tracks&per_page=$limit",
                headers = mapOf(
                    "Accept" to "application/json",
                    "User-Agent" to "Mozilla/5.0",
                )
            )
            if (resp.code != 200) return emptyList()
            val results = JSONObject(resp.text).optJSONArray("results") ?: return emptyList()
            (0 until results.length()).mapNotNull { i ->
                val obj = results.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optLong("id", 0).toString()
                if (id == "0") return@mapNotNull null
                val artists = obj.optJSONArray("artists")
                val artistName = if (artists != null && artists.length() > 0)
                    artists.optJSONObject(0)?.optString("name", "") ?: "" else ""
                val releaseObj = obj.optJSONObject("release")
                val coverUrl = releaseObj?.optJSONObject("image")?.optString("uri", "") ?: ""
                MusicTrack(
                    id = "beatport:$id",
                    title = obj.optString("name", ""),
                    artist = artistName,
                    album = releaseObj?.optString("name", "") ?: "",
                    duration = obj.optInt("length_ms", 0) / 1000,
                    coverUrl = coverUrl,
                    source = "beatport",
                    sourceId = id,
                    isrc = obj.optString("isrc", ""),
                )
            }
        } catch (_: Exception) { emptyList() }
    }
}

// ========== Blowfish Decryption for Deezer ==========

object DeezerDecrypt {
    private const val BLOWFISH_SECRET = "g4el58wc0zvf9na1"
    private val BLOWFISH_IV = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)
    private const val CHUNK_SIZE = 2048

    fun generateKey(trackId: String): ByteArray {
        val md5 = java.security.MessageDigest.getInstance("MD5")
            .digest(trackId.toByteArray())
            .joinToString("") { "%02x".format(it) }

        return ByteArray(16) { i ->
            (md5[i].code xor md5[i + 16].code xor BLOWFISH_SECRET[i].code).toByte()
        }
    }

    fun decrypt(encryptedData: ByteArray, trackId: String): ByteArray {
        val key = generateKey(trackId)
        val cipher = Cipher.getInstance("Blowfish/CBC/NoPadding")
        val keySpec = SecretKeySpec(key, "Blowfish")
        val ivSpec = javax.crypto.spec.IvParameterSpec(BLOWFISH_IV)

        val output = encryptedData.copyOf()
        var offset = 0
        var chunkIndex = 0

        while (offset + CHUNK_SIZE <= output.size) {
            if (chunkIndex % 3 == 0) {
                cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
                val decrypted = cipher.doFinal(output, offset, CHUNK_SIZE)
                System.arraycopy(decrypted, 0, output, offset, CHUNK_SIZE)
            }
            offset += CHUNK_SIZE
            chunkIndex++
        }

        return output
    }
}

// ========== YouTube Music search (for playback fallback) ==========

object YouTubeMusicApi {
    private const val BASE = "https://music.youtube.com/youtubei/v1"

    // The API key is a public key tied to the WEB_REMIX client.
    // It is relatively stable but may need updating if YT rotates it.
    private const val API_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"

    private val context = JSONObject().apply {
        put("client", JSONObject().apply {
            put("clientName", "WEB_REMIX")
            put("clientVersion", "1.20231204.01.00")
            put("hl", "en")
            put("gl", "US")
        })
    }

    suspend fun searchVideoId(query: String): String? {
        val payload = JSONObject().apply {
            put("context", context)
            put("query", query)
        }

        val resp = app.post(
            "$BASE/search?key=$API_KEY",
            headers = mapOf(
                "Content-Type" to "application/json",
                "User-Agent" to "Mozilla/5.0",
                "Referer" to "https://music.youtube.com/",
                "Origin" to "https://music.youtube.com",
            ),
            requestBody = payload.toString()
                .toRequestBody("application/json".toMediaType())
        )

        if (resp.code != 200) return null
        val text = resp.text
        val videoIdMatch = Regex(""""videoId"\s*:\s*"([a-zA-Z0-9_-]{11})"""").find(text)
        return videoIdMatch?.groupValues?.get(1)
    }
}

// Utility extensions
private fun String.encodeUrl(): String = java.net.URLEncoder.encode(this, "UTF-8")
