package com.watchuu.echo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Echo - YouTube Music, without the ads.
 *
 * Card urls:
 *   /track/<videoId>?t=<title>&a=<artist>&d=<seconds>&art=<cover>
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

    /** the playable link for a song; [fallbackArtist] covers album rows that leave the artist out */
    private fun trackUrl(t: EchoParser.Track, fallbackArtist: String = ""): String {
        val artist = EchoParser.artistOf(t.subtitle).ifBlank { fallbackArtist }
        val secs = EchoParser.seconds(t.durationText)
        return "$mainUrl/track/${t.videoId}?t=${enc(t.title)}&a=${enc(artist)}" +
            (if (secs != null) "&d=$secs" else "") + "&art=${enc(t.cover.orEmpty())}"
    }

    private fun trackCard(t: EchoParser.Track): SearchResponse {
        val artist = EchoParser.artistOf(t.subtitle)
        return newMovieSearchResponse(
            if (artist.isNotBlank()) "${t.title} - $artist" else t.title,
            trackUrl(t),
            TvType.Music,
        ) { this.posterUrl = t.cover }
    }

    private fun tileCard(t: EchoParser.Tile): SearchResponse? = when {
        t.isArtist -> newTvSeriesSearchResponse(t.title, "$mainUrl/artist/${t.browseId}/${enc(t.title)}", TvType.Music) {
            this.posterUrl = t.cover
        }
        else -> newTvSeriesSearchResponse(
            EchoParser.artistOf(t.subtitle).let { if (it.isNotBlank()) "${t.title} - $it" else t.title },
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
            return loadCollection(m.groupValues[1], url, m.groupValues[2], dec(m.groupValues[2]))
        }
        Regex("/album/([^/]+)/([^/]*)/([^/]*)/([^?]*)").find(url)?.let { m ->
            return loadCollection(m.groupValues[1], url, m.groupValues[2], EchoParser.artistOf(dec(m.groupValues[3])))
        }
        Regex("/track/([A-Za-z0-9_-]{6,})").find(url)?.let { m ->
            return loadTrack(m.groupValues[1], url)
        }
        return null
    }

    private suspend fun loadTrack(videoId: String, url: String): LoadResponse? {
        val title = param(url, "t") ?: "Track"
        val artist = EchoParser.artistOf(param(url, "a").orEmpty())
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
    private suspend fun loadCollection(browseId: String, url: String, titleEnc: String, ownerArtist: String): LoadResponse? {
        val root = EchoApi.browse(browseId) ?: return null

        val name = dec(titleEnc).ifBlank { "Collection" }
        val tracks = EchoParser.tracks(root)
        if (tracks.isEmpty()) return null

        var n = 0
        val eps = tracks.mapNotNull { t ->
            newEpisode(trackUrl(t, ownerArtist)) {
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

    /**
     * Echo Music with "Prefer Extension Audio" on: every add-on source is searched for the same song
     * (matched by EchoMatch, the app's own rules) and its stream offered; YouTube Music runs at the
     * same time and stays as the fallback. Links are handed over as each source answers.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoId = Regex("/track/([A-Za-z0-9_-]{6,})").find(data)?.groupValues?.get(1)
            ?: return false
        val title = param(data, "t").orEmpty()
        val artist = EchoParser.artistOf(param(data, "a").orEmpty())
        val durationMs = param(data, "d")?.toLongOrNull()?.times(1000)

        val any = AtomicBoolean(false)
        val lock = Any()
        val emit: (ExtractorLink) -> Unit = { link ->
            synchronized(lock) { callback.invoke(link) }
            any.set(true)
        }

        coroutineScope {
            if (title.isNotBlank()) {
                EchoSources.all.forEach { source ->
                    launch {
                        runCatching {
                            withTimeoutOrNull(SOURCE_TIMEOUT_MS) { fromSource(source, title, artist, durationMs, emit) }
                        }
                    }
                }
            }
            launch { fromYouTube(videoId, subtitleCallback, emit) }
        }
        return any.get()
    }

    /** ExtensionManager.resolveStream for one source: queries in order, strict match, then stream */
    private suspend fun fromSource(
        source: EchoSources.Source,
        title: String,
        artist: String,
        durationMs: Long?,
        emit: (ExtractorLink) -> Unit,
    ) {
        for (query in EchoMatch.candidateQueries(title, artist)) {
            val hits = runCatching { source.search(query) }.getOrNull() ?: continue
            val match = EchoMatch.selectBestMatch(hits, title, artist, durationMs) ?: continue
            val stream = runCatching { source.stream(match.id) }.getOrNull() ?: return
            val label = when {
                stream.lossless -> stream.format?.takeIf { !it.equals("flac", true) }?.uppercase()?.let { "$it Lossless" } ?: "FLAC"
                else -> stream.quality?.takeIf { it.isNotBlank() } ?: stream.format?.uppercase() ?: "Audio"
            }
            emit(
                newExtractorLink(
                    name,
                    "${source.label} · $label",
                    stream.url,
                    when (stream.manifest) {
                        "hls" -> ExtractorLinkType.M3U8
                        "dash" -> ExtractorLinkType.DASH
                        else -> ExtractorLinkType.VIDEO
                    },
                ) {
                    this.quality = if (stream.lossless) LOSSLESS_QUALITY else
                        Regex("(\\d+)\\s*kbps", RegexOption.IGNORE_CASE).find(stream.quality.orEmpty())
                            ?.groupValues?.get(1)?.toIntOrNull() ?: 320
                    if (stream.headers.isNotEmpty()) this.headers = stream.headers
                }
            )
            return
        }
    }

    /**
     * Cloudstream's own YouTube resolver handles signatures, `n` transforms and po tokens. Ask with
     * the music.youtube.com link first; www.youtube.com is only tried if that gives nothing.
     */
    private suspend fun fromYouTube(
        videoId: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        emit: (ExtractorLink) -> Unit,
    ) {
        val found = java.util.Collections.synchronizedList(mutableListOf<ExtractorLink>())
        for ((watch, referer) in listOf(
            "https://music.youtube.com/watch?v=$videoId" to "${EchoApi.SITE}/",
            "https://www.youtube.com/watch?v=$videoId" to "https://www.youtube.com/",
        )) {
            runCatching {
                loadExtractor(watch, referer, subtitleCallback) { found.add(it) }
            }
            if (found.isNotEmpty()) break
        }
        found.sortedByDescending { it.quality }.forEach { link ->
            emit(
                newExtractorLink(name, "YouTube Music · ${link.name.ifBlank { "Audio" }}", link.url, link.type) {
                    this.referer = link.referer.ifBlank { "https://www.youtube.com/" }
                    this.quality = if (link.quality == Qualities.Unknown.value) Qualities.Unknown.value else link.quality
                    this.headers = link.headers
                }
            )
        }
    }

    private companion object {
        /** the app's add-on client gives up after 15s per call; a source makes up to four */
        const val SOURCE_TIMEOUT_MS = 40_000L

        /** ranks a FLAC link above any lossy one, as MusicBox does */
        const val LOSSLESS_QUALITY = 1411
    }
}
