package com.watchuu.musicbox

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import java.security.MessageDigest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * MusicBox — every source from the SpotiFLAC extension registry, in one extension.
 *
 * The .sflx bundles were unpacked and their request code read, so each provider here follows what
 * the real extension does rather than a guess:
 *
 *   deezer   api.deezer.com — public. Search, albums, artists, and a 30s preview per track.
 *   qobuz    www.qobuz.com/api.json/0.2 — public with an app id. getFileUrl is signed as
 *            md5(object + method + sorted params + timestamp + secret), exactly as the extension's
 *            qobuzSignedURL does, and returns a stream url (a sample without a subscription).
 *   tidal    tidal.com/v1 with the public web token 49YxDN9a2aFV6RTG. Search and track metadata
 *            work; playbackinfopostpaywall answers a manifest, which is a preview unauthenticated.
 *   ytmusic  music.youtube.com/youtubei with the extension's own public key — full audio, and it is
 *            resolved through Cloudstream's YouTube extractor rather than a hand-rolled signature.
 *
 * What is deliberately absent: qobuz-web, tidal-web, deezer and amzn all route their *downloads*
 * through api.zarz.moe with an X-Zarz-Ticket, and Amazon additionally needs a registered device id
 * and session. Those are a paid third-party gate and an account respectively; nothing an extension
 * can honestly reproduce. Every source still appears in search and still plays what it can - the
 * preview from its own service, plus full audio from YouTube.
 *
 * So a search returns four sources' worth of results, and a track plays whichever of them answers.
 */
class MusicBoxProvider : MainAPI() {
    override var mainUrl = "https://api.deezer.com"
    override var name = "MusicBox"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Music)

    // deezer
    private val dz = "https://api.deezer.com"
    // qobuz: app id for catalog reads, and the preview app for the signed stream call
    private val qbAppId = "798273057"
    private val qbPreviewAppId = "712109809"
    private val qbPreviewSecret = "589be88e4538daea11f509d29e4a23b1"
    private val qbApi = "https://www.qobuz.com/api.json/0.2"
    // tidal public web token, from the extension's CONFIG.publicToken
    private val tidalToken = "49YxDN9a2aFV6RTG"
    private val tidalApi = "https://tidal.com/v1"
    // youtube music
    private val ytApiKey = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
    private val ytClientVersion = "1.20260101.01.00"

    override val mainPage = mainPageOf(
        "chart/tracks" to "Top Tracks",
        "chart/albums" to "Top Albums",
        "chart/artists" to "Top Artists"
    )

    private fun ua(): String = UA

    private fun headers(url: String, extra: Map<String, String> = emptyMap()): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["User-Agent"] = ua()
        h["Accept"] = "application/json"
        // whatever clearance the challenge window earned for this host, if any
        MusicBoxBrowser.cookiesFor(url)?.let { h["Cookie"] = it }
        h.putAll(extra)
        return h
    }

    private suspend fun getJson(url: String, extra: Map<String, String> = emptyMap()): JSONObject? =
        runCatching {
            JSONObject(app.get(url, headers = headers(url, extra), timeout = 30L).text)
        }.getOrNull()

    // ── Deezer ─────────────────────────────────────────────────────────────────

    private suspend fun deezer(path: String) = getJson("$dz/$path")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), false)

        val json = deezer(request.data) ?: return newHomePageResponse(request.name, emptyList(), false)
        val data = json.optJSONObject("data") ?: json

        val out = ArrayList<SearchResponse>()
        listOf("tracks", "albums", "artists").forEach { key ->
            data.optJSONArray(key)?.let { out.addAll(deezerCards(it)) }
        }
        return newHomePageResponse(request.name, out, hasNext = false)
    }

    private fun deezerCards(arr: org.json.JSONArray): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").ifBlank { continue }
            val kind = o.optString("type").ifBlank {
                when {
                    o.has("nb_tracks") || o.has("record_type") -> "album"
                    o.has("nb_album") || o.has("radio") -> "artist"
                    else -> "track"
                }
            }
            val title = o.optString("title").ifBlank { o.optString("name") }.ifBlank { continue }
            val artist = o.optJSONObject("artist")?.optString("name").orEmpty()
            val display = if (artist.isNotBlank() && kind == "track") "$artist - $title" else title
            val poster = o.optString("cover_xl").ifBlank {
                o.optString("cover_big").ifBlank {
                    o.optString("cover_medium").ifBlank { o.optString("picture_xl").ifBlank { null } }
                }
            }
            val url = "$dz/$kind/$id"
            out.add(
                if (kind == "track") {
                    newMovieSearchResponse(display, url, TvType.Music) { this.posterUrl = poster }
                } else {
                    newTvSeriesSearchResponse(display, url, TvType.Music) { this.posterUrl = poster }
                }
            )
        }
        return out
    }

    // ── Qobuz ──────────────────────────────────────────────────────────────────

    private fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        val sb = StringBuilder()
        for (b in bytes) sb.append("%02x".format(b))
        return sb.toString()
    }

    /** the extension's qobuzSignedURL: object + method + sorted params + ts + secret, md5'd */
    private fun qobuzSigned(objectName: String, methodName: String, params: Map<String, String>): String {
        val ts = (System.currentTimeMillis() / 1000).toString()
        val keys = params.keys.sorted()
        val raw = StringBuilder(objectName).append(methodName)
        keys.forEach { raw.append(it).append(params[it]) }
        raw.append(ts).append(qbPreviewSecret)

        val query = keys.joinToString("&") {
            "${URLEncoder.encode(it, "UTF-8")}=${URLEncoder.encode(params[it], "UTF-8")}"
        }
        val sig = md5(raw.toString())
        return "$qbApi/$objectName/$methodName?$query&request_ts=$ts&request_sig=$sig"
    }

    private suspend fun qobuzSearch(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val json = getJson("$qbApi/catalog/search?query=$q&limit=20&app_id=$qbAppId") ?: return emptyList()
        val items = json.optJSONObject("tracks")?.optJSONArray("items") ?: return emptyList()

        val out = ArrayList<SearchResponse>()
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val id = o.optString("id").ifBlank { continue }
            val title = o.optString("title").ifBlank { continue }
            val artist = o.optJSONObject("performer")?.optString("name")
                ?: o.optString("performer").ifBlank { null }
            val poster = o.optJSONObject("album")?.optJSONObject("image")?.optString("large")?.ifBlank { null }
            val display = if (!artist.isNullOrBlank()) "$artist - $title" else title

            out.add(
                newMovieSearchResponse(display, "$qbApi/track/$id", TvType.Music) {
                    this.posterUrl = poster
                }
            )
        }
        return out
    }

    private suspend fun qobuzStream(trackId: String): String? {
        val url = qobuzSigned(
            "track", "getFileUrl",
            mapOf("track_id" to trackId, "format_id" to "5", "intent" to "stream")
        )
        val json = getJson(url, mapOf("X-App-Id" to qbPreviewAppId)) ?: return null
        return json.optString("url").ifBlank { null }
    }

    // ── Tidal ──────────────────────────────────────────────────────────────────

    private suspend fun tidalSearch(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val json = getJson(
            "$tidalApi/search/tracks?query=$q&limit=20&countryCode=US",
            mapOf("x-tidal-token" to tidalToken)
        ) ?: return emptyList()

        val items = json.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<SearchResponse>()
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val id = o.optString("id").ifBlank { continue }
            val title = o.optString("title").ifBlank { continue }
            val artist = o.optJSONArray("artists")?.optJSONObject(0)?.optString("name").orEmpty()
            val cover = o.optJSONObject("album")?.optString("cover")?.ifBlank { null }
            val poster = cover?.let { "https://resources.tidal.com/images/${it.replace("-", "/")}/640x640.jpg" }
            val display = if (artist.isNotBlank()) "$artist - $title" else title

            out.add(
                newMovieSearchResponse(display, "$tidalApi/track/$id", TvType.Music) {
                    this.posterUrl = poster
                }
            )
        }
        return out
    }

    private suspend fun tidalStream(trackId: String): String? {
        val json = getJson(
            "$tidalApi/tracks/$trackId/playbackinfopostpaywall" +
                "?audioquality=LOW&playbackmode=STREAM&assetpresentation=FULL&countryCode=US",
            mapOf("x-tidal-token" to tidalToken)
        ) ?: return null

        val manifest = json.optString("manifest").ifBlank { null } ?: return null
        val decoded = runCatching {
            String(android.util.Base64.decode(manifest, android.util.Base64.DEFAULT))
        }.getOrNull() ?: return null

        // the manifest is json with urls; take the first one it offers
        return Regex(""""urls"\s*:\s*\[\s*"([^"]+)"""").find(decoded)?.groupValues?.get(1)
            ?: Regex(""""(https?://[^"]+\.(?:mp3|m4a|flac)[^"]*)"""").find(decoded)?.groupValues?.get(1)
    }

    // soundcloud: the client_id is pulled from its own js bundles, the same way the sflx
    // extension does it, and cached for the session
    private val scApi = "https://api-v2.soundcloud.com"
    @Volatile
    private var scClientId: String? = null

    private suspend fun soundcloudClientId(): String? {
        scClientId?.let { return it }
        val html = runCatching {
            app.get(
                "https://soundcloud.com/",
                headers = mapOf("User-Agent" to ua(), "Accept" to "text/html"),
                timeout = 30L
            ).text
        }.getOrNull() ?: return null

        // the id is often in the page itself, otherwise in one of the asset bundles
        Regex("""client_id\s*[:=]\s*["']([A-Za-z0-9]{32})["']""").find(html)
            ?.groupValues?.get(1)?.let { scClientId = it; return it }

        val bundles = Regex("""src="(https://a-v2\.sndcdn\.com/assets/[^"]+\.js)"""")
            .findAll(html).map { it.groupValues[1] }.take(6).toList()

        for (bundle in bundles) {
            val js = runCatching {
                app.get(bundle, headers = mapOf("User-Agent" to ua()), timeout = 30L).text
            }.getOrNull() ?: continue
            Regex("""client_id\s*[:=]\s*["']([A-Za-z0-9]{32})["']""").find(js)
                ?.groupValues?.get(1)?.let { scClientId = it; return it }
        }
        return null
    }

    private suspend fun soundcloudSearch(query: String): List<SearchResponse> {
        val cid = soundcloudClientId() ?: return emptyList()
        val q = URLEncoder.encode(query, "UTF-8")
        val json = getJson("$scApi/search/tracks?q=$q&client_id=$cid&limit=20") ?: return emptyList()
        val items = json.optJSONArray("collection") ?: return emptyList()

        val out = ArrayList<SearchResponse>()
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val id = o.optString("id").ifBlank { continue }
            val title = o.optString("title").ifBlank { continue }
            val artist = o.optJSONObject("user")?.optString("username").orEmpty()
            val poster = o.optString("artwork_url").ifBlank { null }?.replace("large", "t500x500")
            val display = if (artist.isNotBlank()) "$artist - $title" else title

            out.add(
                newMovieSearchResponse(display, "https://soundcloud.com/track/$id", TvType.Music) {
                    this.posterUrl = poster
                }
            )
        }
        return out
    }

    /** the full stream: a transcoding url, exchanged for the file it points at */
    private suspend fun soundcloudStream(id: String): String? {
        val cid = soundcloudClientId() ?: return null
        val track = getJson("$scApi/tracks/$id?client_id=$cid") ?: return null
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
                        headers = mapOf("User-Agent" to ua(), "Accept" to "application/json"),
                        timeout = 30L
                    ).text
                )
            }.getOrNull() ?: continue

            res.optString("url").ifBlank { null }?.let { return it }
        }
        return null
    }

    // apple music: the itunes catalogue needs no token at all and carries a preview per track
    private suspend fun appleSearch(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val json = getJson("https://itunes.apple.com/search?term=$q&media=music&entity=song&limit=20")
            ?: return emptyList()
        val items = json.optJSONArray("results") ?: return emptyList()

        val out = ArrayList<SearchResponse>()
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val id = o.optString("trackId").ifBlank { continue }
            val title = o.optString("trackName").ifBlank { continue }
            val artist = o.optString("artistName").orEmpty()
            val poster = o.optString("artworkUrl100").ifBlank { null }?.replace("100x100", "600x600")
            val display = if (artist.isNotBlank()) "$artist - $title" else title

            out.add(
                newMovieSearchResponse(display, "https://music.apple.com/song/$id", TvType.Music) {
                    this.posterUrl = poster
                }
            )
        }
        return out
    }

    private suspend fun applePreview(id: String): String? {
        val json = getJson("https://itunes.apple.com/lookup?id=$id&entity=song") ?: return null
        val first = json.optJSONArray("results")?.optJSONObject(0) ?: return null
        return first.optString("previewUrl").ifBlank { null }
    }

    // ── Search: all sources merged ─────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()

        val catalogue = appContext?.let { MusicBoxPrefs.catalogue(it) } ?: MusicBoxPrefs.ALL
        val out = ArrayList<SearchResponse>()
        val wants = { name: String -> catalogue == MusicBoxPrefs.ALL || catalogue == name }

        if (wants("deezer")) {
            runCatching { deezer("search/track?q=${URLEncoder.encode(query, "UTF-8")}&limit=20") }
                .getOrNull()?.optJSONArray("data")?.let { out.addAll(deezerCards(it)) }
        }
        if (wants("qobuz")) runCatching { qobuzSearch(query) }.getOrNull()?.let { out.addAll(it) }
        if (wants("tidal")) runCatching { tidalSearch(query) }.getOrNull()?.let { out.addAll(it) }
        if (wants("soundcloud")) runCatching { soundcloudSearch(query) }.getOrNull()?.let { out.addAll(it) }
        if (wants("apple")) runCatching { appleSearch(query) }.getOrNull()?.let { out.addAll(it) }

        return out.distinctBy { it.url }
    }

    // ── Detail ─────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        return when {
            url.contains("deezer.com") -> deezerLoad(url)
            url.contains("qobuz.com") -> qobuzLoad(url)
            url.contains("tidal.com") -> tidalLoad(url)
            url.contains("soundcloud.com") -> soundcloudLoad(url)
            url.contains("music.apple.com") -> appleLoad(url)
            else -> null
        }
    }

    private suspend fun deezerLoad(url: String): LoadResponse? {
        val m = Regex("""/(track|album|artist)/(\d+)""").find(url) ?: return null
        val kind = m.groupValues[1]
        val id = m.groupValues[2]
        val o = deezer("$kind/$id") ?: return null

        if (kind == "track") {
            val title = o.optString("title").ifBlank { "Track $id" }
            val artist = o.optJSONObject("artist")?.optString("name").orEmpty()
            val poster = o.optJSONObject("album")?.optString("cover_xl")?.ifBlank { null }
            return newMovieLoadResponse(title, url, TvType.Music, "dz|track|$id") {
                this.posterUrl = poster
                this.plot = artist.ifBlank { null }
            }
        }

        val title = o.optString("title").ifBlank { o.optString("name") }.ifBlank { "Deezer $id" }
        val poster = o.optString("cover_xl").ifBlank { o.optString("picture_xl").ifBlank { null } }
        val artist = o.optJSONObject("artist")?.optString("name").orEmpty()

        val tracks = if (kind == "album") {
            deezer("album/$id")?.optJSONArray("tracks")
        } else {
            deezer("artist/$id/top?limit=50")?.optJSONArray("data")
        }

        val episodes = ArrayList<Episode>()
        if (tracks != null) {
            for (i in 0 until tracks.length()) {
                val t = tracks.optJSONObject(i) ?: continue
                val tid = t.optString("id").ifBlank { continue }
                val tname = t.optString("title").ifBlank { "Track ${i + 1}" }
                episodes.add(
                    newEpisode("dz|track|$tid") {
                        this.name = tname
                        this.season = 1
                        this.episode = i + 1
                        this.posterUrl = poster
                    }
                )
            }
        }
        if (episodes.isNotEmpty()) {
            return newTvSeriesLoadResponse(title, url, TvType.Music, episodes) {
                this.posterUrl = poster
                this.plot = artist.ifBlank { null }
            }
        }
        return newMovieLoadResponse(title, url, TvType.Music, "dz|track|$id") {
            this.posterUrl = poster
        }
    }

    private suspend fun qobuzLoad(url: String): LoadResponse? {
        val id = Regex("""/track/(\d+)""").find(url)?.groupValues?.get(1) ?: return null
        val json = getJson("$qbApi/track/get?track_id=$id&app_id=$qbAppId") ?: return null
        val title = json.optString("title").ifBlank { "Qobuz $id" }
        val artist = json.optJSONObject("performer")?.optString("name")
            ?: json.optString("performer").ifBlank { null }
        val poster = json.optJSONObject("album")?.optJSONObject("image")?.optString("large")?.ifBlank { null }

        return newMovieLoadResponse(title, url, TvType.Music, "qb|$id") {
            this.posterUrl = poster
            this.plot = artist
        }
    }

    private suspend fun tidalLoad(url: String): LoadResponse? {
        val id = Regex("""/track/(\d+)""").find(url)?.groupValues?.get(1) ?: return null
        val json = getJson(
            "$tidalApi/tracks/$id?countryCode=US",
            mapOf("x-tidal-token" to tidalToken)
        ) ?: return null
        val title = json.optString("title").ifBlank { "Tidal $id" }
        val artist = json.optJSONArray("artists")?.optJSONObject(0)?.optString("name").orEmpty()
        val cover = json.optJSONObject("album")?.optString("cover")?.ifBlank { null }
        val poster = cover?.let { "https://resources.tidal.com/images/${it.replace("-", "/")}/640x640.jpg" }

        return newMovieLoadResponse(title, url, TvType.Music, "td|$id") {
            this.posterUrl = poster
            this.plot = artist.ifBlank { null }
        }
    }

    private suspend fun soundcloudLoad(url: String): LoadResponse? {
        val id = Regex("""/track/(\d+)""").find(url)?.groupValues?.get(1) ?: return null
        val cid = soundcloudClientId() ?: return null
        val o = getJson("$scApi/tracks/$id?client_id=$cid") ?: return null

        val title = o.optString("title").ifBlank { "SoundCloud $id" }
        val artist = o.optJSONObject("user")?.optString("username").orEmpty()
        val poster = o.optString("artwork_url").ifBlank { null }?.replace("large", "t500x500")

        return newMovieLoadResponse(title, url, TvType.Music, "sc|$id") {
            this.posterUrl = poster
            this.plot = artist.ifBlank { null }
        }
    }

    private suspend fun appleLoad(url: String): LoadResponse? {
        val id = Regex("""/song/(\d+)""").find(url)?.groupValues?.get(1) ?: return null
        val json = getJson("https://itunes.apple.com/lookup?id=$id&entity=song") ?: return null
        val o = json.optJSONArray("results")?.optJSONObject(0) ?: return null

        val title = o.optString("trackName").ifBlank { "Apple $id" }
        val artist = o.optString("artistName").orEmpty()
        val poster = o.optString("artworkUrl100").ifBlank { null }?.replace("100x100", "600x600")

        return newMovieLoadResponse(title, url, TvType.Music, "am|$id") {
            this.posterUrl = poster
            this.plot = artist.ifBlank { null }
        }
    }

    // ── Streams: every source that can answer ──────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split('|')
        val source = parts.getOrNull(0).orEmpty()
        val kind = parts.getOrNull(1).orEmpty()
        val id = parts.getOrNull(2).orEmpty().ifBlank { kind }
        if (id.isBlank()) return false

        var found = false
        var title = ""
        var artist = ""
        var preview: String? = null

        val enabled = appContext?.let { MusicBoxPrefs.enabledSources(it) }
            ?: MusicBoxPrefs.sources.map { it.first }

        // the track's own service is asked first when it is enabled, then everything else in the
        // stored order - so a search result from Deezer still gets YouTube's full audio afterwards
        val order = ArrayList<String>()
        if (enabled.contains(source)) order.add(source)
        enabled.filter { it != source }.forEach { order.add(it) }

        for (src in order) {
            when (src) {
                "deezer" -> if (source == "dz") {
                    val t = deezer("track/$id")
                    title = t?.optString("title").orEmpty()
                    artist = t?.optJSONObject("artist")?.optString("name").orEmpty()
                    preview = t?.optString("preview")?.ifBlank { null }
                    if (preview != null) {
                        emit(callback, "Deezer preview (30s)", preview, "https://www.deezer.com/")
                        found = true
                    }
                }
                "qobuz" -> if (source == "qb") {
                    preview = runCatching { qobuzStream(id) }.getOrNull()
                    if (preview != null) {
                        emit(callback, "Qobuz stream", preview, "https://play.qobuz.com/")
                        found = true
                    }
                }
                "tidal" -> if (source == "td") {
                    preview = runCatching { tidalStream(id) }.getOrNull()
                    if (preview != null) {
                        emit(callback, "Tidal stream", preview, "https://listen.tidal.com/")
                        found = true
                    }
                }
                "soundcloud" -> if (source == "sc") {
                    val full = runCatching { soundcloudStream(id) }.getOrNull()
                    if (full != null) {
                        emit(callback, "SoundCloud", full, "https://soundcloud.com/")
                        found = true
                    }
                }
                "apple" -> if (source == "am") {
                    preview = runCatching { applePreview(id) }.getOrNull()
                    if (preview != null) {
                        emit(callback, "Apple Music preview (30s)", preview, "https://music.apple.com/")
                        found = true
                    }
                }
            }
        }

        // if the entry did not come from one of those, the title still needs filling for YouTube
        if (title.isBlank() && source == "dz") {
            deezer("track/$id")?.let {
                title = it.optString("title")
                artist = it.optJSONObject("artist")?.optString("name").orEmpty()
            }
        }

        // full audio: YouTube Music, resolved by Cloudstream's own extractor
        val query = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" ")
            .ifBlank { title }
        val youtubeEnabled = enabled.contains("youtube")
        val videoId = if (youtubeEnabled && query.isNotBlank()) {
            runCatching { youtubeSearch(query) }.getOrNull()
        } else null
        if (videoId != null) {
            val got = mutableListOf<ExtractorLink>()
            runCatching {
                loadExtractor("https://www.youtube.com/watch?v=$videoId", "https://music.youtube.com/", subtitleCallback) {
                    got.add(it)
                }
            }
            got.forEach { link ->
                callback.invoke(
                    newExtractorLink(
                        source = link.source ?: name,
                        name = "${link.name.ifBlank { name }} · YouTube",
                        url = link.url,
                        type = link.type
                    ) {
                        this.referer = link.referer.ifBlank { "https://www.youtube.com/" }
                        this.quality = link.quality
                        this.headers = link.headers
                    }
                )
                found = true
            }
        }

        return found
    }

    private suspend fun emit(
        callback: (ExtractorLink) -> Unit,
        label: String,
        url: String,
        referer: String
    ) {
        callback.invoke(
            newExtractorLink(
                source = name,
                name = "$name · $label",
                url = url,
                type = if (url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
                this.headers = mapOf("User-Agent" to ua(), "Accept" to "*/*")
            }
        )
    }

    private suspend fun youtubeSearch(query: String): String? {
        if (query.isBlank()) return null
        val body = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", ytClientVersion)
                    put("hl", "en")
                    put("gl", "US")
                })
            })
            put("query", query)
        }

        val res = runCatching {
            app.post(
                "https://music.youtube.com/youtubei/v1/search?key=$ytApiKey&prettyPrint=false",
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "User-Agent" to ua(),
                    "Origin" to "https://music.youtube.com",
                    "Referer" to "https://music.youtube.com/"
                ),
                requestBody = body.toString().toRequestBody("application/json".toMediaType()),
                timeout = 30L
            ).text
        }.getOrNull() ?: return null

        return Regex(""""videoId"\s*:\s*"([A-Za-z0-9_-]{11})"""")
            .find(res)?.groupValues?.get(1)
    }

    companion object {
        /** set by the plugin so the settings dialog's choices can be read */
        @Volatile
        var appContext: android.content.Context? = null

        const val UA = "Mozilla/5.0 (Linux; Android 16; SM-S911B Build/BP4A.251205.006) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.47 Mobile Safari/537.36"
    }
}
