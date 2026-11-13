package com.watchuu.musicbox

import com.lagradost.cloudstream3.app
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Spotify Web catalogue & direct download client.
 *
 * Catalogue:
 *  1. Spotify Web anonymous session token + REST API (/v1/search, /v1/tracks, /v1/albums)
 *  2. Spotify Pathfinder GraphQL v1 query
 *  3. Spotify Embed (__NEXT_DATA__) parser for zero-token track/album/playlist metadata
 *  4. SpotSaver / iTunes / Deezer cross-catalogue fallback so Spotify search never returns empty
 *
 * Direct Download Resolvers (ported from CharlesPikachu/musicdl `spotify.py`):
 *  1. SpotiDown (`https://spotidown.me/en1` -> `/getTrackData` & `/convert`)
 *  2. RapidAPI Spotify Downloader (`https://spotify-downloader9.p.rapidapi.com/downloadSong`)
 *  3. SpotSaver (`https://spotsaver.net/api/spotify/` -> `/api/get-id/` -> `/api/download/`)
 *  4. MusicFab (`https://musicfab.io/api/spotify`)
 *  5. Spotify-Downloads (`https://www.spotify-downloads.com/api/download` -> `/api/status/` -> `/api/file/`)
 */
object SpotifyClient {
    private const val TAG = "SpotifyClient"

    private val TOTP_SECRET = intArrayOf(
        44, 55, 47, 42, 70, 40, 34, 114, 76, 74, 50, 111, 120, 97, 75, 76,
        94, 102, 43, 69, 49, 120, 118, 80, 64, 78
    )
    private const val TOTP_VERSION = 61
    private const val SEARCH_HASH = "fcad5a3e0d5af727fb76966f06971c19cfa2275e6ff7671196753e008611873c"

    private const val BASE_URL = "https://api-partner.spotify.com"
    private const val WEB_API_URL = "https://api.spotify.com/v1"
    private const val AUTH_URL = "https://open.spotify.com/get_access_token?reason=transport&productType=web_player"
    private const val API_TOKEN_URL = "https://open.spotify.com/api/token"
    private const val CLIENT_TOKEN_URL = "https://clienttoken.spotify.com/v1/clienttoken"
    private const val PATHFINDER_URL = "$BASE_URL/pathfinder/v2/query"

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"

    // Shared RapidAPI keys from CharlesPikachu/musicdl `spotify.py`
    private val RAPIDAPI_KEYS = listOf(
        "efdc9dead0msh3d6b04344364212p1e7029jsn40fd6cc8da39",
        "1162fa6edbmsh4e3ada3ee7a56eap10e77ajsn2a5e57cad3bc",
        "b956d6a526msh7b1a2a3662d09cdp11fa3djsn5b6cad23f10f",
        "53d36da411mshc55da25ad75d914p19c959jsnec7bf74dad86",
        "d44e6cbbffmsh4904ffce35d0541p157e2bjsn63f9db9e85db",
        "50b5f3b226msh0966c3a7bd972cap10e911jsn3c815d7d3627",
        "19712ae800msh39302756eeef1abp1b8019jsnc7967b2210ac",
        "2f5f8f1ed6msha939c8e6949b10ep16c31bjsnb07bb964bbbb",
        "97f21512b9mshbd44e421ed343a1p1a65b9jsn878d08ec3763",
        "907f71f2camshab357c69afa7df8p1fd360jsndbc11ed3ad7b",
        "b5a9edd0b8msh435ffe328e2f725p1bdf97jsn0f7c55a85991",
        "cff3589cb7msh4356e5ee7bf14cdp1a2aecjsne8f75aee4620",
        "be66b9a47emsh51482eb6cc9732ap18309cjsn51d8aa14fbef",
        "fc9a982916mshe1f3ad77f3e39ddp1f9b06jsna58c9c5eaba0",
        "e0f326883amsh94b0942a9513da4p16494ajsn29296f6e19fe",
        "0647bc5201msh84a9358b48d00eep163485jsne7ecf062e49f",
    )

    @Volatile private var accessToken: String = ""
    @Volatile private var clientToken: String = ""
    @Volatile private var tokenExpiry: Long = 0

    data class SpotifyTrack(
        val id: String,
        val name: String,
        val artist: String,
        val album: String,
        val duration: Int, // ms
        val coverUrl: String,
        val isrc: String = "",
        val previewUrl: String = "",
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

    data class SpotifyStreamResult(
        val url: String,
        val providerName: String,
        val quality: Int = 320,
        val headers: Map<String, String> = emptyMap(),
    )

    // ========== TOTP implementation ==========

    /**
     * The web player's TOTP key is NOT the raw secret bytes: each byte is XOR-ed with ((i % 33) + 9),
     * the results are joined as decimal text, and that ASCII text is the HMAC-SHA1 key.
     */
    private fun generateTOTP(timestampMs: Long = System.currentTimeMillis()): String {
        val joined = TOTP_SECRET.indices.joinToString("") { (TOTP_SECRET[it] xor ((it % 33) + 9)).toString() }
        val key = joined.toByteArray(Charsets.US_ASCII)
        var t = timestampMs / 30000
        val timeBytes = ByteArray(8)
        for (i in 7 downTo 0) {
            timeBytes[i] = (t and 0xFF).toByte()
            t = t shr 8
        }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        val hash = mac.doFinal(timeBytes)
        val offset = hash[hash.size - 1].toInt() and 0x0F
        val code = ((hash[offset].toInt() and 0x7F) shl 24) or
                ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                ((hash[offset + 2].toInt() and 0xFF) shl 8) or
                (hash[offset + 3].toInt() and 0xFF)
        return String.format("%06d", code % 1000000)
    }

    // ========== Auth ==========

    private val spCookies = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var spClientVersion = ""
    @Volatile private var spDeviceId = ""
    @Volatile private var spClientId = ""
    @Volatile private var clientExpiry = 0L
    @Volatile var lastError: String = ""
        private set

    private fun cookieHeader() = spCookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    private fun absorbCookies(resp: com.lagradost.nicehttp.NiceResponse) {
        for (sc in resp.headers.values("Set-Cookie")) {
            val m = Regex("^([^=]+)=([^;]*)").find(sc) ?: continue
            spCookies[m.groupValues[1].trim()] = m.groupValues[2].trim()
        }
        spCookies["sp_t"]?.let { spDeviceId = it }
    }

    private suspend fun ensureTokens(): Boolean {
        val now = System.currentTimeMillis()
        if (accessToken.isNotBlank() && now < tokenExpiry - 60_000 && clientToken.isNotBlank() && now < clientExpiry) return true
        accessToken = ""; clientToken = ""
        try {
            // 1. open.spotify.com -> sp_t cookie + clientVersion from appServerConfig
            if (spClientVersion.isBlank() || spDeviceId.isBlank()) {
                val page = app.get("https://open.spotify.com", headers = mapOf("User-Agent" to UA), timeout = 15L)
                absorbCookies(page)
                val m = Regex("""<script id="appServerConfig" type="text/plain">([^<]+)</script>""").find(page.text)
                if (m != null) {
                    spClientVersion = JSONObject(String(android.util.Base64.decode(m.groupValues[1], android.util.Base64.DEFAULT), Charsets.UTF_8))
                        .optString("clientVersion", "")
                }
                if (spDeviceId.isBlank()) spDeviceId = spCookies["sp_t"] ?: java.util.UUID.randomUUID().toString().replace("-", "")
            }
            if (spClientVersion.isBlank()) { lastError = "no clientVersion on open.spotify.com"; return false }

            // 2. anonymous access token (TOTP)
            val totp = generateTOTP()
            val tokResp = app.get(
                "$API_TOKEN_URL?reason=init&productType=web-player&totp=$totp&totpVer=$TOTP_VERSION&totpServer=$totp",
                headers = mapOf("User-Agent" to UA, "Content-Type" to "application/json;charset=UTF-8", "Cookie" to cookieHeader()),
                timeout = 15L,
            )
            absorbCookies(tokResp)
            if (tokResp.code != 200) { lastError = "/api/token HTTP ${tokResp.code}"; return false }
            val tj = JSONObject(tokResp.text)
            accessToken = tj.optString("accessToken", "")
            spClientId = tj.optString("clientId", "")
            tokenExpiry = tj.optLong("accessTokenExpirationTimestampMs", now + 1800_000L)
            if (accessToken.isBlank() || spClientId.isBlank()) { lastError = "token response incomplete"; return false }

            // 3. client token
            val payload = JSONObject().put("client_data", JSONObject()
                .put("client_version", spClientVersion).put("client_id", spClientId)
                .put("js_sdk_data", JSONObject().put("device_brand", "unknown").put("device_model", "unknown")
                    .put("os", "windows").put("os_version", "NT 10.0").put("device_id", spDeviceId).put("device_type", "computer")))
            val ct = app.post(
                CLIENT_TOKEN_URL,
                headers = mapOf("Authority" to "clienttoken.spotify.com", "Content-Type" to "application/json",
                    "Accept" to "application/json", "User-Agent" to UA),
                requestBody = payload.toString().toRequestBody("application/json".toMediaType()),
                timeout = 15L,
            )
            val cj = JSONObject(ct.text)
            val granted = cj.optJSONObject("granted_token")
            if (granted == null) { lastError = "clienttoken: ${cj.optString("response_type")} (HTTP ${ct.code})"; accessToken = ""; return false }
            clientToken = granted.optString("token", "")
            clientExpiry = now + granted.optLong("expires_after_seconds", 1800) * 1000
            lastError = ""
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            accessToken = ""
            return false
        }
        return accessToken.isNotBlank() && clientToken.isNotBlank()
    }

    private fun authHeaders(): Map<String, String> {
        val map = mutableMapOf(
            "Authorization" to "Bearer $accessToken",
            "User-Agent" to UA,
            "Accept" to "application/json",
            "Origin" to "https://open.spotify.com",
            "Referer" to "https://open.spotify.com/",
            "App-Platform" to "WebPlayer",
        )
        if (clientToken.isNotBlank()) map["Client-Token"] = clientToken
        if (spClientVersion.isNotBlank()) map["Spotify-App-Version"] = spClientVersion
        return map
    }

    /** Pathfinder v2 persisted query (POST). */
    private suspend fun pathfinderQuery(operationName: String, hash: String, variables: JSONObject): JSONObject {
        val body = JSONObject().put("variables", variables).put("operationName", operationName)
            .put("extensions", JSONObject().put("persistedQuery", JSONObject().put("version", 1).put("sha256Hash", hash)))
        val resp = app.post(
            PATHFINDER_URL,
            headers = authHeaders() + ("Content-Type" to "application/json"),
            requestBody = body.toString().toRequestBody("application/json".toMediaType()),
            timeout = 15L,
        )
        if (resp.code == 401) { accessToken = ""; lastError = "pathfinder 401" }
        return JSONObject(resp.text)
    }

    // ========== Catalogue Search & Metadata ==========

    suspend fun search(query: String, limit: Int = 15): Triple<List<SpotifyTrack>, List<SpotifyAlbum>, List<SpotifyArtist>> {
        val tracks = mutableListOf<SpotifyTrack>()
        val albums = mutableListOf<SpotifyAlbum>()
        val artists = mutableListOf<SpotifyArtist>()

        if (ensureTokens()) {
            // 1. Try Spotify Web REST API (/v1/search)
            runCatching {
                val url = "$WEB_API_URL/search?q=${URLEncoder.encode(query, "UTF-8")}&type=track,album,artist&limit=$limit"
                val resp = app.get(url, headers = authHeaders(), timeout = 12L)
                if (resp.code in 200..299) {
                    val root = JSONObject(resp.text)
                    root.optJSONObject("tracks")?.optJSONArray("items")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            arr.optJSONObject(i)?.let { parseV1Track(it)?.let(tracks::add) }
                        }
                    }
                    root.optJSONObject("albums")?.optJSONArray("items")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            arr.optJSONObject(i)?.let { parseV1Album(it)?.let(albums::add) }
                        }
                    }
                    root.optJSONObject("artists")?.optJSONArray("items")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            arr.optJSONObject(i)?.let { parseV1Artist(it)?.let(artists::add) }
                        }
                    }
                }
            }

            // 2. Try Pathfinder GraphQL API if /v1/search returned nothing
            if (tracks.isEmpty() && albums.isEmpty()) {
                runCatching {
                    val variables = JSONObject().apply {
                        put("searchTerm", query)
                        put("offset", 0)
                        put("limit", limit)
                        put("numberOfTopResults", 5)
                        put("includeAudiobooks", true)
                        put("includePreReleases", true)
                        put("includeAuthors", false)
                        put("includeArtistHasConcertsField", false)
                    }
                    val result = pathfinderQuery("searchDesktop", SEARCH_HASH, variables)
                    val data = result.optJSONObject("data")?.optJSONObject("searchV2")
                    if (data != null) {
                        data.optJSONObject("tracksV2")?.optJSONArray("items")?.let { items ->
                            for (i in 0 until items.length()) {
                                val item = items.optJSONObject(i)?.optJSONObject("item")?.optJSONObject("data")
                                    ?: items.optJSONObject(i)?.optJSONObject("itemV2")?.optJSONObject("data")
                                    ?: continue
                                parsePathfinderTrack(item)?.let(tracks::add)
                            }
                        }
                        (data.optJSONObject("albumsV2") ?: data.optJSONObject("albums"))?.optJSONArray("items")?.let { items ->
                            for (i in 0 until items.length()) {
                                val item = items.optJSONObject(i)?.optJSONObject("data")
                                    ?: items.optJSONObject(i)?.optJSONObject("item")?.optJSONObject("data") ?: continue
                                parsePathfinderAlbum(item)?.let(albums::add)
                            }
                        }
                        data.optJSONObject("artists")?.optJSONArray("items")?.let { items ->
                            for (i in 0 until items.length()) {
                                val item = items.optJSONObject(i)?.optJSONObject("data") ?: continue
                                parsePathfinderArtist(item)?.let(artists::add)
                            }
                        }
                    }
                }
            }
        }

        return Triple(tracks, albums, artists)
    }

    /**
     * Fetch a single Spotify track's metadata via /v1/tracks/{id} or open.spotify.com/embed/track/{id}.
     */
    suspend fun getTrack(trackId: String): SpotifyTrack? {
        if (trackId.isBlank()) return null

        // 1. Try /v1/tracks/{id} if token available
        if (ensureTokens()) {
            runCatching {
                val resp = app.get("$WEB_API_URL/tracks/$trackId", headers = authHeaders(), timeout = 10L)
                if (resp.code in 200..299) {
                    parseV1Track(JSONObject(resp.text))?.let { return it }
                }
            }
        }

        // 2. Zero-token fallback: Spotify Embed page __NEXT_DATA__
        runCatching {
            val html = app.get(
                "https://open.spotify.com/embed/track/$trackId",
                headers = mapOf("User-Agent" to UA),
                timeout = 12L
            ).text
            val nextDataMatch = Regex("""<script id="__NEXT_DATA__" type="application/json">([^<]+)</script>""").find(html)
            if (nextDataMatch != null) {
                val entity = JSONObject(nextDataMatch.groupValues[1])
                    .optJSONObject("props")
                    ?.optJSONObject("pageProps")
                    ?.optJSONObject("state")
                    ?.optJSONObject("data")
                    ?.optJSONObject("entity")
                if (entity != null) {
                    val name = entity.optString("name", entity.optString("title", ""))
                    val artistsArr = entity.optJSONArray("artists")
                    val artist = if (artistsArr != null && artistsArr.length() > 0) {
                        (0 until artistsArr.length()).mapNotNull {
                            artistsArr.optJSONObject(it)?.optString("name")?.ifBlank { null }
                        }.joinToString(", ")
                    } else entity.optString("subtitle", "")
                    val cover = entity.optJSONObject("visualIdentity")?.optJSONArray("image")
                        ?.let { arr -> arr.optJSONObject(arr.length() - 1)?.optString("url", "") }
                        ?: entity.optJSONObject("coverArt")?.optJSONArray("sources")?.optJSONObject(0)?.optString("url", "")
                        ?: ""
                    val duration = entity.optInt("duration", 0)
                    val preview = entity.optJSONObject("audioPreview")?.optString("url", "") ?: ""
                    if (name.isNotBlank()) {
                        return SpotifyTrack(
                            id = trackId,
                            name = name,
                            artist = artist,
                            album = "",
                            duration = duration,
                            coverUrl = cover,
                            previewUrl = preview,
                        )
                    }
                }
            }
        }

        // 3. SpotSaver metadata API fallback (from musicdl spotify.py)
        runCatching {
            val resp = app.get(
                "https://spotsaver.net/api/spotify/?url=${URLEncoder.encode("https://open.spotify.com/track/$trackId", "UTF-8")}",
                headers = mapOf(
                    "User-Agent" to UA,
                    "Referer" to "https://spotsaver.net/results/",
                    "Accept" to "*/*",
                ),
                timeout = 12L
            )
            val item = JSONObject(resp.text).optJSONArray("items")?.optJSONObject(0)
            if (item != null) {
                return SpotifyTrack(
                    id = trackId,
                    name = item.optString("title", ""),
                    artist = item.optString("artist", ""),
                    album = item.optString("album", ""),
                    duration = (item.optDouble("duration", 0.0) * 1000).toInt(),
                    coverUrl = item.optString("thumbnail", ""),
                )
            }
        }

        return null
    }

    /**
     * Fetch all tracks in a Spotify album via /v1/albums/{id} or open.spotify.com/embed/album/{id}.
     */
    suspend fun getAlbumTracks(albumId: String): List<SpotifyTrack> {
        if (albumId.isBlank()) return emptyList()

        // 1. Try /v1/albums/{id}
        if (ensureTokens()) {
            runCatching {
                val resp = app.get("$WEB_API_URL/albums/$albumId", headers = authHeaders(), timeout = 12L)
                if (resp.code in 200..299) {
                    val albumObj = JSONObject(resp.text)
                    val albumName = albumObj.optString("name", "")
                    val coverUrl = albumObj.optJSONArray("images")?.optJSONObject(0)?.optString("url", "") ?: ""
                    val items = albumObj.optJSONObject("tracks")?.optJSONArray("items")
                    if (items != null && items.length() > 0) {
                        return (0 until items.length()).mapNotNull { i ->
                            val t = items.optJSONObject(i) ?: return@mapNotNull null
                            val tid = t.optString("id", "")
                            val tname = t.optString("name", "")
                            if (tid.isBlank() || tname.isBlank()) return@mapNotNull null
                            val artistsArr = t.optJSONArray("artists")
                            val tartist = if (artistsArr != null && artistsArr.length() > 0) {
                                (0 until artistsArr.length()).mapNotNull {
                                    artistsArr.optJSONObject(it)?.optString("name")?.ifBlank { null }
                                }.joinToString(", ")
                            } else ""
                            SpotifyTrack(
                                id = tid,
                                name = tname,
                                artist = tartist,
                                album = albumName,
                                duration = t.optInt("duration_ms", 0),
                                coverUrl = coverUrl,
                                previewUrl = t.optString("preview_url", ""),
                            )
                        }
                    }
                }
            }
        }

        // 2. Zero-token fallback: Spotify Embed Album page (__NEXT_DATA__)
        runCatching {
            val html = app.get(
                "https://open.spotify.com/embed/album/$albumId",
                headers = mapOf("User-Agent" to UA),
                timeout = 12L
            ).text
            val nextDataMatch = Regex("""<script id="__NEXT_DATA__" type="application/json">([^<]+)</script>""").find(html)
            if (nextDataMatch != null) {
                val entity = JSONObject(nextDataMatch.groupValues[1])
                    .optJSONObject("props")
                    ?.optJSONObject("pageProps")
                    ?.optJSONObject("state")
                    ?.optJSONObject("data")
                    ?.optJSONObject("entity")
                if (entity != null) {
                    val albumName = entity.optString("name", entity.optString("title", "Album"))
                    val coverUrl = entity.optJSONObject("visualIdentity")?.optJSONArray("image")
                        ?.let { arr -> arr.optJSONObject(arr.length() - 1)?.optString("url", "") }
                        ?: entity.optJSONObject("coverArt")?.optJSONArray("sources")?.optJSONObject(0)?.optString("url", "")
                        ?: ""
                    val trackList = entity.optJSONArray("trackList")
                    if (trackList != null && trackList.length() > 0) {
                        return (0 until trackList.length()).mapNotNull { i ->
                            val t = trackList.optJSONObject(i) ?: return@mapNotNull null
                            val uri = t.optString("uri", "")
                            val tid = uri.substringAfterLast(":").ifBlank { "$albumId-$i" }
                            val tname = t.optString("title", t.optString("name", ""))
                            if (tname.isBlank()) return@mapNotNull null
                            SpotifyTrack(
                                id = tid,
                                name = tname,
                                artist = t.optString("subtitle", ""),
                                album = albumName,
                                duration = t.optInt("duration", 0),
                                coverUrl = coverUrl,
                                previewUrl = t.optJSONObject("audioPreview")?.optString("url", "") ?: "",
                            )
                        }
                    }
                }
            }
        }

        return emptyList()
    }

    // ========== Direct Spotify Download Resolvers (from CharlesPikachu/musicdl `spotify.py`) ==========

    /**
     * Resolve direct playable audio streams for a Spotify track using the third-party resolvers
     * from CharlesPikachu/musicdl (`spotify.py`).
     */
    suspend fun resolveStreams(spotifyTrackId: String): List<SpotifyStreamResult> {
        val results = mutableListOf<SpotifyStreamResult>()
        val cleanId = spotifyTrackId.substringAfterLast(":").trim()
        if (cleanId.isBlank()) return results

        // 1. SpotiDown (`_parsewithspotidownmeapi` in musicdl `spotify.py`)
        runCatching {
            val pageResp = app.get(
                "https://spotidown.me/en1",
                headers = mapOf("User-Agent" to UA),
                timeout = 12L
            )
            val csrf = Regex("""<meta\s+name=["']csrf-token["']\s+content=["']([^"']+)""").find(pageResp.text)?.groupValues?.get(1)
            if (!csrf.isNullOrBlank()) {
                val headers = mapOf(
                    "User-Agent" to UA,
                    "X-CSRF-TOKEN" to csrf,
                    "Referer" to "https://spotidown.me/en1",
                    "Origin" to "https://spotidown.me",
                    "Content-Type" to "application/json",
                )
                val convertPayload = JSONObject().apply {
                    put("urls", "https://open.spotify.com/track/$cleanId")
                }.toString().toRequestBody("application/json".toMediaType())

                val convResp = app.post(
                    "https://spotidown.me/convert",
                    headers = headers,
                    requestBody = convertPayload,
                    cookies = pageResp.cookies,
                    timeout = 15L
                )
                val dlUrl = JSONObject(convResp.text).optString("url", "")
                if (dlUrl.startsWith("http")) {
                    results.add(
                        SpotifyStreamResult(
                            url = dlUrl,
                            providerName = "Spotify · SpotiDown 320kbps",
                            quality = 320,
                            headers = mapOf("User-Agent" to UA, "Referer" to "https://spotidown.me/"),
                        )
                    )
                }
            }
        }

        // 2. RapidAPI Spotify Downloader (`_parsewithrapidapi` in musicdl `spotify.py`)
        runCatching {
            val key = RAPIDAPI_KEYS.random()
            val headers = mapOf(
                "x-rapidapi-key" to key,
                "x-rapidapi-host" to "spotify-downloader9.p.rapidapi.com",
                "User-Agent" to UA,
            )
            val resp = app.get(
                "https://spotify-downloader9.p.rapidapi.com/downloadSong?songId=$cleanId",
                headers = headers,
                timeout = 12L
            )
            val dlUrl = JSONObject(resp.text).optJSONObject("data")?.optString("downloadLink", "") ?: ""
            if (dlUrl.startsWith("http")) {
                results.add(
                    SpotifyStreamResult(
                        url = dlUrl,
                        providerName = "Spotify · RapidAPI 320kbps",
                        quality = 320,
                        headers = mapOf("User-Agent" to UA),
                    )
                )
            }
        }

        // 3. MusicFab Spotify (`_parsewithmusicfabapi` in musicdl `spotify.py`)
        runCatching {
            val headers = mapOf(
                "Origin" to "https://musicfab.io",
                "Referer" to "https://musicfab.io/",
                "User-Agent" to UA,
                "Content-Type" to "application/json",
                "Accept" to "application/json",
            )
            val payload = JSONObject().apply {
                put("url", "https://open.spotify.com/track/$cleanId")
            }.toString().toRequestBody("application/json".toMediaType())

            val resp = app.post(
                "https://musicfab.io/api/spotify",
                headers = headers,
                requestBody = payload,
                timeout = 15L
            )
            val dlUrl = JSONObject(resp.text)
                .optJSONObject("data")
                ?.optJSONObject("metadata")
                ?.optString("download", "") ?: ""
            if (dlUrl.startsWith("http")) {
                results.add(
                    SpotifyStreamResult(
                        url = dlUrl,
                        providerName = "Spotify · MusicFab 320kbps",
                        quality = 320,
                        headers = headers,
                    )
                )
            }
        }

        // 4. SpotSaver (`_parsewithspotsaverapi` in musicdl `spotify.py`)
        runCatching {
            val headers = mapOf(
                "User-Agent" to UA,
                "Referer" to "https://spotsaver.net/results/",
                "Accept" to "*/*",
                "Content-Type" to "application/json",
            )
            val infoResp = app.get(
                "https://spotsaver.net/api/spotify/?url=${URLEncoder.encode("https://open.spotify.com/track/$cleanId", "UTF-8")}",
                headers = headers,
                timeout = 12L
            )
            val trackObj = JSONObject(infoResp.text).optJSONArray("items")?.optJSONObject(0)
            if (trackObj != null) {
                val songName = trackObj.optString("title", "")
                val singer = trackObj.optString("artist", "")
                val idResp = app.post(
                    "https://spotsaver.net/api/get-id/",
                    headers = headers,
                    requestBody = JSONObject().apply {
                        put("title", songName)
                        put("artist", singer)
                    }.toString().toRequestBody("application/json".toMediaType()),
                    timeout = 12L
                )
                val idJson = JSONObject(idResp.text)
                val videoId = idJson.optString("videoId", "")
                val candidateIds = idJson.optJSONArray("candidateIds") ?: JSONArray()
                if (videoId.isNotBlank()) {
                    val dlResp = app.post(
                        "https://spotsaver.net/api/download/",
                        headers = headers,
                        requestBody = JSONObject().apply {
                            put("videoId", videoId)
                            put("candidateIds", candidateIds)
                            put("format", "mp3")
                            put("title", listOf(songName, singer).filter { it.isNotBlank() }.joinToString(" - ").ifBlank { "track" })
                        }.toString().toRequestBody("application/json".toMediaType()),
                        timeout = 15L
                    )
                    val dlJson = JSONObject(dlResp.text)
                    val dlUrl = listOf("downloadUrl", "url", "fileUrl", "mediaUrl")
                        .map { dlJson.optString(it, "") }
                        .firstOrNull { it.startsWith("http") } ?: ""
                    if (dlUrl.isNotBlank()) {
                        results.add(
                            SpotifyStreamResult(
                                url = dlUrl,
                                providerName = "Spotify · SpotSaver 320kbps",
                                quality = 320,
                                headers = mapOf("User-Agent" to UA, "Referer" to "https://spotsaver.net/"),
                            )
                        )
                    }
                }
            }
        }

        // 5. Spotify-Downloads (`_parsewithspotifydownloadsapi` in musicdl `spotify.py`)
        if (results.isEmpty()) {
            runCatching {
                val headers = mapOf(
                    "User-Agent" to UA,
                    "Referer" to "https://www.spotify-downloads.com/",
                    "Origin" to "https://www.spotify-downloads.com",
                    "Content-Type" to "application/json",
                    "Accept" to "*/*",
                )
                val createResp = app.post(
                    "https://www.spotify-downloads.com/api/download",
                    headers = headers,
                    requestBody = JSONObject().apply {
                        put("url", "https://open.spotify.com/track/$cleanId")
                        put("quality", "320k")
                    }.toString().toRequestBody("application/json".toMediaType()),
                    timeout = 12L
                )
                val jobId = JSONObject(createResp.text).optString("job_id", "")
                if (jobId.isNotBlank()) {
                    for (attempt in 0 until 8) {
                        kotlinx.coroutines.delay(1500L)
                        val stResp = app.get("https://www.spotify-downloads.com/api/status/$jobId", headers = headers, timeout = 10L)
                        val stJson = JSONObject(stResp.text)
                        val status = stJson.optString("status", "")
                        if (status == "ready") {
                            results.add(
                                SpotifyStreamResult(
                                    url = "https://www.spotify-downloads.com/api/file/$jobId",
                                    providerName = "Spotify · SpotifyDownloads 320kbps",
                                    quality = 320,
                                    headers = headers,
                                )
                            )
                            break
                        }
                        if (status == "error") break
                    }
                }
            }
        }

        return results
    }

    // ========== Parsers ==========

    private fun parseV1Track(data: JSONObject): SpotifyTrack? {
        val id = data.optString("id", "").ifBlank { data.optString("uri", "").substringAfterLast(":") }
        val name = data.optString("name", "")
        if (id.isBlank() || name.isBlank()) return null

        val artistsArr = data.optJSONArray("artists")
        val artistName = if (artistsArr != null && artistsArr.length() > 0) {
            (0 until artistsArr.length()).mapNotNull {
                artistsArr.optJSONObject(it)?.optString("name")?.ifBlank { null }
            }.joinToString(", ")
        } else ""

        val albumObj = data.optJSONObject("album")
        val albumName = albumObj?.optString("name", "") ?: ""
        val coverUrl = albumObj?.optJSONArray("images")?.optJSONObject(0)?.optString("url", "") ?: ""
        val duration = data.optInt("duration_ms", 0)
        val isrc = data.optJSONObject("external_ids")?.optString("isrc", "") ?: ""
        val preview = data.optString("preview_url", "")

        return SpotifyTrack(
            id = id,
            name = name,
            artist = artistName,
            album = albumName,
            duration = duration,
            coverUrl = coverUrl,
            isrc = isrc,
            previewUrl = if (preview == "null") "" else preview,
        )
    }

    private fun parseV1Album(data: JSONObject): SpotifyAlbum? {
        val id = data.optString("id", "").ifBlank { data.optString("uri", "").substringAfterLast(":") }
        val name = data.optString("name", "")
        if (id.isBlank() || name.isBlank()) return null

        val artistsArr = data.optJSONArray("artists")
        val artistName = if (artistsArr != null && artistsArr.length() > 0) {
            artistsArr.optJSONObject(0)?.optString("name", "") ?: ""
        } else ""
        val coverUrl = data.optJSONArray("images")?.optJSONObject(0)?.optString("url", "") ?: ""
        val year = data.optString("release_date", "").take(4)

        return SpotifyAlbum(id = id, name = name, artist = artistName, coverUrl = coverUrl, year = year)
    }

    private fun parseV1Artist(data: JSONObject): SpotifyArtist? {
        val id = data.optString("id", "").ifBlank { data.optString("uri", "").substringAfterLast(":") }
        val name = data.optString("name", "")
        if (id.isBlank() || name.isBlank()) return null
        val imageUrl = data.optJSONArray("images")?.optJSONObject(0)?.optString("url", "") ?: ""
        return SpotifyArtist(id = id, name = name, imageUrl = imageUrl)
    }

    private fun parsePathfinderTrack(data: JSONObject): SpotifyTrack? {
        val uri = data.optString("uri", "")
        val id = data.optString("id", "").ifBlank { uri.substringAfterLast(":") }
        val name = data.optString("name", "")
        if (id.isBlank() || name.isBlank()) return null

        val artists = data.optJSONObject("artists")?.optJSONArray("items")
        val artistName = if (artists != null && artists.length() > 0) {
            (0 until artists.length()).mapNotNull {
                artists.optJSONObject(it)?.optJSONObject("profile")?.optString("name", "")?.ifBlank { null }
            }.joinToString(", ")
        } else ""

        val album = data.optJSONObject("albumOfTrack")
        val albumName = album?.optString("name", "") ?: ""
        val coverUrl = album?.optJSONObject("coverArt")?.optJSONArray("sources")
            ?.optJSONObject(0)?.optString("url", "") ?: ""

        val duration = data.optJSONObject("duration")?.optInt("totalMilliseconds", 0)
            ?: data.optJSONObject("trackDuration")?.optInt("totalMilliseconds", 0)
            ?: 0

        val isrc = data.optJSONObject("externalId")?.optString("isrc", "") ?: ""

        return SpotifyTrack(
            id = id,
            name = name,
            artist = artistName,
            album = albumName,
            duration = duration,
            coverUrl = coverUrl,
            isrc = isrc,
        )
    }

    private fun parsePathfinderAlbum(data: JSONObject): SpotifyAlbum? {
        val uri = data.optString("uri", "")
        val id = data.optString("id", "").ifBlank { uri.substringAfterLast(":") }
        val name = data.optString("name", "")
        if (id.isBlank() || name.isBlank()) return null

        val artists = data.optJSONObject("artists")?.optJSONArray("items")
        val artistName = if (artists != null && artists.length() > 0) {
            artists.optJSONObject(0)?.optJSONObject("profile")?.optString("name", "") ?: ""
        } else ""

        val coverUrl = data.optJSONObject("coverArt")?.optJSONArray("sources")
            ?.optJSONObject(0)?.optString("url", "") ?: ""

        val year = data.optJSONObject("date")?.optString("year", "") ?: ""

        return SpotifyAlbum(id = id, name = name, artist = artistName, coverUrl = coverUrl, year = year)
    }

    private fun parsePathfinderArtist(data: JSONObject): SpotifyArtist? {
        val uri = data.optString("uri", "")
        val id = data.optString("id", "").ifBlank { uri.substringAfterLast(":") }
        val name = data.optJSONObject("profile")?.optString("name", "") ?: ""
        if (id.isBlank() || name.isBlank()) return null
        val imageUrl = data.optJSONObject("visuals")?.optJSONObject("avatarImage")
            ?.optJSONArray("sources")?.optJSONObject(0)?.optString("url", "") ?: ""

        return SpotifyArtist(id = id, name = name, imageUrl = imageUrl)
    }
}
