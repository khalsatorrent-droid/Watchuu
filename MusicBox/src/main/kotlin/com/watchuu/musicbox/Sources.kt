package com.watchuu.musicbox

import android.util.Base64
import com.lagradost.cloudstream3.app
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Unified music track, album, artist, and stream result models.
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
    val extraUrlId: String = "", // e.g., GDStudio url_id
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

data class ResolvedStream(
    val url: String,
    val name: String,
    val quality: Int = 320,
    val isM3u8: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
    val referer: String = "",
)

private const val DEFAULT_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"

private fun String.encodeUrl(): String = URLEncoder.encode(this, "UTF-8")

// =========================================================================
// 1. Deezer Catalogue & Download Resolvers (Ported from CharlesPikachu/musicdl `deezer.py`)
// =========================================================================

object DeezerApi {
    private const val BASE = "https://api.deezer.com"
    private const val GW_LIGHT = "https://www.deezer.com/ajax/gw-light.php"
    private const val ANAND_API_KEY = "ak_8e3f1a7c2b5d9e4f0a6c3b8d1e5f2a9c7b4d0e6f"

    // Pool accounts from CharlesPikachu/musicdl `deezer.py` (_parsewithantrahoshiapi)
    private val ANTRA_ACCOUNTS = listOf(
        "charlespikachubGFvd2FuZw==" to "charlespikachubGFvd2FuZzk2MDIxMg==",
        "charlespikachuZ2lybHNsb3ZldG9t" to "charlespikachuZ2lybHNsb3ZldG9tMTIzNDU2",
        "charlespikachuRXJpYw==" to "charlespikachucmFuZG9tOTk5",
        "charlespikachuZ3JlYXRtYW5hbnRyYQ==" to "charlespikachuZ3JlYXRtYW5hbnRyYQ==",
        "charlespikachuaWxpa2V0aGlzc2l0ZQ==" to "charlespikachuaWxpa2V0aGlzc2l0ZQ==",
        "charlespikachubWFya2hlcmU=" to "charlespikachubWFya2hlcmU5OTk2NjY=",
        "charlespikachubGFkeWdhZ2FmYW5z" to "charlespikachuMTIzNDU2Nzc2NTQzMjE=",
        "charlespikachudGFsYXlvcg==" to "charlespikachudGFsYXlvcnRhbGF5b3I=",
        "charlespikachuYnVzaW5lc3M=" to "charlespikachuYnVzaW5lc3M=",
        "charlespikachuZ29kbG92ZXlvdQ==" to "charlespikachuZ29kbG92ZXlvdTEyMzEyMw==",
    )

    private fun decodeAntraCred(encoded: String): String {
        val raw = encoded.removePrefix("charlespikachu")
        return String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8)
    }

    suspend fun search(query: String, limit: Int = 15): List<MusicTrack> {
        // 1. Public REST API
        runCatching {
            val resp = app.get("$BASE/search?q=${query.encodeUrl()}&limit=$limit", timeout = 10L)
            val data = JSONObject(resp.text).optJSONArray("data")
            if (data != null && data.length() > 0) {
                return (0 until data.length()).mapNotNull { parseTrack(data.optJSONObject(it)) }
            }
        }
        // 2. Fallback to gw-light.php (from musicdl `deezer.py`)
        runCatching {
            val token = getGwToken()
            val payload = JSONObject().apply {
                put("query", query)
                put("start", 0)
                put("nb", limit)
                put("filter", "ALL")
                put("output", "TRACK")
            }
            val resp = app.post(
                "$GW_LIGHT?method=search.music&input=3&api_version=1.0&api_token=$token",
                headers = mapOf("User-Agent" to DEFAULT_UA, "Content-Type" to "application/json"),
                requestBody = payload.toString().toRequestBody("application/json".toMediaType()),
                timeout = 10L
            )
            val data = JSONObject(resp.text).optJSONObject("results")?.optJSONArray("data")
            if (data != null && data.length() > 0) {
                return (0 until data.length()).mapNotNull { parseGwTrack(data.optJSONObject(it)) }
            }
        }
        return emptyList()
    }

    suspend fun searchAlbums(query: String, limit: Int = 10): List<MusicAlbum> {
        return runCatching {
            val resp = app.get("$BASE/search/album?q=${query.encodeUrl()}&limit=$limit", timeout = 10L)
            val data = JSONObject(resp.text).optJSONArray("data") ?: return emptyList()
            (0 until data.length()).mapNotNull { parseAlbum(data.optJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    suspend fun searchArtists(query: String, limit: Int = 10): List<MusicArtist> {
        return runCatching {
            val resp = app.get("$BASE/search/artist?q=${query.encodeUrl()}&limit=$limit", timeout = 10L)
            val data = JSONObject(resp.text).optJSONArray("data") ?: return emptyList()
            (0 until data.length()).mapNotNull { parseArtist(data.optJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    suspend fun getTrack(id: String): MusicTrack? {
        runCatching {
            val resp = app.get("$BASE/track/$id", timeout = 10L)
            parseTrack(JSONObject(resp.text))?.let { return it }
        }
        runCatching {
            val token = getGwToken()
            val resp = app.post(
                "$GW_LIGHT?method=song.getData&input=3&api_version=1.0&api_token=$token",
                headers = mapOf("User-Agent" to DEFAULT_UA, "Content-Type" to "application/json"),
                requestBody = JSONObject().put("SNG_ID", id).toString().toRequestBody("application/json".toMediaType()),
                timeout = 10L
            )
            val results = JSONObject(resp.text).optJSONObject("results")
            parseGwTrack(results)?.let { return it }
        }
        return null
    }

    suspend fun getAlbumTracks(albumId: String): List<MusicTrack> {
        runCatching {
            val resp = app.get("$BASE/album/$albumId", timeout = 10L)
            val obj = JSONObject(resp.text)
            val albumTitle = obj.optString("title", "")
            val albumCover = obj.optString("cover_xl", obj.optString("cover_big", ""))
            val tracks = obj.optJSONObject("tracks")?.optJSONArray("data")
            if (tracks != null && tracks.length() > 0) {
                return (0 until tracks.length()).mapNotNull { i ->
                    parseTrack(tracks.optJSONObject(i))?.copy(album = albumTitle, coverUrl = albumCover)
                }
            }
        }
        return emptyList()
    }

    suspend fun getArtistTopTracks(artistId: String, limit: Int = 20): List<MusicTrack> {
        return runCatching {
            val resp = app.get("$BASE/artist/$artistId/top?limit=$limit", timeout = 10L)
            val data = JSONObject(resp.text).optJSONArray("data") ?: return emptyList()
            (0 until data.length()).mapNotNull { parseTrack(data.optJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    suspend fun getChart(limit: Int = 20): List<MusicTrack> {
        return runCatching {
            val resp = app.get("$BASE/chart/0/tracks?limit=$limit", timeout = 10L)
            val data = JSONObject(resp.text).optJSONArray("data") ?: return emptyList()
            (0 until data.length()).mapNotNull { parseTrack(data.optJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private suspend fun getGwToken(): String {
        return runCatching {
            val resp = app.post(
                "$GW_LIGHT?method=deezer.getUserData&input=3&api_version=1.0&api_token=null",
                headers = mapOf("User-Agent" to DEFAULT_UA),
                timeout = 8L
            )
            JSONObject(resp.text).optJSONObject("results")?.optString("checkForm", "null") ?: "null"
        }.getOrDefault("null")
    }

    /**
     * Resolve direct audio streams for a Deezer track ID using CharlesPikachu/musicdl (`deezer.py`).
     */
    suspend fun resolveStreams(trackId: String): List<ResolvedStream> {
        val out = mutableListOf<ResolvedStream>()
        if (trackId.isBlank()) return out

        // 1. AnandServer FLAC API (`_parsewithanandserverapi` in `deezer.py`)
        val anandHeaders = mapOf(
            "X-API-Key" to ANAND_API_KEY,
            "api-key" to ANAND_API_KEY,
            "User-Agent" to DEFAULT_UA,
        )
        for (candidateUrl in listOf(
            "https://deezer.anandserver.cfd/api/stream/$trackId?strict_24=1",
            "https://deezer.anandserver.cfd/api/stream/$trackId"
        )) {
            runCatching {
                val resp = app.head(candidateUrl, headers = anandHeaders, timeout = 8L)
                if (resp.code in 200..299) {
                    out.add(
                        ResolvedStream(
                            url = candidateUrl,
                            name = "Deezer · AnandServer FLAC",
                            quality = 1411,
                            headers = anandHeaders,
                        )
                    )
                }
            }
            if (out.isNotEmpty()) break
        }

        // 2. Deemixer API (`_parsewithdeemixerapi` in `deezer.py`)
        runCatching {
            val dmHeaders = mapOf(
                "Content-Type" to "application/json",
                "User-Agent" to DEFAULT_UA,
                "Referer" to "https://deemixer.com/",
                "Origin" to "https://deemixer.com",
            )
            for (bitrate in listOf("FLAC", "320", "128")) {
                val payload = JSONObject().apply {
                    put("temp_id", UUID.randomUUID().toString())
                    put("link", "https://www.deezer.com/track/$trackId")
                    put("bitrate", bitrate)
                }
                val resp = app.post(
                    "https://deemixer.com/process",
                    headers = dmHeaders,
                    requestBody = payload.toString().toRequestBody("application/json".toMediaType()),
                    timeout = 10L
                )
                if (resp.code in 200..299) {
                    val rawDl = JSONObject(resp.text).optString("download_url", "")
                    if (rawDl.isNotBlank()) {
                        val fullUrl = if (rawDl.startsWith("http")) rawDl
                        else "https://deemixer.com/" + rawDl.trimStart('/')
                        out.add(
                            ResolvedStream(
                                url = fullUrl,
                                name = "Deezer · Deemixer $bitrate",
                                quality = if (bitrate == "FLAC") 1411 else bitrate.toIntOrNull() ?: 320,
                                headers = dmHeaders,
                                referer = "https://deemixer.com/",
                            )
                        )
                        break
                    }
                }
            }
        }

        // 3. MusicFab Deezer API (`_parsewithmusicfabapi` in `deezer.py`)
        runCatching {
            val mfHeaders = mapOf(
                "User-Agent" to DEFAULT_UA,
                "Content-Type" to "application/json",
                "Accept" to "application/json",
                "Referer" to "https://musicfab.io/deezer-to-mp3/",
                "Origin" to "https://musicfab.io",
            )
            val payload = JSONObject().put("url", "https://www.deezer.com/en/track/$trackId")
            val resp = app.post(
                "https://musicfab.io/api/deezer",
                headers = mfHeaders,
                requestBody = payload.toString().toRequestBody("application/json".toMediaType()),
                timeout = 12L
            )
            val dlUrl = JSONObject(resp.text)
                .optJSONObject("data")
                ?.optJSONObject("metadata")
                ?.optString("download", "") ?: ""
            if (dlUrl.startsWith("http")) {
                out.add(
                    ResolvedStream(
                        url = dlUrl,
                        name = "Deezer · MusicFab 320kbps",
                        quality = 320,
                        headers = mfHeaders,
                        referer = "https://musicfab.io/",
                    )
                )
            }
        }

        // 4. DeezDownloaders API (`_parsewithdeezdownloadersapi` in `deezer.py`)
        runCatching {
            val ddHeaders = mapOf(
                "User-Agent" to DEFAULT_UA,
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to "https://deezdownloaders.com/",
            )
            val trackUrl = "https://www.deezer.com/en/track/$trackId"
            val trackResp = app.get(
                "https://deezdownloaders.com/api/track.php?url=${trackUrl.encodeUrl()}",
                headers = ddHeaders,
                timeout = 10L
            )
            val details = JSONObject(trackResp.text)
            val name = details.optString("name", "")
            val artist = details.optString("artist", "")
            if (name.isNotBlank()) {
                val formBody = "name=${name.encodeUrl()}&artist=${artist.encodeUrl()}&url=${trackUrl.encodeUrl()}&token=none&zip_download=false&quality=128"
                val dlResp = app.post(
                    "https://deezdownloaders.com/api/downloader.php",
                    headers = ddHeaders + mapOf(
                        "Referer" to "https://deezdownloaders.com/track.php",
                        "Origin" to "https://deezdownloaders.com",
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    ),
                    requestBody = formBody.toRequestBody("application/x-www-form-urlencoded".toMediaType()),
                    cookies = trackResp.cookies,
                    timeout = 12L
                )
                val dlink = JSONObject(dlResp.text).optString("dlink", "")
                if (dlink.startsWith("http")) {
                    out.add(
                        ResolvedStream(
                            url = dlink,
                            name = "Deezer · DeezDownloaders",
                            quality = 128,
                            headers = ddHeaders,
                            referer = "https://deezdownloaders.com/",
                        )
                    )
                }
            }
        }

        // 5. Antra Hoshi Lossless API (`_parsewithantrahoshiapi` in `deezer.py`)
        if (out.isEmpty()) {
            runCatching {
                val (encUser, encPass) = ANTRA_ACCOUNTS.random()
                val username = decodeAntraCred(encUser)
                val password = decodeAntraCred(encPass)
                val antraHeaders = mapOf(
                    "User-Agent" to DEFAULT_UA,
                    "Origin" to "https://antra.hoshi.cfd",
                    "Referer" to "https://antra.hoshi.cfd/",
                    "Content-Type" to "application/json",
                )
                val loginResp = app.post(
                    "https://antra.hoshi.cfd/api/auth/login",
                    headers = antraHeaders,
                    requestBody = JSONObject().apply {
                        put("username", username)
                        put("password", password)
                    }.toString().toRequestBody("application/json".toMediaType()),
                    timeout = 10L
                )
                if (loginResp.code in 200..299) {
                    val cookies = loginResp.cookies
                    val trackUrl = "https://www.deezer.com/en/track/$trackId"
                    app.post(
                        "https://antra.hoshi.cfd/api/resolve",
                        headers = antraHeaders,
                        cookies = cookies,
                        requestBody = JSONObject().apply {
                            put("url", trackUrl)
                            put("format", "lossless-16")
                        }.toString().toRequestBody("application/json".toMediaType()),
                        timeout = 10L
                    )
                    val jobResp = app.post(
                        "https://antra.hoshi.cfd/api/jobs",
                        headers = antraHeaders,
                        cookies = cookies,
                        requestBody = JSONObject().apply {
                            put("url", trackUrl)
                            put("format", "lossless-16")
                            put("start_index", 0)
                            put("end_index", 1)
                            put("client_packaging", false)
                        }.toString().toRequestBody("application/json".toMediaType()),
                        timeout = 10L
                    )
                    val jobId = JSONObject(jobResp.text).optString("job_id", "")
                    if (jobId.isNotBlank()) {
                        for (poll in 0 until 8) {
                            kotlinx.coroutines.delay(1000L)
                            val stResp = app.get(
                                "https://antra.hoshi.cfd/api/jobs/$jobId/status",
                                headers = antraHeaders,
                                cookies = cookies,
                                timeout = 8L
                            )
                            val st = JSONObject(stResp.text).optString("status", "")
                            if (st == "complete") {
                                out.add(
                                    ResolvedStream(
                                        url = "https://antra.hoshi.cfd/api/jobs/$jobId/download",
                                        name = "Deezer · Antra Lossless FLAC",
                                        quality = 1411,
                                        headers = antraHeaders,
                                        referer = "https://antra.hoshi.cfd/",
                                    )
                                )
                                break
                            }
                            if (st == "failed" || st == "error") break
                        }
                    }
                }
            }
        }

        return out
    }

    private fun parseTrack(obj: JSONObject?): MusicTrack? {
        obj ?: return null
        val id = obj.optLong("id", 0).toString()
        if (id == "0") return null
        val albumObj = obj.optJSONObject("album")
        return MusicTrack(
            id = "deezer:$id",
            title = obj.optString("title", ""),
            artist = obj.optJSONObject("artist")?.optString("name", "") ?: "",
            album = albumObj?.optString("title", "") ?: "",
            duration = obj.optInt("duration", 0),
            coverUrl = albumObj?.optString("cover_xl", albumObj.optString("cover_big", "")) ?: "",
            source = "deezer",
            sourceId = id,
            previewUrl = obj.optString("preview", ""),
            isrc = obj.optString("isrc", ""),
        )
    }

    private fun parseGwTrack(obj: JSONObject?): MusicTrack? {
        obj ?: return null
        val id = obj.optString("SNG_ID", "")
        if (id.isBlank() || id == "0") return null
        val albPic = obj.optString("ALB_PICTURE", "")
        val cover = if (albPic.isNotBlank()) "https://e-cdns-images.dzcdn.net/images/cover/$albPic/500x500-000000-80-0-0.jpg" else ""
        val preview = obj.optJSONArray("MEDIA")?.optJSONObject(0)?.optString("HREF", "") ?: ""
        return MusicTrack(
            id = "deezer:$id",
            title = obj.optString("SNG_TITLE", ""),
            artist = obj.optString("ART_NAME", ""),
            album = obj.optString("ALB_TITLE", ""),
            duration = obj.optString("DURATION", "0").toIntOrNull() ?: 0,
            coverUrl = cover,
            source = "deezer",
            sourceId = id,
            previewUrl = preview,
            isrc = obj.optString("ISRC", ""),
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

// =========================================================================
// 2. Apple Music / iTunes Catalogue & Charts
// =========================================================================

object AppleMusicApi {
    private const val BASE = "https://itunes.apple.com"

    suspend fun search(query: String, limit: Int = 15): List<MusicTrack> {
        return runCatching {
            val resp = app.get("$BASE/search?term=${query.encodeUrl()}&media=music&entity=song&limit=$limit", timeout = 10L)
            val results = JSONObject(resp.text).optJSONArray("results") ?: return emptyList()
            (0 until results.length()).mapNotNull { parseTrack(results.optJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    suspend fun searchAlbums(query: String, limit: Int = 10): List<MusicAlbum> {
        return runCatching {
            val resp = app.get("$BASE/search?term=${query.encodeUrl()}&media=music&entity=album&limit=$limit", timeout = 10L)
            val results = JSONObject(resp.text).optJSONArray("results") ?: return emptyList()
            (0 until results.length()).mapNotNull { i ->
                val obj = results.optJSONObject(i) ?: return@mapNotNull null
                val collectionId = obj.optLong("collectionId", 0).toString()
                if (collectionId == "0") return@mapNotNull null
                MusicAlbum(
                    id = "apple-album:$collectionId",
                    title = obj.optString("collectionName", ""),
                    artist = obj.optString("artistName", ""),
                    coverUrl = obj.optString("artworkUrl100", "").replace("100x100", "600x600"),
                    source = "apple",
                    year = obj.optString("releaseDate", "").take(4),
                    trackCount = obj.optInt("trackCount", 0),
                )
            }
        }.getOrDefault(emptyList())
    }

    suspend fun getTrack(trackId: String): MusicTrack? {
        return runCatching {
            val resp = app.get("$BASE/lookup?id=${trackId.encodeUrl()}&entity=song", timeout = 10L)
            val results = JSONObject(resp.text).optJSONArray("results") ?: return null
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                if (item.optString("wrapperType") == "track" || item.optLong("trackId", 0L) > 0L) {
                    return parseTrack(item)
                }
            }
            null
        }.getOrNull()
    }

    suspend fun getAlbumTracks(collectionId: String): List<MusicTrack> {
        return runCatching {
            val resp = app.get("$BASE/lookup?id=${collectionId.encodeUrl()}&entity=song", timeout = 12L)
            val results = JSONObject(resp.text).optJSONArray("results") ?: return emptyList()
            (0 until results.length()).mapNotNull { i ->
                val item = results.optJSONObject(i) ?: return@mapNotNull null
                if (item.optString("wrapperType") == "track") parseTrack(item) else null
            }
        }.getOrDefault(emptyList())
    }

    suspend fun getChart(country: String = "in", limit: Int = 25): List<MusicTrack> {
        runCatching {
            val resp = app.get("https://rss.applemarketingtools.com/api/v2/$country/music/most-played/$limit/songs.json", timeout = 10L)
            val results = JSONObject(resp.text).optJSONObject("feed")?.optJSONArray("results")
            if (results != null && results.length() > 0) {
                return (0 until results.length()).mapNotNull { i ->
                    val obj = results.optJSONObject(i) ?: return@mapNotNull null
                    val id = obj.optString("id", "")
                    val name = obj.optString("name", "")
                    if (id.isBlank() || name.isBlank()) return@mapNotNull null
                    MusicTrack(
                        id = "apple:$id",
                        title = name,
                        artist = obj.optString("artistName", ""),
                        coverUrl = obj.optString("artworkUrl100", "").replace("100x100", "600x600"),
                        source = "apple",
                        sourceId = id,
                    )
                }
            }
        }
        return search("top hits", limit)
    }

    private fun parseTrack(obj: JSONObject?): MusicTrack? {
        obj ?: return null
        val trackId = obj.optLong("trackId", 0).toString()
        val trackName = obj.optString("trackName", "")
        if (trackId == "0" || trackName.isBlank()) return null
        return MusicTrack(
            id = "apple:$trackId",
            title = trackName,
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

// =========================================================================
// 3. SoundCloud Catalogue & Stream Resolver (Ported from CharlesPikachu/musicdl `soundcloud.py`)
// =========================================================================

object SoundCloudApi {
    private const val BASE = "https://api-v2.soundcloud.com"
    // Fallback client_id from CharlesPikachu/musicdl `soundcloud.py`
    private const val FALLBACK_CLIENT_ID = "9jZvetLfDs6An08euQgJ0lYlHkKdGFzV"
    @Volatile private var clientId: String = ""

    private suspend fun getClientId(): String {
        if (clientId.isNotBlank()) return clientId
        runCatching {
            val page = app.get("https://soundcloud.com/", headers = mapOf("User-Agent" to DEFAULT_UA), timeout = 10L).text
            val scripts = Regex("""<script[^>]+src="([^"]+)"""").findAll(page).map { it.groupValues[1] }.toList().asReversed()
            for (scriptUrl in scripts.take(8)) {
                runCatching {
                    val fullUrl = if (scriptUrl.startsWith("http")) scriptUrl else "https://soundcloud.com/${scriptUrl.trimStart('/')}"
                    val js = app.get(fullUrl, headers = mapOf("User-Agent" to DEFAULT_UA), timeout = 8L).text
                    val idMatch = Regex("""client_id\s*[:=]\s*["']([0-9a-zA-Z]{32})["']""").find(js)
                    if (idMatch != null) {
                        clientId = idMatch.groupValues[1]
                        return clientId
                    }
                }
            }
        }
        clientId = FALLBACK_CLIENT_ID
        return clientId
    }

    suspend fun search(query: String, limit: Int = 15): List<MusicTrack> {
        val cid = getClientId()
        for (candidateCid in listOf(cid, FALLBACK_CLIENT_ID).distinct()) {
            runCatching {
                val url = "$BASE/search/tracks?q=${query.encodeUrl()}&client_id=$candidateCid&limit=$limit&offset=0&linked_partitioning=1&app_locale=en"
                val resp = app.get(url, headers = mapOf("User-Agent" to DEFAULT_UA), timeout = 10L)
                if (resp.code in 200..299) {
                    val collection = JSONObject(resp.text).optJSONArray("collection")
                    if (collection != null && collection.length() > 0) {
                        clientId = candidateCid
                        return (0 until collection.length()).mapNotNull { parseTrack(collection.optJSONObject(it)) }
                    }
                }
            }
        }
        // Fallback to MP3Juice SoundCloud search (from musicdl `mp3juice.py`)
        return MP3JuiceApi.searchSoundCloud(query, limit)
    }

    suspend fun getTrack(trackId: String): MusicTrack? {
        val cid = getClientId()
        return runCatching {
            val resp = app.get("$BASE/tracks/$trackId?client_id=$cid", headers = mapOf("User-Agent" to DEFAULT_UA), timeout = 10L)
            parseTrack(JSONObject(resp.text))
        }.getOrNull()
    }

    suspend fun getPlaylistTracks(playlistId: String): List<MusicTrack> {
        val cid = getClientId()
        return runCatching {
            val resp = app.get("$BASE/playlists/$playlistId?client_id=$cid", headers = mapOf("User-Agent" to DEFAULT_UA), timeout = 10L)
            val tracks = JSONObject(resp.text).optJSONArray("tracks") ?: return emptyList()
            (0 until tracks.length()).mapNotNull { parseTrack(tracks.optJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    /**
     * Resolve stream URLs for a SoundCloud track (from CharlesPikachu/musicdl `soundcloud.py`).
     * Supports direct download endpoint, progressive HTTP streams, non-DRM HLS streams,
     * and `track_authorization` tokens.
     */
    suspend fun getStreamUrl(trackId: String, fallbackQuery: String = ""): String {
        val cid = getClientId()
        runCatching {
            val resp = app.get("$BASE/tracks/$trackId?client_id=$cid", headers = mapOf("User-Agent" to DEFAULT_UA), timeout = 10L)
            val track = JSONObject(resp.text)

            // 1. Check direct original download if enabled
            if (track.optBoolean("downloadable", false) && track.optBoolean("has_downloads_left", false)) {
                runCatching {
                    val dlResp = app.get("$BASE/tracks/$trackId/download?client_id=$cid", headers = mapOf("User-Agent" to DEFAULT_UA), timeout = 8L)
                    val redirectUri = JSONObject(dlResp.text).optString("redirectUri", "")
                    if (redirectUri.startsWith("http")) return redirectUri
                }
            }

            val trackAuth = track.optString("track_authorization", "")
            val transcodings = track.optJSONObject("media")?.optJSONArray("transcodings")
            if (transcodings != null && transcodings.length() > 0) {
                val candidates = mutableListOf<Pair<Int, String>>()
                for (i in 0 until transcodings.length()) {
                    val t = transcodings.optJSONObject(i) ?: continue
                    val url = t.optString("url", "")
                    val format = t.optJSONObject("format") ?: continue
                    val protocol = format.optString("protocol", "").lowercase()
                    val quality = t.optString("quality", "sq").lowercase()

                    // Skip Apple FairPlay (cbc-) or Widevine (ctr-) encrypted streams
                    if (url.isBlank() || protocol.startsWith("cbc-") || protocol.startsWith("ctr-")) continue

                    val score = (if (protocol == "progressive") 100 else 50) + (if (quality == "hq") 20 else 10)
                    candidates.add(score to url)
                }

                for ((_, candidateUrl) in candidates.sortedByDescending { it.first }) {
                    runCatching {
                        val sep = if (candidateUrl.contains("?")) "&" else "?"
                        val authPart = if (trackAuth.isNotBlank()) "&track_authorization=${trackAuth.encodeUrl()}" else ""
                        val streamResp = app.get(
                            "$candidateUrl${sep}client_id=$cid$authPart",
                            headers = mapOf("User-Agent" to DEFAULT_UA),
                            timeout = 10L
                        )
                        val resolved = JSONObject(streamResp.text).optString("url", "")
                        if (resolved.startsWith("http")) return resolved
                    }
                }
            }
        }

        // Fallback to MP3Juice SoundCloud stream resolver if query provided
        if (fallbackQuery.isNotBlank()) {
            val mp3JuiceUrl = MP3JuiceApi.resolveSoundCloudStream(fallbackQuery)
            if (mp3JuiceUrl.isNotBlank()) return mp3JuiceUrl
        }
        return ""
    }

    private fun parseTrack(obj: JSONObject?): MusicTrack? {
        obj ?: return null
        val id = obj.optLong("id", 0).toString()
        val title = obj.optString("title", "")
        if (id == "0" || title.isBlank()) return null
        val pubMeta = obj.optJSONObject("publisher_metadata")
        val artist = pubMeta?.optString("artist", "")?.ifBlank { null }
            ?: obj.optJSONObject("user")?.optString("username", "")
            ?: ""
        val album = pubMeta?.optString("album_title", "") ?: ""
        val artwork = obj.optString("artwork_url", "").ifBlank {
            obj.optJSONObject("user")?.optString("avatar_url", "") ?: ""
        }.replace("-large", "-t500x500")

        return MusicTrack(
            id = "soundcloud:$id",
            title = title,
            artist = artist,
            album = album,
            duration = (obj.optLong("duration", 0) / 1000).toInt(),
            coverUrl = artwork,
            source = "soundcloud",
            sourceId = id,
        )
    }
}

// =========================================================================
// 4. Tidal / Monochrome Catalogue & Lossless FLAC Resolver (`tidal.py` + `Monochrome`)
// =========================================================================

object TidalApi {
    private const val TIDAL_BASE = "https://api.tidal.com/v1"
    private const val TIDAL_HIFI_BASE = "https://api.tidalhifi.com/v1"
    private const val MONOCHROME_API = "https://tracks.monochrome.st"
    private const val MONOCHROME_MAIN = "https://monochrome.st"
    private const val TOKEN = "49YxDN9a2aFV6RTG"

    private val monochromeHeaders = mapOf(
        "Origin" to MONOCHROME_MAIN,
        "Referer" to "$MONOCHROME_MAIN/",
        "User-Agent" to DEFAULT_UA,
        "Accept" to "application/json",
    )

    private fun tidalHeaders() = mapOf(
        "X-Tidal-Token" to TOKEN,
        "Accept" to "application/json",
        "User-Agent" to "TIDAL_ANDROID/1039 okhttp/3.14.9",
    )

    suspend fun search(query: String, limit: Int = 15): List<MusicTrack> {
        // 1. Monochrome / Tidal lossless index (fast, unblocked, direct FLAC playback)
        runCatching {
            val resp = app.get("$MONOCHROME_API/search/tracks?q=${query.encodeUrl()}&limit=$limit", headers = monochromeHeaders, timeout = 12L)
            if (resp.code in 200..299) {
                val tracks = JSONObject(resp.text).optJSONArray("tracks")
                if (tracks != null && tracks.length() > 0) {
                    val parsed = (0 until tracks.length()).mapNotNull { parseMonochromeTrack(tracks.optJSONObject(it)) }
                    if (parsed.isNotEmpty()) return parsed
                }
            }
        }
        // 2. Fallback to combined Monochrome search
        runCatching {
            val resp = app.get("$MONOCHROME_API/search?q=${query.encodeUrl()}", headers = monochromeHeaders, timeout = 12L)
            if (resp.code in 200..299) {
                val tracks = JSONObject(resp.text).optJSONArray("tracks")
                if (tracks != null && tracks.length() > 0) {
                    val parsed = (0 until tracks.length()).mapNotNull { parseMonochromeTrack(tracks.optJSONObject(it)) }
                    if (parsed.isNotEmpty()) return parsed
                }
            }
        }
        // 3. Fallback to official Tidal API (`tidal.py`)
        for (base in listOf(TIDAL_HIFI_BASE, TIDAL_BASE)) {
            runCatching {
                val resp = app.get(
                    "$base/search?query=${query.encodeUrl()}&limit=$limit&offset=0&types=TRACKS&countryCode=US",
                    headers = tidalHeaders(),
                    timeout = 10L
                )
                val tracks = JSONObject(resp.text).optJSONObject("tracks")?.optJSONArray("items")
                if (tracks != null && tracks.length() > 0) {
                    return (0 until tracks.length()).mapNotNull { parseTidalTrack(tracks.optJSONObject(it)) }
                }
            }
        }
        // 4. Fallback to GDStudio Tidal search (`gdstudio.py`)
        return GDStudioApi.search(query, "tidal", limit)
    }

    suspend fun searchAlbums(query: String, limit: Int = 10): List<MusicAlbum> {
        runCatching {
            val resp = app.get("$MONOCHROME_API/search/releases?q=${query.encodeUrl()}&limit=$limit", headers = monochromeHeaders, timeout = 12L)
            val releases = JSONObject(resp.text).optJSONArray("releases")
            if (releases != null && releases.length() > 0) {
                return (0 until releases.length()).mapNotNull { i ->
                    val o = releases.optJSONObject(i) ?: return@mapNotNull null
                    val id = o.optString("releaseId", o.optString("id", ""))
                    val title = o.optString("title", "")
                    if (id.isBlank() || title.isBlank()) return@mapNotNull null
                    val artist = extractMonochromeArtists(o)
                    val cover = o.optString("artwork", o.optString("cover", "")).ifBlank {
                        "$MONOCHROME_API/proxy/mi/$id-1A01.jpg"
                    }
                    MusicAlbum(
                        id = "tidal-album:$id",
                        title = title,
                        artist = artist,
                        coverUrl = cover,
                        source = "tidal",
                        year = o.optString("releaseDate", "").take(4),
                    )
                }
            }
        }
        return emptyList()
    }

    suspend fun getAlbumTracks(albumId: String): List<MusicTrack> {
        runCatching {
            val resp = app.get("$MONOCHROME_API/releases/$albumId", headers = monochromeHeaders, timeout = 12L)
            if (resp.code in 200..299) {
                val root = JSONObject(resp.text)
                val albumTitle = root.optString("title", "Album")
                val cover = root.optString("artwork", root.optString("cover", "")).ifBlank {
                    "$MONOCHROME_API/proxy/mi/$albumId-1A01.jpg"
                }
                val tracks = root.optJSONArray("tracks")
                if (tracks != null && tracks.length() > 0) {
                    return (0 until tracks.length()).mapNotNull { i ->
                        parseMonochromeTrack(tracks.optJSONObject(i))?.copy(
                            album = albumTitle,
                            coverUrl = cover,
                        )
                    }
                }
            }
        }
        runCatching {
            val resp = app.get("$TIDAL_BASE/albums/$albumId/tracks?countryCode=US", headers = tidalHeaders(), timeout = 10L)
            val items = JSONObject(resp.text).optJSONArray("items")
            if (items != null && items.length() > 0) {
                return (0 until items.length()).mapNotNull { parseTidalTrack(items.optJSONObject(it)) }
            }
        }
        return emptyList()
    }

    /**
     * Resolve lossless FLAC streams via Monochrome (`tracks.monochrome.st`) and GDStudio (`tidal`).
     */
    suspend fun resolveStreams(tidalId: String, query: String): List<ResolvedStream> {
        val out = mutableListOf<ResolvedStream>()

        // 1. Direct Monochrome lossless FLAC stream
        val candidateId = if (tidalId.isNotBlank() && tidalId.all { it.isDigit() }) {
            tidalId
        } else {
            runCatching {
                search(query, 1).firstOrNull()?.sourceId ?: ""
            }.getOrDefault("")
        }

        if (candidateId.isNotBlank() && candidateId.all { it.isDigit() }) {
            out.add(
                ResolvedStream(
                    url = "$MONOCHROME_API/track/$candidateId",
                    name = "Tidal · Monochrome FLAC (Lossless)",
                    quality = 1411,
                    headers = mapOf("Origin" to MONOCHROME_MAIN, "User-Agent" to DEFAULT_UA),
                    referer = "$MONOCHROME_MAIN/",
                )
            )
        }

        // 2. GDStudio Tidal resolver (from CharlesPikachu/musicdl `gdstudio.py`)
        runCatching {
            val gdStream = GDStudioApi.resolveBestStream(query, "tidal")
            if (gdStream != null) out.add(gdStream)
        }

        return out
    }

    private fun extractMonochromeArtists(o: JSONObject): String {
        o.optJSONArray("artistNames")?.let { a ->
            val names = (0 until a.length()).mapNotNull { a.optString(it).ifBlank { null } }
            if (names.isNotEmpty()) return names.take(3).joinToString(", ")
        }
        o.optJSONArray("artists")?.let { a ->
            val names = (0 until a.length()).mapNotNull {
                val ao = a.optJSONObject(it)
                ao?.optString("name", ao.optString("displayName", ""))?.ifBlank { null }
            }
            if (names.isNotEmpty()) return names.take(3).joinToString(", ")
        }
        return ""
    }

    private fun parseMonochromeTrack(o: JSONObject?): MusicTrack? {
        o ?: return null
        if (o.has("playable") && !o.optBoolean("playable", true)) return null
        val id = o.optString("trackId", o.optString("id", ""))
        val title = o.optString("title", "")
        if (id.isBlank() || title.isBlank()) return null
        val releaseId = o.optString("releaseId", "")
        val cover = o.optString("artwork", o.optString("cover", "")).ifBlank {
            if (releaseId.isNotBlank()) "$MONOCHROME_API/proxy/mi/$releaseId-1A01.jpg" else ""
        }
        val durMs = o.optLong("duration", 0L)
        val durationSec = if (durMs > 1000L) (durMs / 1000L).toInt() else durMs.toInt()
        return MusicTrack(
            id = "tidal:$id",
            title = title,
            artist = extractMonochromeArtists(o),
            album = o.optString("albumTitle", ""),
            duration = durationSec,
            coverUrl = cover,
            source = "tidal",
            sourceId = id,
            isrc = o.optString("isrc", ""),
        )
    }

    private fun parseTidalTrack(obj: JSONObject?): MusicTrack? {
        obj ?: return null
        val id = obj.optLong("id", 0).toString()
        val title = obj.optString("title", "")
        if (id == "0" || title.isBlank()) return null
        val albumObj = obj.optJSONObject("album")
        val coverId = albumObj?.optString("cover", "")?.replace("-", "/") ?: ""
        val artists = obj.optJSONArray("artists")
        return MusicTrack(
            id = "tidal:$id",
            title = title,
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

// =========================================================================
// 5. Qobuz Catalogue & Lossless FLAC Resolver (Ported from CharlesPikachu/musicdl `qobuz.py`)
// =========================================================================

object QobuzApi {
    private const val BASE = "https://www.qobuz.com/api.json/0.2"
    private val APP_IDS = listOf("798273057", "950096963", "285473059")
    private const val ANAND_API_KEY = "ak_8e3f1a7c2b5d9e4f0a6c3b8d1e5f2a9c7b4d0e6f"

    private fun headers(appId: String = APP_IDS.first()) = mapOf(
        "X-App-Id" to appId,
        "Accept" to "application/json",
        "User-Agent" to DEFAULT_UA,
    )

    suspend fun search(query: String, limit: Int = 15): List<MusicTrack> {
        for (appId in APP_IDS) {
            runCatching {
                val resp = app.get(
                    "$BASE/track/search?query=${query.encodeUrl()}&limit=$limit&app_id=$appId",
                    headers = headers(appId),
                    timeout = 10L
                )
                if (resp.code in 200..299) {
                    val tracks = JSONObject(resp.text).optJSONObject("tracks")?.optJSONArray("items")
                    if (tracks != null && tracks.length() > 0) {
                        return (0 until tracks.length()).mapNotNull { parseTrack(tracks.optJSONObject(it)) }
                    }
                }
            }
        }
        // Fallback to GDStudio Qobuz search (from `qobuz.py` / `gdstudio.py`)
        return GDStudioApi.search(query, "qobuz", limit)
    }

    suspend fun getAlbumTracks(albumId: String): List<MusicTrack> {
        for (appId in APP_IDS) {
            runCatching {
                val resp = app.get(
                    "$BASE/album/get?album_id=${albumId.encodeUrl()}&app_id=$appId",
                    headers = headers(appId),
                    timeout = 10L
                )
                if (resp.code in 200..299) {
                    val root = JSONObject(resp.text)
                    val albumTitle = root.optString("title", "")
                    val cover = root.optJSONObject("image")?.optString("large", "") ?: ""
                    val items = root.optJSONObject("tracks")?.optJSONArray("items")
                    if (items != null && items.length() > 0) {
                        return (0 until items.length()).mapNotNull { i ->
                            parseTrack(items.optJSONObject(i))?.copy(album = albumTitle, coverUrl = cover)
                        }
                    }
                }
            }
        }
        return emptyList()
    }

    /**
     * Resolve direct audio streams for a Qobuz track using CharlesPikachu/musicdl (`qobuz.py`):
     *  1. `qobuz.anandserver.cfd` (`_parsewithanandserverapi`)
     *  2. `api.dezalty.com` (`_parsewithdezaltyapi`)
     *  3. `GDStudioApi` (`_parsewithgdstudioxyzapi` / `_parsewithgdstudioorgapi`)
     *  4. `clashflac.kanjijewels.com` (`_parsewithclashflacapi`)
     */
    suspend fun resolveStreams(qobuzId: String, query: String): List<ResolvedStream> {
        val out = mutableListOf<ResolvedStream>()
        val targetId = qobuzId.ifBlank {
            runCatching { search(query, 1).firstOrNull()?.sourceId ?: "" }.getOrDefault("")
        }

        if (targetId.isNotBlank()) {
            // 1. AnandServer Qobuz Hi-Res FLAC (`_parsewithanandserverapi` in `qobuz.py`)
            val anandHeaders = mapOf(
                "User-Agent" to DEFAULT_UA,
                "Accept" to "audio/flac, audio/*, */*",
                "Referer" to "https://open.qobuz.com/",
                "Origin" to "https://open.qobuz.com",
                "X-API-Key" to ANAND_API_KEY,
                "api-key" to ANAND_API_KEY,
            )
            for (url in listOf(
                "https://qobuz.anandserver.cfd/api/stream/$targetId?strict_24=1",
                "https://qobuz.anandserver.cfd/api/stream/$targetId"
            )) {
                runCatching {
                    val head = app.head(url, headers = anandHeaders, timeout = 8L)
                    if (head.code in 200..299) {
                        out.add(
                            ResolvedStream(
                                url = url,
                                name = "Qobuz · AnandServer FLAC",
                                quality = 1411,
                                headers = anandHeaders,
                                referer = "https://open.qobuz.com/",
                            )
                        )
                    }
                }
                if (out.isNotEmpty()) break
            }

            // 2. DezAlty Qobuz Guest Stream (`_parsewithdezaltyapi` in `qobuz.py`)
            runCatching {
                val dzHeaders = mapOf(
                    "User-Agent" to DEFAULT_UA,
                    "Origin" to "https://dezalty.com",
                    "Referer" to "https://dezalty.com/",
                )
                val guestResp = app.post(
                    "https://api.dezalty.com/auth/telegram/guest",
                    headers = dzHeaders,
                    requestBody = "".toRequestBody("application/json".toMediaType()),
                    timeout = 10L
                )
                val token = JSONObject(guestResp.text).optString("token", "")
                if (token.isNotBlank()) {
                    val authHeaders = dzHeaders + ("Authorization" to "Bearer $token")
                    val streamResp = app.get(
                        "https://api.dezalty.com/download/qobuz/stream?url_or_id=${targetId.encodeUrl()}&quality_str=Normal",
                        headers = authHeaders,
                        timeout = 10L
                    )
                    val respJson = JSONObject(streamResp.text)
                    val dataObj = respJson.optJSONObject("data") ?: respJson
                    val dlUrl = dataObj.optString("url", "")
                    if (dlUrl.startsWith("http")) {
                        out.add(
                            ResolvedStream(
                                url = dlUrl,
                                name = "Qobuz · DezAlty Stream",
                                quality = 1411,
                                headers = authHeaders,
                                referer = "https://dezalty.com/",
                            )
                        )
                    }
                }
            }

            // 3. GDStudio Qobuz URL resolver (`_parsewithgdstudioxyzapi` & `_parsewithgdstudioorgapi` in `qobuz.py`)
            runCatching {
                val gdUrl = GDStudioApi.getSongUrl(targetId, "qobuz", 999)
                if (gdUrl.isNotBlank()) {
                    out.add(
                        ResolvedStream(
                            url = gdUrl,
                            name = "Qobuz · GDStudio Lossless",
                            quality = 1411,
                            headers = mapOf("User-Agent" to DEFAULT_UA),
                        )
                    )
                }
            }
        }

        // 4. Fallback via GDStudio Qobuz search if targetId wasn't found directly
        if (out.isEmpty() && query.isNotBlank()) {
            runCatching {
                GDStudioApi.resolveBestStream(query, "qobuz")?.let { out.add(it) }
            }
        }

        return out
    }

    private fun parseTrack(obj: JSONObject?): MusicTrack? {
        obj ?: return null
        val id = obj.optLong("id", 0).toString()
        val title = obj.optString("title", "")
        if (id == "0" || title.isBlank()) return null
        val albumObj = obj.optJSONObject("album")
        val imageObj = albumObj?.optJSONObject("image")
        return MusicTrack(
            id = "qobuz:$id",
            title = title,
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

// =========================================================================
// 6. JioSaavn Catalogue & Direct 320kbps Stream Resolver
// =========================================================================

object JioSaavnApi {
    private const val BASE = "https://www.jiosaavn.com/api.php"
    private const val DES_KEY = "38346591"

    private val defaultHeaders = mapOf(
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "en-US,en;q=0.9",
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to "https://www.jiosaavn.com/",
        "User-Agent" to DEFAULT_UA,
        "Cookie" to "DL=english; L=english%2Chindi%2Cpunjabi; geo=28.6139%2C77.2090%2CIN%2CDelhi%2CNew%20Delhi%2C110001",
    )

    suspend fun search(query: String, limit: Int = 15): List<MusicTrack> {
        return runCatching {
            val resp = app.get(
                "$BASE?__call=search.getResults&_format=json&_marker=0&api_version=4&ctx=web6dot0" +
                        "&q=${query.encodeUrl()}&p=1&n=$limit",
                headers = defaultHeaders,
                timeout = 10L
            )
            val results = JSONObject(resp.text).optJSONArray("results") ?: return emptyList()
            (0 until results.length()).mapNotNull { parseSaavnTrack(results.optJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    suspend fun searchAlbums(query: String, limit: Int = 10): List<MusicAlbum> {
        return runCatching {
            val resp = app.get(
                "$BASE?__call=search.getAlbumResults&_format=json&_marker=0&api_version=4&ctx=web6dot0" +
                        "&q=${query.encodeUrl()}&p=1&n=$limit",
                headers = defaultHeaders,
                timeout = 10L
            )
            val results = JSONObject(resp.text).optJSONArray("results") ?: return emptyList()
            (0 until results.length()).mapNotNull { i ->
                val obj = results.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optString("id", "")
                val title = obj.optString("title", obj.optString("name", "")).cleanHtml()
                if (id.isBlank() || title.isBlank()) return@mapNotNull null
                MusicAlbum(
                    id = "jiosaavn-album:$id",
                    title = title,
                    artist = obj.optString("subtitle", obj.optString("music", "")).cleanHtml(),
                    coverUrl = obj.optString("image", "").replace("150x150", "500x500").replace("50x50", "500x500"),
                    source = "jiosaavn",
                    year = obj.optString("year", ""),
                )
            }
        }.getOrDefault(emptyList())
    }

    suspend fun getChart(limit: Int = 20): List<MusicTrack> {
        runCatching {
            val resp = app.get(
                "$BASE?__call=content.getTrending&_format=json&_marker=0&api_version=4&ctx=web6dot0&entity_type=song&entity_language=english,hindi",
                headers = defaultHeaders,
                timeout = 10L
            )
            val arr = JSONArray(resp.text)
            if (arr.length() > 0) {
                val tracks = (0 until minOf(arr.length(), limit)).mapNotNull { parseSaavnTrack(arr.optJSONObject(it)) }
                if (tracks.isNotEmpty()) return tracks
            }
        }
        return search("trending hits", limit)
    }

    suspend fun getTrackById(id: String): MusicTrack? {
        return runCatching {
            val resp = app.get(
                "$BASE?__call=song.getDetails&_format=json&_marker=0&api_version=4&pids=$id&ctx=web6dot0",
                headers = defaultHeaders,
                timeout = 10L
            )
            val obj = JSONObject(resp.text)
            val song = obj.optJSONObject(id)
                ?: obj.optJSONArray("songs")?.optJSONObject(0)
                ?: obj.optJSONObject("songs")?.optJSONObject(id)
            song?.let { parseSaavnTrack(it) }
        }.getOrNull()
    }

    suspend fun getAlbumTracks(albumId: String): List<MusicTrack> {
        return runCatching {
            val resp = app.get(
                "$BASE?__call=content.getAlbumDetails&_format=json&_marker=0&api_version=4&ctx=web6dot0&albumid=$albumId",
                headers = defaultHeaders,
                timeout = 10L
            )
            val obj = JSONObject(resp.text)
            val list = obj.optJSONArray("list") ?: obj.optJSONArray("songs") ?: return emptyList()
            (0 until list.length()).mapNotNull { parseSaavnTrack(list.optJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun decryptMediaUrl(encrypted: String): String {
        if (encrypted.isBlank()) return ""
        return try {
            val keyBytes = DES_KEY.toByteArray(Charsets.UTF_8)
            val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "DES"))
            val decoded = Base64.decode(encrypted.trim(), Base64.DEFAULT)
            val decrypted = cipher.doFinal(decoded)
            String(decrypted, Charsets.UTF_8).trim()
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Get direct streaming URL for a JioSaavn track.
     * Checks both `more_info.encrypted_media_url` (v4 API) and top-level `encrypted_media_url`,
     * plus `song.generateAuthToken` fallback.
     */
    suspend fun getStreamUrl(trackId: String, quality: String = "320"): String {
        return runCatching {
            val resp = app.get(
                "$BASE?__call=song.getDetails&_format=json&_marker=0&api_version=4&pids=$trackId&ctx=web6dot0",
                headers = defaultHeaders,
                timeout = 10L
            )
            val obj = JSONObject(resp.text)
            val song = obj.optJSONObject(trackId)
                ?: obj.optJSONArray("songs")?.optJSONObject(0)
                ?: obj.optJSONObject("songs")?.optJSONObject(trackId)
                ?: return ""

            val moreInfo = song.optJSONObject("more_info")
            val encryptedUrl = moreInfo?.optString("encrypted_media_url", "")?.ifBlank { null }
                ?: song.optString("encrypted_media_url", "")

            if (encryptedUrl.isBlank()) return ""

            // 1. Try DES-ECB direct decryption
            val decrypted = decryptMediaUrl(encryptedUrl)
            if (decrypted.startsWith("http")) {
                val has320 = moreInfo?.optString("320kbps", "true") != "false"
                val targetQuality = if (quality == "320" && !has320) "160" else quality
                // the decrypted url carries whatever bitrate the server chose - 96, 160 or 320 -
                // so the number itself has to be replaced, not the literal "_96."
                return Regex("""_\d+\.mp4""").replace(decrypted, "_${targetQuality}.mp4")
            }

            // 2. Fallback to song.generateAuthToken API
            val authResp = app.get(
                "$BASE?__call=song.generateAuthToken&url=${encryptedUrl.encodeUrl()}&bitrate=$quality&_format=json&api_version=4&_marker=0&ctx=web6dot0",
                headers = defaultHeaders,
                timeout = 8L
            )
            JSONObject(authResp.text).optString("auth_url", "")
        }.getOrDefault("")
    }

    private fun parseSaavnTrack(obj: JSONObject?): MusicTrack? {
        obj ?: return null
        val id = obj.optString("id", "")
        if (id.isBlank()) return null

        val imageUrl = obj.optString("image", "")
            .replace("150x150", "500x500")
            .replace("50x50", "500x500")

        val moreInfo = obj.optJSONObject("more_info")
        val artistMap = moreInfo?.optJSONObject("artistMap")?.optJSONArray("primary_artists")
            ?: obj.optJSONObject("artistMap")?.optJSONArray("primary_artists")

        val artist = when {
            artistMap != null && artistMap.length() > 0 ->
                (0 until artistMap.length()).mapNotNull {
                    artistMap.optJSONObject(it)?.optString("name", "")?.cleanHtml()?.ifBlank { null }
                }.joinToString(", ")
            else -> moreInfo?.optString("music", "")?.cleanHtml()?.ifBlank { null }
                ?: obj.optString("primary_artists", obj.optString("subtitle", "")).cleanHtml()
        }

        val encryptedUrl = moreInfo?.optString("encrypted_media_url", "")?.ifBlank { null }
            ?: obj.optString("encrypted_media_url", "")

        val duration = moreInfo?.optString("duration", "")?.toIntOrNull()
            ?: obj.optString("duration", "0").toIntOrNull()
            ?: 0

        val album = moreInfo?.optString("album", "")?.cleanHtml()?.ifBlank { null }
            ?: obj.optString("album", "").cleanHtml()

        return MusicTrack(
            id = "jiosaavn:$id",
            title = obj.optString("title", obj.optString("song", "")).cleanHtml(),
            artist = artist,
            album = album,
            duration = duration,
            coverUrl = imageUrl,
            source = "jiosaavn",
            sourceId = id,
            previewUrl = if (encryptedUrl.isNotBlank()) {
                Regex("""_\d+\.mp4""").replace(decryptMediaUrl(encryptedUrl), "_320.mp4")
            } else "",
        )
    }

    private fun String.cleanHtml(): String =
        this.replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
}

// =========================================================================
// 7. GDStudio Multi-Source Aggregator (Ported from CharlesPikachu/musicdl `gdstudio.py`)
// =========================================================================

object GDStudioApi {
    private const val VERSION = "2026.09.25"
    private val HOSTS = listOf("music.gdstudio.xyz", "music.gdstudio.org")
    val SUPPORTED_SOURCES = listOf("tidal", "qobuz", "apple", "ytmusic", "joox", "netease", "kuwo")

    private fun headers(host: String) = mapOf(
        "User-Agent" to DEFAULT_UA,
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "Referer" to "https://$host/",
        "Origin" to "https://$host",
        "X-Requested-With" to "XMLHttpRequest",
        "Accept" to "application/json, text/javascript, */*; q=0.01",
    )

    fun jsEncodeUriComponent(value: String): String {
        return URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20")
            .replace("%7E", "~")
    }

    private fun normalizeVersion(version: String): String {
        return version.split(".").joinToString("") { if (it.length == 1) "0$it" else it }
    }

    private fun leftRotate(x: Int, amount: Int): Int {
        return (x shl amount) or (x ushr (32 - amount))
    }

    /**
     * Exact port of `GDStudioMusicClient.gdstudiomd5` from CharlesPikachu/musicdl `gdstudio.py`.
     * Uses the custom constant table (note index 29 `0xFCFEA3F8` and index 31 `0x8D2A4C8A`).
     */
    fun gdstudioMd5(message: String): String {
        val msgBytes = message.toByteArray(Charsets.UTF_8)
        val bitLen = msgBytes.size.toLong() * 8L
        val padLen = ((56 - (msgBytes.size + 1) % 64) + 64) % 64
        val totalLen = msgBytes.size + 1 + padLen + 8
        val data = ByteArray(totalLen)
        System.arraycopy(msgBytes, 0, data, 0, msgBytes.size)
        data[msgBytes.size] = 0x80.toByte()
        ByteBuffer.wrap(data, totalLen - 8, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(bitLen)

        var a0 = 0x67452301
        var b0 = 0xEFCDAB89.toInt()
        var c0 = 0x98BADCFE.toInt()
        var d0 = 0x10325476

        val shifts = intArrayOf(
            7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
            5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
            4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
            6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21
        )

        val table = intArrayOf(
            0xD76AA478.toInt(), 0xE8C7B756.toInt(), 0x242070DB, 0xC1BDCEEE.toInt(),
            0xF57C0FAF.toInt(), 0x4787C62A, 0xA8304613.toInt(), 0xFD469501.toInt(),
            0x698098D8, 0x8B44F7AF.toInt(), 0xFFFF5BB1.toInt(), 0x895CD7BE.toInt(),
            0x6B901122, 0xFD987193.toInt(), 0xA679438E.toInt(), 0x49B40821,
            0xF61E2562.toInt(), 0xC040B340.toInt(), 0x265E5A51, 0xE9B6C7AA.toInt(),
            0xD62F105D.toInt(), 0x02441453, 0xD8A1E681.toInt(), 0xE7D3FBC8.toInt(),
            0x21E1CDE6, 0xC33707D6.toInt(), 0xF4D50D87.toInt(), 0x455A14ED,
            0xA9E3E905.toInt(), 0xFCFEA3F8.toInt(), 0x676F02D9, 0x8D2A4C8A.toInt(),
            0xFFFA3942.toInt(), 0x8771F681.toInt(), 0x6D9D6122, 0xFDE5380C.toInt(),
            0xA4BEEA44.toInt(), 0x4BDECFA9, 0xF6BB4B60.toInt(), 0xBEBFBC70.toInt(),
            0x289B7EC6, 0xEAA127FA.toInt(), 0xD4EF3085.toInt(), 0x04881D05,
            0xD9D4D039.toInt(), 0xE6DB99E5.toInt(), 0x1FA27CF8, 0xC4AC5665.toInt(),
            0xF4292244.toInt(), 0x432AFF97, 0xAB9423A7.toInt(), 0xFC93A039.toInt(),
            0x655B59C3, 0x8F0CCC92.toInt(), 0xFFEFF47D.toInt(), 0x85845DD1.toInt(),
            0x6FA87E4F, 0xFE2CE6E0.toInt(), 0xA3014314.toInt(), 0x4E0811A1,
            0xF7537E82.toInt(), 0xBD3AF235.toInt(), 0x2AD7D2BB, 0xEB86D391.toInt()
        )

        val words = IntArray(16)
        var offset = 0
        while (offset < totalLen) {
            val bb = ByteBuffer.wrap(data, offset, 64).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until 16) words[i] = bb.int
            var a = a0
            var b = b0
            var c = c0
            var d = d0

            for (i in 0 until 64) {
                val f: Int
                val g: Int
                when {
                    i < 16 -> {
                        f = (b and c) or (b.inv() and d)
                        g = i
                    }
                    i < 32 -> {
                        f = (d and b) or (c and d.inv())
                        g = (5 * i + 1) % 16
                    }
                    i < 48 -> {
                        f = b xor c xor d
                        g = (3 * i + 5) % 16
                    }
                    else -> {
                        f = c xor (b or d.inv())
                        g = (7 * i) % 16
                    }
                }
                val temp = f + a + table[i] + words[g]
                a = d
                d = c
                c = b
                b += leftRotate(temp, shifts[i])
            }
            a0 += a
            b0 += b
            c0 += c
            d0 += d
            offset += 64
        }

        val out = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(a0).putInt(b0).putInt(c0).putInt(d0)
        return out.array().joinToString("") { "%02x".format(it) }
    }

    private suspend fun getServerTime(host: String): String {
        return runCatching {
            val resp = app.get("https://$host/time", headers = headers(host), timeout = 6L)
            val txt = resp.text.trim()
            if (txt.length >= 9 && txt.all { it.isDigit() }) txt else (System.currentTimeMillis() / 1000).toString()
        }.getOrDefault((System.currentTimeMillis() / 1000).toString())
    }

    private suspend fun makeSign(payload: String, host: String): String {
        val timePrefix = getServerTime(host).take(9)
        val versionText = normalizeVersion(VERSION)
        return gdstudioMd5("$timePrefix|$host|$versionText|$payload").takeLast(8).uppercase()
    }

    suspend fun search(query: String, source: String = "netease", count: Int = 15): List<MusicTrack> {
        val encodedKeyword = jsEncodeUriComponent(query)
        for (host in HOSTS) {
            runCatching {
                val sign = makeSign(encodedKeyword, host)
                val url = "https://$host/api.php?types=search&count=$count&source=$source&pages=1&name=${query.encodeUrl()}&s=$sign"
                val resp = app.get(url, headers = headers(host), timeout = 10L)
                val arr = JSONArray(resp.text)
                if (arr.length() > 0) {
                    return (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        val id = o.optString("id", "")
                        val urlId = o.optString("url_id", id)
                        val name = o.optString("name", "")
                        if (id.isBlank() || name.isBlank()) return@mapNotNull null
                        val artistsArr = o.optJSONArray("artist")
                        val artist = if (artistsArr != null && artistsArr.length() > 0) {
                            (0 until artistsArr.length()).mapNotNull { artistsArr.optString(it).ifBlank { null } }.joinToString(", ")
                        } else o.optString("artist", "")
                        val rootSrc = o.optString("source", source)
                        MusicTrack(
                            id = "gdstudio:$rootSrc:$urlId",
                            title = name,
                            artist = artist,
                            album = o.optString("album", ""),
                            source = "gdstudio",
                            sourceId = "$rootSrc:$urlId",
                            extraUrlId = urlId,
                        )
                    }
                }
            }
        }
        return emptyList()
    }

    suspend fun getSongUrl(songUrlId: String, source: String = "netease", br: Int = 999): String {
        val encodedId = jsEncodeUriComponent(songUrlId)
        for (host in HOSTS) {
            runCatching {
                val sign = makeSign(encodedId, host)
                val url = "https://$host/api.php?types=url&id=${songUrlId.encodeUrl()}&source=$source&br=$br&s=$sign"
                val resp = app.get(url, headers = headers(host), timeout = 10L)
                val obj = JSONObject(resp.text)
                val dlUrl = obj.optString("url", "")
                val size = obj.optLong("size", -1L)
                val actualBr = obj.optInt("br", 0)
                if (dlUrl.isNotBlank() && size != 0L && actualBr != -1) {
                    return if (dlUrl.startsWith("http")) dlUrl else "https://$host/${dlUrl.trimStart('/')}"
                }
            }
        }
        return ""
    }

    suspend fun resolveBestStream(query: String, preferredSource: String = "netease"): ResolvedStream? {
        val sourcesToTry = listOf(preferredSource, "netease", "kuwo", "joox", "tidal", "qobuz", "ytmusic").distinct()
        for (src in sourcesToTry) {
            val hits = search(query, src, 3)
            val first = hits.firstOrNull() ?: continue
            val urlId = first.extraUrlId.ifBlank { first.sourceId.substringAfter(":") }
            val streamUrl = getSongUrl(urlId, src, 999)
            if (streamUrl.startsWith("http")) {
                return ResolvedStream(
                    url = streamUrl,
                    name = "GDStudio · ${src.uppercase()}",
                    quality = 999,
                    headers = mapOf("User-Agent" to DEFAULT_UA),
                )
            }
        }
        return null
    }
}

// =========================================================================
// 8. Jamendo Catalogue & Direct FLAC/MP3 Resolver (Ported from CharlesPikachu/musicdl `jamendo.py`)
// =========================================================================

object JamendoApi {
    private const val BASE = "https://www.jamendo.com/api"

    private fun makeXJamCall(path: String): String {
        val rand = Math.random().toString()
        val sha1 = MessageDigest.getInstance("SHA-1")
            .digest((path + rand).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "\$$sha1*$rand~"
    }

    private fun headers(path: String) = mapOf(
        "Referer" to "https://www.jamendo.com/search?q=musicdl",
        "User-Agent" to DEFAULT_UA,
        "x-jam-version" to "4rkl5f",
        "x-jam-call" to makeXJamCall(path),
        "X-Requested-With" to "XMLHttpRequest",
        "Accept" to "application/json",
    )

    suspend fun search(query: String, limit: Int = 15): List<MusicTrack> {
        return runCatching {
            val url = "$BASE/search?query=${query.encodeUrl()}&type=track&limit=$limit&identities=www&offset=0"
            val resp = app.get(url, headers = headers("/api/search"), timeout = 10L)
            val arr = JSONArray(resp.text)
            (0 until arr.length()).mapNotNull { parseTrack(arr.optJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    suspend fun getTrack(songId: String): MusicTrack? {
        return runCatching {
            val resp = app.get("$BASE/tracks?id[]=${songId.encodeUrl()}", headers = headers("/api/tracks"), timeout = 10L)
            val arr = JSONArray(resp.text)
            if (arr.length() > 0) parseTrack(arr.optJSONObject(0)) else null
        }.getOrNull()
    }

    suspend fun resolveStreams(songId: String, query: String): List<ResolvedStream> {
        val out = mutableListOf<ResolvedStream>()
        val targetId = songId.ifBlank {
            runCatching { search(query, 1).firstOrNull()?.sourceId ?: "" }.getOrDefault("")
        }
        if (targetId.isBlank()) return out

        // 1. Direct Jamendo storage FLAC URL (from `jamendo.py`)
        val flacUrl = "https://prod-1.storage.jamendo.com/download/track/$targetId/flac/"
        runCatching {
            val head = app.head(flacUrl, headers = mapOf("User-Agent" to DEFAULT_UA), timeout = 8L)
            if (head.code in 200..299) {
                out.add(
                    ResolvedStream(
                        url = flacUrl,
                        name = "Jamendo · Direct FLAC",
                        quality = 1411,
                        headers = mapOf("User-Agent" to DEFAULT_UA),
                    )
                )
            }
        }

        // 2. Track stream endpoints from /api/tracks
        runCatching {
            val resp = app.get("$BASE/tracks?id[]=${targetId.encodeUrl()}", headers = headers("/api/tracks"), timeout = 10L)
            val item = JSONArray(resp.text).optJSONObject(0)
            if (item != null) {
                val streamObj = item.optJSONObject("stream")
                val downloadObj = item.optJSONObject("download")
                val candidates = listOfNotNull(
                    streamObj?.optString("flac", "")?.takeIf { it.startsWith("http") }?.let { "Jamendo · FLAC" to it },
                    downloadObj?.optString("flac", "")?.takeIf { it.startsWith("http") }?.let { "Jamendo · Download FLAC" to it },
                    streamObj?.optString("mp33", "")?.takeIf { it.startsWith("http") }?.let { "Jamendo · 320kbps MP3" to it },
                    streamObj?.optString("mp32", "")?.takeIf { it.startsWith("http") }?.let { "Jamendo · MP3" to it },
                    downloadObj?.optString("mp3", "")?.takeIf { it.startsWith("http") }?.let { "Jamendo · Download MP3" to it },
                )
                for ((label, u) in candidates) {
                    out.add(
                        ResolvedStream(
                            url = u,
                            name = label,
                            quality = if (label.contains("FLAC")) 1411 else 320,
                            headers = mapOf("User-Agent" to DEFAULT_UA),
                        )
                    )
                }
            }
        }

        return out
    }

    private fun parseTrack(obj: JSONObject?): MusicTrack? {
        obj ?: return null
        val id = obj.optString("id", "")
        val name = obj.optString("name", "")
        if (id.isBlank() || name.isBlank()) return null
        val albumId = obj.optJSONObject("album")?.optString("id", "") ?: obj.optString("albumId", "")
        val artistName = obj.optJSONObject("artist")?.optString("name", "") ?: obj.optString("artistName", "")
        val albumName = obj.optJSONObject("album")?.optString("name", "") ?: ""
        return MusicTrack(
            id = "jamendo:$id",
            title = name,
            artist = artistName,
            album = albumName,
            duration = obj.optInt("duration", 0),
            coverUrl = if (albumId.isNotBlank()) "https://usercontent.jamendo.com?type=album&id=$albumId&width=500&trackid=$id" else "",
            source = "jamendo",
            sourceId = id,
        )
    }
}

// =========================================================================
// 9. MP3Juice YouTube + SoundCloud Gateway (Ported from CharlesPikachu/musicdl `mp3juice.py`)
// =========================================================================

object MP3JuiceApi {
    private const val BASE = "https://mp3juice.sc"
    @Volatile private var apiKey: String = ""

    private val defaultHeaders = mapOf(
        "User-Agent" to DEFAULT_UA,
        "Referer" to "$BASE/",
        "Origin" to BASE,
    )

    private suspend fun getApiKey(): String {
        if (apiKey.isNotBlank()) return apiKey
        runCatching {
            val html = app.get("$BASE/", headers = defaultHeaders, timeout = 10L).text
            val extracted = html.substringAfter("var apiKey='", "").substringBefore("'", "")
            if (extracted.isNotBlank()) {
                apiKey = extracted
                return apiKey
            }
        }
        return apiKey
    }

    private suspend fun rawSearch(query: String): JSONObject? {
        val key = getApiKey()
        if (key.isBlank()) return null
        return runCatching {
            val qEncoded = Base64.encodeToString(
                URLEncoder.encode(query, "UTF-8").replace("+", "%20").toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )
            val ts = System.currentTimeMillis()
            val url = "$BASE/api/v1/search?api_key=${key.encodeUrl()}&y=s&q=${qEncoded.encodeUrl()}&_=$ts"
            val resp = app.get(url, headers = defaultHeaders, timeout = 10L)
            JSONObject(resp.text)
        }.getOrNull()
    }

    suspend fun searchSoundCloud(query: String, limit: Int = 10): List<MusicTrack> {
        val json = rawSearch(query) ?: return emptyList()
        val sc = json.optJSONArray("sc") ?: return emptyList()
        return (0 until minOf(sc.length(), limit)).mapNotNull { i ->
            val item = sc.optJSONObject(i) ?: return@mapNotNull null
            val id = item.optString("id", "")
            val title = item.optString("title", "")
            val idB64 = item.optString("id_base64", "")
            val titleB64 = item.optString("title_base64", "")
            if (id.isBlank() || title.isBlank()) return@mapNotNull null
            MusicTrack(
                id = "soundcloud:$id",
                title = title,
                artist = "SoundCloud",
                source = "soundcloud",
                sourceId = id,
                previewUrl = if (idB64.isNotBlank() && titleB64.isNotBlank()) "https://thetacloud.org/s/$idB64/$titleB64/" else "",
            )
        }
    }

    suspend fun resolveSoundCloudStream(query: String): String {
        val json = rawSearch(query) ?: return ""
        val first = json.optJSONArray("sc")?.optJSONObject(0) ?: return ""
        val idB64 = first.optString("id_base64", "")
        val titleB64 = first.optString("title_base64", "")
        return if (idB64.isNotBlank() && titleB64.isNotBlank()) "https://thetacloud.org/s/$idB64/$titleB64/" else ""
    }

    suspend fun resolveYouTubeStream(videoId: String): String {
        val key = getApiKey()
        if (key.isBlank() || videoId.isBlank()) return ""
        return runCatching {
            val ts = System.currentTimeMillis()
            val authResp = app.get(
                "https://theta.thetacloud.org/api/v1/auth?api_key=${key.encodeUrl()}&_=$ts",
                headers = defaultHeaders,
                timeout = 10L
            )
            val authKey = JSONObject(authResp.text).optString("key", "")
            if (authKey.isBlank()) return ""
            val authHeaders = defaultHeaders + ("Authorization" to "Bearer $authKey")
            val initResp = app.get(
                "https://theta.thetacloud.org/api/v1/init?_=${System.currentTimeMillis()}",
                headers = authHeaders,
                timeout = 10L
            )
            val convertUrl = JSONObject(initResp.text).optString("convertURL", "")
            if (convertUrl.isBlank()) return ""
            val convResp = app.get(
                "$convertUrl&v=${videoId.encodeUrl()}&f=mp3&_=${System.currentTimeMillis() / 1000}",
                headers = defaultHeaders,
                timeout = 12L
            )
            val redirectUrl = JSONObject(convResp.text).optString("redirectURL", "")
            if (redirectUrl.isBlank()) return ""
            val redirResp = app.get(redirectUrl, headers = defaultHeaders, timeout = 12L)
            val dlUrl = JSONObject(redirResp.text).optString("downloadURL", "")
            if (dlUrl.startsWith("http")) "$dlUrl&v=$videoId&f=mp3&r=mp3juice.sc" else ""
        }.getOrDefault("")
    }
}

// =========================================================================
// 10. YouTube Music Catalogue & Multi-Converter Stream Resolver
//     (Ported from CharlesPikachu/musicdl `youtube.py`)
// =========================================================================

object YouTubeMusicApi {
    private const val BASE = "https://music.youtube.com/youtubei/v1"
    private const val IOS_UA =
        "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X)"
    private const val API_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"

    private val context = JSONObject().apply {
        put("client", JSONObject().apply {
            put("clientName", "WEB_REMIX")
            put("clientVersion", "1.20241009.01.00")
            put("hl", "en")
            put("gl", "US")
        })
    }

    suspend fun search(query: String, limit: Int = 15): List<MusicTrack> {
        runCatching {
            val payload = JSONObject().apply {
                put("context", context)
                put("query", query)
            }
            val resp = app.post(
                "$BASE/search?key=$API_KEY",
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "User-Agent" to DEFAULT_UA,
                    "Referer" to "https://music.youtube.com/",
                    "Origin" to "https://music.youtube.com",
                ),
                requestBody = payload.toString().toRequestBody("application/json".toMediaType()),
                timeout = 10L
            )
            val text = resp.text
            val videoIds = Regex(""""videoId"\s*:\s*"([a-zA-Z0-9_-]{11})"""").findAll(text)
                .map { it.groupValues[1] }
                .distinct()
                .take(limit)
                .toList()
            if (videoIds.isNotEmpty()) {
                return videoIds.mapIndexed { idx, vid ->
                    MusicTrack(
                        id = "ytmusic:$vid",
                        title = if (idx == 0) query else "$query (#${idx + 1})",
                        artist = "YouTube Music",
                        coverUrl = "https://i.ytimg.com/vi/$vid/hqdefault.jpg",
                        source = "ytmusic",
                        sourceId = vid,
                    )
                }
            }
        }
        return GDStudioApi.search(query, "ytmusic", limit)
    }

    suspend fun searchVideoId(query: String): String? {
        return runCatching {
            val payload = JSONObject().apply {
                put("context", context)
                put("query", query)
            }
            val resp = app.post(
                "$BASE/search?key=$API_KEY",
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "User-Agent" to DEFAULT_UA,
                    "Referer" to "https://music.youtube.com/",
                    "Origin" to "https://music.youtube.com",
                ),
                requestBody = payload.toString().toRequestBody("application/json".toMediaType()),
                timeout = 10L
            )
            Regex(""""videoId"\s*:\s*"([a-zA-Z0-9_-]{11})"""").find(resp.text)?.groupValues?.get(1)
        }.getOrNull()
    }

    /**
     * Resolve direct MP3/audio streams for a YouTube videoId using CharlesPikachu/musicdl (`youtube.py`):
     *  1. Y2Mate / cnv.cx (`_parsewithy2mateapi`)
     *  2. CnvMP3 (`_parsewithcnvmp3api`)
     *  3. MediaYTMP3 / Convert1s (`_parsewithmediaytmp3api`)
     *  4. GMPion / ezsrv.net (`_parsewithgmpionapi`)
     *  5. MP3Juice / ThetaCloud (`mp3juice.py`)
     */
    /**
     * Audio-only stream straight from YouTube's player endpoint.
     *
     * The CloudStream extractor hands back every stream it finds, video included, and a video-only
     * stream plays as a silent picture - which is exactly what was happening. This asks for the
     * adaptive formats and keeps only the audio mime types, so what comes back is a song, not a clip.
     */
    suspend fun audioOnlyStream(videoId: String): ResolvedStream? {
        if (videoId.length != 11) return null

        // clients that still answer with direct audio urls
        val clients = listOf(
            Triple("ANDROID_MUSIC", "7.27.52", 30),
            Triple("ANDROID_VR", "1.61.48", 32),
            Triple("IOS", "19.45.4", 0)
        )

        for ((clientName, clientVersion, sdk) in clients) {
            val payload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", clientName)
                        put("clientVersion", clientVersion)
                        put("hl", "en")
                        put("gl", "US")
                        if (sdk > 0) put("androidSdkVersion", sdk)
                    })
                })
                put("videoId", videoId)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }

            val json = runCatching {
                JSONObject(
                    app.post(
                        "https://www.youtube.com/youtubei/v1/player?key=$API_KEY&prettyPrint=false",
                        headers = mapOf(
                            "Content-Type" to "application/json",
                            "User-Agent" to if (clientName == "IOS") IOS_UA else DEFAULT_UA,
                            "Origin" to "https://www.youtube.com",
                            "Referer" to "https://www.youtube.com/"
                        ),
                        requestBody = payload.toString().toRequestBody("application/json".toMediaType()),
                        timeout = 15L
                    ).text
                )
            }.getOrNull() ?: continue

            val formats = json.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats")
                ?: continue

            var best: ResolvedStream? = null
            var bestRate = -1
            for (i in 0 until formats.length()) {
                val f = formats.optJSONObject(i) ?: continue
                // the whole point: audio formats only, never a video track
                if (!f.optString("mimeType").startsWith("audio/", ignoreCase = true)) continue
                val url = f.optString("url").ifBlank { continue }
                val rate = f.optInt("bitrate", 0)
                if (rate > bestRate) {
                    bestRate = rate
                    val isOpus = f.optString("mimeType").contains("opus", true)
                    best = ResolvedStream(
                        url = url,
                        name = "YouTube Music · audio ${rate / 1000}kbps" + if (isOpus) " opus" else "",
                        quality = rate / 1000,
                        referer = "https://music.youtube.com/",
                        headers = mapOf("User-Agent" to if (clientName == "IOS") IOS_UA else DEFAULT_UA)
                    )
                }
            }
            if (best != null) return best
        }
        return null
    }

    suspend fun resolveStreams(videoId: String, titleHint: String = "track"): List<ResolvedStream> {
        val out = mutableListOf<ResolvedStream>()
        if (videoId.isBlank()) return out

        // 1. Y2Mate / cnv.cx (`_parsewithy2mateapi` in `youtube.py`)
        runCatching {
            val y2Headers = mapOf(
                "Origin" to "https://frame.y2meta-uk.com",
                "Referer" to "https://frame.y2meta-uk.com/",
                "Accept" to "*/*",
                "User-Agent" to DEFAULT_UA,
            )
            val keyResp = app.get(
                "https://cnv.cx/v2/sanity/key?id=${videoId.encodeUrl()}",
                headers = y2Headers + ("Content-Type" to "application/json"),
                timeout = 10L
            )
            val key = JSONObject(keyResp.text).optString("key", "")
            if (key.isNotBlank()) {
                val form = "link=${"https://youtu.be/$videoId".encodeUrl()}&format=mp3&audioBitrate=320&videoQuality=720&filenameStyle=pretty&vCodec=h264"
                val convResp = app.post(
                    "https://cnv.cx/v2/converter",
                    headers = y2Headers + mapOf(
                        "key" to key,
                        "Content-Type" to "application/x-www-form-urlencoded",
                    ),
                    requestBody = form.toRequestBody("application/x-www-form-urlencoded".toMediaType()),
                    timeout = 12L
                )
                val dlUrl = JSONObject(convResp.text).optString("url", "").replace("/tunnel?", "/tunnel/?")
                if (dlUrl.startsWith("http")) {
                    out.add(
                        ResolvedStream(
                            url = dlUrl,
                            name = "YouTube · Y2Mate 320kbps",
                            quality = 320,
                            headers = mapOf("Referer" to "https://frame.y2meta-uk.com/", "User-Agent" to DEFAULT_UA),
                            referer = "https://frame.y2meta-uk.com/",
                        )
                    )
                }
            }
        }

        // 2. CnvMP3 (`_parsewithcnvmp3api` in `youtube.py`)
        runCatching {
            val cnvHeaders = mapOf(
                "User-Agent" to DEFAULT_UA,
                "Origin" to "https://cnvmp3.com",
                "Referer" to "https://cnvmp3.com/v55",
                "Content-Type" to "application/json",
            )
            val checkResp = app.post(
                "https://cnvmp3.com/check_database.php",
                headers = cnvHeaders,
                requestBody = JSONObject().apply {
                    put("youtube_id", videoId)
                    put("quality", 0)
                    put("formatValue", 1)
                }.toString().toRequestBody("application/json".toMediaType()),
                timeout = 10L
            )
            val checkJson = JSONObject(checkResp.text)
            val cachedUrl = if (checkJson.optBoolean("success", false)) {
                checkJson.optJSONObject("data")?.optString("server_path", "") ?: ""
            } else ""

            if (cachedUrl.startsWith("http")) {
                out.add(
                    ResolvedStream(
                        url = cachedUrl,
                        name = "YouTube · CnvMP3 320kbps",
                        quality = 320,
                        headers = mapOf("Referer" to "https://cnvmp3.com/", "User-Agent" to DEFAULT_UA),
                        referer = "https://cnvmp3.com/",
                    )
                )
            } else {
                val convResp = app.post(
                    "https://cnvmp3.com/download_video_ucep.php",
                    headers = cnvHeaders,
                    requestBody = JSONObject().apply {
                        put("url", "https://www.youtube.com/watch?v=$videoId")
                        put("quality", 0)
                        put("title", titleHint.ifBlank { videoId })
                        put("formatValue", 1)
                    }.toString().toRequestBody("application/json".toMediaType()),
                    timeout = 15L
                )
                val convJson = JSONObject(convResp.text)
                val dlLink = convJson.optString("download_link", "")
                if (convJson.optBoolean("success", false) && dlLink.startsWith("http")) {
                    out.add(
                        ResolvedStream(
                            url = dlLink,
                            name = "YouTube · CnvMP3 320kbps",
                            quality = 320,
                            headers = mapOf("Referer" to "https://cnvmp3.com/", "User-Agent" to DEFAULT_UA),
                            referer = "https://cnvmp3.com/",
                        )
                    )
                }
            }
        }

        // 3. MediaYTMP3 / Convert1s (`_parsewithmediaytmp3api` in `youtube.py`)
        if (out.isEmpty()) {
            runCatching {
                val baseHeaders = mapOf(
                    "User-Agent" to DEFAULT_UA,
                    "Accept" to "application/json",
                    "Origin" to "https://media.ytmp3.gg",
                    "Referer" to "https://media.ytmp3.gg/",
                    "Content-Type" to "application/json",
                )
                val payload = JSONObject().apply {
                    put("url", "https://www.youtube.com/watch?v=$videoId")
                    put("os", "windows")
                    put("output", JSONObject().apply {
                        put("type", "audio")
                        put("format", "mp3")
                    })
                    put("audio", JSONObject().apply {
                        put("bitrate", "320k")
                    })
                }
                val convResp = app.post(
                    "https://hub.convert1s.com/api/download",
                    headers = baseHeaders,
                    requestBody = payload.toString().toRequestBody("application/json".toMediaType()),
                    timeout = 10L
                )
                val statusUrl = JSONObject(convResp.text).optString("statusUrl", "")
                if (statusUrl.startsWith("http")) {
                    for (poll in 0 until 8) {
                        kotlinx.coroutines.delay(1000L)
                        val stResp = app.get(statusUrl, headers = baseHeaders, timeout = 8L)
                        val stJson = JSONObject(stResp.text)
                        val st = stJson.optString("status", "")
                        val dlUrl = stJson.optString("downloadUrl", "")
                        if (st == "completed" && dlUrl.startsWith("http")) {
                            out.add(
                                ResolvedStream(
                                    url = dlUrl,
                                    name = "YouTube · YTMP3 320kbps",
                                    quality = 320,
                                    headers = mapOf("Referer" to "https://media.ytmp3.gg/", "User-Agent" to DEFAULT_UA),
                                    referer = "https://media.ytmp3.gg/",
                                )
                            )
                            break
                        }
                        if (st in setOf("failed", "error", "blocked")) break
                    }
                }
            }
        }

        // 4. MP3Juice / ThetaCloud fallback (`mp3juice.py`)
        if (out.isEmpty()) {
            val mp3JuiceUrl = MP3JuiceApi.resolveYouTubeStream(videoId)
            if (mp3JuiceUrl.startsWith("http")) {
                out.add(
                    ResolvedStream(
                        url = mp3JuiceUrl,
                        name = "YouTube · MP3Juice",
                        quality = 192,
                        headers = mapOf("Referer" to "https://mp3juice.sc/", "User-Agent" to DEFAULT_UA),
                        referer = "https://mp3juice.sc/",
                    )
                )
            }
        }

        return out
    }
}

// =========================================================================
// 11. Blowfish Decryption for Deezer
// =========================================================================

object DeezerDecrypt {
    private const val BLOWFISH_SECRET = "g4el58wc0zvf9na1"
    private val BLOWFISH_IV = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)
    private const val CHUNK_SIZE = 2048

    fun generateKey(trackId: String): ByteArray {
        val md5 = MessageDigest.getInstance("MD5")
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
        val ivSpec = IvParameterSpec(BLOWFISH_IV)

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
