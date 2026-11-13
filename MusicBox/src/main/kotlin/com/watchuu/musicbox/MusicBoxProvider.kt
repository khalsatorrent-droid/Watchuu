package com.watchuu.musicbox

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.json.JSONObject

/**
 * MusicBox — a CloudStream music provider.
 *
 * Catalogue: Spotify Web (via anonymous TOTP tokens)
 * Metadata/fallback: Deezer, Apple Music, Tidal, Qobuz, JioSaavn, SoundCloud, Beatport
 * Downloads (priority order):
 *   1. Amazon Music   → ZARZ amzn@2.3.19   → /dl/amazeamazeamaze
 *   2. Deezer         → ZARZ deezer@1.3.11  → /dl/dzr  (+ Blowfish)
 *   3. JioSaavn       → Direct DES-decrypted URL (no ZARZ)
 *   4. SoundCloud     → Direct HLS/progressive (no ZARZ, ZARZ /dl/sc as upgrade)
 *   5. Tidal          → ZARZ tidal-web@1.2.14 → /dl/tid
 *   6. Qobuz          → ZARZ qobuz-web@1.2.27 → /dl/qbz
 *   7. YouTube Music  → Cobalt /v1/dl/cobalt (plain POST, no ticket)
 *   8. Apple preview  → public 30s snippet
 *   9. Deezer preview → public 30s snippet
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
    }

    private val zarzSession = ZarzSession()

    @Volatile
    private var pendingAuthUrl: String? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun loadSession() {
        try {
            val stored = com.lagradost.cloudstream3.CloudStreamApp.context
                ?.getSharedPreferences("musicbox_prefs", 0)
                ?.getString(PREF_KEY_SESSION, null)
            zarzSession.loadState(stored)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load session: ${e.message}")
            zarzSession.loadState(null)
        }
    }

    private fun saveSession() {
        try {
            com.lagradost.cloudstream3.CloudStreamApp.context
                ?.getSharedPreferences("musicbox_prefs", 0)
                ?.edit()
                ?.putString(PREF_KEY_SESSION, zarzSession.saveState())
                ?.apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save session: ${e.message}")
        }
    }

    private fun getCatalogue(): String {
        return com.lagradost.cloudstream3.CloudStreamApp.context
            ?.getSharedPreferences("musicbox_prefs", 0)
            ?.getString(PREF_KEY_CATALOGUE, "all") ?: "all"
    }

    // ========== Home Page ==========

    override val mainPage = mainPageOf(
        "chart"   to "Top Charts",
        "trending" to "Trending Now",
        "new"     to "New Releases",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        loadSession()

        val items = when (request.data) {
            "chart"    -> getDeezerChart()
            "trending" -> getSpotifyTrending()
            "new"      -> getDeezerNew()
            else       -> emptyList()
        }

        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = true),
            hasNext = false,
        )
    }

    private suspend fun getDeezerChart(): List<SearchResponse> {
        return try {
            val deezer = DeezerApi.getChart(20).map { it.toSearchResponse() }
            if (deezer.isNotEmpty()) deezer else appleRow("top hits")
        } catch (e: Exception) {
            Log.w(TAG, "Deezer chart failed: ${e.message}")
            appleRow("top hits")
        }
    }

    private suspend fun appleRow(query: String): List<SearchResponse> {
        return try {
            AppleMusicApi.search(query, 20).map { it.toSearchResponse() }
        } catch (e: Exception) {
            Log.w(TAG, "Apple fallback failed for '$query': ${e.message}")
            emptyList()
        }
    }

    private suspend fun getSpotifyTrending(): List<SearchResponse> {
        return try {
            val (tracks, _, _) = SpotifyClient.search("top hits", 20)
            tracks.map { it.toSearchResponse() }
        } catch (e: Exception) {
            Log.w(TAG, "Spotify trending failed: ${e.message}")
            try {
                val deezer = DeezerApi.search("top hits", 20).map { it.toSearchResponse() }
                if (deezer.isNotEmpty()) deezer else appleRow("top hits")
            } catch (_: Exception) {
                appleRow("top hits")
            }
        }
    }

    private suspend fun getDeezerNew(): List<SearchResponse> {
        return try {
            val deezer = DeezerApi.search("new releases", 20).map { it.toSearchResponse() }
            if (deezer.isNotEmpty()) deezer else appleRow("new music releases")
        } catch (e: Exception) {
            Log.w(TAG, "Deezer new releases failed: ${e.message}")
            appleRow("new music releases")
        }
    }

    // ========== Search ==========

    override suspend fun search(query: String): List<SearchResponse> {
        val catalogue = getCatalogue()
        val primary = when (catalogue) {
            "jiosaavn"  -> searchJioSaavn(query)
            "spotify"   -> searchSpotify(query)
            "apple"     -> appleRow(query)
            "soundcloud"-> searchSoundCloud(query)
            "deezer"    -> searchDeezer(query)
            "qobuz"     -> searchQobuz(query)
            "tidal"     -> searchTidal(query)
            "beatport"  -> searchBeatport(query)
            else -> {
                // All: JioSaavn first (always plays), then catalogue sources
                val merged = LinkedHashMap<String, SearchResponse>()
                searchJioSaavn(query).forEach { merged[it.url] = it }
                searchSpotify(query).forEach  { merged[it.url] = it }
                appleRow(query).forEach        { merged[it.url] = it }
                searchSoundCloud(query).forEach{ merged[it.url] = it }
                merged.values.toList()
            }
        }
        return primary.ifEmpty { appleRow(query) }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private suspend fun searchSpotify(query: String): List<SearchResponse> {
        return try {
            val (tracks, albums, _) = SpotifyClient.search(query, 15)
            buildList {
                addAll(tracks.map { it.toSearchResponse() })
                addAll(albums.map { it.toSearchResponse() })
            }
        } catch (e: Exception) {
            Log.w(TAG, "Spotify search failed, falling back: ${e.message}")
            searchDeezer(query)
        }
    }

    private suspend fun searchDeezer(query: String): List<SearchResponse> {
        return try { DeezerApi.search(query, 20).map { it.toSearchResponse() } }
        catch (e: Exception) { Log.w(TAG, "Deezer search failed: ${e.message}"); emptyList() }
    }

    private suspend fun searchSoundCloud(query: String): List<SearchResponse> {
        return try { SoundCloudApi.search(query, 20).map { it.toSearchResponse() } }
        catch (e: Exception) { Log.w(TAG, "SoundCloud search failed: ${e.message}"); emptyList() }
    }

    private suspend fun searchJioSaavn(query: String): List<SearchResponse> {
        return try { JioSaavnApi.search(query, 20).map { it.toSearchResponse() } }
        catch (e: Exception) { Log.w(TAG, "JioSaavn search failed: ${e.message}"); emptyList() }
    }

    private suspend fun searchQobuz(query: String): List<SearchResponse> {
        return try { QobuzApi.search(query, 20).map { it.toSearchResponse() } }
        catch (e: Exception) { Log.w(TAG, "Qobuz search failed: ${e.message}"); emptyList() }
    }

    private suspend fun searchTidal(query: String): List<SearchResponse> {
        return try { TidalApi.search(query, 20).map { it.toSearchResponse() } }
        catch (e: Exception) { Log.w(TAG, "Tidal search failed: ${e.message}"); emptyList() }
    }

    private suspend fun searchBeatport(query: String): List<SearchResponse> {
        return try { BeatportApi.search(query, 20).map { it.toSearchResponse() } }
        catch (e: Exception) { Log.w(TAG, "Beatport search failed: ${e.message}"); emptyList() }
    }

    // ========== Load (detail page) ==========

    override suspend fun load(url: String): LoadResponse? {
        loadSession()

        val parts = url.removePrefix("$mainUrl/").split("/", limit = 4)
        if (parts.size < 3) return null

        val kind   = parts[0]
        val source = parts[1]
        val id     = parts[2]
        val extra  = parts.getOrNull(3)?.split("|") ?: emptyList()

        return when (kind) {
            "track" -> loadTrack(source, id, extra)
            "album" -> loadAlbum(source, id, extra)
            else    -> null
        }
    }

    private suspend fun loadTrack(source: String, id: String, extra: List<String>): LoadResponse? {
        val title  = extra.getOrNull(0)?.decodeUrl() ?: "Unknown"
        val artist = extra.getOrNull(1)?.decodeUrl() ?: ""

        val track = when (source) {
            "deezer" -> DeezerApi.getTrack(id)
            else -> try { DeezerApi.search("$title $artist", 1).firstOrNull() } catch (_: Exception) { null }
        }

        val finalTitle  = track?.title  ?: title
        val finalArtist = track?.artist ?: artist
        val coverUrl    = track?.coverUrl ?: ""
        val duration    = track?.duration ?: 0

        val playData = JSONObject().apply {
            put("source",    source)
            put("id",        id)
            put("title",     finalTitle)
            put("artist",    finalArtist)
            put("deezer_id", if (source == "deezer") id else (track?.sourceId ?: ""))
            put("isrc",      track?.isrc ?: "")
        }.toString()

        return newMovieLoadResponse(
            "$finalTitle - $finalArtist",
            "$mainUrl/track/$source/$id/${finalTitle.encodeUrl()}|${finalArtist.encodeUrl()}",
            TvType.Music,
            playData,
        ) {
            this.posterUrl = coverUrl
            this.plot = buildString {
                append("Artist: $finalArtist")
                if (track?.album?.isNotBlank() == true) append("\nAlbum: ${track.album}")
                if (duration > 0) append("\nDuration: ${duration / 60}:${"%02d".format(duration % 60)}")
                append("\nSource: ${source.replaceFirstChar { it.uppercase() }}")
            }
            this.tags = listOfNotNull(
                source.replaceFirstChar { it.uppercase() },
                if (track?.album?.isNotBlank() == true) track.album else null,
            )
        }
    }

    private suspend fun loadAlbum(source: String, albumId: String, extra: List<String>): LoadResponse? {
        val albumTitle = extra.getOrNull(0)?.decodeUrl() ?: "Album"

        val tracks = when (source) {
            "deezer" -> try { DeezerApi.getAlbumTracks(albumId) } catch (_: Exception) { emptyList() }
            else     -> emptyList()
        }
        if (tracks.isEmpty()) return null

        val episodes = tracks.mapIndexed { index, track ->
            val epData = JSONObject().apply {
                put("source",    "deezer")
                put("id",        track.sourceId)
                put("title",     track.title)
                put("artist",    track.artist)
                put("deezer_id", track.sourceId)
                put("isrc",      track.isrc)
            }.toString()

            newEpisode(epData) {
                this.name    = track.title
                this.season  = 1
                this.episode = index + 1
                this.description = "${track.artist} • ${track.duration / 60}:${"%02d".format(track.duration % 60)}"
                this.posterUrl = track.coverUrl
            }
        }

        return newTvSeriesLoadResponse(
            albumTitle,
            "$mainUrl/album/$source/$albumId/${albumTitle.encodeUrl()}",
            TvType.Music,
            episodes,
        ) {
            this.posterUrl = tracks.firstOrNull()?.coverUrl
            this.plot = "Album by ${tracks.firstOrNull()?.artist ?: "Unknown"}\n${tracks.size} tracks"
        }
    }

    // ========== Load Links (play) ==========

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        loadSession()

        val obj = try { JSONObject(data) } catch (_: Exception) { return false }
        val title    = obj.optString("title", "")
        val artist   = obj.optString("artist", "")
        val deezerId = obj.optString("deezer_id", "")
        val isrc     = obj.optString("isrc", "")
        val source   = obj.optString("source", "")
        val id       = obj.optString("id", "")

        var found = false
        pendingAuthUrl = null

        runCatching { zarzSession.refreshIfNeeded() }
            .onFailure { Log.w(TAG, "Session refresh failed: ${it.message}") }
        saveSession()

        val searchQuery = "$title $artist".trim()

        // 1. JioSaavn — always works without ZARZ, used as reliable floor
        if (!found && MusicBoxSettings.enableJioSaavn) {
            found = tryJioSaavn(if (source == "jiosaavn") id else "", searchQuery, callback)
        }

        // 2. Amazon Music (ZARZ signed)
        if (!found && MusicBoxSettings.enableAmazonDownload) {
            found = tryAmazonMusic(searchQuery, isrc, callback)
        }

        // 3. Deezer (ZARZ signed + Blowfish)
        if (!found && MusicBoxSettings.enableDeezerDownload) {
            found = tryDeezer(deezerId, searchQuery, callback)
        }

        // 4. SoundCloud — tries ZARZ /dl/sc first, then falls back to direct stream
        if (!found && MusicBoxSettings.enableSoundCloud) {
            found = trySoundCloud(source, if (source == "soundcloud") id else "", searchQuery, callback)
        }

        // 5. Tidal (ZARZ signed)
        if (!found && MusicBoxSettings.enableTidal) {
            found = tryTidal(searchQuery, isrc, callback)
        }

        // 6. Qobuz (ZARZ signed)
        if (!found && MusicBoxSettings.enableQobuz) {
            found = tryQobuz(searchQuery, isrc, callback)
        }

        // 7. YouTube Music (Cobalt → CloudStream extractor)
        if (!found && MusicBoxSettings.enableYouTubeFallback) {
            found = tryYouTubeMusic(searchQuery, subtitleCallback, callback)
        }

        // 8. Apple preview (always available, 30s)
        if (!found) {
            found = tryApplePreview(searchQuery, callback)
        }

        // 9. Deezer 30s preview
        if (!found && deezerId.isNotBlank()) {
            found = tryDeezerPreview(deezerId, callback)
        }

        if (!found) {
            pendingAuthUrl?.let { offerVerification(it) }
        }

        return found
    }

    private fun offerVerification(authUrl: String) {
        try {
            val activity = com.lagradost.cloudstream3.CommonActivity.activity ?: return
            MusicBoxBrowser.openVerification(activity, authUrl) { grant ->
                scope.launch {
                    try {
                        zarzSession.exchange(grant)
                        saveSession()
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(
                                activity,
                                "MusicBox: resolver verified — press play again",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Grant exchange failed: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not open verification: ${e.message}")
        }
    }

    // ── Amazon Music ───────────────────────────────────────────────────────────

    private suspend fun tryAmazonMusic(query: String, isrc: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            val identifier = isrc.ifBlank { query }
            val descriptor = zarzSession.resolveAmazonDownload(identifier)
            saveSession()

            val url = extractDownloadUrl(descriptor)
            if (url != null) {
                callback.invoke(newExtractorLink("MusicBox", "Amazon Music FLAC", url, ExtractorLinkType.VIDEO) {
                    this.quality = Qualities.Unknown.value
                })
                return true
            }
        } catch (e: ZarzSession.VerificationRequiredException) {
            Log.w(TAG, "ZARZ verification required (Amazon): ${e.authUrl}")
            pendingAuthUrl = e.authUrl
            saveSession()
        } catch (e: Exception) {
            Log.d(TAG, "Amazon Music failed: ${e.message}")
            saveSession()
        }
        return false
    }

    // ── Deezer ─────────────────────────────────────────────────────────────────

    private suspend fun tryDeezer(deezerId: String, query: String, callback: (ExtractorLink) -> Unit): Boolean {
        val trackId = deezerId.ifBlank {
            try { DeezerApi.search(query, 1).firstOrNull()?.sourceId ?: "" } catch (_: Exception) { "" }
        }
        if (trackId.isBlank()) return false

        try {
            val descriptor = zarzSession.resolveDownloadDescriptor(trackId)
            saveSession()

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
                    callback.invoke(newExtractorLink("MusicBox", "Deezer $qualityLabel", url, ExtractorLinkType.VIDEO) {
                        this.quality = when {
                            format.contains("flac", true) -> Qualities.Unknown.value
                            quality.contains("320") -> 320
                            quality.contains("128") -> 128
                            else -> Qualities.Unknown.value
                        }
                    })
                    return true
                }
            }

            val cdnUrl = descriptor.optString("url", "")
            if (cdnUrl.isNotBlank()) {
                callback.invoke(newExtractorLink("MusicBox", "Deezer CDN", cdnUrl, ExtractorLinkType.VIDEO) {
                    this.quality = Qualities.Unknown.value
                })
                return true
            }
        } catch (e: ZarzSession.VerificationRequiredException) {
            Log.w(TAG, "ZARZ verification required (Deezer): ${e.authUrl}")
            pendingAuthUrl = e.authUrl
            saveSession()
        } catch (e: Exception) {
            Log.d(TAG, "Deezer download failed: ${e.message}")
            saveSession()
        }
        return false
    }

    // ── JioSaavn ───────────────────────────────────────────────────────────────

    private suspend fun tryJioSaavn(saavnId: String, query: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            val streamUrl = if (saavnId.isNotBlank()) {
                JioSaavnApi.getStreamUrl(saavnId, "320")
            } else {
                val results = JioSaavnApi.search(query, 1)
                val first = results.firstOrNull()
                if (first != null && first.sourceId.isNotBlank()) {
                    JioSaavnApi.getStreamUrl(first.sourceId, "320")
                } else ""
            }
            if (streamUrl.isNotBlank()) {
                callback.invoke(newExtractorLink("MusicBox", "JioSaavn 320kbps", streamUrl, ExtractorLinkType.VIDEO) {
                    this.quality = 320
                })
                return true
            }
        } catch (e: Exception) {
            Log.d(TAG, "JioSaavn failed: ${e.message}")
        }
        return false
    }

    // ── SoundCloud ────────────────────────────────────────────────────────────
    // Strategy: try ZARZ /dl/sc first (higher quality), fall back to direct stream.

    private suspend fun trySoundCloud(source: String, scId: String, query: String, callback: (ExtractorLink) -> Unit): Boolean {
        // Resolve the SoundCloud track ID if we don't have it yet
        val trackId = if (scId.isNotBlank()) scId else {
            try { SoundCloudApi.search(query, 1).firstOrNull()?.sourceId ?: "" } catch (_: Exception) { "" }
        }
        if (trackId.isBlank()) return false

        // Attempt ZARZ /dl/sc for higher quality
        try {
            val descriptor = zarzSession.resolveSoundCloudDownload(trackId)
            saveSession()
            val url = extractDownloadUrl(descriptor)
            if (url != null) {
                val isHls = url.contains(".m3u8", true)
                callback.invoke(newExtractorLink(
                    "MusicBox", "SoundCloud (ZARZ)",
                    url,
                    if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) {
                    this.quality = 320
                })
                return true
            }
        } catch (e: ZarzSession.VerificationRequiredException) {
            Log.w(TAG, "ZARZ verification required (SoundCloud): ${e.authUrl}")
            pendingAuthUrl = e.authUrl
            saveSession()
            // Fall through to direct stream — don't block playback
        } catch (e: Exception) {
            Log.d(TAG, "SoundCloud ZARZ failed, trying direct: ${e.message}")
            saveSession()
        }

        // Direct stream fallback (no ZARZ needed)
        try {
            val streamUrl = SoundCloudApi.getStreamUrl(trackId)
            if (streamUrl.isNotBlank()) {
                val isHls = streamUrl.contains(".m3u8", true)
                callback.invoke(newExtractorLink(
                    "MusicBox", "SoundCloud",
                    streamUrl,
                    if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) {
                    this.quality = 160
                })
                return true
            }
        } catch (e: Exception) {
            Log.d(TAG, "SoundCloud direct failed: ${e.message}")
        }
        return false
    }

    // ── Tidal ──────────────────────────────────────────────────────────────────

    private suspend fun tryTidal(query: String, isrc: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            val results = TidalApi.search(query, 1)
            val track = results.firstOrNull() ?: return false
            val trackId = track.sourceId.ifBlank { return false }

            val descriptor = zarzSession.resolveTidalDownload(trackId)
            saveSession()

            val url = extractDownloadUrl(descriptor)
            if (url != null) {
                val isHls = url.contains(".m3u8", true) || url.contains("manifest", true)
                callback.invoke(newExtractorLink(
                    "MusicBox", "Tidal FLAC",
                    url,
                    if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) {
                    this.quality = Qualities.Unknown.value
                })
                return true
            }
        } catch (e: ZarzSession.VerificationRequiredException) {
            Log.w(TAG, "ZARZ verification required (Tidal): ${e.authUrl}")
            pendingAuthUrl = e.authUrl
            saveSession()
        } catch (e: Exception) {
            Log.d(TAG, "Tidal download failed: ${e.message}")
            saveSession()
        }
        return false
    }

    // ── Qobuz ─────────────────────────────────────────────────────────────────

    private suspend fun tryQobuz(query: String, isrc: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            val results = QobuzApi.search(query, 1)
            val track = results.firstOrNull() ?: return false
            val trackId = track.sourceId.ifBlank { return false }

            val descriptor = zarzSession.resolveQobuzDownload(trackId)
            saveSession()

            val url = extractDownloadUrl(descriptor)
            if (url != null) {
                callback.invoke(newExtractorLink("MusicBox", "Qobuz FLAC", url, ExtractorLinkType.VIDEO) {
                    this.quality = Qualities.Unknown.value
                })
                return true
            }
        } catch (e: ZarzSession.VerificationRequiredException) {
            Log.w(TAG, "ZARZ verification required (Qobuz): ${e.authUrl}")
            pendingAuthUrl = e.authUrl
            saveSession()
        } catch (e: Exception) {
            Log.d(TAG, "Qobuz download failed: ${e.message}")
            saveSession()
        }
        return false
    }

    // ── YouTube Music ──────────────────────────────────────────────────────────

    private suspend fun tryYouTubeMusic(
        query: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        try {
            val videoId = YouTubeMusicApi.searchVideoId(query) ?: return false
            val youtubeUrl = "https://music.youtube.com/watch?v=$videoId"

            // Try CloudStream's own extractor first (no session needed)
            val viaExtractor = mutableListOf<ExtractorLink>()
            try {
                loadExtractor(youtubeUrl, "https://music.youtube.com/", subtitleCallback) {
                    viaExtractor.add(it)
                }
            } catch (_: Exception) {}

            if (viaExtractor.isNotEmpty()) {
                viaExtractor.forEach { link ->
                    callback.invoke(newExtractorLink(
                        "MusicBox", "YouTube Music · ${link.name}",
                        link.url, link.type,
                    ) {
                        this.quality = link.quality
                        this.referer = link.referer.ifBlank { "https://www.youtube.com/" }
                        this.headers = link.headers
                    })
                }
                return true
            }

            // Cobalt (ZARZ API v1) as second option
            try {
                val cobalt = zarzSession.resolveYouTubeDownload(youtubeUrl)
                val url = cobalt.optString("url", "")
                if (url.isNotBlank()) {
                    callback.invoke(newExtractorLink("MusicBox", "YouTube Music", url, ExtractorLinkType.VIDEO) {
                        this.quality = Qualities.Unknown.value
                    })
                    return true
                }
            } catch (e: Exception) {
                Log.d(TAG, "Cobalt failed: ${e.message}")
            }

            // Final fallback: plain YouTube extractor
            val watchUrl = "https://www.youtube.com/watch?v=$videoId"
            loadExtractor(watchUrl, subtitleCallback, callback)
            return true

        } catch (e: Exception) {
            Log.d(TAG, "YouTube Music failed: ${e.message}")
        }
        return false
    }

    // ── Apple preview ──────────────────────────────────────────────────────────

    private suspend fun tryApplePreview(query: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val track = AppleMusicApi.search(query, 5).firstOrNull() ?: return false
            val url = track.previewUrl.ifBlank { return false }
            callback.invoke(newExtractorLink("MusicBox", "Apple Music preview (30s)", url, ExtractorLinkType.VIDEO) {
                this.quality = Qualities.Unknown.value
                this.referer = "https://music.apple.com/"
            })
            true
        } catch (e: Exception) {
            Log.w(TAG, "Apple preview failed: ${e.message}")
            false
        }
    }

    // ── Deezer preview ─────────────────────────────────────────────────────────

    private suspend fun tryDeezerPreview(deezerId: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            val track = DeezerApi.getTrack(deezerId)
            if (track != null && track.previewUrl.isNotBlank()) {
                callback.invoke(newExtractorLink("MusicBox", "Deezer Preview (30s)", track.previewUrl, ExtractorLinkType.VIDEO) {
                    this.quality = 128
                })
                return true
            }
        } catch (_: Exception) {}
        return false
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun extractDownloadUrl(descriptor: JSONObject): String? {
        val url = descriptor.optString("url", "")
        if (url.isNotBlank()) return url

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

        val manifest = descriptor.optString("manifest", descriptor.optString("manifestUrl", ""))
        if (manifest.isNotBlank()) return manifest

        return null
    }

    // ========== Converters ==========

    private fun MusicTrack.toSearchResponse(): SearchResponse {
        return newMovieSearchResponse(
            "$title - $artist",
            "$mainUrl/track/$source/$sourceId/${title.encodeUrl()}|${artist.encodeUrl()}",
            TvType.Music,
        ) { this.posterUrl = coverUrl }
    }

    private fun SpotifyClient.SpotifyTrack.toSearchResponse(): SearchResponse {
        return newMovieSearchResponse(
            "$name - $artist",
            "$mainUrl/track/spotify/$id/${name.encodeUrl()}|${artist.encodeUrl()}",
            TvType.Music,
        ) { this.posterUrl = coverUrl }
    }

    private fun SpotifyClient.SpotifyAlbum.toSearchResponse(): SearchResponse {
        return newMovieSearchResponse(
            "$name - $artist",
            "$mainUrl/album/spotify/$id/${name.encodeUrl()}",
            TvType.Music,
        ) { this.posterUrl = coverUrl }
    }

    private fun MusicAlbum.toSearchResponse(): SearchResponse {
        return newMovieSearchResponse(
            "$title - $artist",
            "$mainUrl/album/$source/${id.substringAfter(":")}/${title.encodeUrl()}",
            TvType.Music,
        ) { this.posterUrl = coverUrl }
    }

    private fun String.encodeUrl(): String = java.net.URLEncoder.encode(this, "UTF-8")
    private fun String.decodeUrl(): String = java.net.URLDecoder.decode(this, "UTF-8")
}
