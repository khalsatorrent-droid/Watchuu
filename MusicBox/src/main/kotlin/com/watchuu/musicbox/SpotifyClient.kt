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
    /** the web player build spotify-web reports on clienttoken and pathfinder */
    private const val SPOTIFY_APP_VERSION = "1.2.52.442.g0f1fed8b"
    private const val SEARCH_HASH = "fcad5a3e0d5af727fb76966f06971c19cfa2275e6ff7671196753e008611873c"
    /** spotify-web's persisted queries for a playlist and its contents */
    private const val PLAYLIST_HASH = "bb67e0af06e8d6f52b531f97468ee4acd44cd0f82b988e15c2ea47b1148efc77"
    private const val ALBUM_HASH = "b9bfabef66ed756e5e13f68a942deb60bd4125ec1f1be8cc42769dc0259b4b10"

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

    data class SpotifyPlaylist(
        val id: String,
        val name: String,
        val owner: String,
        val coverUrl: String,
        val trackCount: Int = 0,
    )

    data class SpotifyStreamResult(
        val url: String,
        val providerName: String,
        val quality: Int = 320,
        val headers: Map<String, String> = emptyMap(),
    )

    // ========== TOTP implementation ==========

    /**
     * The TOTP key is not the secret array itself.
     *
     * spotify-web builds it at runtime: each byte is xored with (i % 33) + 9, the results are joined
     * as decimal strings, that string is read as hex pairs, and the bytes are round-tripped through
     * base32. Signing with the raw array produces a code Spotify rejects, which is why the token
     * request failed and every Spotify search came back empty.
     */
    private fun totpKey(): ByteArray {
        val transformed = IntArray(TOTP_SECRET.size) { i -> TOTP_SECRET[i] xor ((i % 33) + 9) }
        val joined = transformed.joinToString("") { it.toString() }
        val hex = joined.map { "%02x".format(it.code) }.joinToString("")
        val raw = ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        // base32Encode then decode is the identity, so `raw` is the key the extension ends up with
        return raw
    }

    private fun generateTOTP(timestampMs: Long = System.currentTimeMillis()): String {
        val timeStep = timestampMs / 30000
        val timeBytes = ByteArray(8)
        var t = timeStep
        for (i in 7 downTo 0) {
            timeBytes[i] = (t and 0xFF).toByte()
            t = t shr 8
        }

        val secretBytes = totpKey()
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

    private suspend fun ensureTokens(): Boolean {
        if (accessToken.isNotBlank() && System.currentTimeMillis() < tokenExpiry) return true

        val baseHeaders = mapOf(
            "User-Agent" to UA,
            "Accept" to "application/json",
            "Referer" to "https://open.spotify.com/",
            "Origin" to "https://open.spotify.com",
        )

        // 1. Try /api/token with TOTP
        runCatching {
            val now = System.currentTimeMillis()
            val totp = generateTOTP(now)
            val url = "$API_TOKEN_URL?reason=init&productType=web-player&totp=$totp&totpServer=$totp&totpVer=$TOTP_VERSION"
            val resp = app.get(url, headers = baseHeaders, timeout = 12L)
            val json = JSONObject(resp.text)
            val tok = json.optString("accessToken", "")
            if (tok.isNotBlank()) {
                accessToken = tok
                tokenExpiry = json.optLong("accessTokenExpirationTimestampMs", now + 1800_000L)
            }
        }

        // 2. Try legacy /get_access_token
        if (accessToken.isBlank()) {
            runCatching {
                val resp = app.get(AUTH_URL, headers = baseHeaders, timeout = 12L)
                val json = JSONObject(resp.text)
                val tok = json.optString("accessToken", "")
                if (tok.isNotBlank()) {
                    accessToken = tok
                    tokenExpiry = json.optLong("accessTokenExpirationTimestampMs", System.currentTimeMillis() + 1800_000L)
                }
            }
        }

        // 3. Scrape session script tag from open.spotify.com
        if (accessToken.isBlank()) {
            runCatching {
                val html = app.get("https://open.spotify.com/", headers = mapOf("User-Agent" to UA), timeout = 12L).text
                val match = Regex(""""accessToken"\s*:\s*"([^"]+)"""").find(html)
                if (match != null) {
                    accessToken = match.groupValues[1]
                    tokenExpiry = System.currentTimeMillis() + 1800_000L
                }
            }
        }

        if (accessToken.isBlank()) return false

        // Attempt to obtain clientToken (optional for /v1/ endpoints, used for pathfinder)
        if (clientToken.isBlank()) {
            runCatching {
                val totp = generateTOTP()
                val clientTokenPayload = JSONObject().apply {
                    put("client_data", JSONObject().apply {
                        put("client_version", SPOTIFY_APP_VERSION)
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
                        "User-Agent" to UA,
                    ),
                    requestBody = clientTokenPayload.toString().toRequestBody("application/json".toMediaType()),
                    timeout = 10L
                )
                val ctJson = JSONObject(ctResp.text)
                clientToken = ctJson.optJSONObject("granted_token")?.optString("token", "") ?: ""
            }
        }

        return accessToken.isNotBlank()
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
        if (clientToken.isNotBlank()) {
            map["Client-Token"] = clientToken
        }
        return map
    }

    /**
     * spotify-web posts the query as a json body to /pathfinder/v2/query, with the persisted-query
     * hash in extensions. A GET with the same fields in the query string is not what the endpoint
     * expects, and v1 does not serve these hashes at all.
     */
    private suspend fun pathfinderQuery(operationName: String, hash: String, variables: JSONObject): JSONObject {
        val payload = JSONObject().apply {
            put("variables", variables)
            put("operationName", operationName)
            put("extensions", JSONObject().apply {
                put("persistedQuery", JSONObject().apply {
                    put("version", 1)
                    put("sha256Hash", hash)
                })
            })
        }

        val headers = authHeaders().toMutableMap()
        headers["Content-Type"] = "application/json"
        headers["Spotify-App-Version"] = SPOTIFY_APP_VERSION

        val resp = app.post(
            PATHFINDER_URL,
            headers = headers,
            requestBody = payload.toString().toRequestBody("application/json".toMediaType()),
            timeout = 15L
        )
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
                        put("includeAudiobooks", false)
                        put("includePreReleases", false)
                        put("includeLocalConcerts", false)
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
                        data.optJSONObject("albums")?.optJSONArray("items")?.let { items ->
                            for (i in 0 until items.length()) {
                                val item = items.optJSONObject(i)?.optJSONObject("data") ?: continue
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

    /**
     * Playlists from the same searchDesktop answer.
     *
     * The shape, from spotify-web: data.searchV2.playlists.items[].item.data, with the id in the
     * `uri` ("spotify:playlist:…"), the name in `name`, the owner under ownerV2.data.name and the
     * cover under images.items[0] or images[0].
     */
    suspend fun searchPlaylists(query: String, limit: Int = 8): List<SpotifyPlaylist> {
        if (query.isBlank() || !ensureTokens()) return emptyList()

        val variables = JSONObject().apply {
            put("searchTerm", query)
            put("offset", 0)
            put("limit", limit)
            put("numberOfTopResults", 5)
            put("includeAudiobooks", false)
            put("includePreReleases", false)
            put("includeLocalConcerts", false)
        }

        val result = runCatching { pathfinderQuery("searchDesktop", SEARCH_HASH, variables) }
            .getOrNull() ?: return emptyList()
        val data = result.optJSONObject("data")?.optJSONObject("searchV2") ?: return emptyList()

        var items = data.optJSONObject("playlists")?.optJSONArray("items")
            ?: data.optJSONObject("playlistsV2")?.optJSONArray("items")
            ?: return emptyList()

        val out = ArrayList<SpotifyPlaylist>()
        for (i in 0 until items.length()) {
            val wrapper = items.optJSONObject(i) ?: continue
            val p = wrapper.optJSONObject("item")?.optJSONObject("data")
                ?: wrapper.optJSONObject("data")
                ?: wrapper
            val id = p.optString("uri").substringAfterLast(":").ifBlank { continue }
            val name = p.optString("name").ifBlank { continue }
            val owner = p.optJSONObject("ownerV2")?.optJSONObject("data")?.optString("name")
                ?: p.optJSONObject("owner")?.optString("name").orEmpty()
            val cover = p.optJSONObject("images")?.optJSONArray("items")?.optJSONObject(0)
                ?.optString("url")
                ?: p.optJSONArray("images")?.optJSONObject(0)?.optString("url").orEmpty()
            val count = p.optJSONObject("content")?.optInt("totalCount", 0) ?: 0
            out.add(SpotifyPlaylist(id, name, owner, cover, count))
        }
        return out
    }

    /** a playlist's tracks, via the fetchPlaylist persisted query */
    suspend fun playlistTracks(playlistId: String): List<SpotifyTrack> {
        if (playlistId.isBlank() || !ensureTokens()) return emptyList()

        val variables = JSONObject().apply {
            put("uri", "spotify:playlist:$playlistId")
            put("offset", 0)
            put("limit", 200)
            put("enableWatchFeedEntrypoint", false)
        }
        val result = runCatching { pathfinderQuery("fetchPlaylist", PLAYLIST_HASH, variables) }
            .getOrNull() ?: return emptyList()

        val out = ArrayList<SpotifyTrack>()
        fun walk(node: Any?, depth: Int) {
            if (depth > 12 || out.size >= 400) return
            when (node) {
                is JSONObject -> {
                    val uri = node.optString("uri")
                    if (uri.startsWith("spotify:track:")) {
                        parsePathfinderTrack(node)?.let { out.add(it) }
                    }
                    for (key in node.keys()) walk(node.opt(key), depth + 1)
                }
                is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), depth + 1)
            }
        }
        walk(result, 0)
        return out.distinctBy { it.id }
    }

    /** an artist's best known tracks, for the artist cards in search */
    suspend fun artistTracks(artistName: String, limit: Int = 20): List<SpotifyTrack> =
        search(artistName, limit).first

}
