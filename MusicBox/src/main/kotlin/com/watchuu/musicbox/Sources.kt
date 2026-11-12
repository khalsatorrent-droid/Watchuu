package com.watchuu.musicbox

import com.lagradost.cloudstream3.app
import java.net.URLEncoder
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * One track, as a source sees it.
 *
 * [data] is everything playback needs, carried in the entry itself: source, id, and whatever the
 * source already knows (a preview url, a title, an artist). Nothing is re-fetched to start playing,
 * because the fetch is exactly what used to fail and leave the list empty.
 */
data class Track(
    val source: String,
    val id: String,
    val title: String,
    val artist: String,
    val poster: String? = null,
    val preview: String? = null,
    val kind: String = "track"
) {
    /** fields are pipe separated; pipes inside values are stripped by the caller */
    fun encode(): String =
        listOf(source, kind, id, preview.orEmpty(), title, artist).joinToString("|")

    companion object {
        fun decode(raw: String): Track? {
            val p = raw.split('|')
            val source = p.getOrNull(0).orEmpty().ifBlank { return null }
            val kind = p.getOrNull(1).orEmpty()
            val id = p.getOrNull(2).orEmpty().ifBlank { return null }
            return Track(
                source = source,
                id = id,
                title = p.getOrNull(4).orEmpty(),
                artist = p.getOrNull(5).orEmpty(),
                preview = p.getOrNull(3).orEmpty().ifBlank { null },
                kind = kind
            )
        }
    }
}

/** one playable url, with the quality label it should be shown under */
data class Play(val label: String, val url: String, val referer: String, val isHls: Boolean = false)

/**
 * The sources, as the SpotiFLAC extensions implement them.
 *
 * Every method here is taken from the matching .sflx bundle rather than guessed: the endpoint, the
 * parameters and the parsing are theirs. What each one can give is not the same, and that is what
 * the quality list per source reflects.
 */
object Sources {

    private const val UA = "Mozilla/5.0 (Linux; Android 16; SM-S911B Build/BP4A.251205.006) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.47 Mobile Safari/537.36"

    fun userAgent() = UA

    fun headers(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["User-Agent"] = UA
        h["Accept"] = "application/json"
        h.putAll(extra)
        return h
    }

    // ── Deezer ─────────────────────────────────────────────────────────────────
    // api.deezer.com, public. 30s preview on every track; the full file needs the resolver.

    private const val DZ = "https://api.deezer.com"

    suspend fun deezerSearch(query: String): List<Track> {
        val q = URLEncoder.encode(query, "UTF-8")
        return deezerTracks("$DZ/search/track?q=$q&limit=25")
    }

    suspend fun deezerChart(kind: String): List<Track> {
        val json = getJson("$DZ/chart") ?: return emptyList()
        val key = when (kind) {
            "albums" -> "albums"
            "artists" -> "artists"
            else -> "tracks"
        }
        val arr = json.optJSONObject(key)?.optJSONArray("data") ?: return emptyList()
        return deezerArray(arr)
    }

    suspend fun deezerAlbum(id: String): List<Track> {
        val json = getJson("$DZ/album/$id") ?: return emptyList()
        val arr = json.optJSONArray("tracks") ?: return emptyList()
        return deezerArray(arr)
    }

    suspend fun deezerArtist(id: String): List<Track> {
        val json = getJson("$DZ/artist/$id/top?limit=50") ?: return emptyList()
        val arr = json.optJSONArray("data") ?: return emptyList()
        return deezerArray(arr)
    }

    private suspend fun deezerTracks(url: String): List<Track> {
        val json = getJson(url) ?: return emptyList()
        val arr = json.optJSONArray("data") ?: return emptyList()
        return deezerArray(arr)
    }

    private fun deezerArray(arr: JSONArray): List<Track> {
        val out = ArrayList<Track>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").ifBlank { continue }
            val isTrack = o.optString("type").ifBlank {
                if (o.has("nb_tracks")) "album" else if (o.has("nb_album")) "artist" else "track"
            }
            val title = o.optString("title").ifBlank { o.optString("name") }.ifBlank { continue }
            val artist = o.optJSONObject("artist")?.optString("name").orEmpty()
            val poster = o.optString("cover_xl").ifBlank {
                o.optString("cover_big").ifBlank {
                    o.optString("cover_medium").ifBlank { o.optString("picture_xl").ifBlank { null } }
                }
            }
            out.add(
                Track(
                    source = "dz",
                    id = id,
                    title = title,
                    artist = artist,
                    poster = poster,
                    preview = o.optString("preview").ifBlank { null },
                    kind = if (isTrack == "album" || isTrack == "artist") isTrack else "track"
                )
            )
        }
        return out
    }

    suspend fun deezerTrack(id: String): Track? {
        val json = getJson("$DZ/track/$id") ?: return null
        val arr = JSONArray().put(json)
        return deezerArray(arr).firstOrNull()
    }

    suspend fun appleTrack(id: String): Track? {
        val json = getJson("https://itunes.apple.com/lookup?id=$id&entity=song") ?: return null
        return appleArray(json.optJSONArray("results")).firstOrNull()
    }

    suspend fun deezerPreview(id: String): String? {
        val json = getJson("$DZ/track/$id") ?: return null
        return json.optString("preview").ifBlank { null }
    }

    // ── Apple Music ────────────────────────────────────────────────────────────
    // the itunes catalogue: public, no token, and every track carries a preview.

    suspend fun appleSearch(query: String): List<Track> {
        val q = URLEncoder.encode(query, "UTF-8")
        val json = getJson("https://itunes.apple.com/search?term=$q&media=music&entity=song&limit=25")
            ?: return emptyList()
        return appleArray(json.optJSONArray("results"))
    }

    suspend fun appleTop(kind: String): List<Track> {
        val feed = when (kind) {
            "albums" -> "most-played/25/albums.json"
            "tracks" -> "most-played/25/songs.json"
            else -> return emptyList()
        }
        val json = getJson("https://rss.applemarketingtools.com/api/v2/us/music/$feed")
            ?: return emptyList()
        val results = json.optJSONObject("feed")?.optJSONArray("results") ?: return emptyList()

        val out = ArrayList<Track>()
        for (i in 0 until results.length()) {
            val o = results.optJSONObject(i) ?: continue
            val id = o.optString("id").ifBlank { continue }
            val name = o.optString("name").ifBlank { continue }
            out.add(
                Track(
                    source = "am",
                    id = id,
                    title = name,
                    artist = o.optString("artistName"),
                    poster = o.optString("artworkUrl100").ifBlank { null }
                        ?.replace("100x100", "600x600"),
                    kind = "track"
                )
            )
        }
        return out
    }

    private fun appleArray(arr: JSONArray?): List<Track> {
        if (arr == null) return emptyList()
        val out = ArrayList<Track>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("trackId").ifBlank { continue }
            val title = o.optString("trackName").ifBlank { continue }
            out.add(
                Track(
                    source = "am",
                    id = id,
                    title = title,
                    artist = o.optString("artistName"),
                    poster = o.optString("artworkUrl100").ifBlank { null }
                        ?.replace("100x100", "600x600"),
                    preview = o.optString("previewUrl").ifBlank { null }
                )
            )
        }
        return out
    }

    suspend fun applePreview(id: String): String? {
        val json = getJson("https://itunes.apple.com/lookup?id=$id&entity=song") ?: return null
        return json.optJSONArray("results")?.optJSONObject(0)?.optString("previewUrl")?.ifBlank { null }
    }

    // ── SoundCloud ─────────────────────────────────────────────────────────────
    // client_id scraped from its own bundles, as the extension does; the whole track is playable.

    private const val SC = "https://api-v2.soundcloud.com"
    @Volatile private var scClientId: String? = null

    suspend fun soundcloudClientId(): String? {
        scClientId?.let { return it }
        val html = runCatching {
            app.get(
                "https://soundcloud.com/",
                headers = mapOf("User-Agent" to UA, "Accept" to "text/html"),
                timeout = 30L
            ).text
        }.getOrNull() ?: return null

        Regex("""client_id\s*[:=]\s*["']([A-Za-z0-9]{32})["']""").find(html)
            ?.groupValues?.get(1)?.let { scClientId = it; return it }

        val bundles = Regex("""src="(https://a-v2\.sndcdn\.com/assets/[^"]+\.js)"""")
            .findAll(html).map { it.groupValues[1] }.take(6).toList()
        for (b in bundles) {
            val js = runCatching {
                app.get(b, headers = mapOf("User-Agent" to UA), timeout = 30L).text
            }.getOrNull() ?: continue
            Regex("""client_id\s*[:=]\s*["']([A-Za-z0-9]{32})["']""").find(js)
                ?.groupValues?.get(1)?.let { scClientId = it; return it }
        }
        return null
    }

    suspend fun soundcloudSearch(query: String): List<Track> {
        val cid = soundcloudClientId() ?: return emptyList()
        val q = URLEncoder.encode(query, "UTF-8")
        val json = getJson("$SC/search/tracks?q=$q&client_id=$cid&limit=25") ?: return emptyList()
        val arr = json.optJSONArray("collection") ?: return emptyList()
        return soundcloudArray(arr)
    }

    suspend fun soundcloudTop(kind: String): List<Track> {
        if (kind != "tracks") return emptyList()
        val cid = soundcloudClientId() ?: return emptyList()
        val json = getJson(
            "$SC/charts?kind=top&genre=soundcloud:genres:all-music&client_id=$cid&limit=25"
        ) ?: return emptyList()
        val arr = json.optJSONArray("collection") ?: return emptyList()

        val out = ArrayList<Track>()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i)?.optJSONObject("track") ?: continue
            soundcloudArray(JSONArray().put(t)).firstOrNull()?.let { out.add(it) }
        }
        return out
    }

    private fun soundcloudArray(arr: JSONArray): List<Track> {
        val out = ArrayList<Track>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").ifBlank { continue }
            val title = o.optString("title").ifBlank { continue }
            out.add(
                Track(
                    source = "sc",
                    id = id,
                    title = title,
                    artist = o.optJSONObject("user")?.optString("username").orEmpty(),
                    poster = o.optString("artwork_url").ifBlank { null }?.replace("large", "t500x500")
                )
            )
        }
        return out
    }

    /** the whole track: a transcoding url, exchanged for the file it points at */
    suspend fun soundcloudStream(id: String): String? {
        val cid = soundcloudClientId() ?: return null
        val track = getJson("$SC/tracks/$id?client_id=$cid") ?: return null
        val transcodings = track.optJSONObject("media")?.optJSONArray("transcodings") ?: return null

        for (i in 0 until transcodings.length()) {
            val t = transcodings.optJSONObject(i) ?: continue
            if (t.optBoolean("snipped", false)) continue
            val protocol = t.optJSONObject("format")?.optString("protocol").orEmpty()
            if (protocol != "progressive" && protocol != "hls") continue
            val url = t.optString("url").ifBlank { continue }
            val res = runCatching {
                JSONObject(
                    app.get(
                        "$url?client_id=$cid",
                        headers = headers(),
                        timeout = 30L
                    ).text
                )
            }.getOrNull() ?: continue
            res.optString("url").ifBlank { null }?.let { return it }
        }
        return null
    }

    // ── Tidal ──────────────────────────────────────────────────────────────────
    // tidal.com/v1 with the extension's public web token. playbackinfopostpaywall answers a base64
    // manifest; unauthenticated that is a preview, with an account it is the real file.

    private const val TD = "https://tidal.com/v1"
    private const val TD_TOKEN = "49YxDN9a2aFV6RTG"

    private fun tidalHeaders() = headers(mapOf("x-tidal-token" to TD_TOKEN))

    suspend fun tidalSearch(query: String): List<Track> {
        val q = URLEncoder.encode(query, "UTF-8")
        val json = runCatching {
            JSONObject(
                app.get(
                    "$TD/search/tracks?query=$q&limit=25&countryCode=US",
                    headers = tidalHeaders(),
                    timeout = 30L
                ).text
            )
        }.getOrNull() ?: return emptyList()

        val arr = json.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<Track>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").ifBlank { continue }
            val title = o.optString("title").ifBlank { continue }
            val artist = o.optJSONArray("artists")?.optJSONObject(0)?.optString("name").orEmpty()
            val cover = o.optJSONObject("album")?.optString("cover")?.ifBlank { null }
            out.add(
                Track(
                    source = "td",
                    id = id,
                    title = title,
                    artist = artist,
                    poster = cover?.let {
                        "https://resources.tidal.com/images/${it.replace("-", "/")}/640x640.jpg"
                    }
                )
            )
        }
        return out
    }

    /** every quality tidal will answer for this track */
    suspend fun tidalPlays(id: String): List<Play> {
        val out = ArrayList<Play>()
        // LOW/HIGH are the preview-capable ones; LOSSLESS and HI_RES need an account and simply
        // come back empty when they do, which costs one call and no correctness
        for ((quality, label) in listOf(
            "LOW" to "Tidal low",
            "HIGH" to "Tidal high",
            "LOSSLESS" to "Tidal lossless",
            "HI_RES" to "Tidal hi-res"
        )) {
            val json = runCatching {
                JSONObject(
                    app.get(
                        "$TD/tracks/$id/playbackinfopostpaywall?audioquality=$quality" +
                            "&playbackmode=STREAM&assetpresentation=FULL&countryCode=US",
                        headers = tidalHeaders(),
                        timeout = 30L
                    ).text
                )
            }.getOrNull() ?: continue

            val manifest = json.optString("manifest").ifBlank { continue }
            val decoded = runCatching {
                String(android.util.Base64.decode(manifest, android.util.Base64.DEFAULT))
            }.getOrNull() ?: continue

            val url = Regex(""""urls"\s*:\s*\[\s*"([^"]+)"""").find(decoded)?.groupValues?.get(1)
                ?: Regex(""""(https?://[^"]+\.(?:mp3|m4a|flac)[^"]*)"""")
                    .find(decoded)?.groupValues?.get(1)
                ?: continue

            out.add(Play(label, url, "https://listen.tidal.com/", url.contains("m3u8")))
        }
        return out
    }

    // ── Qobuz ──────────────────────────────────────────────────────────────────
    // www.qobuz.com/api.json/0.2 with the extension's preview app, getFileUrl signed exactly as its
    // qobuzSignedURL does it. format ids: 5 mp3 320, 6 flac, 7 flac 24 bit.

    private const val QB_API = "https://www.qobuz.com/api.json/0.2"
    private const val QB_APP = "798273057"
    private const val QB_PREVIEW_APP = "712109809"
    private const val QB_SECRET = "589be88e4538daea11f509d29e4a23b1"

    private fun md5(input: String): String =
        MessageDigest.getInstance("MD5").digest(input.toByteArray()).joinToString("") {
            "%02x".format(it)
        }

    private fun qobuzSigned(objectName: String, methodName: String, params: Map<String, String>): String {
        val ts = (System.currentTimeMillis() / 1000).toString()
        val keys = params.keys.sorted()
        val raw = StringBuilder(objectName).append(methodName)
        keys.forEach { raw.append(it).append(params[it]) }
        raw.append(ts).append(QB_SECRET)

        val query = keys.joinToString("&") {
            "${URLEncoder.encode(it, "UTF-8")}=${URLEncoder.encode(params[it], "UTF-8")}"
        }
        return "$QB_API/$objectName/$methodName?$query&request_ts=$ts&request_sig=${md5(raw.toString())}"
    }

    suspend fun qobuzSearch(query: String): List<Track> {
        val q = URLEncoder.encode(query, "UTF-8")
        val json = getJson("$QB_API/catalog/search?query=$q&limit=25&app_id=$QB_APP") ?: return emptyList()
        val arr = json.optJSONObject("tracks")?.optJSONArray("items") ?: return emptyList()

        val out = ArrayList<Track>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").ifBlank { continue }
            val title = o.optString("title").ifBlank { continue }
            out.add(
                Track(
                    source = "qb",
                    id = id,
                    title = title,
                    artist = o.optJSONObject("performer")?.optString("name")
                        ?: o.optString("performer").ifBlank { "" },
                    poster = o.optJSONObject("album")?.optJSONObject("image")
                        ?.optString("large")?.ifBlank { null }
                )
            )
        }
        return out
    }

    suspend fun qobuzPlays(id: String): List<Play> {
        val out = ArrayList<Play>()
        for ((format, label) in listOf("5" to "Qobuz MP3 320", "6" to "Qobuz FLAC", "7" to "Qobuz FLAC 24bit")) {
            val url = qobuzSigned(
                "track", "getFileUrl",
                mapOf("track_id" to id, "format_id" to format, "intent" to "stream")
            )
            val json = runCatching {
                JSONObject(
                    app.get(
                        url,
                        headers = headers(mapOf("X-App-Id" to QB_PREVIEW_APP)),
                        timeout = 30L
                    ).text
                )
            }.getOrNull() ?: continue

            val file = json.optString("url").ifBlank { continue }
            out.add(Play(label, file, "https://play.qobuz.com/"))
        }
        return out
    }

    // ── YouTube ────────────────────────────────────────────────────────────────
    // music.youtube.com/youtubei with the extension's key; the stream itself is left to
    // Cloudstream's YouTube extractor rather than a hand-rolled signature.

    private const val YT_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
    private const val YT_CLIENT = "1.20260101.01.00"

    suspend fun youtubeVideoId(query: String): String? {
        if (query.isBlank()) return null
        val body = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", YT_CLIENT)
                    put("hl", "en")
                    put("gl", "US")
                })
            })
            put("query", query)
        }
        val res = runCatching {
            app.post(
                "https://music.youtube.com/youtubei/v1/search?key=$YT_KEY&prettyPrint=false",
                headers = headers(
                    mapOf(
                        "Content-Type" to "application/json",
                        "Origin" to "https://music.youtube.com",
                        "Referer" to "https://music.youtube.com/"
                    )
                ),
                requestBody = body.toString()
                    .toRequestBody("application/json".toMediaType()),
                timeout = 30L
            ).text
        }.getOrNull() ?: return null

        return Regex(""""videoId"\s*:\s*"([A-Za-z0-9_-]{11})"""")
            .find(res)?.groupValues?.get(1)
    }

    // ── the resolver the extensions download through ───────────────────────────
    // POST /dl/dzr | /dl/qbz | /dl/tid with X-Zarz-Ticket. The ticket cannot be minted here - the
    // extensions call session.signedFetch and the host app signs with a ZARZ-HMAC-V1 session - so it
    // is supplied in settings, and without it these return nothing and the previews stand.

    suspend fun zarz(kind: String, id: String, trackUrl: String, ticket: String): String? {
        val path = when (kind) {
            "dz" -> "/dl/dzr"
            "qb" -> "/dl/qbz"
            "td" -> "/dl/tid"
            else -> return null
        }
        val body = JSONObject().apply {
            put("id", id)
            put("type", "track")
            put("platform", when (kind) { "dz" -> "deezer"; "qb" -> "qobuz"; else -> "tidal" })
            put("url", trackUrl)
        }
        val res = runCatching {
            app.post(
                "https://api.zarz.moe$path",
                headers = headers(
                    mapOf("Content-Type" to "application/json", "X-Zarz-Ticket" to ticket)
                ),
                requestBody = body.toString().toRequestBody("application/json".toMediaType()),
                timeout = 45L
            ).text
        }.getOrNull() ?: return null

        val json = runCatching { JSONObject(res) }.getOrNull() ?: return null
        if (json.optBoolean("needsVerification", false)) return null
        if (!json.optBoolean("success", false)) return null
        return json.optString("direct_download_url").ifBlank { null }
            ?: json.optString("download_url").ifBlank { null }
    }

    // ── shared ─────────────────────────────────────────────────────────────────

    private suspend fun getJson(url: String): JSONObject? = runCatching {
        JSONObject(app.get(url, headers = headers(), timeout = 30L).text)
    }.getOrNull()
}
