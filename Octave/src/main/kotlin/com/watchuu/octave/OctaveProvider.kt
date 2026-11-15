package com.watchuu.octave

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.launch
import java.net.URLDecoder
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Octave - lossless music from music.octavestreaming.com.
 *
 * The catalogue is Deezer's, and the site's public endpoints answer without any credential, so the
 * extension is useful with nothing configured: home, search, albums, artists and a 30-second preview
 * for every track. A valid account key plus a Cloudflare clearance cookie unlock the lossless file,
 * and nothing else does - that is the site's rule, not a limitation here.
 *
 * Card urls look like:
 *   /track/<id>?t=<title>&a=<artist>&d=<seconds>&p=<preview>
 *   /album/<id>/<title>/<artist>/<cover>
 *   /artist/<id>/<name>
 */
class OctaveProvider : MainAPI() {

    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    /** fetches the playback token in the background, so a later tap has it cached */
    private fun warmToken() {
        scope.launch { runCatching { OctaveApi.playbackToken() } }
    }
    override var mainUrl = OctaveApi.SITE
    override var name = "Octave"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Music, TvType.Others)

    private val api = OctaveApi.API

    // ------------------------------------------------------------------ helpers

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun dec(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private fun JSONObject.str(vararg keys: String): String? {
        for (k in keys) {
            val v = optString(k, "")
            if (v.isNotBlank() && v != "null") return v
        }
        return null
    }

    private fun JSONArray?.objs(): List<JSONObject> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optJSONObject(it) }
    }

    private fun artistOf(o: JSONObject): String =
        o.optJSONObject("artist")?.str("name")
            ?: o.str("artist", "artistName")
            ?: ""

    private fun artistsOf(o: JSONObject): String {
        val all = o.optJSONArray("artists").objs().mapNotNull { it.str("name") }.distinct()
        return if (all.isNotEmpty()) all.take(3).joinToString(", ") else artistOf(o)
    }

    private fun coverOf(o: JSONObject): String? {
        val al = o.optJSONObject("album") ?: o
        return al.str("cover_big", "cover_xl", "cover_medium", "cover", "image", "picture_big", "picture_xl")
            ?: o.optJSONObject("artist")?.str("picture_big", "picture_xl", "picture_medium")
    }

    private fun previewOf(o: JSONObject): String =
        o.str("previewUrl", "preview") ?: ""

    private fun trackUrl(o: JSONObject): String? {
        val id = o.str("id") ?: return null
        val title = o.str("title") ?: return null
        val artist = artistsOf(o)
        val dur = o.optInt("duration", 0)
        val prev = previewOf(o)
        return "$mainUrl/track/$id?t=${enc(title)}&a=${enc(artist)}&d=$dur" +
            (if (prev.isNotBlank()) "&p=${enc(prev)}" else "")
    }

    private fun param(url: String, key: String): String? =
        Regex("[?&]$key=([^&]*)").find(url)?.groupValues?.get(1)?.let { dec(it) }

    // ------------------------------------------------------------------ cards

    private fun trackCard(o: JSONObject): SearchResponse? {
        val url = trackUrl(o) ?: return null
        val title = o.str("title") ?: return null
        val artist = artistsOf(o)
        return newMovieSearchResponse(if (artist.isNotBlank()) "$title - $artist" else title, url, TvType.Music) {
            this.posterUrl = coverOf(o)
        }
    }

    private fun albumCard(o: JSONObject): SearchResponse? {
        val id = o.str("id") ?: return null
        val title = o.str("title") ?: return null
        val artist = artistOf(o)
        val cover = coverOf(o).orEmpty()
        return newTvSeriesSearchResponse(
            if (artist.isNotBlank()) "$title - $artist" else title,
            "$mainUrl/album/$id/${enc(title)}/${enc(artist)}/${enc(cover)}",
            TvType.Music,
        ) { this.posterUrl = cover }
    }

    private fun artistCard(o: JSONObject): SearchResponse? {
        val id = o.str("id") ?: return null
        val nm = o.str("name") ?: return null
        return newTvSeriesSearchResponse(nm, "$mainUrl/artist/$id/${enc(nm)}", TvType.Music) {
            this.posterUrl = o.str("picture_big", "picture_xl", "picture_medium", "image")
        }
    }

    // ------------------------------------------------------------------ home

    override val mainPage = mainPageOf(
        "chart" to "Global Chart",
        "mix" to "Because of the chart",
        "radio" to "Radio picks",
        "apple" to "Apple top songs",
        "q:bollywood hits" to "Bollywood",
        "q:arijit singh" to "Arijit Singh",
        "q:punjabi hits" to "Punjabi",
        "q:lofi" to "Lo-fi",
        "q:romantic" to "Romantic",
    )

    @Volatile private var chartCache: List<JSONObject> = emptyList()
    @Volatile private var chartAt = 0L

    private suspend fun chart(): List<JSONObject> {
        val now = System.currentTimeMillis()
        if (chartCache.isNotEmpty() && now - chartAt < 5 * 60_000L) return chartCache
        val list = OctaveApi.json("$api/api/lastfm/global-chart-tracks?limit=30")
            ?.optJSONArray("tracks").objs().orEmpty()
        if (list.isNotEmpty()) {
            chartCache = list
            chartAt = now
        }
        return list
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
        val items = runCatching {
            when (request.data) {
                "chart" -> chart().mapNotNull { trackCard(it) }
                "mix" -> {
                    val seeds = chart().mapNotNull { it.optJSONObject("artist")?.str("id") }.distinct().take(3)
                    val seen = LinkedHashMap<String, SearchResponse>()
                    seeds.forEach { id ->
                        OctaveApi.json("$api/api/mix?limit=20&artists=$id")
                            ?.optJSONArray("tracks").objs().orEmpty()
                            .mapNotNull { trackCard(it) }
                            .forEach { seen[it.url] = it }
                    }
                    seen.values.toList()
                }
                "radio" -> {
                    val seeds = chart().mapNotNull { it.str("id") }.take(3)
                    val seen = LinkedHashMap<String, SearchResponse>()
                    seeds.forEach { id ->
                        OctaveApi.json("$api/api/track/$id/radio")
                            ?.optJSONArray("tracks").objs().orEmpty()
                            .mapNotNull { trackCard(it) }
                            .forEach { seen[it.url] = it }
                    }
                    seen.values.toList()
                }
                "apple" -> search("top hits").take(30)
                else -> search(request.data.removePrefix("q:")).take(30)
            }
        }.getOrElse { emptyList() }
        return newHomePageResponse(HomePageList(request.name, items), false)
    }

    // ------------------------------------------------------------------ search

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()

        val own = runCatching {
            val root = OctaveApi.json("$api/api/search/page?query=${enc(query.trim())}&limit=30")
                ?: OctaveApi.pageJson("$api/api/search/page?query=${enc(query.trim())}&limit=30")
                ?: return@runCatching emptyList<SearchResponse>()
            val out = ArrayList<SearchResponse>()
            root.optJSONArray("artists").objs().take(3).mapNotNullTo(out) { artistCard(it) }
            root.optJSONArray("albums").objs().take(6).mapNotNullTo(out) { albumCard(it) }
            root.optJSONArray("tracks").objs().mapNotNullTo(out) { trackCard(it) }
            out.distinctBy { it.url }
        }.getOrDefault(emptyList())

        OctaveLog.step("search \"$query\": own api gave ${own.size}, deezer fallback ${if (own.isEmpty()) "used" else "not needed"}")
        if (own.isNotEmpty()) return own
        return deezerSearch(query)
    }

    /** Deezer's public search: same ids, same previews, no credentials at all */
    private suspend fun deezerSearch(query: String): List<SearchResponse> {
        val body = OctaveApi.json("${OctaveApi.DEEZER}/search?q=${enc(query.trim())}&limit=30") ?: return emptyList()
        return body.optJSONArray("data").objs().mapNotNull { trackCard(it) }
    }

    // ------------------------------------------------------------------ load

    override suspend fun load(url: String): LoadResponse? {
        // the token takes a WebView round trip; start it now, not when the user presses play
        warmToken()
        Regex("/album/(\\d+)").find(url)?.groupValues?.get(1)?.let { return loadAlbum(it) }
        Regex("/artist/(\\d+)").find(url)?.groupValues?.get(1)?.let { return loadArtist(it, url) }
        Regex("/track/(\\d+)").find(url)?.groupValues?.get(1)?.let { return loadTrack(it, url) }
        return null
    }

    private suspend fun loadTrack(id: String, url: String): LoadResponse? {
        val title = param(url, "t") ?: "Track"
        val artist = param(url, "a").orEmpty()
        val dur = param(url, "d")?.toIntOrNull() ?: 0
        return newMovieLoadResponse(
            if (artist.isNotBlank()) "$title - $artist" else title,
            url,
            TvType.Music,
            url,
        ) {
            this.posterUrl = null
            this.plot = artist.ifBlank { null }
            this.duration = dur
        }
    }

    private suspend fun loadAlbum(id: String): LoadResponse? {
        // the site's album endpoint needs the cookie; Deezer's does not, and the tracks are the same
        val own = runCatching { OctaveApi.json("$api/api/album/$id") }.getOrNull()
            ?: OctaveApi.pageJson("$api/api/album/$id")
        val root = own
            ?: OctaveApi.json("$api/api/album/$id")
            ?: OctaveApi.json("${OctaveApi.DEEZER}/album/$id")
            ?: return null
        val a = root.optJSONObject("album") ?: root
        val title = a.str("title") ?: "Album"
        val artist = artistOf(a)
        val poster = coverOf(a)
        val tracks = a.optJSONArray("tracks") ?: a.optJSONObject("tracks")?.optJSONArray("data")
        var n = 0
        val eps = tracks.objs().mapNotNull { t ->
            val u = trackUrl(t) ?: return@mapNotNull null
            newEpisode(u) {
                this.name = t.str("title") ?: "Track ${n + 1}"
                this.season = 1
                this.episode = ++n
                this.posterUrl = coverOf(t) ?: poster
                this.description = artistsOf(t).ifBlank { null }
            }
        }
        OctaveLog.step("album $id: \"$title\" ${eps.size} tracks (${if (own != null) "own api" else "deezer"})")
        if (eps.isEmpty()) return null
        return newTvSeriesLoadResponse(
            if (artist.isNotBlank()) "$title - $artist" else title,
            "$mainUrl/album/$id",
            TvType.Music,
            eps,
        ) {
            this.posterUrl = poster
            this.plot = listOfNotNull(artist.ifBlank { null }, "${eps.size} tracks").joinToString(" · ")
        }
    }

    private suspend fun loadArtist(id: String, url: String): LoadResponse? {
        val nm = url.substringAfterLast("/").let { if (it.all { c -> c.isDigit() }) "Artist" else dec(it) }
        val top = OctaveApi.json("$api/api/artist/$id/radio")
            ?: OctaveApi.pageJson("$api/api/artist/$id/radio")
            ?: OctaveApi.json("${OctaveApi.DEEZER}/artist/$id/top?limit=50")
        val items = top?.optJSONArray("data").objs().orEmpty().ifEmpty { top?.optJSONArray("tracks").objs().orEmpty() }
        var n = 0
        val eps = items.mapNotNull { t ->
            val u = trackUrl(t) ?: return@mapNotNull null
            newEpisode(u) {
                this.name = t.str("title") ?: "Track ${n + 1}"
                this.season = 1
                this.episode = ++n
                this.posterUrl = coverOf(t)
                this.description = artistsOf(t).ifBlank { null }
            }
        }
        OctaveLog.step("artist $id \"$nm\": ${eps.size} tracks")
        if (eps.isEmpty()) return null
        return newTvSeriesLoadResponse(nm, "$mainUrl/artist/$id", TvType.Music, eps) {
            this.posterUrl = items.firstOrNull()?.let { coverOf(it) }
        }
    }

    // ------------------------------------------------------------------ playback

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = Regex("/track/(\\d+)").find(data)?.groupValues?.get(1)
            ?: Regex("(\\d{5,})").findAll(data).lastOrNull()?.groupValues?.get(1)
            ?: return false

        val title = param(data, "t").orEmpty()
        val artist = param(data, "a").orEmpty()
        val dur = param(data, "d") ?: "0"

        suspend fun emitPreview(url: String, reason: String): Boolean {
            if (!url.startsWith("http")) return false
            callback.invoke(
                newExtractorLink(name, "$name preview (30s) - $reason", url, ExtractorLinkType.VIDEO) {
                    this.referer = "$mainUrl/"
                    this.quality = 128
                }
            )
            return true
        }

        // 1. what the site says about this track. Public, no cookie, and it hands back a fresh
        //    preview url plus the lossless url and whether that url is gated.
        OctaveLog.step("play $id \"$title\" - $artist (${dur}s)")
        val info = OctaveApi.json("$api/api/track/$id?quality=lossless")
            ?: OctaveApi.pageJson("$api/api/track/$id?quality=lossless")
        val direct = info?.optString("url").orEmpty()
        val gated = info?.optBoolean("gated", false) == true
        var preview = info?.optString("preview").orEmpty().ifBlank { param(data, "p").orEmpty() }
        OctaveLog.step(
            "resolve: " + (if (info == null) "no answer" else
                "url=${if (direct.isBlank()) "none" else "yes"}, gated=$gated, preview=${if (preview.isBlank()) "none" else "yes"}")
        )

        // 2. a preview from Deezer if the site gave none - any id here is a Deezer id
        if (!preview.startsWith("http")) {
            preview = OctaveApi.json("${OctaveApi.DEEZER}/track/$id")?.optString("preview").orEmpty()
            OctaveLog.step("preview: fell back to deezer, ${if (preview.isBlank()) "still none" else "got one"}")
        }

        // 3. the lossless file. The capture shows this request needs nothing but k:
        //      HEAD 200, content-type: audio/flac
        //      User-Agent, Origin, Accept: */*  - no cookie at all
        //    So a token is the whole requirement, and the link is emitted as soon as there is one.
        val key = OctaveApi.quickToken()
        OctaveLog.step("token: ${if (key == null) "NOT available - flac cannot be offered" else "available"}")
        if (key != null) {
            val base = direct.takeIf { it.startsWith("http") }
                ?: "$api/audio/lossless?track=$id&t=${enc(title)}&a=${enc(artist)}&d=$dur&x=0"
            val url = if (base.contains("k=")) base else "$base${if (base.contains("?")) "&" else "?"}k=$key"

            val code = OctaveApi.serves(url)
            val works = code in 200..299 || code == 416
            OctaveLog.step("flac url built with k, probe HTTP $code -> ${if (works) "good" else "doubtful"}")
            callback.invoke(
                newExtractorLink(
                    name,
                    if (works) "$name FLAC (lossless)" else "$name FLAC (lossless, HTTP $code)",
                    url,
                    ExtractorLinkType.VIDEO,
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                    this.headers = OctaveApi.playerHeaders()
                }
            )
            // the preview beside it: if the player is refused, something still plays
            emitPreview(preview, "preview, in case FLAC is refused")
            OctaveLog.step("emitted: FLAC + preview")
            return true
        }

        // 4. no key, or nothing above worked: the preview. Never silence - Cloudstream reports
        //    silence as "no links found", which hides every reason above it.
        if (emitPreview(preview, if (gated) "account key needed for FLAC" else "no playback key")) {
            OctaveLog.step("emitted: preview only (no token)")
            return true
        }

        // A url without k answers 403 - the capture proves the working request carries k - so
        // offering the bare resolve url here only produced a dead link that looked like a bug.
        OctaveLog.warn("emitted: nothing - no token and no preview")
        return false
    }
}
