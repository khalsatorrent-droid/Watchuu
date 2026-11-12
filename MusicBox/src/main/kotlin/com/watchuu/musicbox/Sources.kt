package com.watchuu.musicbox

import com.lagradost.cloudstream3.app
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
    val duration: Int = 0, // seconds
    val coverUrl: String = "",
    val source: String = "",
    val sourceId: String = "", // ID within that source
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
                coverUrl = obj.optString("artworkUrl100", "")
                    .replace("100x100", "600x600"),
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
    @Volatile private var clientId: String = ""

    private suspend fun getClientId(): String {
        if (clientId.isNotBlank()) return clientId
        // Scrape client_id from SoundCloud page scripts
        val page = app.get("https://soundcloud.com/").text
        val scripts = Regex("""src="(https://a-v2\.sndcdn\.com/assets/[^"]+\.js)"""").findAll(page)
        for (match in scripts) {
            try {
                val js = app.get(match.groupValues[1]).text
                val idMatch = Regex("""client_id\s*[:=]\s*["']([a-zA-Z0-9]{32})["']""").find(js)
                if (idMatch != null) {
                    clientId = idMatch.groupValues[1]
                    return clientId
                }
            } catch (_: Exception) {}
        }
        throw Exception("Failed to get SoundCloud client_id")
    }

    suspend fun search(query: String, limit: Int = 10): List<MusicTrack> {
        val cid = getClientId()
        val resp = app.get("$BASE/search/tracks?q=${query.encodeUrl()}&client_id=$cid&limit=$limit")
        val collection = JSONObject(resp.text).optJSONArray("collection") ?: return emptyList()
        return (0 until collection.length()).mapNotNull { i ->
            val obj = collection.optJSONObject(i) ?: return@mapNotNull null
            val id = obj.optLong("id", 0).toString()
            MusicTrack(
                id = "soundcloud:$id",
                title = obj.optString("title", ""),
                artist = obj.optJSONObject("user")?.optString("username", "") ?: "",
                duration = (obj.optLong("duration", 0) / 1000).toInt(),
                coverUrl = obj.optString("artwork_url", "")
                    .replace("-large", "-t500x500"),
                source = "soundcloud",
                sourceId = id,
            )
        }
    }

    /**
     * Resolve a stream URL for a SoundCloud track.
     * Uses the /tracks/{id} endpoint to get transcodings, then resolves the stream URL.
     */
    suspend fun getStreamUrl(trackId: String): String {
        val cid = getClientId()
        val resp = app.get("$BASE/tracks/$trackId?client_id=$cid")
        val track = JSONObject(resp.text)
        val media = track.optJSONObject("media") ?: return ""
        val transcodings = media.optJSONArray("transcodings") ?: return ""

        // Prefer progressive > HLS, and higher quality
        var bestUrl = ""
        for (i in 0 until transcodings.length()) {
            val t = transcodings.optJSONObject(i) ?: continue
            val url = t.optString("url", "")
            val format = t.optJSONObject("format") ?: continue
            val protocol = format.optString("protocol", "")

            if (url.isBlank()) continue

            // Prefer progressive (direct MP3) over HLS
            if (protocol == "progressive" || bestUrl.isBlank()) {
                bestUrl = url
            }
        }

        if (bestUrl.isBlank()) return ""

        // Resolve the stream URL (SoundCloud returns a redirect URL)
        val streamResp = app.get("$bestUrl?client_id=$cid")
        val streamJson = JSONObject(streamResp.text)
        return streamJson.optString("url", "")
    }
}

// ========== Tidal API ==========

object TidalApi {
    private const val BASE = "https://api.tidal.com/v1"
    private const val TOKEN = "49YxDN9a2aFV6RTG" // Public web token

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
                coverUrl = imageObj?.optString("large",
                    imageObj.optString("small", "")) ?: "",
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

    /**
     * Decrypt JioSaavn encrypted media URL using DES-ECB.
     */
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

    /**
     * Get direct streaming URL for a JioSaavn track.
     * Returns decrypted URL that can be played directly.
     */
    suspend fun getStreamUrl(trackId: String, quality: String = "320"): String {
        val resp = app.get(
            "$BASE?__call=song.getDetails&_format=json&_marker=0&pids=$trackId&ctx=web6dot0",
            headers = defaultHeaders
        )
        val obj = JSONObject(resp.text)
        // Navigate into songs structure
        val song = obj.optJSONObject(trackId)
            ?: obj.optJSONArray("songs")?.optJSONObject(0)
            ?: return ""

        val encryptedUrl = song.optString("encrypted_media_url", "")
        if (encryptedUrl.isBlank()) return ""

        val decrypted = decryptMediaUrl(encryptedUrl)
        if (decrypted.isBlank()) return ""

        // Replace quality in URL
        return decrypted.replace("_96.", "_${quality}.")
    }

    private fun parseSaavnTrack(obj: JSONObject): MusicTrack? {
        val id = obj.optString("id", "")
        if (id.isBlank()) return null

        val imageUrl = obj.optString("image", "")
            .replace("150x150", "500x500")
            .replace("50x50", "500x500")

        val artistsObj = obj.optJSONObject("more_info")
        val artistMap = obj.optJSONObject("artistMap")?.optJSONArray("primary_artists")

        val artist = when {
            artistMap != null && artistMap.length() > 0 ->
                (0 until artistMap.length()).joinToString(", ") {
                    artistMap.optJSONObject(it)?.optString("name", "") ?: ""
                }
            else -> obj.optString("primary_artists",
                obj.optString("subtitle", ""))
        }

        val encryptedUrl = obj.optString("encrypted_media_url",
            artistsObj?.optString("encrypted_media_url", "") ?: "")

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

// ========== Blowfish Decryption for Deezer ==========

object DeezerDecrypt {
    private const val BLOWFISH_SECRET = "g4el58wc0zvf9na1"
    private val BLOWFISH_IV = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)
    private const val CHUNK_SIZE = 2048

    /**
     * Generate Blowfish key for a given Deezer track ID.
     * key[i] = md5(trackId)[i] XOR md5(trackId)[i+16] XOR BLOWFISH_SECRET[i]
     */
    fun generateKey(trackId: String): ByteArray {
        val md5 = java.security.MessageDigest.getInstance("MD5")
            .digest(trackId.toByteArray())
            .joinToString("") { "%02x".format(it) }

        return ByteArray(16) { i ->
            (md5[i].code xor md5[i + 16].code xor BLOWFISH_SECRET[i].code).toByte()
        }
    }

    /**
     * Decrypt Deezer-encrypted audio data.
     * Pattern: every 3rd 2048-byte chunk is Blowfish-CBC encrypted.
     */
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
    private const val API_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"

    private val context = JSONObject().apply {
        put("client", JSONObject().apply {
            put("clientName", "WEB_REMIX")
            put("clientVersion", "1.20231204.01.00")
            put("hl", "en")
            put("gl", "US")
        })
    }

    /**
     * Search YouTube Music to find a video ID for playback.
     */
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
                .toRequestBody("application/json".toMediaTypeOrNull())
        )

        val text = resp.text
        // Extract first video ID from response
        val videoIdMatch = Regex(""""videoId"\s*:\s*"([a-zA-Z0-9_-]{11})"""").find(text)
        return videoIdMatch?.groupValues?.get(1)
    }
}

// Utility extension
private fun String.encodeUrl(): String = java.net.URLEncoder.encode(this, "UTF-8")
