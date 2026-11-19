package com.watchuu.echo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Echo - YouTube Music, without the ads.
 *
 * Card urls:
 *   /track/<videoId>?t=<title>&a=<artist>&art=<cover>
 *   /album/<browseId>/<title>/<artist>/<cover>
 *   /artist/<browseId>/<name>
 */
class EchoProvider : MainAPI() {
    override var mainUrl = EchoApi.SITE
    override var name = "Echo"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Music, TvType.Others)

    // ------------------------------------------------------------------ helpers

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun dec(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private fun param(url: String, key: String): String? =
        Regex("[?&]$key=([^&]*)").find(url)?.groupValues?.get(1)?.let { dec(it) }

    private fun trackCard(t: EchoParser.Track): SearchResponse =
        newMovieSearchResponse(
            if (t.subtitle.isNotBlank()) "${t.title} - ${t.subtitle.substringBefore('•').trim()}" else t.title,
            "$mainUrl/track/${t.videoId}?t=${enc(t.title)}&a=${enc(t.subtitle)}&art=${enc(t.cover.orEmpty())}",
            TvType.Music,
        ) { this.posterUrl = t.cover }

    private fun tileCard(t: EchoParser.Tile): SearchResponse? = when {
        t.isArtist -> newTvSeriesSearchResponse(t.title, "$mainUrl/artist/${t.browseId}/${enc(t.title)}", TvType.Music) {
            this.posterUrl = t.cover
        }
        else -> newTvSeriesSearchResponse(
            if (t.subtitle.isNotBlank()) "${t.title} - ${t.subtitle.substringBefore('•').trim()}" else t.title,
            "$mainUrl/album/${t.browseId}/${enc(t.title)}/${enc(t.subtitle)}/${enc(t.cover.orEmpty())}",
            TvType.Music,
        ) { this.posterUrl = t.cover }
    }

    // ------------------------------------------------------------------ home

    override val mainPage = mainPageOf(
        "home" to "Home",
        "charts" to "Charts",
        "q:top hits" to "Top hits",
        "q:arijit singh" to "Arijit Singh",
        "q:punjabi" to "Punjabi",
        "q:lofi" to "Lo-fi",
        "q:english hits" to "English",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), false)

        val items = runCatching {
            when {
                // the home page is a tree of carousels; flattening them gives one long, varied row
                request.data == "home" -> {
                    val root = EchoApi.home()
                    val tracks = EchoParser.tracks(root).mapNotNull { runCatching { trackCard(it) }.getOrNull() }
                    val tiles = EchoParser.tiles(root).mapNotNull { runCatching { tileCard(it) }.getOrNull() }
                    (tiles + tracks).distinctBy { it.url }.take(60)
                }
                request.data == "charts" -> {
                    val root = EchoApi.browse("FEmusic_charts")
                    val tracks = EchoParser.tracks(root).mapNotNull { runCatching { trackCard(it) }.getOrNull() }
                    val tiles = EchoParser.tiles(root).mapNotNull { runCatching { tileCard(it) }.getOrNull() }
                    (tiles + tracks).distinctBy { it.url }.take(60)
                }
                else -> {
                    val root = EchoApi.search(request.data.removePrefix("q:"))
                    val tracks = EchoParser.tracks(root).mapNotNull { runCatching { trackCard(it) }.getOrNull() }
                    val tiles = EchoParser.tiles(root).mapNotNull { runCatching { tileCard(it) }.getOrNull() }
                    (tiles + tracks).distinctBy { it.url }.take(40)
                }
            }
        }.getOrElse { emptyList() }

        return newHomePageResponse(HomePageList(request.name, items), false)
    }

    // ------------------------------------------------------------------ search

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val root = EchoApi.search(query.trim()) ?: return emptyList()
        val tiles = EchoParser.tiles(root).mapNotNull { runCatching { tileCard(it) }.getOrNull() }
        val tracks = EchoParser.tracks(root).mapNotNull { runCatching { trackCard(it) }.getOrNull() }
        return (tiles + tracks).distinctBy { it.url }
    }

    // ------------------------------------------------------------------ load

    override suspend fun load(url: String): LoadResponse? {
        Regex("/artist/([^/]+)/([^?]*)").find(url)?.let { m ->
            return loadCollection(m.groupValues[1], url, m.groupValues[2], artist = true)
        }
        Regex("/album/([^/]+)/([^/]*)/([^/]*)/([^?]*)").find(url)?.let { m ->
            return loadCollection(m.groupValues[1], url, m.groupValues[2], artist = false)
        }
        Regex("/track/([A-Za-z0-9_-]{6,})").find(url)?.let { m ->
            return loadTrack(m.groupValues[1], url)
        }
        return null
    }

    private suspend fun loadTrack(videoId: String, url: String): LoadResponse? {
        val title = param(url, "t") ?: "Track"
        val artist = param(url, "a").orEmpty().substringBefore('•').trim()
        val art = param(url, "art")?.takeIf { it.startsWith("http") }
        return newMovieLoadResponse(
            if (artist.isNotBlank()) "$title - $artist" else title,
            url,
            TvType.Music,
            url,
        ) {
            this.posterUrl = art
            this.plot = artist.ifBlank { null }
        }
    }

    /** an album, playlist or artist: browsed and shown as a series of its songs */
    private suspend fun loadCollection(browseId: String, url: String, titleEnc: String, artist: Boolean): LoadResponse? {
        val root = EchoApi.browse(browseId) ?: return null

        val name = dec(titleEnc).ifBlank { "Collection" }
        val tracks = EchoParser.tracks(root)
        if (tracks.isEmpty()) return null

        var n = 0
        val eps = tracks.mapNotNull { t ->
            newEpisode("$mainUrl/track/${t.videoId}?t=${enc(t.title)}&a=${enc(t.subtitle)}&art=${enc(t.cover.orEmpty())}") {
                this.name = t.title
                this.season = 1
                this.episode = ++n
                this.posterUrl = t.cover
                this.runTime = t.durationText?.split(":")?.let { parts ->
                    when (parts.size) {
                        3 -> parts[0].toIntOrNull()?.times(60)?.plus(parts[1].toIntOrNull() ?: 0)
                        2 -> parts[0].toIntOrNull()
                        else -> null
                    }
                }
            }
        }
        if (eps.isEmpty()) return null

        val cover = EchoParser.tiles(root).firstOrNull()?.cover
            ?: tracks.firstOrNull()?.cover
        // keep the full card url so the page re-opens from history and bookmarks
        return newTvSeriesLoadResponse(name, url, TvType.Music, eps) {
            this.posterUrl = cover
            this.plot = "${eps.size} songs"
        }
    }

    // ------------------------------------------------------------------ playback

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoId = Regex("/track/([A-Za-z0-9_-]{6,})").find(data)?.groupValues?.get(1)
            ?: return false

        // Cloudstream ships a YouTube resolver that already handles signatures, `n` transforms and
        // po tokens; asking it is far more reliable than reimplementing the player endpoint here.
        // The resolver is registered for www.youtube.com, so ask with that link first; the music
        // domain is only tried if the first ask gives nothing.
        val found = java.util.Collections.synchronizedList(mutableListOf<ExtractorLink>())
        for (watch in listOf(
            "https://www.youtube.com/watch?v=$videoId",
            "https://music.youtube.com/watch?v=$videoId",
        )) {
            runCatching {
                loadExtractor(watch, "https://www.youtube.com/", subtitleCallback) { found.add(it) }
            }
            if (found.isNotEmpty()) break
        }
        if (found.isEmpty()) return false

        found.sortedByDescending { it.quality }.forEach { link ->
            callback.invoke(
                newExtractorLink(name, link.name.ifBlank { "Audio" }, link.url, link.type) {
                    this.referer = link.referer.ifBlank { "https://www.youtube.com/" }
                    this.quality = if (link.quality == Qualities.Unknown.value) Qualities.Unknown.value else link.quality
                    this.headers = link.headers
                }
            )
        }
        return true
    }
}
