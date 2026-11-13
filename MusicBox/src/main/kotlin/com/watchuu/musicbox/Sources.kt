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

/**
 * Audius — https://audius.co/
 *
 * Taken from musicdl's AudiusMusicClient, which is the cleanest source in that project: a public
 * JSON api, no key, no session, and a stream endpoint that redirects straight to the audio file.
 *
 *   search  https://api.audius.co/v1/tracks/search?query=&limit=&offset=&app_name=musicdl
 *   stream  https://api.audius.co/v1/tracks/{id}/stream?app_name=musicdl
 */
object AudiusApi {
    private const val BASE = "https://api.audius.co/v1"
    private const val APP = "musicdl"

    private val headers = mapOf(
        "accept" to "application/json",
        "referer" to "https://audius.co/",
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"
    )

    suspend fun search(query: String, limit: Int = 20): List<MusicTrack> {
        if (query.isBlank()) return emptyList()
        val url = "$BASE/tracks/search?query=${query.encodeUrl()}&limit=$limit&offset=0&app_name=$APP"
        val resp = app.get(url, headers = headers)
        val data = JSONObject(resp.text).optJSONArray("data") ?: return emptyList()

        val out = ArrayList<MusicTrack>()
        for (i in 0 until data.length()) {
            val o = data.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            val artwork = o.optJSONObject("artwork")
            out.add(
                MusicTrack(
                    id = id,
                    title = o.optString("title"),
                    artist = o.optJSONObject("user")?.optString("name").orEmpty()
                        .ifBlank { o.optJSONObject("user")?.optString("handle").orEmpty() },
                    album = o.optString("album_name").ifBlank { o.optString("playlist_name") },
                    duration = o.optDouble("duration", 0.0).toInt(),
                    coverUrl = artwork?.optString("1000x1000").orEmpty()
                        .ifBlank { artwork?.optString("480x480").orEmpty() }
                        .ifBlank { artwork?.optString("150x150").orEmpty() },
                    source = "audius",
                    sourceId = id
                )
            )
        }
        return out
    }

    fun streamUrl(id: String): String = "$BASE/tracks/$id/stream?app_name=$APP"

    /** a playlist's tracks, so a playlist can be shown as a series */
    suspend fun playlistTracks(playlistId: String): List<MusicTrack> {
        val resp = app.get("$BASE/playlists/$playlistId/tracks?app_name=$APP", headers = headers)
        val data = JSONObject(resp.text).optJSONArray("data") ?: return emptyList()
        val out = ArrayList<MusicTrack>()
        for (i in 0 until data.length()) {
            val o = data.optJSONObject(i) ?: continue
            val id = o.optString("id").ifBlank { continue }
            out.add(
                MusicTrack(
                    id = id,
                    title = o.optString("title"),
                    artist = o.optJSONObject("user")?.optString("name").orEmpty(),
                    album = o.optString("album_name"),
                    duration = o.optDouble("duration", 0.0).toInt(),
                    coverUrl = o.optJSONObject("artwork")?.optString("480x480").orEmpty(),
                    source = "audius",
                    sourceId = id
                )
            )
        }
        return out
    }
}

/**
 * Jamendo — https://www.jamendo.com/
 *
 * From musicdl's JamendoMusicClient. Its api wants an x-jam-call header, which is not a secret:
 * the client makes it itself as sha1(path + random) + "*" + random + "~".
 */
object JamendoApi {
    private const val BASE = "https://www.jamendo.com/api"

    private fun xJam(path: String): String {
        val rand = java.math.BigDecimal(Math.random()).toPlainString()
        val sha = java.security.MessageDigest.getInstance("SHA-1")
            .digest((path + rand).toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "$sha*$rand~"
    }

    private val baseHeaders = mapOf(
        "accept" to "application/json",
        "referer" to "https://www.jamendo.com/",
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36"
    )

    suspend fun search(query: String, limit: Int = 20): List<MusicTrack> {
        if (query.isBlank()) return emptyList()
        val url = "$BASE/search?query=${query.encodeUrl()}&type=track&limit=$limit&identities=www&offset=0"
        val resp = app.get(url, headers = baseHeaders + mapOf("x-jam-call" to xJam("/api/search")))
        val root = JSONObject(resp.text)

        // the search answer has moved between results/results.tracks/data across versions
        val arr = root.optJSONArray("results")
            ?: root.optJSONObject("results")?.optJSONArray("tracks")
            ?: root.optJSONArray("data")
            ?: return emptyList()

        val out = ArrayList<MusicTrack>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").ifBlank { continue }
            val title = o.optString("name").ifBlank { o.optString("title") }.ifBlank { continue }
            out.add(
                MusicTrack(
                    id = id,
                    title = title,
                    artist = o.optJSONObject("artist")?.optString("name").orEmpty(),
                    album = o.optJSONObject("album")?.optString("name").orEmpty(),
                    duration = o.optInt("duration", 0),
                    coverUrl = o.optJSONObject("album")?.optString("image").orEmpty()
                        .ifBlank { o.optString("image").orEmpty() },
                    source = "jamendo",
                    sourceId = id
                )
            )
        }
        return out
    }

    /** the track's own file: flac if offered, then mp3 */
    suspend fun streamUrl(id: String): String? {
        val resp = app.get(
            "$BASE/tracks?id[]=$id",
            headers = baseHeaders + mapOf("x-jam-call" to xJam("/api/tracks"))
        )
        val first = JSONObject(resp.text).optJSONArray("results")?.optJSONObject(0)
            ?: return null

        val candidates = listOf(
            first.optJSONObject("stream")?.optString("flac"),
            first.optJSONObject("download")?.optString("flac"),
            first.optJSONObject("stream")?.optString("mp3"),
            first.optJSONObject("download")?.optString("mp3"),
            first.optString("audio"),
            first.optString("audiodownload")
        )
        return candidates.firstOrNull { !it.isNullOrBlank() && it.startsWith("http") }
    }
}

/**
 * ccMixter — https://ccmixter.org/
 *
 * From musicdl's CCMixterMusicClient: /api/query with f=xspf answers an XSPF playlist, and each
 * <location> in it is a direct audio file.
 */
object CcMixterApi {
    private const val BASE = "https://ccmixter.org/api/query"

    suspend fun search(query: String, limit: Int = 20): List<MusicTrack> {
        if (query.isBlank()) return emptyList()
        val url = "$BASE?f=xspf&t=search_uploads&search_type=any&search=${query.encodeUrl()}" +
            "&limit=$limit&offset=0"
        val xml = app.get(
            url,
            headers = mapOf(
                "accept" to "application/xspf+xml, application/xml, text/xml, */*",
                "referer" to "https://ccmixter.org/",
                "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"
            )
        ).text

        val out = ArrayList<MusicTrack>()
        // <track><location>URL</location><title>..</title><creator>..</creator></track>
        for (block in Regex("<track>(.*?)</track>", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
            val body = block.groupValues[1]
            val loc = Regex("<location>(.*?)</location>").find(body)?.groupValues?.get(1)
                ?.trim()?.takeIf { it.startsWith("http") } ?: continue
            val title = Regex("<title>(.*?)</title>").find(body)?.groupValues?.get(1)
                ?.trim().orEmpty().ifBlank { "ccMixter track" }
            val creator = Regex("<creator>(.*?)</creator>").find(body)?.groupValues?.get(1)
                ?.trim().orEmpty()
            out.add(
                MusicTrack(
                    id = loc,
                    title = title,
                    artist = creator,
                    source = "ccmixter",
                    sourceId = loc,
                    previewUrl = loc
                )
            )
        }
        return out
    }
}

/**
 * Wikimedia Commons — public domain and freely licensed audio, no key.
 *   api: https://commons.wikimedia.org/w/api.php?action=query&generator=search…
 */
object WikimediaApi {
    suspend fun search(query: String, limit: Int = 20): List<MusicTrack> {
        if (query.isBlank()) return emptyList()
        val url = "https://commons.wikimedia.org/w/api.php?action=query&format=json" +
            "&generator=search&gsrsearch=${"filetype:audio ".encodeUrl()}${query.encodeUrl()}" +
            "&gsrlimit=$limit&prop=imageinfo&iiprop=url"
        val resp = app.get(url, headers = mapOf("user-agent" to "MusicBox/1.0 (Cloudstream extension)"))
        val pages = JSONObject(resp.text).optJSONObject("query")?.optJSONObject("pages")
            ?: return emptyList()

        val out = ArrayList<MusicTrack>()
        for (key in pages.keys()) {
            val page = pages.optJSONObject(key) ?: continue
            val file = page.optJSONArray("imageinfo")?.optJSONObject(0)?.optString("url")
                ?.takeIf { it.startsWith("http") } ?: continue
            val title = page.optString("title").removePrefix("File:").substringBeforeLast(".")
            out.add(
                MusicTrack(
                    id = file,
                    title = title.ifBlank { "Commons audio" },
                    artist = "Wikimedia Commons",
                    source = "wikimedia",
                    sourceId = file,
                    previewUrl = file
                )
            )
        }
        return out
    }
}

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

    /** an album or playlist's tracks, so it can be shown as a series */
    suspend fun getAlbumTracks(albumId: String): List<MusicTrack> {
        val resp = app.get(
            "$BASE?__call=content.getAlbumDetails&_format=json&_marker=0&cc=in&albumid=$albumId",
            headers = defaultHeaders
        )
        val obj = JSONObject(resp.text)
        val songs = obj.optJSONArray("songs") ?: obj.optJSONObject("songs")?.optJSONArray("results")
            ?: return emptyList()
        val albumName = obj.optString("title").ifBlank { obj.optString("name") }
        val cover = obj.optString("image").ifBlank { "" }

        return (0 until songs.length()).mapNotNull { i ->
            val song = songs.optJSONObject(i) ?: return@mapNotNull null
            parseSaavnTrack(song)?.copy(
                album = albumName,
                coverUrl = cover
            )
        }
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
