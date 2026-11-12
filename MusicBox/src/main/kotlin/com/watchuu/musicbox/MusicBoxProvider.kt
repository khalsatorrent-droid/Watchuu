package com.watchuu.musicbox

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

/**
 * MusicBox — rebuilt around two ideas: a catalogue that always answers, and playback that never
 * comes back empty.
 *
 * Catalogue
 *   Spotify Web is the primary, using the flow its own extension uses (TOTP -> token -> client
 *   token -> pathfinder). Apple, Deezer and SoundCloud are the backups, and the home rows fall
 *   through them in order, so a row fills even where one service is unreachable - Deezer is blocked
 *   outright in some countries.
 *
 * Playback
 *   Every source contributes whatever it can, at every quality it will answer for:
 *     carried preview (no network call, always emitted first)
 *     SoundCloud  full track
 *     Tidal       low / high / lossless / hi-res, whichever the call returns
 *     Qobuz       mp3 320 / flac / flac 24 bit
 *     Deezer      preview, plus the resolver's file when a ticket is set
 *     Apple       preview
 *     YouTube     full audio, through Cloudstream's extractor
 *
 * The resolver behind all of that (api.zarz.moe) needs a ticket the extensions cannot mint either -
 * they call session.signedFetch and the app signs it. That ticket is pasted in settings; without one
 * the previews stand and nothing breaks.
 */
class MusicBoxProvider : MainAPI() {
    override var mainUrl = "https://musicbox.local"
    override var name = "MusicBox"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Music, TvType.Others)

    override val mainPage = mainPageOf(
        "tracks" to "Top Tracks",
        "albums" to "Top Albums",
        "artists" to "Top Artists"
    )

    private fun ctx() = MusicBoxProvider.appContext

    // ── home ───────────────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), false)

        val catalogue = ctx()?.let { MusicBoxPrefs.catalogue(it) } ?: MusicBoxPrefs.ALL
        val kind = request.data

        val tracks = when (catalogue) {
            "spotify" -> spotifyRows(kind).ifEmpty { Sources.appleTop(kind) }
            "apple" -> Sources.appleTop(kind).ifEmpty { Sources.deezerChart(kind) }
            "deezer" -> Sources.deezerChart(kind).ifEmpty { Sources.appleTop(kind) }
            "soundcloud" -> Sources.soundcloudTop(kind).ifEmpty { Sources.appleTop(kind) }
            "qobuz", "tidal" -> Sources.deezerChart(kind).ifEmpty { Sources.appleTop(kind) }
            else -> spotifyRows(kind)
                .ifEmpty { Sources.appleTop(kind) }
                .ifEmpty { Sources.deezerChart(kind) }
        }

        return newHomePageResponse(request.name, tracks.mapNotNull { it.toCard() }, hasNext = false)
    }

    /** spotify publishes no anonymous chart, so its rows are a broad search */
    private suspend fun spotifyRows(kind: String): List<Track> {
        val term = when (kind) {
            "albums" -> "top albums"
            "artists" -> "top artists"
            else -> "top hits"
        }
        return runCatching { spotifySearch(term) }.getOrNull().orEmpty()
    }

    // ── search ─────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val catalogue = ctx()?.let { MusicBoxPrefs.catalogue(it) } ?: MusicBoxPrefs.ALL

        val out = ArrayList<Track>()
        suspend fun add(name: String, block: suspend () -> List<Track>) {
            if (catalogue != MusicBoxPrefs.ALL && catalogue != name) return
            runCatching { block() }.getOrNull()?.let { out.addAll(it) }
        }

        add("spotify") { spotifySearch(query) }
        add("deezer") { Sources.deezerSearch(query) }
        add("apple") { Sources.appleSearch(query) }
        add("soundcloud") { Sources.soundcloudSearch(query) }
        add("tidal") { Sources.tidalSearch(query) }
        add("qobuz") { Sources.qobuzSearch(query) }

        return out.mapNotNull { it.toCard() }.distinctBy { it.url }
    }

    private suspend fun spotifySearch(query: String): List<Track> {
        val nodes = SpotifyClient.search(query)
        val out = ArrayList<Track>()
        for (node in nodes) {
            val id = SpotifyClient.trackIdOf(node) ?: continue
            val title = SpotifyClient.titleOf(node).ifBlank { continue }
            out.add(
                Track(
                    source = "sp",
                    id = id,
                    title = title,
                    artist = SpotifyClient.artistsOf(node),
                    poster = SpotifyClient.coverOf(node)
                )
            )
        }
        return out
    }

    // ── cards ──────────────────────────────────────────────────────────────────

    private fun Track.toCard(): SearchResponse? {
        val label = if (artist.isNotBlank()) "$artist - $title" else title
        if (label.isBlank()) return null

        return when (kind) {
            "album", "artist" -> newTvSeriesSearchResponse(
                label, "$mainUrl/${source}/${kind}/$id", TvType.Music
            ) {
                this.posterUrl = poster
            }
            else -> newMovieSearchResponse(label, "$mainUrl/${source}/track/$id", TvType.Music) {
                this.posterUrl = poster
            }
        }
    }

    // ── detail ─────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val parts = url.removePrefix(mainUrl).trim('/').split('/')
        val source = parts.getOrNull(0) ?: return null
        val kind = parts.getOrNull(1) ?: "track"
        val id = parts.getOrNull(2) ?: return null

        // an album or artist becomes a series of tracks
        if (kind == "album" || kind == "artist") {
            val tracks = when (source) {
                "dz" -> if (kind == "album") Sources.deezerAlbum(id) else Sources.deezerArtist(id)
                else -> emptyList()
            }
            if (tracks.isEmpty()) return null

            val first = tracks.firstOrNull()
            val title = if (kind == "album") first?.title.orEmpty() else first?.artist.orEmpty()
            val episodes = tracks.mapIndexed { index, t ->
                newEpisode(t.encode()) {
                    this.name = t.title
                    this.season = 1
                    this.episode = index + 1
                    this.posterUrl = t.poster
                }
            }
            return newTvSeriesLoadResponse(
                title.ifBlank { "Album $id" }, url, TvType.Music, episodes
            ) {
                this.posterUrl = first?.poster
                this.plot = first?.artist
            }
        }

        // a single track: look up what it is called, and keep it with the entry
        val track = when (source) {
            "dz" -> Sources.deezerTrack(id)
            "am" -> Sources.appleTrack(id)
            else -> null
        }

        return newMovieLoadResponse(
            track?.let { if (it.artist.isNotBlank()) "${it.artist} - ${it.title}" else it.title }
                ?: "Track $id",
            url,
            TvType.Music,
            Track(source, id, track?.title.orEmpty(), track?.artist.orEmpty(),
                track?.poster, track?.preview).encode()
        ) {
            this.posterUrl = track?.poster
            this.plot = track?.artist
        }
    }

    // ── playback ───────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val track = Track.decode(data) ?: return false
        val enabled = ctx()?.let { MusicBoxPrefs.enabledSources(it) }
            ?: MusicBoxPrefs.sources.map { it.first }
        val ticket = ctx()?.let { MusicBoxPrefs.zarzTicket(it) }

        var found = false

        suspend fun emit(label: String, url: String, referer: String, hls: Boolean = false) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name · $label",
                    url = url,
                    type = if (hls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "User-Agent" to Sources.userAgent(),
                        "Accept" to "*/*"
                    )
                }
            )
            found = true
        }

        // 1. what the entry already carried. No network, so this cannot fail.
        track.preview?.let { emit("preview", it, "https://www.deezer.com/") }

        // 2. the entry's own service, at every quality it will answer for
        if (enabled.contains(sourceKey(track.source))) {
            when (track.source) {
                "sc" -> runCatching { Sources.soundcloudStream(track.id) }.getOrNull()
                    ?.let { emit("SoundCloud", it, "https://soundcloud.com/") }

                "td" -> runCatching { Sources.tidalPlays(track.id) }.getOrNull()?.forEach {
                    emit(it.label, it.url, it.referer, it.isHls)
                }

                "qb" -> runCatching { Sources.qobuzPlays(track.id) }.getOrNull()?.forEach {
                    emit(it.label, it.url, it.referer, it.isHls)
                }

                "dz" -> {
                    runCatching { Sources.deezerPreview(track.id) }.getOrNull()
                        ?.takeIf { it != track.preview }
                        ?.let { emit("Deezer preview", it, "https://www.deezer.com/") }
                    if (ticket != null) {
                        runCatching {
                            Sources.zarz("dz", track.id, "https://www.deezer.com/track/${track.id}", ticket)
                        }.getOrNull()?.let { emit("Deezer FLAC", it, "https://www.deezer.com/") }
                    }
                }

                "am" -> runCatching { Sources.applePreview(track.id) }.getOrNull()
                    ?.takeIf { it != track.preview }
                    ?.let { emit("Apple preview", it, "https://music.apple.com/") }
            }

            // the resolver, for the two services that cannot serve a file themselves
            if (ticket != null && track.source in listOf("qb", "td")) {
                val url = when (track.source) {
                    "qb" -> "https://play.qobuz.com/track/${track.id}"
                    else -> "https://tidal.com/browse/track/${track.id}"
                }
                runCatching { Sources.zarz(track.source, track.id, url, ticket) }.getOrNull()
                    ?.let { emit(if (track.source == "qb") "Qobuz FLAC" else "Tidal FLAC", it, url) }
            }
        }

        // 3. full audio from YouTube, which is the only source that always has one
        if (enabled.contains("youtube")) {
            val query = listOf(track.artist, track.title)
                .filter { it.isNotBlank() }
                .joinToString(" ")
            if (query.isNotBlank()) {
                runCatching { Sources.youtubeVideoId(query) }.getOrNull()?.let { videoId ->
                    val got = mutableListOf<ExtractorLink>()
                    runCatching {
                        loadExtractor(
                            "https://www.youtube.com/watch?v=$videoId",
                            "https://music.youtube.com/",
                            subtitleCallback
                        ) { got.add(it) }
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
            }
        }

        return found
    }

    private fun sourceKey(source: String): String = when (source) {
        "dz" -> "deezer"
        "qb" -> "qobuz"
        "td" -> "tidal"
        "sc" -> "soundcloud"
        "am" -> "apple"
        "sp" -> "spotify"
        else -> ""
    }

    companion object {
        /** set by the plugin, for the settings and the challenge cookies */
        @Volatile
        var appContext: android.content.Context? = null
    }
}
