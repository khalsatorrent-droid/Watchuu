package com.watchuu.musicbox

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
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

        /** how long any single provider gets before the others carry on without it */
        private const val PROVIDER_TIMEOUT_MS = 25_000L
        private const val PREF_KEY_CATALOGUE = "musicbox_catalogue"

        private val trackCache = ConcurrentHashMap<String, MusicTrack>()
        private val albumCache = ConcurrentHashMap<String, MusicAlbum>()
    }

    /**
     * One session per provider. The app scopes a signed session to
     * namespace + base_url + app_version + platform, so a session minted as deezer@1.2.0 cannot
     * sign a request declared as tidal-web@1.1.0 - the server answers 403, which is exactly what
     * the log showed while the settings screen said "active".
     */
    private val zarzDeezer get() = ZarzSessionManager.session(ZarzSession.APP_VERSION_DEEZER)
    private val zarzQobuz get() = ZarzSessionManager.session(ZarzSession.APP_VERSION_QOBUZ)
    private val zarzTidal get() = ZarzSessionManager.session(ZarzSession.APP_VERSION_TIDAL)
    private val zarzAmazon get() = ZarzSessionManager.session(ZarzSession.APP_VERSION_AMAZON)

    @Volatile
    private var pendingAuthUrl: String? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Providers whose upstream is currently down, and until when.
     *
     * Tidal answers 503 PROVIDER_UNAVAILABLE and Amazon's upstream fails; retrying both on every
     * play costs several seconds each time for a result that cannot change. A short cooldown keeps
     * the play fast, and the log still says the provider is being skipped and why.
     */
    private val upstreamDownUntil = ConcurrentHashMap<String, Long>()
    private fun upstreamDown(key: String) = (upstreamDownUntil[key] ?: 0L) > System.currentTimeMillis()
    private fun markUpstreamDown(key: String, seconds: Long = 600) {
        upstreamDownUntil[key] = System.currentTimeMillis() + seconds * 1000
    }

    private fun loadSession() {
        val context = CloudStreamApp.context ?: return
        runCatching { MusicBoxSettings.init(context) }
        ZarzSessionManager.versions.forEach { version ->
            runCatching { ZarzSessionManager.loadFromPrefs(version, context) }
                .onFailure { MusicBoxLog.w(TAG, "Session load failed for $version: ${it.message}") }
        }
    }

    private fun saveSession() {
        ZarzSessionManager.versions.forEach { version ->
            runCatching { ZarzSessionManager.persist(version) }
                .onFailure { MusicBoxLog.w(TAG, "Session save failed for $version: ${it.message}") }
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
            val (tracks, albums, artists) = SpotifyClient.search(query, limit)
            val results = mutableListOf<SearchResponse>()
            results.addAll(tracks.map { it.toSearchResponse() })
            results.addAll(albums.map { it.toSearchResponse() })
            results.addAll(artists.map { it.toSearchResponse() })
            results.addAll(SpotifyClient.searchPlaylists(query, 8).map { it.toSearchResponse() })
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
            "playlist" -> loadPlaylist(source, id, extra)
            "artist" -> loadArtist(source, id, extra)
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

    /** a playlist is an album-shaped series, but spotify needs its own query for the contents */
    private suspend fun loadPlaylist(source: String, playlistId: String, extra: List<String>): LoadResponse? {
        if (source != "spotify") return loadAlbum(source, playlistId, extra, "$mainUrl/playlist/$source/$playlistId")

        val name = extra.getOrNull(0)?.decodeUrl()?.ifBlank { null } ?: "Playlist"
        val owner = extra.getOrNull(1)?.decodeUrl().orEmpty()
        val cover = extra.getOrNull(2)?.decodeUrl().orEmpty()

        val tracks = runCatching { SpotifyClient.playlistTracks(playlistId) }.getOrDefault(emptyList())
        MusicBoxLog.d(TAG, "Playlist spotify/$playlistId -> ${tracks.size} tracks")
        if (tracks.isEmpty()) return null

        val episodes = tracks.mapIndexed { index, t ->
            val data = JSONObject().apply {
                put("source", "spotify")
                put("id", t.id)
                put("title", t.name)
                put("artist", t.artist)
                put("album", t.album)
                put("deezer_id", "")
                put("isrc", t.isrc)
                put("preview_url", t.previewUrl)
            }.toString()
            newEpisode(data) {
                this.name = t.name
                this.season = 1
                this.episode = index + 1
                this.posterUrl = t.coverUrl.ifBlank { cover }
            }
        }

        return newTvSeriesLoadResponse(
            if (owner.isNotBlank()) "$name · $owner" else name,
            "$mainUrl/playlist/$source/$playlistId/${name.encodeUrl()}",
            TvType.Music,
            episodes,
        ) {
            this.posterUrl = cover.ifBlank { tracks.firstOrNull()?.coverUrl }
            this.plot = "${tracks.size} tracks" + if (owner.isNotBlank()) " · $owner" else ""
        }
    }

    /** an artist page: their best known tracks as episodes */
    private suspend fun loadArtist(source: String, artistId: String, extra: List<String>): LoadResponse? {
        val name = extra.getOrNull(0)?.decodeUrl()?.ifBlank { null } ?: "Artist"
        if (source != "spotify") return null

        val tracks = runCatching { SpotifyClient.artistTracks(name, 25) }.getOrDefault(emptyList())
        MusicBoxLog.d(TAG, "Artist spotify/$artistId -> ${tracks.size} tracks")
        if (tracks.isEmpty()) return null

        val episodes = tracks.mapIndexed { index, t ->
            val data = JSONObject().apply {
                put("source", "spotify")
                put("id", t.id)
                put("title", t.name)
                put("artist", t.artist)
                put("album", t.album)
                put("deezer_id", "")
                put("isrc", t.isrc)
                put("preview_url", t.previewUrl)
            }.toString()
            newEpisode(data) {
                this.name = t.name
                this.season = 1
                this.episode = index + 1
                this.posterUrl = t.coverUrl
            }
        }

        return newTvSeriesLoadResponse(
            name,
            "$mainUrl/artist/$source/$artistId/${name.encodeUrl()}",
            TvType.Music,
            episodes,
        ) {
            this.posterUrl = tracks.firstOrNull()?.coverUrl
            this.plot = "${tracks.size} tracks by $name"
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

        val emitted = java.util.concurrent.atomic.AtomicInteger(0)
        fun emittedCount(): Int = emitted.get()
        pendingAuthUrl = null

        val emitLink: (ExtractorLink) -> Unit = { link ->
            emitted.incrementAndGet()
            MusicBoxLog.d(TAG, "link: ${link.name} -> ${hostOf(link.url)}")
            callback.invoke(link)
        }

        ZarzSessionManager.versions.forEach { v ->
            runCatching { ZarzSessionManager.session(v).refreshIfNeeded() }
                .onFailure { MusicBoxLog.w(TAG, "Session refresh failed for $v: ${it.message}") }
        }

        val resolverState = ZarzSessionManager.versions
            .filter { ZarzSessionManager.isUsable(it) }
            .joinToString(",") { ZarzSessionManager.label(it) }
            .ifBlank { "not active" }
        MusicBoxLog.d(
            TAG,
            "play ${source.ifBlank { "?" }}/$id \"${title.take(40)}\" - resolver $resolverState"
        )
        saveSession()

        val cleanTitle = title
            .replace(
                Regex(
                    """\s*[\(\[]\s*(?:from|feat\.?|ft\.?|featuring|with|prod\.?)[^)\]]*[\)\]]""",
                    RegexOption.IGNORE_CASE
                ),
                " "
            )
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
            .ifBlank { title }
        val primaryArtist = artist.split(Regex("""\s*[,;/&]\s*"""))
            .firstOrNull().orEmpty().trim()
        val searchQuery = listOf(cleanTitle, primaryArtist)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()

        // Every enabled provider runs at once.
        //
        // The chain used to be sequential and gated - each step only ran if the previous ones had
        // produced nothing, or fewer than three links - so one play cost the sum of every source's
        // latency (46 seconds in the log: first link at +15s, done at +46s) and the lossless
        // sources were skipped entirely once three MP3 links existed. Running them together makes
        // the wait the slowest single source, and lets the FLAC providers contribute alongside the
        // 320kbps ones instead of behind them.
        coroutineScope {
            val jobs = mutableListOf<Deferred<Boolean>>()

            val fire: (suspend () -> Boolean) -> Unit = { block ->
                jobs += async {
                    // one hanging host must not hold the rest
                    withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                        runCatching { block() }.getOrDefault(false)
                    } ?: false
                }
            }

            // the entry's own service, with its exact id, alongside everything else
            when (source) {
                "jiosaavn" -> fire { tryJioSaavn(id, searchQuery, emitLink) }
                "tidal" -> fire { tryTidal(id, searchQuery, isrc, emitLink) }
                "deezer" -> fire { tryDeezer(id, searchQuery, emitLink) }
                "qobuz" -> fire { tryQobuz(id, searchQuery, emitLink) }
                "spotify" -> fire { trySpotify(id, searchQuery, emitLink) }
                "soundcloud" -> fire { trySoundCloud(id, searchQuery, emitLink) }
                "jamendo" -> fire { tryJamendo(id, searchQuery, emitLink) }
                "gdstudio" -> fire { tryGDStudio(id, searchQuery, emitLink) }
                "ytmusic" -> fire { tryYouTubeMusic(id, searchQuery, subtitleCallback, emitLink) }
                "amazon" -> fire { tryAmazonMusic(title, artist, isrc, emitLink) }
            }

            if (source != "jiosaavn") fire { tryJioSaavn("", searchQuery, emitLink) }
            if (source != "tidal") fire { tryTidal("", searchQuery, isrc, emitLink) }
            if (source != "deezer") fire { tryDeezer(deezerId, searchQuery, emitLink) }
            if (source != "qobuz") fire { tryQobuz("", searchQuery, emitLink) }
            if (source != "spotify") fire { trySpotify("", searchQuery, emitLink) }
            if (source != "gdstudio") fire { tryGDStudio("", searchQuery, emitLink) }
            if (source != "soundcloud") fire { trySoundCloud("", searchQuery, emitLink) }
            if (source != "jamendo") fire { tryJamendo("", searchQuery, emitLink) }
            if (source != "ytmusic") {
                fire { tryYouTubeMusic("", searchQuery, subtitleCallback, emitLink) }
            }
            if (ZarzSessionManager.anyUsable() && source != "amazon") {
                fire { tryAmazonMusic(title, artist, isrc, emitLink) }
            }

            jobs.awaitAll()
        }

        // the 30-second previews are the floor, and only when nothing full-length answered
        if (emittedCount() == 0 && storedPreview.startsWith("http")) {
            emitLink(
                newExtractorLink("MusicBox", "${source.uppercase()} Preview", storedPreview, ExtractorLinkType.VIDEO) {
                    this.quality = 128
                }
            )
        }
        if (emittedCount() == 0) {
            coroutineScope {
                listOf(
                    async { runCatching { tryApplePreview(searchQuery, emitLink) }.getOrDefault(false) },
                    async {
                        if (deezerId.isNotBlank()) {
                            runCatching { tryDeezerPreview(deezerId, emitLink) }.getOrDefault(false)
                        } else false
                    }
                ).awaitAll()
            }
        }

        if (emittedCount() == 0) {
            pendingAuthUrl?.let { offerVerification(it) }
        }

        MusicBoxLog.d(TAG, "done: ${emittedCount()} link(s)")
        return emittedCount() > 0
    }

    /** host and first path segment only: enough to tell sources apart, no tokens in the log */
    private fun hostOf(url: String): String = runCatching {
        val u = java.net.URL(url)
        val path = u.path.orEmpty().trimEnd('/').take(28)
        "${u.host}$path"
    }.getOrDefault(url.take(40))

    private fun offerVerification(authUrl: String) {
        try {
            val activity = CommonActivity.activity ?: return
            MusicBoxBrowser.openVerification(activity, authUrl) { grant ->
                scope.launch {
                    try {
                        ZarzSessionManager.session(ZarzSession.APP_VERSION_DEEZER).exchange(grant)
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
        if (upstreamDown("tidal")) {
            MusicBoxLog.d(TAG, "Tidal ZARZ: skipped, upstream unavailable (retrying later)")
            return false
        }
        var added = false

        // resolve the tidal id once: the monochrome path and the resolver both need it, and
        // searching twice under a parallel chain meant the second call often came back empty
        val resolvedTidalId = tidalId.ifBlank {
            runCatching { TidalApi.search(query, 1).firstOrNull()?.sourceId }.getOrNull().orEmpty()
        }

        // 1. Monochrome / GDStudio Tidal lossless FLAC streams
        try {
            val streams = TidalApi.resolveStreams(resolvedTidalId, query)
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
        if (zarzTidal.record.isUsable()) {
            try {
                val targetId = resolvedTidalId
                if (targetId.isNotBlank()) {
                    var descriptor: JSONObject? = null
                    var lastError = ""
                    for (quality in listOf("LOSSLESS", "HIGH", "LOW")) {
                        val attempt = runCatching {
                            zarzTidal.resolveTidalDownload(targetId, quality)
                        }
                        val error = attempt.exceptionOrNull()
                        if (error == null) {
                            descriptor = attempt.getOrNull()
                            break
                        }
                        lastError = error.message.orEmpty()
                        MusicBoxLog.d(TAG, "Tidal ZARZ $quality: ${error.message}")
                        if (!lastError.contains("503") && !lastError.contains("PROVIDER_UNAVAILABLE")) break
                    }
                    if (descriptor == null) {
                        MusicBoxLog.w(TAG, "Tidal ZARZ: every quality refused ($lastError)")
                        if (lastError.contains("503") || lastError.contains("PROVIDER_UNAVAILABLE")) {
                            markUpstreamDown("tidal")
                        }
                        return added
                    }
                    saveSession()
                    // TIDAL answers a base64 dash manifest, not a link. Decode it and take the
                    // audio url; a PREVIEW asset is rejected outright, as musicdl does.
                    val url = extractDownloadUrl(descriptor)
                        ?: zarzTidal.tidalPlayableUrl(descriptor)
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
                    } else {
                        MusicBoxLog.d(TAG, "Tidal ZARZ: descriptor had no playable url (preview or empty)")
                    }
                } else {
                    MusicBoxLog.d(TAG, "Tidal ZARZ: no track id to ask for")
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
        if (zarzDeezer.record.isUsable()) {
            val deezerTarget = trackId.ifBlank {
                runCatching { DeezerApi.search(query, 1).firstOrNull()?.sourceId }.getOrNull().orEmpty()
            }
            if (deezerTarget.isBlank()) {
                MusicBoxLog.d(TAG, "Deezer ZARZ: no track id to ask for")
            } else
            try {
                val descriptor = zarzDeezer.resolveDownloadDescriptor(deezerTarget)
                saveSession()
                // an error envelope or a Blowfish-encrypted stream is not playable, and handing
                // one to the player is how deezer tracks used to fail after "succeeding"
                if (descriptor.optBoolean("success", true).not() ||
                    descriptor.has("error") || descriptor.has("detail") ||
                    zarzDeezer.requiresClientDecryption(descriptor)
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
                    val cdnUrl = zarzDeezer.descriptorUrl(descriptor)
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

        // The id has to come from the resolver: qobuz.com answers 401 "User authentication is
        // required" for anonymous search on every public app_id, so the store lookup cannot work.
        var resolvedQobuzId = qobuzId
        if (resolvedQobuzId.isBlank() && zarzQobuz.record.isUsable()) {
            resolvedQobuzId = runCatching { zarzQobuz.searchQobuzTrackId(query) }
                .getOrNull().orEmpty()
            if (resolvedQobuzId.isNotBlank()) {
                MusicBoxLog.d(TAG, "Qobuz ZARZ: resolved id $resolvedQobuzId")
            }
        }
        if (resolvedQobuzId.isBlank()) {
            resolvedQobuzId = runCatching { QobuzApi.search(query, 1).firstOrNull()?.sourceId }
                .getOrNull().orEmpty()
        }

        try {
            val streams = QobuzApi.resolveStreams(resolvedQobuzId, query)
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

        if (zarzQobuz.record.isUsable()) {
            try {
                val targetId = resolvedQobuzId
                if (targetId.isBlank()) {
                    MusicBoxLog.d(TAG, "Qobuz ZARZ: no track id to ask for")
                }
                if (targetId.isNotBlank()) {
                    // musicdl's quality chain: hi-res-max, then hi-res, then cd
                    var descriptor: JSONObject? = null
                    for (quality in listOf(27, 7, 6)) {
                        val attempt = runCatching {
                            zarzQobuz.resolveQobuzDownload(targetId, quality)
                        }.getOrNull()
                        if (attempt != null && extractDownloadUrl(attempt) != null) {
                            descriptor = attempt
                            break
                        }
                    }
                    saveSession()
                    val url = descriptor?.let { extractDownloadUrl(it) }
                    if (url == null) {
                        MusicBoxLog.d(TAG, "Qobuz ZARZ: every quality came back without a url")
                    }
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
            if (resolved == null) MusicBoxLog.d(TAG, "GDStudio: nothing matched for this track")
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
                .distinctBy { it.url.substringBefore('?') }
                .sortedByDescending { it.quality }
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
            if (!added) MusicBoxLog.d(TAG, "Jamendo: no stream for this track")
            return added
        } catch (e: Exception) {
            MusicBoxLog.d(TAG, "Jamendo failed: ${e.message}")
        }
        return false
    }

    /**
     * Amazon Music, through the resolver the official extension uses.
     *
     * I had this disabled because musicdl has no such client - but Mornye-Extension's amazon source
     * does, and it is the better reference:
     *
     *   ticket  sha256("amazeamazeamaze:track:<asin>") via POST /tickets
     *   body    POST /dl/amazeamazeamaze  {asin, codec}   header X-Zarz-Ticket
     *   reply   an ARRAY: [{audio: {url, key, codec, sampleRate}, cover: …}]
     *
     * The array is why this never worked: the old parser read the body as a JSONObject, which throws
     * on an array, and looked for a `url` field that does not exist at the top level.
     *
     * Amazon also returns a CENC key with the file. When one is present the audio is downloaded,
     * decrypted and served from a loopback server, so the player gets a plain playable stream.
     */
    private suspend fun tryAmazonMusic(
        title: String,
        artist: String,
        isrc: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (!MusicBoxSettings.enableAmazonDownload) return false
        if (!zarzAmazon.record.isUsable()) return false
        if (upstreamDown("amazon")) {
            MusicBoxLog.d(TAG, "Amazon: skipped, upstream unavailable (retrying later)")
            return false
        }

        return try {
            val asins = AmazonApi.findAsins(title, artist)
            if (asins.isEmpty()) {
                MusicBoxLog.d(TAG, "Amazon: no ASIN matched for \"${title.take(40)}\"")
                return false
            }

            // 404 {"error":"Upstream request failed"} means the resolver could not fetch THAT asin
            // (Amazon lists one song under several albums and only some are served) - not that the
            // route moved. So the next candidate is tried, each with its own one-use ticket.
            var resp: ZarzSession.SignedResponse? = null
            var asin = ""
            for (cand in asins) {
                val ticket = zarzAmazon.signedTicket(
                    "amazeamazeamaze", "track", cand, ZarzSession.APP_VERSION_AMAZON
                )
                val body = JSONObject().apply {
                    put("asin", cand)
                    put("codec", "flac")
                }.toString()
                val attempt = zarzAmazon.signedFetch(
                    "POST", "/dl/amazeamazeamaze", body,
                    mapOf("X-Zarz-Ticket" to ticket),
                    ZarzSession.APP_VERSION_AMAZON
                )
                if (attempt.ok) {
                    resp = attempt
                    asin = cand
                    break
                }
                MusicBoxLog.d(
                    TAG,
                    "Amazon: $cand -> HTTP ${attempt.statusCode} ${attempt.body.orEmpty().trim().take(160)}"
                )
                resp = attempt
                if (attempt.statusCode != 404) break // 5xx / 429 / 401: another asin will not help
            }
            if (resp == null || !resp.ok) {
                MusicBoxLog.w(TAG, "Amazon: resolver refused ${asins.size} candidate(s), last HTTP ${resp?.statusCode}")
                // only a service-level failure pauses Amazon; a per-track 404 must not
                if ((resp?.statusCode ?: 0) >= 500 || resp?.statusCode == 429) markUpstreamDown("amazon")
                return false
            }

            // the reply is an array of one
            val text = resp.body.orEmpty().trim()
            val first = if (text.startsWith("[")) JSONArray(text).optJSONObject(0)
            else runCatching { JSONObject(text) }.getOrNull()
            val audio = first?.optJSONObject("audio")
            val direct = audio?.optString("url").orEmpty()
            if (direct.isBlank()) {
                MusicBoxLog.w(TAG, "Amazon: reply had no audio url")
                return false
            }

            val key = audio?.optString("key").orEmpty()
            val codec = audio?.optString("codec").orEmpty().ifBlank { "flac" }
            val playable = if (key.isNotBlank()) {
                runCatching { AmazonProxy.prepare(direct, key) }.getOrElse {
                    MusicBoxLog.w(TAG, "Amazon: decrypt failed (${it.message})")
                    return false
                }
            } else direct

            MusicBoxLog.d(
                TAG,
                "Amazon: asin=$asin codec=$codec encrypted=${key.isNotBlank()} isrc=${isrc.ifBlank { "-" }}"
            )
            callback.invoke(
                newExtractorLink("MusicBox", "Amazon Music ${codec.uppercase()}", playable, ExtractorLinkType.VIDEO) {
                    this.quality = Qualities.Unknown.value
                }
            )
            true
        } catch (e: ZarzSession.VerificationRequiredException) {
            pendingAuthUrl = e.authUrl
            saveSession()
            false
        } catch (e: Exception) {
            MusicBoxLog.w(TAG, "Amazon failed: ${e.message}")
            false
        }
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
                        val cobalt = zarzDeezer.resolveYouTubeDownload(
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
        if (zarzDeezer.requiresClientDecryption(descriptor)) return null
        zarzDeezer.descriptorUrl(descriptor)?.let { return it }

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

    /** a playlist is a series like an album, opened through the same loader */
    private fun SpotifyClient.SpotifyPlaylist.toSearchResponse(): SearchResponse {
        val extra = listOf(
            name.encodeUrl(),
            owner.encodeUrl(),
            coverUrl.encodeUrl(),
        ).joinToString("|")
        return newTvSeriesSearchResponse(
            if (owner.isNotBlank()) "$name · $owner" else name,
            "$mainUrl/playlist/spotify/${id.encodeUrl()}/$extra",
            TvType.Music,
        ) {
            this.posterUrl = coverUrl
        }
    }

    /** an artist opens as their best known tracks */
    private fun SpotifyClient.SpotifyArtist.toSearchResponse(): SearchResponse {
        return newTvSeriesSearchResponse(
            name,
            "$mainUrl/artist/spotify/${id.encodeUrl()}/${name.encodeUrl()}",
            TvType.Music,
        ) {
            this.posterUrl = imageUrl
        }
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
