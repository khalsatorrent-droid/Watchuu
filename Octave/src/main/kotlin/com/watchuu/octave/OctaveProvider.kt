package com.watchuu.octave

/**
 * Octave (music.octavestreaming.com) - lossless music for Cloudstream.
 *
 * Everything goes through api.octavestreaming.com behind Cloudflare, so every request carries the
 * cf_clearance cookie the user earned in the solver window (see OctaveCf / OctaveSettings).
 *
 *  - search    : GET /api/search/page?query=&limit=   -> {top, tracks[], albums[], artists[], playlists[]}
 *  - album     : GET /api/album/{id}                  -> {album:{title, artist, cover_*, tracks[]}}
 *  - artist    : GET /api/artist/{id}/radio           -> {tracks[]}    GET /api/artists/releases?ids= -> {releases:[{album}]}
 *  - similar   : GET /api/track/{id}/radio            -> {tracks[]}
 *  - charts    : GET /api/lastfm/global-chart-tracks  -> {tracks[]}
 *                GET /api/apple/charts?sf=us&types=songs -> Apple songs, matched with POST /api/import/resolve
 *  - key       : GET /api/playback-token              -> {token, expiresIn:1800}
 *  - stream    : GET /audio/lossless?track=&t=&a=&d=&x=0&k=<key>  -> the FLAC file (range requests)
 *
 * URLs used inside Cloudstream:
 *   https://music.octavestreaming.com/album/{id}                         (series, tracks = episodes)
 *   https://music.octavestreaming.com/artist/{id}                        (series, radio tracks = episodes)
 *   https://music.octavestreaming.com/track/{id}?t=&a=&d=&al=            (single; title/artist/duration travel with it
 *                                                                          because the stream URL needs them)
 */

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLDecoder
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

class OctaveProvider : MainAPI() {
    override var mainUrl = OctaveCf.SITE
    override var name = "Octave"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Music, TvType.Others)

    private val api = OctaveCf.API

    // ---------------------------------------------------------------- networking

    private suspend fun getJson(path: String): JSONObject {
        val r = OctaveCf.get(if (path.startsWith("http")) path else "$api$path")
        if (OctaveCf.isChallenge(r)) {
            throw ErrorLoadingException("Octave: Cloudflare check needed - open Extensions -> Octave -> settings -> Cloudflare check + login")
        }
        if (r.code !in 200..299) throw ErrorLoadingException("Octave: HTTP ${r.code} for ${path.substringBefore("?").take(50)}")
        return try {
            JSONObject(r.text)
        } catch (_: Throwable) {
            throw ErrorLoadingException("Octave: bad answer from server")
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun dec(s: String) = try { URLDecoder.decode(s, "UTF-8") } catch (_: Throwable) { s }

    // ---------------------------------------------------------------- json helpers

    private fun JSONObject.str(vararg keys: String): String? {
        for (k in keys) {
            val v = optString(k, "")
            if (v.isNotBlank() && v != "null") return v
        }
        return null
    }

    private fun JSONArray?.objects(): List<JSONObject> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optJSONObject(it) }
    }

    private fun artistName(o: JSONObject): String =
        o.optJSONObject("artist")?.str("name") ?: o.str("artist", "artistName") ?: ""

    private fun artistNames(o: JSONObject): String {
        val all = o.optJSONArray("artists").objects().mapNotNull { it.str("name") }.distinct()
        return if (all.isNotEmpty()) all.take(3).joinToString(", ") else artistName(o)
    }

    private fun cover(o: JSONObject): String? {
        val al = o.optJSONObject("album") ?: o
        return al.str("cover_big", "cover_xl", "cover_medium", "cover", "image", "picture_big", "picture_xl", "picture", "cover_small")
    }

    // ---------------------------------------------------------------- track links

    private fun trackUrl(o: JSONObject): String? {
        val id = o.str("id") ?: return null
        val title = o.str("title") ?: return null
        val artist = artistName(o)
        val dur = o.optInt("duration", 0)
        val al = o.optJSONObject("album")?.str("id")
        val prev = o.str("previewUrl") ?: ""
        return "$mainUrl/track/$id?t=${enc(title)}&a=${enc(artist)}&d=$dur" +
            (al?.let { "&al=$it" } ?: "") +
            (if (prev.isNotBlank()) "&p=${enc(prev)}" else "")
    }

    private fun param(url: String, key: String): String? =
        Regex("[?&]$key=([^&]*)").find(url)?.groupValues?.get(1)?.let { dec(it) }

    private fun idFrom(url: String, kind: String): String? =
        Regex("/$kind/(\\d+)").find(url)?.groupValues?.get(1)

    // ---------------------------------------------------------------- cards

    private fun trackCard(o: JSONObject): SearchResponse? {
        val url = trackUrl(o) ?: return null
        val title = o.str("title") ?: return null
        val artist = artistNames(o)
        return newMovieSearchResponse(if (artist.isNotBlank()) "$title - $artist" else title, url, TvType.Music) {
            this.posterUrl = cover(o)
        }
    }

    private fun albumCard(o: JSONObject): SearchResponse? {
        val id = o.str("id") ?: return null
        val title = o.str("title") ?: return null
        val artist = artistName(o)
        return newTvSeriesSearchResponse(if (artist.isNotBlank()) "$title - $artist" else title, "$mainUrl/album/$id", TvType.Music) {
            this.posterUrl = cover(o)
            this.year = o.str("releaseDate")?.take(4)?.toIntOrNull()
        }
    }

    private fun artistCard(o: JSONObject): SearchResponse? {
        val id = o.str("id") ?: return null
        val name = o.str("name") ?: return null
        return newTvSeriesSearchResponse(name, "$mainUrl/artist/$id", TvType.Music) {
            this.posterUrl = o.str("picture_big", "picture_xl", "picture_medium", "picture")
        }
    }

    // ---------------------------------------------------------------- home page

    /**
     * Rows built from endpoints that answer without the Cloudflare cookie.
     *
     * The site's latest update put /api/search/page and /api/album behind the clearance check -
     * they answer 403 without it - which is why every "q:" row came back empty and the page looked
     * dead. These do not need it:
     *
     *   /api/lastfm/global-chart-tracks?limit=  -> the chart itself
     *   /api/mix?artists=<ids>                  -> "more like these", seeded from the chart
     *   /api/track/{id}/radio                   -> radio around a chart track
     *   /api/apple/enum?kind=music&sf=us        -> Apple's shelves, still open
     *
     * The search rows stay, and fill in by themselves once the clearance cookie is valid.
     */
    override val mainPage = mainPageOf(
        "chart" to "Global Chart",
        "mix" to "Because of the chart",
        "radio" to "Radio picks",
        "apple" to "Top Songs (Apple Music)",
        "q:bollywood hits" to "Bollywood",
        "q:arijit singh" to "Arijit Singh",
        "q:punjabi" to "Punjabi",
        "q:lofi" to "Lo-fi & Chill",
        "q:pop hits" to "Pop",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), false)

        // A row that throws takes the whole home page down with it, which is exactly what happened
        // when /api/apple/charts started answering 403: the "Top Songs (Apple Music)" row threw
        // ErrorLoadingException and nothing rendered. Every row is now isolated, so a dead endpoint
        // costs that one row and nothing else.
        val items = runCatching {
            when (request.data) {
                "chart" -> chartTracks().mapNotNull { trackCard(it) }
                "mix" -> mixRow()
                "radio" -> radioRow()
                "apple" -> appleChart()
                else -> getJson("/api/search/page?query=${enc(request.data.removePrefix("q:"))}&limit=30")
                    .optJSONArray("tracks").objects().mapNotNull { trackCard(it) }
            }
        }.getOrElse { e ->
            android.util.Log.w("Octave", "row '${request.name}' failed: ${e.message}")
            emptyList()
        }
        return newHomePageResponse(HomePageList(request.name, items), false)
    }

    // ---------------------------------------------------------------- home rows

    @Volatile private var chartCache: List<JSONObject> = emptyList()
    @Volatile private var chartCacheAt = 0L

    /** the global chart, cached a few minutes: the mix and radio rows are seeded from it */
    private suspend fun chartTracks(): List<JSONObject> {
        val now = System.currentTimeMillis()
        if (chartCache.isNotEmpty() && now - chartCacheAt < 5 * 60_000L) return chartCache
        val list = getJson("/api/lastfm/global-chart-tracks?limit=30")
            .optJSONArray("tracks").objects()
        if (list.isNotEmpty()) {
            chartCache = list
            chartCacheAt = now
        }
        return list
    }

    /** "more like these": a mix seeded with the artists on the chart */
    private suspend fun mixRow(): List<SearchResponse> {
        val seeds = chartTracks()
            .mapNotNull { it.optJSONObject("artist")?.str("id") }
            .distinct()
            .take(4)
        if (seeds.isEmpty()) return emptyList()
        val out = LinkedHashMap<String, SearchResponse>()
        seeds.forEach { artistId ->
            runCatching {
                getJson("/api/mix?limit=20&artists=$artistId")
                    .optJSONArray("tracks").objects().mapNotNull { trackCard(it) }
            }.getOrDefault(emptyList()).forEach { out[it.url] = it }
        }
        return out.values.toList()
    }

    /** radio around the top few chart tracks */
    private suspend fun radioRow(): List<SearchResponse> {
        val seeds = chartTracks().mapNotNull { it.str("id") }.take(3)
        if (seeds.isEmpty()) return emptyList()
        val out = LinkedHashMap<String, SearchResponse>()
        seeds.forEach { trackId ->
            runCatching {
                getJson("/api/track/$trackId/radio")
                    .optJSONArray("tracks").objects().mapNotNull { trackCard(it) }
            }.getOrDefault(emptyList()).forEach { out[it.url] = it }
        }
        return out.values.toList()
    }

    /** Apple's chart has no Deezer ids - the site's own resolver matches the songs. */
    private suspend fun appleChart(): List<SearchResponse> {
        // /api/apple/charts answers 403 as of the site's latest update, so this row falls back to a
        // plain search for current hits rather than failing. If the endpoint comes back, the chart
        // is used again.
        val songs = runCatching {
            getJson("/api/apple/charts?sf=us&types=songs").optJSONObject("results")
                ?.optJSONArray("songs").objects().take(25)
        }.getOrNull()
        if (songs.isNullOrEmpty()) {
            return runCatching {
                getJson("/api/search/page?query=${enc("top hits")}&limit=30")
                    .optJSONArray("tracks").objects().mapNotNull { trackCard(it) }
            }.getOrDefault(emptyList())
        }
        val items = JSONArray()
        songs.forEach {
            items.put(JSONObject().put("title", it.str("name") ?: "").put("artist", it.str("artistName") ?: "").put("isrc", it.str("isrc") ?: ""))
        }
        val body = JSONObject().put("items", items).put("name", "Top Songs").put("budgetMs", 12000).toString()
        val r = OctaveCf.postJson("$api/api/import/resolve", body)
        if (r.code !in 200..299) return emptyList()
        return try {
            JSONObject(r.text).optJSONArray("tracks").objects().mapNotNull { trackCard(it) }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    // ---------------------------------------------------------------- search

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val root = getJson("/api/search/page?query=${enc(query.trim())}&limit=30")
        val out = ArrayList<SearchResponse>()
        root.optJSONArray("artists").objects().take(3).mapNotNullTo(out) { artistCard(it) }
        root.optJSONArray("albums").objects().take(8).mapNotNullTo(out) { albumCard(it) }
        root.optJSONArray("tracks").objects().mapNotNullTo(out) { trackCard(it) }
        return out.distinctBy { it.url }
    }

    // ---------------------------------------------------------------- load

    override suspend fun load(url: String): LoadResponse? {
        idFrom(url, "album")?.let { return loadAlbum(it) }
        idFrom(url, "artist")?.let { return loadArtist(it) }
        idFrom(url, "track")?.let { return loadTrack(it, url) }
        throw ErrorLoadingException("Octave: unknown link")
    }

    private fun trackEpisode(t: JSONObject, n: Int, fallbackPoster: String?): Episode? {
        val url = trackUrl(t) ?: return null
        val artist = artistNames(t)
        return newEpisode(url) {
            this.name = t.str("title") ?: "Track $n"
            this.episode = n
            this.season = 1
            this.posterUrl = cover(t) ?: fallbackPoster
            this.description = artist.ifBlank { null }
            this.runTime = (t.optInt("duration", 0) / 60).takeIf { it > 0 }
        }
    }

    private suspend fun loadAlbum(id: String): LoadResponse {
        val root = getJson("/api/album/$id")
        val a = root.optJSONObject("album") ?: root
        val title = a.str("title") ?: "Album"
        val artist = artistName(a)
        val poster = cover(a)
        var n = 0
        val eps = a.optJSONArray("tracks").objects().mapNotNull { trackEpisode(it, ++n, poster) }
        return newTvSeriesLoadResponse(if (artist.isNotBlank()) "$title - $artist" else title, "$mainUrl/album/$id", TvType.Music, eps) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.year = a.str("releaseDate")?.take(4)?.toIntOrNull()
            this.plot = listOfNotNull(
                artist.ifBlank { null },
                a.str("recordType")?.replaceFirstChar { it.uppercase() },
                "${eps.size} tracks",
            ).joinToString(" | ")
        }
    }

    private suspend fun loadArtist(id: String): LoadResponse {
        val tracks = getJson("/api/artist/$id/radio").optJSONArray("tracks").objects()
        val mine = tracks.firstOrNull { it.optJSONObject("artist")?.str("id") == id }
        val name = mine?.let { artistName(it) } ?: "Artist"
        val poster = tracks.firstOrNull()?.let { cover(it) }
        var n = 0
        val eps = tracks.mapNotNull { trackEpisode(it, ++n, poster) }
        val albums = try {
            getJson("/api/artists/releases?ids=$id").optJSONArray("releases").objects()
                .mapNotNull { it.optJSONObject("album") }.mapNotNull { albumCard(it) }.distinctBy { it.url }
        } catch (_: Throwable) {
            emptyList()
        }
        return newTvSeriesLoadResponse(name, "$mainUrl/artist/$id", TvType.Music, eps) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.plot = "Artist radio for $name. Albums are listed below."
            this.recommendations = albums
        }
    }

    private suspend fun loadTrack(id: String, url: String): LoadResponse {
        val title = param(url, "t") ?: "Track"
        val artist = param(url, "a") ?: ""
        val dur = param(url, "d")?.toIntOrNull() ?: 0
        val others = try {
            getJson("/api/track/$id/radio").optJSONArray("tracks").objects()
                .filter { it.str("id") != id }.take(20).mapNotNull { trackCard(it) }
        } catch (_: Throwable) {
            emptyList()
        }
        return newMovieLoadResponse(if (artist.isNotBlank()) "$title - $artist" else title, url, TvType.Music, url) {
            this.plot = artist.ifBlank { null }
            this.duration = (dur / 60).takeIf { it > 0 }
            this.recommendations = others
        }
    }

    // ---------------------------------------------------------------- links

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = idFrom(data, "track") ?: Regex("(\\d{5,})").findAll(data).lastOrNull()?.groupValues?.get(1) ?: return false
        val title = param(data, "t") ?: ""
        val artist = param(data, "a") ?: ""
        val dur = param(data, "d") ?: "0"

        // no cookie gate here: the hidden page decides, and asks the user only if Cloudflare really blocks

        // Without a playback key the lossless file cannot be fetched, but every track object carries
        // a 30-second preview that needs no key at all. Offering that beats returning nothing.
        val preview = param(data, "p").orEmpty()

        suspend fun emitPreview(reason: String): Boolean {
            if (!preview.startsWith("http")) {
                OctaveCf.toast("Octave: no link - $reason, and this track carries no preview")
                return false
            }
            callback.invoke(
                newExtractorLink(name, "$name preview (30s) - $reason", preview, ExtractorLinkType.VIDEO) {
                    this.quality = 128
                }
            )
            OctaveCf.toast("Octave: preview only - $reason")
            return true
        }

        suspend fun emitDirect(u: String, label: String): Boolean {
            if (!u.startsWith("http")) return false
            callback.invoke(
                newExtractorLink(name, "$name $label", u, ExtractorLinkType.VIDEO) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                    this.headers = OctaveCf.playerHeaders()
                }
            )
            return true
        }

        // 1. What the site's own player does first: ask the api to resolve the track.
        //    GET /api/track/{id}?quality=lossless -> {url, preview, gated}
        //    A url here needs no playback token at all - it is only gated when the server says so.
        val resolved = runCatching {
            val r = OctaveCf.get("$api/api/track/$id?quality=lossless")
            if (r.code in 200..299) JSONObject(r.text) else null
        }.getOrNull()
        val directUrl = resolved?.optString("url").orEmpty()
        val gated = resolved?.optBoolean("gated", false) == true
        if (directUrl.startsWith("http") && !gated) {
            if (emitDirect(directUrl, "FLAC (lossless)")) return true
        }

        // 2. The audio endpoint, without a key first: /audio/lossless?track=… is what the page
        //    builds when no token exists, and the server serves it when the track is not gated.
        val bareUrl = "$api/audio/lossless?track=$id&t=${enc(title)}&a=${enc(artist)}&d=$dur&x=0"
        if (!gated && OctaveCf.head(bareUrl) in 200..299) {
            if (emitDirect(bareUrl, "FLAC (lossless)")) return true
        }

        // 3. Only now the playback token, which is what kept failing.
        val keyResult = runCatching { OctaveCf.playbackToken() }
        var key = keyResult.getOrNull() ?: return emitPreview(
            "no playback key (${keyResult.exceptionOrNull()?.message ?: "HTTP 403"})"
        )
        fun streamUrl(k: String) = "$api/audio/lossless?track=$id&t=${enc(title)}&a=${enc(artist)}&d=$dur&x=0&k=$k"

        var url = streamUrl(key)
        for (i in 0 until 4) {
            val code = OctaveCf.head(url)
            if (code in 200..299 || code == -1) break
            if (code == 401 || code == 403) {
                key = OctaveCf.playbackToken(forceNew = true) ?: break
                url = streamUrl(key)
            }
            kotlinx.coroutines.delay(1500L)
        }

        val lastCode = OctaveCf.head(url)
        if (lastCode == 401 || lastCode == 403) {
            return emitPreview("lossless refused (HTTP $lastCode)")
        }

        callback.invoke(
            newExtractorLink(name, "$name FLAC (lossless)", url, ExtractorLinkType.VIDEO) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.Unknown.value
                this.headers = OctaveCf.playerHeaders()
            }
        )
        return true
    }
}
