package com.watchuu.musicbox

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * MusicBox — Multi-Catalogue & Multi-Provider CloudStream Music Extension.
 *
 * Catalogues supported:
 *  - All Sources (Merged)
 *  - JioSaavn
 *  - Spotify (REST /v1 + Pathfinder + Embed __NEXT_DATA__)
 *  - Apple Music / iTunes
 *  - Deezer (REST + gw-light.php)
 *  - Tidal / Monochrome (tracks.monochrome.st + Tidal v1)
 *  - Qobuz (REST v0.2 + GDStudio)
 *  - SoundCloud (API v2 + MP3Juice)
 *  - YouTube Music (InnerTube WEB_REMIX + GDStudio)
 *  - Jamendo (API v1 with x-jam-call)
 *  - GDStudio Multi-Source Aggregator
 *
 * Playback / Download Providers (powered by CharlesPikachu/musicdl + Monochrome + ZARZ):
 *  1. JioSaavn (Direct 320kbps DES-decrypted & generateAuthToken)
 *  2. Tidal / Monochrome (Direct Lossless FLAC from tracks.monochrome.st + GDStudio + ZARZ)
 *  3. Deezer (musicdl: AnandServer FLAC, Deemixer, MusicFab, DeezDownloaders, Antra Hoshi, ZARZ)
 *  4. Qobuz (musicdl: AnandServer FLAC, DezAlty, GDStudio, ZARZ)
 *  5. Spotify Direct (musicdl: SpotiDown, RapidAPI, MusicFab, SpotSaver, SpotifyDownloads)
 *  6. Amazon Music (ZARZ FLAC)
 *  7. SoundCloud (Direct Progressive/HLS + MP3Juice ThetaCloud)
 *  8. GDStudio Aggregator (musicdl custom MD5 signing across Netease, Kuwo, Joox, Tidal, Qobuz, YTMusic)
 *  9. Jamendo (Direct FLAC & 320kbps MP3)
 *  10. YouTube Music (musicdl: Y2Mate/cnv.cx, CnvMP3, MediaYTMP3/Convert1s, MP3Juice, CloudStream Extractor, ZARZ Cobalt)
 */
class MusicBoxProvider : MainAPI() {
    override var mainUrl = "https://api.deezer.com"
    override var name = "MusicBox"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Music, TvType.Others)

    companion object {
        private const val TAG = "MusicBox"
        private const val PREF_KEY_SESSION = "musicbox_zarz_session"
        private const val PREF_KEY_CATALOGUE = "musicbox_catalogue"

        private val trackCache = ConcurrentHashMap<String, MusicTrack>()
        private val albumCache = ConcurrentHashMap<String, MusicAlbum>()
    }

    private val zarzSession = ZarzSession()

    @Volatile
    private var pendingAuthUrl: String? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun loadSession() {
        try {
            val context = CloudStreamApp.context
            if (context != null) {
                MusicBoxSettings.init(context)
                val stored = context
                    .getSharedPreferences("musicbox_prefs", 0)
                    ?.getString(PREF_KEY_SESSION, null)
                zarzSession.loadState(stored)
            } else {
                zarzSession.loadState(null)
            }
        } catch (e: Exception) {
            MusicBoxLog.w(TAG, "Failed to load session: ${e.message}")
            zarzSession.loadState(null)
        }
    }

    private fun saveSession() {
        try {
            CloudStreamApp.context
                ?.getSharedPreferences("musicbox_prefs", 0)
                ?.edit()
                ?.putString(PREF_KEY_SESSION, zarzSession.saveState())
                ?.apply()
        } catch (e: Exception) {
            MusicBoxLog.w(TAG, "Failed to save session: ${e.message}")
        }
    }

    private fun getCatalogue(): String {
        return try {
            MusicBoxSettings.catalogue.ifBlank {
                CloudStreamApp.context
                    ?.getSharedPreferences("musicbox_prefs", 0)
                    ?.getString(PREF_KEY_CATALOGUE, "all") ?: "all"
            }
        } catch (_: Exception) {
            "all"
        }
    }

    // ========== Home Page ==========

    override val mainPage = mainPageOf(
        "chart" to "Top Charts",
        "trending" to "Trending Now",
        "new" to "New Releases",
        "bollywood" to "Bollywood & Indian Hits",
        "pop" to "Global Pop Hits",
        "hiphop" to "Hip-Hop & Phonk",
        "chill" to "Lo-Fi & Chill",
        "albums" to "Featured Albums",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
        loadSession()

        val cat = getCatalogue()
        val items = when (request.data) {
            "chart" -> getCatalogRow(cat, "top hits", isChart = true)
            "trending" -> getCatalogRow(cat, "trending hits 2025")
            "new" -> getCatalogRow(cat, "new music releases")
            "bollywood" -> getCatalogRow(cat, "bollywood top hits")
            "pop" -> getCatalogRow(cat, "pop hits")
            "hiphop" -> getCatalogRow(cat, "hip hop phonk")
            "chill" -> getCatalogRow(cat, "lofi chill beats")
            "albums" -> getFeaturedAlbumsRow(cat, "top albums")
            else -> emptyList()
        }

        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = false,
        )
    }

    private suspend fun getCatalogRow(catalogue: String, query: String, isChart: Boolean = false): List<SearchResponse> {
        val primary = when (catalogue) {
            "jiosaavn" -> if (isChart) {
                runCatching { JioSaavnApi.getChart(20).map { it.toSearchResponse() } }.getOrDefault(emptyList())
            } else {
                searchJioSaavnTracksOnly(query)
            }
            "spotify" -> searchSpotifyTracksOnly(query)
            "apple" -> if (isChart) {
                runCatching { AppleMusicApi.getChart("in", 20).map { it.toSearchResponse() } }.getOrDefault(emptyList())
            } else {
                searchAppleTracksOnly(query)
            }
            "deezer" -> if (isChart) {
                runCatching { DeezerApi.getChart(20).map { it.toSearchResponse() } }.getOrDefault(emptyList())
            } else {
                searchDeezerTracksOnly(query)
            }
            "tidal" -> searchTidalTracksOnly(query)
            "qobuz" -> searchQobuz(query)
            "soundcloud" -> searchSoundCloud(query)
            "ytmusic" -> searchYouTubeMusic(query)
            "jamendo" -> searchJamendo(query)
            "gdstudio" -> searchGDStudio(query)
            else -> {
                // "all" mode: blend fast catalogues based on row type
                val out = LinkedHashMap<String, SearchResponse>()
                if (isChart) {
                    runCatching { JioSaavnApi.getChart(12).forEach { out[it.id] = it.toSearchResponse() } }
                    runCatching { AppleMusicApi.getChart("in", 12).forEach { out[it.id] = it.toSearchResponse() } }
                    runCatching { DeezerApi.getChart(10).forEach { out[it.id] = it.toSearchResponse() } }
                } else {
                    val lists = listOf("jiosaavn", "tidal", "apple", "spotify", "deezer").amap { src ->
                        when (src) {
                            "jiosaavn" -> searchJioSaavnTracksOnly(query, 8)
                            "tidal" -> searchTidalTracksOnly(query, 8)
                            "apple" -> searchAppleTracksOnly(query, 8)
                            "spotify" -> searchSpotifyTracksOnly(query, 6)
                            "deezer" -> searchDeezerTracksOnly(query, 6)
                            else -> emptyList()
                        }
                    }
                    lists.flatten().forEach { out[it.url] = it }
                }
                out.values.toList()
            }
        }

        if (primary.isNotEmpty()) return primary

        // Resilient multi-tier fallback so no home row is ever blank
        val saavnFallback = searchJioSaavnTracksOnly(query, 15)
        if (saavnFallback.isNotEmpty()) return saavnFallback

        val appleFallback = searchAppleTracksOnly(query, 15)
        if (appleFallback.isNotEmpty()) return appleFallback

        return searchTidalTracksOnly(query, 15)
    }

    private suspend fun getFeaturedAlbumsRow(catalogue: String, query: String): List<SearchResponse> {
        val albums = when (catalogue) {
            "jiosaavn" -> runCatching { JioSaavnApi.searchAlbums(query, 15).map { it.toSearchResponse() } }.getOrDefault(emptyList())
            "spotify" -> runCatching { SpotifyClient.search(query, 15).second.map { it.toSearchResponse() } }.getOrDefault(emptyList())
            "apple" -> runCatching { AppleMusicApi.searchAlbums(query, 15).map { it.toSearchResponse() } }.getOrDefault(emptyList())
            "deezer" -> runCatching { DeezerApi.searchAlbums(query, 15).map { it.toSearchResponse() } }.getOrDefault(emptyList())
            "tidal" -> runCatching { TidalApi.searchAlbums(query, 15).map { it.toSearchResponse() } }.getOrDefault(emptyList())
            else -> {
                val merged = LinkedHashMap<String, SearchResponse>()
                val parts = listOf("jiosaavn", "tidal", "apple", "deezer").amap { src ->
                    when (src) {
                        "jiosaavn" -> runCatching { JioSaavnApi.searchAlbums("bollywood hits", 8).map { it.toSearchResponse() } }.getOrDefault(emptyList())
                        "tidal" -> runCatching { TidalApi.searchAlbums("hits", 8).map { it.toSearchResponse() } }.getOrDefault(emptyList())
                        "apple" -> runCatching { AppleMusicApi.searchAlbums("top hits", 8).map { it.toSearchResponse() } }.getOrDefault(emptyList())
                        "deezer" -> runCatching { DeezerApi.searchAlbums("hits", 8).map { it.toSearchResponse() } }.getOrDefault(emptyList())
                        else -> emptyList()
                    }
                }
                parts.flatten().forEach { merged[it.url] = it }
                merged.values.toList()
            }
        }
        return albums.ifEmpty {
            runCatching { AppleMusicApi.searchAlbums("top hits", 15).map { it.toSearchResponse() } }.getOrDefault(emptyList())
        }
    }

    // ========== Search ==========

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        loadSession()

        val catalogue = getCatalogue()
        val primary = when (catalogue) {
            "jiosaavn" -> searchJioSaavn(query)
            "spotify" -> searchSpotify(query)
            "apple" -> searchApple(query)
            "deezer" -> searchDeezer(query)
            "tidal" -> searchTidal(query)
            "qobuz" -> searchQobuz(query)
            "soundcloud" -> searchSoundCloud(query)
            "ytmusic" -> searchYouTubeMusic(query)
            "jamendo" -> searchJamendo(query)
            "gdstudio" -> searchGDStudio(query)
            else -> {
                // Parallel search across all major catalogues
                val merged = LinkedHashMap<String, SearchResponse>()
                val groups = listOf(
                    "jiosaavn",
                    "tidal",
                    "spotify",
                    "apple",
                    "deezer",
                    "qobuz",
                    "soundcloud",
                    "ytmusic",
                ).amap { src ->
                    when (src) {
                        "jiosaavn" -> searchJioSaavn(query, 10)
                        "tidal" -> searchTidal(query, 10)
                        "spotify" -> searchSpotify(query, 8)
                        "apple" -> searchApple(query, 8)
                        "deezer" -> searchDeezer(query, 8)
                        "qobuz" -> searchQobuz(query, 6)
                        "soundcloud" -> searchSoundCloud(query, 6)
                        "ytmusic" -> searchYouTubeMusic(query, 5)
                        else -> emptyList()
                    }
                }
                groups.flatten().forEach { merged[it.url] = it }
                merged.values.toList()
            }
        }

        if (primary.isNotEmpty()) return primary

        // Fallback chain if selected catalogue was unreachable
        val saavn = searchJioSaavn(query, 15)
        if (saavn.isNotEmpty()) return saavn
        val apple = searchApple(query, 15)
        if (apple.isNotEmpty()) return apple
        return searchTidal(query, 15)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private suspend fun searchJioSaavnTracksOnly(query: String, limit: Int = 15): List<SearchResponse> =
        runCatching { JioSaavnApi.search(query, limit).map { it.toSearchResponse() } }.getOrDefault(emptyList())

    private suspend fun searchJioSaavn(query: String, limit: Int = 15): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        runCatching { JioSaavnApi.search(query, limit).mapTo(out) { it.toSearchResponse() } }
        runCatching { JioSaavnApi.searchAlbums(query, 6).mapTo(out) { it.toSearchResponse() } }
        return out
    }

    private suspend fun searchSpotifyTracksOnly(query: String, limit: Int = 15): List<SearchResponse> {
        return try {
            val (tracks, _, _) = SpotifyClient.search(query, limit)
            if (tracks.isNotEmpty()) tracks.map { it.toSearchResponse() }
            else searchAppleTracksOnly(query, limit)
        } catch (e: Exception) {
            MusicBoxLog.w(TAG, "Spotify track search failed: ${e.message}")
            searchAppleTracksOnly(query, limit)
        }
    }

    private suspend fun searchSpotify(query: String, limit: Int = 15): List<SearchResponse> {
        return try {
            val (tracks, albums, _) = SpotifyClient.search(query, limit)
            val results = mutableListOf<SearchResponse>()
            results.addAll(tracks.map { it.toSearchResponse() })
            results.addAll(albums.map { it.toSearchResponse() })
            if (results.isNotEmpty()) results else searchApple(query, limit)
        } catch (e: Exception) {
            MusicBoxLog.w(TAG, "Spotify search failed, falling back: ${e.message}")
            searchApple(query, limit)
        }
    }

    private suspend fun searchAppleTracksOnly(query: String, limit: Int = 15): List<SearchResponse> =
        runCatching { AppleMusicApi.search(query, limit).map { it.toSearchResponse() } }.getOrDefault(emptyList())

    private suspend fun searchApple(query: String, limit: Int = 15): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        runCatching { AppleMusicApi.search(query, limit).mapTo(out) { it.toSearchResponse() } }
        runCatching { AppleMusicApi.searchAlbums(query, 6).mapTo(out) { it.toSearchResponse() } }
        return out
    }

    private suspend fun searchDeezerTracksOnly(query: String, limit: Int = 15): List<SearchResponse> =
        runCatching { DeezerApi.search(query, limit).map { it.toSearchResponse() } }.getOrDefault(emptyList())

    private suspend fun searchDeezer(query: String, limit: Int = 15): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        runCatching { DeezerApi.search(query, limit).mapTo(out) { it.toSearchResponse() } }
        runCatching { DeezerApi.searchAlbums(query, 6).mapTo(out) { it.toSearchResponse() } }
        return out
    }

    private suspend fun searchTidalTracksOnly(query: String, limit: Int = 15): List<SearchResponse> =
        runCatching { TidalApi.search(query, limit).map { it.toSearchResponse() } }.getOrDefault(emptyList())

    private suspend fun searchTidal(query: String, limit: Int = 15): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        runCatching { TidalApi.search(query, limit).mapTo(out) { it.toSearchResponse() } }
        runCatching { TidalApi.searchAlbums(query, 6).mapTo(out) { it.toSearchResponse() } }
        return out
    }

    private suspend fun searchQobuz(query: String, limit: Int = 15): List<SearchResponse> =
        runCatching { QobuzApi.search(query, limit).map { it.toSearchResponse() } }.getOrDefault(emptyList())

    private suspend fun searchSoundCloud(query: String, limit: Int = 15): List<SearchResponse> =
        runCatching { SoundCloudApi.search(query, limit).map { it.toSearchResponse() } }.getOrDefault(emptyList())

    private suspend fun searchYouTubeMusic(query: String, limit: Int = 15): List<SearchResponse> =
        runCatching { YouTubeMusicApi.search(query, limit).map { it.toSearchResponse() } }.getOrDefault(emptyList())

    private suspend fun searchJamendo(query: String, limit: Int = 15): List<SearchResponse> =
        runCatching { JamendoApi.search(query, limit).map { it.toSearchResponse() } }.getOrDefault(emptyList())

    private suspend fun searchGDStudio(query: String, limit: Int = 15): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        for (src in listOf("netease", "tidal", "qobuz", "kuwo", "ytmusic")) {
            runCatching {
                GDStudioApi.search(query, src, limit / 2).mapTo(out) { it.toSearchResponse() }
            }
            if (out.size >= limit) break
        }
        return out.take(limit)
    }

    // ========== Load (Track & Album Detail Pages) ==========

    override suspend fun load(url: String): LoadResponse? {
        loadSession()

        val parts = url.removePrefix("$mainUrl/").split("/", limit = 4)
        if (parts.size < 3) return null

        val kind = parts[0] // "track" or "album"
        val source = parts[1]
        val id = parts[2].decodeUrl()
        val extra = parts.getOrNull(3)?.split("|") ?: emptyList()

        return when (kind) {
            "track" -> loadTrack(source, id, extra, url)
            "album" -> loadAlbum(source, id, extra, url)
            else -> null
        }
    }

    private suspend fun loadTrack(source: String, id: String, extra: List<String>, rawUrl: String): LoadResponse? {
        val cached = trackCache["$source:$id"]
        val urlTitle = extra.getOrNull(0)?.decodeUrl()?.ifBlank { null }
        val urlArtist = extra.getOrNull(1)?.decodeUrl()?.ifBlank { null }
        val urlCover = extra.getOrNull(2)?.decodeUrl()?.ifBlank { null }
        val urlAlbum = extra.getOrNull(3)?.decodeUrl()?.ifBlank { null }
        val urlDuration = extra.getOrNull(4)?.toIntOrNull() ?: 0
        val urlIsrc = extra.getOrNull(5)?.decodeUrl()?.ifBlank { null }

        // Fetch source-specific metadata if not cached or missing cover/duration
        val fetched: MusicTrack? = if (cached != null && cached.coverUrl.isNotBlank()) {
            cached
        } else {
            when (source) {
                "jiosaavn" -> JioSaavnApi.getTrackById(id)
                "deezer" -> DeezerApi.getTrack(id)
                "apple" -> AppleMusicApi.getTrack(id)
                "spotify" -> SpotifyClient.getTrack(id)?.let {
                    MusicTrack(
                        id = "spotify:${it.id}",
                        title = it.name,
                        artist = it.artist,
                        album = it.album,
                        duration = it.duration / 1000,
                        coverUrl = it.coverUrl,
                        source = "spotify",
                        sourceId = it.id,
                        previewUrl = it.previewUrl,
                        isrc = it.isrc,
                    )
                }
                "soundcloud" -> SoundCloudApi.getTrack(id)
                "jamendo" -> JamendoApi.getTrack(id)
                else -> cached
            }
        }

        var finalTitle = fetched?.title?.ifBlank { null } ?: urlTitle ?: cached?.title ?: "Unknown Track"
        var finalArtist = fetched?.artist?.ifBlank { null } ?: urlArtist ?: cached?.artist ?: ""
        var finalAlbum = fetched?.album?.ifBlank { null } ?: urlAlbum ?: cached?.album ?: ""
        var coverUrl = fetched?.coverUrl?.ifBlank { null } ?: urlCover ?: cached?.coverUrl ?: ""
        var duration = fetched?.duration?.takeIf { it > 0 } ?: urlDuration.takeIf { it > 0 } ?: cached?.duration ?: 0
        var isrc = fetched?.isrc?.ifBlank { null } ?: urlIsrc ?: cached?.isrc ?: ""
        val previewUrl = fetched?.previewUrl?.ifBlank { null } ?: cached?.previewUrl ?: ""

        // Cross-reference with Apple Music or Deezer if cover art is still missing
        if (coverUrl.isBlank()) {
            val lookupQuery = "$finalTitle $finalArtist".trim()
            val metaMatch = runCatching { AppleMusicApi.search(lookupQuery, 1).firstOrNull() }.getOrNull()
                ?: runCatching { DeezerApi.search(lookupQuery, 1).firstOrNull() }.getOrNull()
            if (metaMatch != null) {
                if (coverUrl.isBlank()) coverUrl = metaMatch.coverUrl
                if (finalAlbum.isBlank()) finalAlbum = metaMatch.album
                if (duration == 0) duration = metaMatch.duration
                if (isrc.isBlank()) isrc = metaMatch.isrc
            }
        }

        val playData = JSONObject().apply {
            put("source", source)
            put("id", id)
            put("title", finalTitle)
            put("artist", finalArtist)
            put("album", finalAlbum)
            put("deezer_id", if (source == "deezer") id else "")
            put("isrc", isrc)
            put("preview_url", previewUrl)
        }.toString()

        val displayTitle = if (finalArtist.isNotBlank()) "$finalTitle - $finalArtist" else finalTitle

        return newMovieLoadResponse(
            displayTitle,
            rawUrl,
            TvType.Music,
            playData,
        ) {
            this.posterUrl = coverUrl
            this.backgroundPosterUrl = coverUrl
            this.plot = buildString {
                if (finalArtist.isNotBlank()) append("Artist: $finalArtist\n")
                if (finalAlbum.isNotBlank()) append("Album: $finalAlbum\n")
                if (duration > 0) append("Duration: ${duration / 60}:${"%02d".format(duration % 60)}\n")
                append("Catalogue: ${source.uppercase()}")
            }
            this.tags = listOfNotNull(
                source.uppercase(),
                finalAlbum.ifBlank { null },
            )
        }
    }

    private suspend fun loadAlbum(source: String, albumId: String, extra: List<String>, rawUrl: String): LoadResponse? {
        val cachedAlbum = albumCache["$source:$albumId"]
        val albumTitle = extra.getOrNull(0)?.decodeUrl()?.ifBlank { null } ?: cachedAlbum?.title ?: "Album"
        val albumArtist = extra.getOrNull(1)?.decodeUrl()?.ifBlank { null } ?: cachedAlbum?.artist ?: ""
        val albumCover = extra.getOrNull(2)?.decodeUrl()?.ifBlank { null } ?: cachedAlbum?.coverUrl ?: ""

        var tracks: List<MusicTrack> = when (source) {
            "deezer" -> DeezerApi.getAlbumTracks(albumId)
            "jiosaavn" -> JioSaavnApi.getAlbumTracks(albumId)
            "apple" -> AppleMusicApi.getAlbumTracks(albumId)
            "tidal" -> TidalApi.getAlbumTracks(albumId)
            "qobuz" -> QobuzApi.getAlbumTracks(albumId)
            "soundcloud" -> SoundCloudApi.getPlaylistTracks(albumId)
            "spotify" -> SpotifyClient.getAlbumTracks(albumId).map {
                MusicTrack(
                    id = "spotify:${it.id}",
                    title = it.name,
                    artist = it.artist.ifBlank { albumArtist },
                    album = it.album.ifBlank { albumTitle },
                    duration = it.duration / 1000,
                    coverUrl = it.coverUrl.ifBlank { albumCover },
                    source = "spotify",
                    sourceId = it.id,
                    previewUrl = it.previewUrl,
                    isrc = it.isrc,
                )
            }
            else -> emptyList()
        }

        // Universal fallback: if source-specific album endpoint was blocked or empty, search tracks matching the album
        if (tracks.isEmpty()) {
            val fallbackQuery = "$albumTitle $albumArtist".trim()
            tracks = when (source) {
                "jiosaavn" -> JioSaavnApi.search(fallbackQuery, 15)
                "apple" -> AppleMusicApi.search(fallbackQuery, 15)
                "tidal" -> TidalApi.search(fallbackQuery, 15)
                else -> emptyList()
            }.ifEmpty {
                JioSaavnApi.search(fallbackQuery, 15).ifEmpty {
                    AppleMusicApi.search(fallbackQuery, 15)
                }
            }
        }

        if (tracks.isEmpty()) return null

        val resolvedCover = albumCover.ifBlank { tracks.firstOrNull()?.coverUrl ?: "" }
        val resolvedArtist = albumArtist.ifBlank { tracks.firstOrNull()?.artist ?: "Various Artists" }

        val episodes = tracks.mapIndexed { index, track ->
            val epData = JSONObject().apply {
                put("source", track.source.ifBlank { source })
                put("id", track.sourceId)
                put("title", track.title)
                put("artist", track.artist.ifBlank { resolvedArtist })
                put("album", track.album.ifBlank { albumTitle })
                put("deezer_id", if (track.source == "deezer") track.sourceId else "")
                put("isrc", track.isrc)
                put("preview_url", track.previewUrl)
            }.toString()

            newEpisode(epData) {
                this.name = track.title
                this.season = 1
                this.episode = index + 1
                val durText = if (track.duration > 0) " • ${track.duration / 60}:${"%02d".format(track.duration % 60)}" else ""
                this.description = "${track.artist.ifBlank { resolvedArtist }}$durText"
                this.posterUrl = track.coverUrl.ifBlank { resolvedCover }
            }
        }

        return newTvSeriesLoadResponse(
            if (resolvedArtist.isNotBlank()) "$albumTitle - $resolvedArtist" else albumTitle,
            rawUrl,
            TvType.Music,
            episodes,
        ) {
            this.posterUrl = resolvedCover
            this.backgroundPosterUrl = resolvedCover
            this.plot = "Artist: $resolvedArtist\nTracks: ${tracks.size}\nCatalogue: ${source.uppercase()}"
        }
    }

    // ========== Load Links (Multi-Provider Playback) ==========

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        loadSession()

        val obj = try { JSONObject(data) } catch (_: Exception) { return false }
        val title = obj.optString("title", "")
        val artist = obj.optString("artist", "")
        val deezerId = obj.optString("deezer_id", "")
        val isrc = obj.optString("isrc", "")
        val source = obj.optString("source", "")
        val id = obj.optString("id", "")
        val storedPreview = obj.optString("preview_url", "")

        var emittedCount = 0
        pendingAuthUrl = null

        val emitLink: (ExtractorLink) -> Unit = { link ->
            emittedCount++
            callback.invoke(link)
        }

        runCatching { zarzSession.refreshIfNeeded() }
            .onFailure { MusicBoxLog.w(TAG, "Session refresh failed: ${it.message}") }
        saveSession()

        val searchQuery = "$title $artist".trim()

        // 0. If the track came from a specific source, try its native provider first with its exact ID!
        when (source) {
            "jiosaavn" -> tryJioSaavn(id, searchQuery, emitLink)
            "tidal" -> tryTidal(id, searchQuery, isrc, emitLink)
            "deezer" -> tryDeezer(id, searchQuery, emitLink)
            "qobuz" -> tryQobuz(id, searchQuery, emitLink)
            "spotify" -> trySpotify(id, searchQuery, emitLink)
            "soundcloud" -> trySoundCloud(id, searchQuery, emitLink)
            "jamendo" -> tryJamendo(id, searchQuery, emitLink)
            "gdstudio" -> tryGDStudio(id, searchQuery, emitLink)
            "ytmusic" -> tryYouTubeMusic(id, searchQuery, subtitleCallback, emitLink)
        }

        // 1. JioSaavn (Direct 320kbps MP3/M4A — fast, reliable in India & globally)
        if (source != "jiosaavn") {
            tryJioSaavn("", searchQuery, emitLink)
        }

        // 2. Tidal / Monochrome (Lossless FLAC via tracks.monochrome.st, GDStudio & ZARZ)
        if (source != "tidal") {
            tryTidal("", searchQuery, isrc, emitLink)
        }

        // 3. Deezer (CharlesPikachu/musicdl: AnandServer FLAC, Deemixer, MusicFab, DeezDownloaders, Antra, ZARZ)
        if (source != "deezer") {
            tryDeezer(deezerId, searchQuery, emitLink)
        }

        // 4. Qobuz (CharlesPikachu/musicdl: AnandServer FLAC, DezAlty, GDStudio, ZARZ)
        if (source != "qobuz") {
            tryQobuz("", searchQuery, emitLink)
        }

        // 5. Spotify Direct (CharlesPikachu/musicdl: SpotiDown, RapidAPI, MusicFab, SpotSaver, SpotifyDownloads)
        if (source != "spotify" && emittedCount < 3) {
            trySpotify("", searchQuery, emitLink)
        }

        // 6. GDStudio Multi-Source Aggregator (CharlesPikachu/musicdl: Netease, Kuwo, Joox, Tidal, Qobuz, YTMusic)
        if (source != "gdstudio" && emittedCount < 3) {
            tryGDStudio("", searchQuery, emitLink)
        }

        // 7. SoundCloud (Direct Progressive/HLS + MP3Juice)
        if (source != "soundcloud" && emittedCount < 3) {
            trySoundCloud("", searchQuery, emitLink)
        }

        // 8. Jamendo (Direct FLAC & 320kbps MP3)
        if (source != "jamendo" && emittedCount < 2) {
            tryJamendo("", searchQuery, emitLink)
        }

        // 9. Amazon Music (ZARZ signed session - only if session is already usable or nothing found yet)
        if (zarzSession.record.isUsable() || emittedCount == 0) {
            tryAmazonMusic(searchQuery, isrc, emitLink)
        }

        // 10. YouTube Music (CharlesPikachu/musicdl: Y2Mate, CnvMP3, YTMP3, MP3Juice, CloudStream Extractor, Cobalt)
        if (source != "ytmusic" && emittedCount < 3) {
            tryYouTubeMusic("", searchQuery, subtitleCallback, emitLink)
        }

        // 11. Fallback 30-second Previews if all full-length providers failed
        if (emittedCount == 0) {
            if (storedPreview.startsWith("http")) {
                emitLink(
                    newExtractorLink("MusicBox", "${source.uppercase()} Preview", storedPreview, ExtractorLinkType.VIDEO) {
                        this.quality = 128
                    }
                )
            }
            if (emittedCount == 0) {
                tryApplePreview(searchQuery, emitLink)
            }
            if (emittedCount == 0 && deezerId.isNotBlank()) {
                tryDeezerPreview(deezerId, emitLink)
            }
        }

        if (emittedCount == 0) {
            pendingAuthUrl?.let { offerVerification(it) }
        }

        return emittedCount > 0
    }

    private fun offerVerification(authUrl: String) {
        try {
            val activity = CommonActivity.activity ?: return
            MusicBoxBrowser.openVerification(activity, authUrl) { grant ->
                scope.launch {
                    try {
                        zarzSession.exchange(grant)
                        saveSession()
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(
                                activity,
                                "MusicBox: ZARZ resolver verified - press play again",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    } catch (e: Exception) {
                        MusicBoxLog.w(TAG, "Grant exchange failed: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            MusicBoxLog.w(TAG, "Could not open verification: ${e.message}")
        }
    }

    // ========== Per-Provider Playback Methods ==========

    private suspend fun tryJioSaavn(
        saavnId: String,
        query: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (!MusicBoxSettings.enableJioSaavn) return false
        try {
            val streamUrl = if (saavnId.isNotBlank()) {
                JioSaavnApi.getStreamUrl(saavnId, "320")
            } else {
                val first = JioSaavnApi.search(query, 1).firstOrNull()
                if (first != null && first.sourceId.isNotBlank()) {
                    JioSaavnApi.getStreamUrl(first.sourceId, "320").ifBlank { first.previewUrl }
                } else ""
            }
            if (streamUrl.startsWith("http")) {
                callback.invoke(
                    newExtractorLink("MusicBox", "JioSaavn 320kbps", streamUrl, ExtractorLinkType.VIDEO) {
                        this.quality = 320
                        this.referer = "https://www.jiosaavn.com/"
                    }
                )
                return true
            }
        } catch (e: Exception) {
            MusicBoxLog.d(TAG, "JioSaavn failed: ${e.message}")
        }
        return false
    }

    private suspend fun tryTidal(
        tidalId: String,
        query: String,
        isrc: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (!MusicBoxSettings.enableTidal) return false
        var added = false

        // 1. Monochrome / GDStudio Tidal lossless FLAC streams
        try {
            val streams = TidalApi.resolveStreams(tidalId, query)
            for (s in streams) {
                callback.invoke(
                    newExtractorLink(
                        "MusicBox",
                        s.name,
                        s.url,
                        if (s.isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                    ) {
                        this.quality = s.quality
                        this.referer = s.referer
                        this.headers = s.headers
                    }
                )
                added = true
            }
        } catch (e: Exception) {
            MusicBoxLog.d(TAG, "Tidal Monochrome/GDStudio failed: ${e.message}")
        }

        // 2. Optional ZARZ Tidal resolver if session is active
        if (!added && zarzSession.record.isUsable()) {
            try {
                val targetId = tidalId.ifBlank {
                    TidalApi.search(query, 1).firstOrNull()?.sourceId ?: ""
                }
                if (targetId.isNotBlank()) {
                    val descriptor = zarzSession.resolveTidalDownload(targetId)
                    saveSession()
                    // TIDAL answers a base64 dash manifest, not a link. Decode it and take the
                    // audio url; a PREVIEW asset is rejected outright, as musicdl does.
                    val url = extractDownloadUrl(descriptor)
                        ?: zarzSession.tidalPlayableUrl(descriptor)
                    if (url != null && url.startsWith("http")) {
                        val isHls = url.contains(".m3u8", true)
                        callback.invoke(
                            newExtractorLink(
                                "MusicBox",
                                "Tidal · ZARZ FLAC",
                                url,
                                if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                            ) {
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        added = true
                    }
                }
            } catch (e: ZarzSession.VerificationRequiredException) {
                pendingAuthUrl = e.authUrl
                saveSession()
            } catch (e: Exception) {
                MusicBoxLog.d(TAG, "Tidal ZARZ failed: ${e.message}")
            }
        }

        return added
    }

    private suspend fun tryDeezer(
        deezerId: String,
        query: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (!MusicBoxSettings.enableDeezerDownload) return false
        var added = false

        val trackId = deezerId.ifBlank {
            runCatching { DeezerApi.search(query, 1).firstOrNull()?.sourceId ?: "" }.getOrDefault("")
        }
        if (trackId.isBlank()) return false

        // 1. CharlesPikachu/musicdl Deezer resolvers (AnandServer, Deemixer, MusicFab, DeezDownloaders, Antra)
        try {
            val streams = DeezerApi.resolveStreams(trackId)
            for (s in streams) {
                callback.invoke(
                    newExtractorLink("MusicBox", s.name, s.url, ExtractorLinkType.VIDEO) {
                        this.quality = s.quality
                        this.referer = s.referer
                        this.headers = s.headers
                    }
                )
                added = true
            }
        } catch (e: Exception) {
            MusicBoxLog.d(TAG, "Deezer musicdl resolvers failed: ${e.message}")
        }

        // 2. ZARZ Deezer resolver
        if (!added && zarzSession.record.isUsable()) {
            try {
                val descriptor = zarzSession.resolveDownloadDescriptor(trackId)
                saveSession()
                // an error envelope or a Blowfish-encrypted stream is not playable, and handing
                // one to the player is how deezer tracks used to fail after "succeeding"
                if (descriptor.optBoolean("success", true).not() ||
                    descriptor.has("error") || descriptor.has("detail") ||
                    zarzSession.requiresClientDecryption(descriptor)
                ) {
                    MusicBoxLog.d(TAG, "Deezer ZARZ: unusable descriptor (encrypted or error)")
                } else {
                val sources = descriptor.optJSONArray("sources")
                if (sources != null && sources.length() > 0) {
                    for (i in 0 until sources.length()) {
                        val src = sources.optJSONObject(i) ?: continue
                        val url = src.optString("url", "")
                        val format = src.optString("format", "")
                        val quality = src.optString("quality", "")
                        if (url.isBlank()) continue
                        val qualityLabel = when {
                            format.contains("flac", true) -> "FLAC"
                            quality.contains("320", true) -> "320kbps"
                            quality.contains("128", true) -> "128kbps"
                            else -> quality.ifBlank { format }
                        }
                        callback.invoke(
                            newExtractorLink("MusicBox", "Deezer · ZARZ $qualityLabel", url, ExtractorLinkType.VIDEO) {
                                this.quality = 320
                            }
                        )
                        added = true
                    }
                } else {
                    val cdnUrl = zarzSession.descriptorUrl(descriptor)
                        ?: descriptor.optString("url", "")
                    if (cdnUrl.isNotBlank()) {
                        callback.invoke(
                            newExtractorLink("MusicBox", "Deezer · ZARZ CDN", cdnUrl, ExtractorLinkType.VIDEO) {
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        added = true
                    }
                }
                }
            } catch (e: ZarzSession.VerificationRequiredException) {
                pendingAuthUrl = e.authUrl
                saveSession()
            } catch (e: Exception) {
                MusicBoxLog.d(TAG, "Deezer ZARZ failed: ${e.message}")
            }
        }

        return added
    }

    private suspend fun tryQobuz(
        qobuzId: String,
        query: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (!MusicBoxSettings.enableQobuz) return false
        var added = false

        try {
            val streams = QobuzApi.resolveStreams(qobuzId, query)
            for (s in streams) {
                callback.invoke(
                    newExtractorLink("MusicBox", s.name, s.url, ExtractorLinkType.VIDEO) {
                        this.quality = s.quality
                        this.referer = s.referer
                        this.headers = s.headers
                    }
                )
                added = true
            }
        } catch (e: Exception) {
            MusicBoxLog.d(TAG, "Qobuz musicdl failed: ${e.message}")
        }

        if (!added && zarzSession.record.isUsable()) {
            try {
                val targetId = qobuzId.ifBlank {
                    QobuzApi.search(query, 1).firstOrNull()?.sourceId ?: ""
                }
                if (targetId.isNotBlank()) {
                    // musicdl's quality chain: hi-res-max, then hi-res, then cd
                    var descriptor: JSONObject? = null
                    for (quality in listOf(27, 7, 6)) {
                        val attempt = runCatching {
                            zarzSession.resolveQobuzDownload(targetId, quality)
                        }.getOrNull()
                        if (attempt != null && extractDownloadUrl(attempt) != null) {
                            descriptor = attempt
                            break
                        }
                    }
                    saveSession()
                    val url = descriptor?.let { extractDownloadUrl(it) }
                    if (url != null) {
                        callback.invoke(
                            newExtractorLink("MusicBox", "Qobuz · ZARZ FLAC", url, ExtractorLinkType.VIDEO) {
                                this.quality = 1411
                            }
                        )
                        added = true
                    }
                }
            } catch (e: ZarzSession.VerificationRequiredException) {
                pendingAuthUrl = e.authUrl
                saveSession()
            } catch (e: Exception) {
                MusicBoxLog.d(TAG, "Qobuz ZARZ failed: ${e.message}")
            }
        }

        return added
    }

    private suspend fun trySpotify(
        spotifyId: String,
        query: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (!MusicBoxSettings.enableSpotifyDownload) return false
        try {
            val targetId = spotifyId.ifBlank {
                runCatching { SpotifyClient.search(query, 1).first.firstOrNull()?.id ?: "" }.getOrDefault("")
            }
            if (targetId.isBlank()) return false

            val streams = SpotifyClient.resolveStreams(targetId)
            var added = false
            for (s in streams) {
                callback.invoke(
                    newExtractorLink("MusicBox", s.providerName, s.url, ExtractorLinkType.VIDEO) {
                        this.quality = s.quality
                        this.headers = s.headers
                    }
                )
                added = true
            }
            return added
        } catch (e: Exception) {
            MusicBoxLog.d(TAG, "Spotify direct failed: ${e.message}")
        }
        return false
    }

    private suspend fun tryGDStudio(
        gdstudioId: String,
        query: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (!MusicBoxSettings.enableGDStudio) return false
        try {
            if (gdstudioId.contains(":")) {
                val src = gdstudioId.substringBefore(":")
                val urlId = gdstudioId.substringAfter(":")
                val directUrl = GDStudioApi.getSongUrl(urlId, src, 999)
                if (directUrl.startsWith("http")) {
                    callback.invoke(
                        newExtractorLink("MusicBox", "GDStudio · ${src.uppercase()}", directUrl, ExtractorLinkType.VIDEO) {
                            this.quality = 999
                        }
                    )
                    return true
                }
            }
            val resolved = GDStudioApi.resolveBestStream(query)
            if (resolved != null) {
                callback.invoke(
                    newExtractorLink("MusicBox", resolved.name, resolved.url, ExtractorLinkType.VIDEO) {
                        this.quality = resolved.quality
                        this.headers = resolved.headers
                    }
                )
                return true
            }
        } catch (e: Exception) {
            MusicBoxLog.d(TAG, "GDStudio failed: ${e.message}")
        }
        return false
    }

    private suspend fun trySoundCloud(
        scId: String,
        query: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (!MusicBoxSettings.enableSoundCloud) return false
        try {
            val trackId = scId.ifBlank {
                SoundCloudApi.search(query, 1).firstOrNull()?.sourceId ?: ""
            }
            val streamUrl = if (trackId.isNotBlank()) {
                SoundCloudApi.getStreamUrl(trackId, query)
            } else {
                MP3JuiceApi.resolveSoundCloudStream(query)
            }
            if (streamUrl.startsWith("http")) {
                val isHls = streamUrl.contains(".m3u8", true)
                callback.invoke(
                    newExtractorLink(
                        "MusicBox",
                        "SoundCloud",
                        streamUrl,
                        if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                    ) {
                        this.quality = 160
                    }
                )
                return true
            }
        } catch (e: Exception) {
            MusicBoxLog.d(TAG, "SoundCloud failed: ${e.message}")
        }
        return false
    }

    private suspend fun tryJamendo(
        jamendoId: String,
        query: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (!MusicBoxSettings.enableJamendo) return false
        try {
            val streams = JamendoApi.resolveStreams(jamendoId, query)
            var added = false
            for (s in streams) {
                callback.invoke(
                    newExtractorLink("MusicBox", s.name, s.url, ExtractorLinkType.VIDEO) {
                        this.quality = s.quality
                        this.headers = s.headers
                    }
                )
                added = true
            }
            return added
        } catch (e: Exception) {
            MusicBoxLog.d(TAG, "Jamendo failed: ${e.message}")
        }
        return false
    }

    /**
     * Amazon is deliberately not attempted through the resolver.
     *
     * musicdl's zarz.py implements exactly three download clients - deezer, qobuz and tidal-web -
     * and its amazon.py goes through Amazon's own a2z skill api with a registered device. The
     * /dl/amazeamazeamaze path this used to call appears nowhere in either, so every attempt was a
     * guaranteed failure that still cost a round trip.
     */
    @Suppress("UNUSED_PARAMETER")
    private suspend fun tryAmazonMusic(
        query: String,
        isrc: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (!MusicBoxSettings.enableAmazonDownload) return false
        MusicBoxLog.d(TAG, "Amazon: no resolver endpoint exists (musicdl uses the a2z skill api) - skipped")
        return false
    }

    private suspend fun tryYouTubeMusic(
        ytId: String,
        query: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (!MusicBoxSettings.enableYouTubeFallback) return false
        var added = false
        try {
            val videoId = ytId.takeIf { it.length == 11 } ?: YouTubeMusicApi.searchVideoId(query)
            if (!videoId.isNullOrBlank()) {
                // 1. CharlesPikachu/musicdl YouTube converters (Y2Mate/cnv.cx, CnvMP3, YTMP3, MP3Juice)
                runCatching {
                    val streams = YouTubeMusicApi.resolveStreams(videoId, query)
                    for (s in streams) {
                        callback.invoke(
                            newExtractorLink("MusicBox", s.name, s.url, ExtractorLinkType.VIDEO) {
                                this.quality = s.quality
                                this.referer = s.referer
                                this.headers = s.headers
                            }
                        )
                        added = true
                    }
                }

                // 2. audio-only stream from YouTube's own player endpoint
                if (!added) {
                    runCatching { YouTubeMusicApi.audioOnlyStream(videoId) }.getOrNull()?.let { s ->
                        callback.invoke(
                            newExtractorLink("MusicBox", s.name, s.url, ExtractorLinkType.VIDEO) {
                                this.quality = s.quality
                                this.referer = s.referer
                                this.headers = s.headers
                            }
                        )
                        added = true
                    }
                }

                // 3. CloudStream built-in YouTube extractor, audio tracks only. It returns video
                //    streams too, and emitting those is what played a silent picture instead of a
                //    song - so a video-only link is dropped rather than offered.
                if (!added) {
                    val youtubeUrl = "https://music.youtube.com/watch?v=$videoId"
                    val viaExtractor = mutableListOf<ExtractorLink>()
                    runCatching {
                        loadExtractor(youtubeUrl, "https://music.youtube.com/", subtitleCallback) {
                            viaExtractor.add(it)
                        }
                    }
                    val audioOnly = viaExtractor.filter { link ->
                        val u = link.url.lowercase()
                        u.contains("mime=audio") || u.contains("audio%2f") || u.contains("mime%3daudio")
                    }
                    audioOnly.sortedByDescending { it.quality }.forEach { link ->
                        callback.invoke(
                            newExtractorLink(
                                "MusicBox",
                                "YouTube Music · ${link.name}",
                                link.url,
                                link.type,
                            ) {
                                this.quality = link.quality
                                this.referer = link.referer.ifBlank { "https://www.youtube.com/" }
                                this.headers = link.headers
                            }
                        )
                        added = true
                    }
                }

                // 4. ZARZ Cobalt v1 fallback
                if (!added) {
                    runCatching {
                        val cobalt = zarzSession.resolveYouTubeDownload(
                            "https://music.youtube.com/watch?v=$videoId"
                        )
                        // cobalt is asked for audio only; anything else is ignored
                        val url = cobalt.optString("url", "")
                        if (url.isNotBlank()) {
                            callback.invoke(
                                newExtractorLink("MusicBox", "YouTube Music · Cobalt", url, ExtractorLinkType.VIDEO) {
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            added = true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            MusicBoxLog.d(TAG, "YouTube Music failed: ${e.message}")
        }
        return added
    }

    private suspend fun tryApplePreview(
        query: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        return try {
            val track = AppleMusicApi.search(query, 5).firstOrNull() ?: return false
            val url = track.previewUrl.ifBlank { return false }
            callback.invoke(
                newExtractorLink("MusicBox", "Apple Music Preview (30s)", url, ExtractorLinkType.VIDEO) {
                    this.quality = 128
                    this.referer = "https://music.apple.com/"
                }
            )
            true
        } catch (e: Exception) {
            MusicBoxLog.w(TAG, "Apple preview failed: ${e.message}")
            false
        }
    }

    private suspend fun tryDeezerPreview(
        deezerId: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        try {
            val track = DeezerApi.getTrack(deezerId)
            if (track != null && track.previewUrl.isNotBlank()) {
                callback.invoke(
                    newExtractorLink("MusicBox", "Deezer Preview (30s)", track.previewUrl, ExtractorLinkType.VIDEO) {
                        this.quality = 128
                    }
                )
                return true
            }
        } catch (_: Exception) {}
        return false
    }

    private fun extractDownloadUrl(descriptor: JSONObject): String? {
        // musicdl's rules first: an error envelope, or a stream that needs client-side Blowfish
        // decryption, is not a playable url however tempting the field looks
        if (descriptor.optBoolean("success", true).not() ||
            descriptor.has("error") || descriptor.has("detail")
        ) {
            return null
        }
        if (zarzSession.requiresClientDecryption(descriptor)) return null
        zarzSession.descriptorUrl(descriptor)?.let { return it }

        val url = descriptor.optString("url", "")
        if (url.isNotBlank() && url.startsWith("http")) return url

        val sources = descriptor.optJSONArray("sources")
        if (sources != null && sources.length() > 0) {
            for (i in 0 until sources.length()) {
                val src = sources.optJSONObject(i) ?: continue
                val srcUrl = src.optString("url", "")
                if (srcUrl.isNotBlank()) return srcUrl
            }
        }

        val dlUrl = descriptor.optString("download_url", descriptor.optString("downloadUrl", ""))
        if (dlUrl.isNotBlank()) return dlUrl

        // deliberately not returning `manifest`: TIDAL sends a base64 dash manifest there, and
        // passing that to the player as a link is what made tidal tracks fail to start
        return null
    }

    // ========== Converters ==========

    private fun MusicTrack.toSearchResponse(): SearchResponse {
        trackCache["$source:$sourceId"] = this
        val extraPayload = listOf(
            title.encodeUrl(),
            artist.encodeUrl(),
            coverUrl.encodeUrl(),
            album.encodeUrl(),
            duration.toString(),
            isrc.encodeUrl(),
        ).joinToString("|")

        val displayTitle = if (artist.isNotBlank()) "$title - $artist" else title
        return newMovieSearchResponse(
            displayTitle,
            "$mainUrl/track/$source/${sourceId.encodeUrl()}/$extraPayload",
            TvType.Music,
        ) {
            this.posterUrl = coverUrl
        }
    }

    private fun SpotifyClient.SpotifyTrack.toSearchResponse(): SearchResponse {
        val mt = MusicTrack(
            id = "spotify:$id",
            title = name,
            artist = artist,
            album = album,
            duration = duration / 1000,
            coverUrl = coverUrl,
            source = "spotify",
            sourceId = id,
            previewUrl = previewUrl,
            isrc = isrc,
        )
        return mt.toSearchResponse()
    }

    private fun SpotifyClient.SpotifyAlbum.toSearchResponse(): SearchResponse {
        val ma = MusicAlbum(
            id = "spotify-album:$id",
            title = name,
            artist = artist,
            coverUrl = coverUrl,
            source = "spotify",
            year = year,
        )
        return ma.toSearchResponse()
    }

    private fun MusicAlbum.toSearchResponse(): SearchResponse {
        val cleanId = id.substringAfter(":")
        albumCache["$source:$cleanId"] = this
        val extraPayload = listOf(
            title.encodeUrl(),
            artist.encodeUrl(),
            coverUrl.encodeUrl(),
        ).joinToString("|")

        val displayTitle = if (artist.isNotBlank()) "$title - $artist" else title
        // read before the lambda: inside it `year` is the response builder's own Int? property,
        // which shadows this album's String and has no toIntOrNull()
        val albumYear = year.toIntOrNull()
        return newTvSeriesSearchResponse(
            displayTitle,
            "$mainUrl/album/$source/${cleanId.encodeUrl()}/$extraPayload",
            TvType.Music,
        ) {
            this.posterUrl = coverUrl
            this.year = albumYear
        }
    }

    private fun String.encodeUrl(): String = URLEncoder.encode(this, "UTF-8")
    private fun String.decodeUrl(): String = URLDecoder.decode(this, "UTF-8")
}
