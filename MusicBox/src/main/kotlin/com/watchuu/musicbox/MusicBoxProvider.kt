package com.watchuu.musicbox

import android.util.Log
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
 * Metadata/fallback: Deezer, Apple Music, Tidal, Qobuz, JioSaavn, SoundCloud
 * Downloads: ZARZ-HMAC-V1 signed session → Deezer FLAC / JioSaavn 320kbps / fallback YouTube
 */
class MusicBoxProvider : MainAPI() {
    override var mainUrl = "https://api.deezer.com"
    override var name = "MusicBox"
    override val hasMainPage = true
    override val hasQuickSearch = true
    // TvType.Others is what makes the provider reachable. Music alone is not a type this app
    // offers in its filters, so a provider declaring only that never appears in the list - which is
    // exactly what happened here twice. Monochrome declares both for the same reason.
    override val supportedTypes = setOf(TvType.Music, TvType.Others)

    companion object {
        private const val TAG = "MusicBox"
        private const val PREF_KEY_SESSION = "musicbox_zarz_session"
        private const val PREF_KEY_CATALOGUE = "musicbox_catalogue"
    }

    // ========== Session + state ==========

    private val zarzSession = ZarzSession()

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
        "chart" to "Top Charts",
        "trending" to "Trending Now",
        "new" to "New Releases",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        loadSession()

        val items = when (request.data) {
            "chart" -> getDeezerChart()
            "trending" -> getSpotifyTrending()
            "new" -> getDeezerNew()
            else -> emptyList()
        }

        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = true),
            hasNext = false,
        )
    }

    private suspend fun getDeezerChart(): List<SearchResponse> {
        // Deezer is blocked outright in some countries, so a chart from it alone can come back
        // empty and leave the row blank. Apple's feed is reachable almost everywhere and needs no
        // token, so it is the backstop for every row here.
        return try {
            val deezer = DeezerApi.getChart(20).map { it.toSearchResponse() }
            if (deezer.isNotEmpty()) deezer else appleRow("top hits")
        } catch (e: Exception) {
            Log.w(TAG, "Deezer chart failed: ${e.message}")
            appleRow("top hits")
        }
    }

    /** apple's public catalogue, used wherever another source has nothing to show */
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
            // Deezer next, then Apple - two fallbacks, because either one can be unreachable
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
            "jiosaavn" -> searchJioSaavn(query)
            "spotify" -> searchSpotify(query)
            "apple" -> appleRow(query)
            "soundcloud" -> searchSoundCloud(query)
            "deezer" -> searchDeezer(query)
            // All: every source that can answer, merged. JioSaavn first because it is the one that
            // actually serves audio here; the others add catalogue breadth on top.
            else -> {
                val merged = LinkedHashMap<String, SearchResponse>()
                searchJioSaavn(query).forEach { merged[it.url] = it }
                searchSpotify(query).forEach { merged[it.url] = it }
                appleRow(query).forEach { merged[it.url] = it }
                searchSoundCloud(query).forEach { merged[it.url] = it }
                merged.values.toList()
            }
        }
        // a chosen catalogue that is unreachable must not mean an empty screen: Apple's catalogue
        // needs no token and answers nearly everywhere, so it stands in when the pick returns
        // nothing at all
        return primary.ifEmpty { appleRow(query) }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private suspend fun searchSpotify(query: String): List<SearchResponse> {
        return try {
            val (tracks, albums, artists) = SpotifyClient.search(query, 15)
            val results = mutableListOf<SearchResponse>()
            results.addAll(tracks.map { it.toSearchResponse() })
            results.addAll(albums.map { it.toSearchResponse() })
            results
        } catch (e: Exception) {
            Log.w(TAG, "Spotify search failed, falling back: ${e.message}")
            searchDeezer(query)
        }
    }

    private suspend fun searchDeezer(query: String): List<SearchResponse> {
        return try {
            DeezerApi.search(query, 20).map { it.toSearchResponse() }
        } catch (e: Exception) {
            Log.w(TAG, "Deezer search failed: ${e.message}")
            emptyList()
        }
    }

    private suspend fun searchSoundCloud(query: String): List<SearchResponse> {
        return try {
            SoundCloudApi.search(query, 20).map { it.toSearchResponse() }
        } catch (e: Exception) {
            Log.w(TAG, "SoundCloud search failed: ${e.message}")
            emptyList()
        }
    }

    private suspend fun searchJioSaavn(query: String): List<SearchResponse> {
        return try {
            JioSaavnApi.search(query, 20).map { it.toSearchResponse() }
        } catch (e: Exception) {
            Log.w(TAG, "JioSaavn search failed: ${e.message}")
            emptyList()
        }
    }

    // ========== Load (detail page) ==========

    override suspend fun load(url: String): LoadResponse? {
        loadSession()

        // URL format: musicbox://track/{source}/{sourceId}/{title}|{artist}
        // or: musicbox://album/{source}/{albumId}/{title}
        val parts = url.removePrefix("$mainUrl/").split("/", limit = 4)
        if (parts.size < 3) return null

        val kind = parts[0] // "track" or "album"
        val source = parts[1]
        val id = parts[2]
        val extra = parts.getOrNull(3)?.split("|") ?: emptyList()

        return when (kind) {
            "track" -> loadTrack(source, id, extra)
            "album" -> loadAlbum(source, id, extra)
            else -> null
        }
    }

    private suspend fun loadTrack(source: String, id: String, extra: List<String>): LoadResponse? {
        val title = extra.getOrNull(0)?.decodeUrl() ?: "Unknown"
        val artist = extra.getOrNull(1)?.decodeUrl() ?: ""

        // Try to get full metadata from Deezer if source is deezer
        val track = when (source) {
            "deezer" -> DeezerApi.getTrack(id)
            else -> {
                // Cross-reference with Deezer for metadata
                try {
                    val results = DeezerApi.search("$title $artist", 1)
                    results.firstOrNull()
                } catch (_: Exception) { null }
            }
        }

        val finalTitle = track?.title ?: title
        val finalArtist = track?.artist ?: artist
        val coverUrl = track?.coverUrl ?: ""
        val duration = track?.duration ?: 0

        // Build the play data that loadLinks will use
        val playData = JSONObject().apply {
            put("source", source)
            put("id", id)
            put("title", finalTitle)
            put("artist", finalArtist)
            put("deezer_id", if (source == "deezer") id else (track?.sourceId ?: ""))
            put("isrc", track?.isrc ?: "")
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
            else -> emptyList()
        }

        if (tracks.isEmpty()) return null

        val episodes = tracks.mapIndexed { index, track ->
            val epData = JSONObject().apply {
                put("source", "deezer")
                put("id", track.sourceId)
                put("title", track.title)
                put("artist", track.artist)
                put("deezer_id", track.sourceId)
                put("isrc", track.isrc)
            }.toString()

            newEpisode(epData) {
                this.name = track.title
                this.season = 1
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

    /**
     * Download provider priority (matches Mornye):
     * 1. Amazon Music   → ZARZ ticket "amazeamazeamaze" → /dl/amazeamazeamaze
     * 2. Deezer         → ZARZ ticket "dzr"              → /dl/dzr (+ Blowfish)
     * 3. JioSaavn       → Direct DES-decrypted URL (no ZARZ)
     * 4. SoundCloud     → Direct HLS/progressive stream (no ZARZ)
     * 5. Tidal          → ZARZ ticket "tid"              → /dl/tid
     * 6. YouTube Music  → Cobalt /v1/dl/cobalt (no ticket, plain POST)
     *
     * Each provider is tried top-to-bottom. If a track isn't available on one,
     * the next is tried automatically.
     */
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

        var found = false
        val searchQuery = "$title $artist".trim()

        // JioSaavn first. In this region it is the one source that reliably serves audio without a
        // signed session, so it is what actually plays; the rest follow for quality and breadth.
        if (!found) {
            found = tryJioSaavn(if (source == "jiosaavn") id else "", searchQuery, callback)
        }

        // ---- Amazon Music (ZARZ signed) ----
        if (!found) {
            found = tryAmazonMusic(searchQuery, isrc, callback)
        }

        // ---- Deezer (ZARZ signed + Blowfish) ----
        if (!found) {
            found = tryDeezer(deezerId, searchQuery, callback)
        }

        // ---- SoundCloud (direct stream, no ZARZ) ----
        if (!found) {
            found = trySoundCloud(searchQuery, callback)
        }

        // ---- Tidal (ZARZ signed) ----
        if (!found) {
            found = tryTidal(searchQuery, isrc, callback)
        }

        // ---- YouTube Music ----
        if (!found) {
            found = tryYouTubeMusic(searchQuery, subtitleCallback, callback)
        }

        // ---- Apple's preview: public, no token, and it answers in this region ----
        if (!found) {
            found = tryApplePreview(searchQuery, callback)
        }

        // ---- Last resort: Deezer 30s preview ----
        if (!found && deezerId.isNotBlank()) {
            found = tryDeezerPreview(deezerId, callback)
        }

        return found
    }

    // ========== Per-provider download methods ==========

    private suspend fun tryAmazonMusic(
        query: String,
        isrc: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (!MusicBoxSettings.enableAmazonDownload) return false
        try {
            // Use ISRC or query as the identifier for Amazon
            val identifier = isrc.ifBlank { query }
            val descriptor = zarzSession.resolveAmazonDownload(identifier)
            saveSession()

            val url = extractDownloadUrl(descriptor)
            if (url != null) {
                callback.invoke(
                    newExtractorLink("MusicBox", "Amazon Music FLAC", url, ExtractorLinkType.VIDEO) {
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }
        } catch (e: ZarzSession.VerificationRequiredException) {
            Log.w(TAG, "ZARZ verification required for Amazon: ${e.authUrl}")
            saveSession()
        } catch (e: Exception) {
            Log.d(TAG, "Amazon Music failed: ${e.message}")
            saveSession()
        }
        return false
    }

    private suspend fun tryDeezer(
        deezerId: String,
        query: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (!MusicBoxSettings.enableDeezerDownload) return false

        val trackId = deezerId.ifBlank {
            try {
                DeezerApi.search(query, 1).firstOrNull()?.sourceId ?: ""
            } catch (_: Exception) { "" }
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
                    callback.invoke(
                        newExtractorLink("MusicBox", "Deezer $qualityLabel", url, ExtractorLinkType.VIDEO) {
                            this.quality = when {
                                format.contains("flac", true) -> Qualities.Unknown.value
                                quality.contains("320") -> 320
                                quality.contains("128") -> 128
                                else -> Qualities.Unknown.value
                            }
                        }
                    )
                    return true
                }
            }

            val cdnUrl = descriptor.optString("url", "")
            if (cdnUrl.isNotBlank()) {
                callback.invoke(
                    newExtractorLink("MusicBox", "Deezer CDN", cdnUrl, ExtractorLinkType.VIDEO) {
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }
        } catch (e: ZarzSession.VerificationRequiredException) {
            Log.w(TAG, "ZARZ verification required for Deezer: ${e.authUrl}")
            saveSession()
        } catch (e: Exception) {
            Log.d(TAG, "Deezer download failed: ${e.message}")
            saveSession()
        }
        return false
    }

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
                val results = JioSaavnApi.search(query, 1)
                val first = results.firstOrNull()
                if (first != null && first.sourceId.isNotBlank()) {
                    JioSaavnApi.getStreamUrl(first.sourceId, "320")
                } else ""
            }
            if (streamUrl.isNotBlank()) {
                callback.invoke(
                    newExtractorLink("MusicBox", "JioSaavn 320kbps", streamUrl, ExtractorLinkType.VIDEO) {
                        this.quality = 320
                    }
                )
                return true
            }
        } catch (e: Exception) {
            Log.d(TAG, "JioSaavn failed: ${e.message}")
        }
        return false
    }

    private suspend fun trySoundCloud(
        query: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (!MusicBoxSettings.enableSoundCloud) return false
        try {
            val results = SoundCloudApi.search(query, 1)
            val track = results.firstOrNull() ?: return false
            // SoundCloud tracks have a progressive or HLS stream URL
            // We need to resolve it through the API
            val trackId = track.sourceId
            if (trackId.isBlank()) return false

            val streamUrl = SoundCloudApi.getStreamUrl(trackId)
            if (streamUrl.isNotBlank()) {
                val isHls = streamUrl.contains(".m3u8", true)
                callback.invoke(
                    newExtractorLink(
                        "MusicBox", "SoundCloud",
                        streamUrl,
                        if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                    ) {
                        this.quality = 160
                    }
                )
                return true
            }
        } catch (e: Exception) {
            Log.d(TAG, "SoundCloud failed: ${e.message}")
        }
        return false
    }

    private suspend fun tryTidal(
        query: String,
        isrc: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (!MusicBoxSettings.enableTidal) return false
        try {
            // Search Tidal for the track to get its ID
            val results = TidalApi.search(query, 1)
            val track = results.firstOrNull() ?: return false
            val trackId = track.sourceId
            if (trackId.isBlank()) return false

            val descriptor = zarzSession.resolveTidalDownload(trackId)
            saveSession()

            val url = extractDownloadUrl(descriptor)
            if (url != null) {
                val isHls = url.contains(".m3u8", true) || url.contains("manifest", true)
                callback.invoke(
                    newExtractorLink(
                        "MusicBox", "Tidal FLAC",
                        url,
                        if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                    ) {
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }
        } catch (e: ZarzSession.VerificationRequiredException) {
            Log.w(TAG, "ZARZ verification required for Tidal: ${e.authUrl}")
            saveSession()
        } catch (e: Exception) {
            Log.d(TAG, "Tidal download failed: ${e.message}")
            saveSession()
        }
        return false
    }


    /**
     * Apple's catalogue needs no token and its previews are reachable in this region, so this is
     * the floor: if every other provider declines, a track still plays 30 seconds.
     */
    private suspend fun tryApplePreview(
        query: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        return try {
            val track = AppleMusicApi.search(query, 5).firstOrNull() ?: return false
            val url = track.previewUrl.ifBlank { return false }
            callback.invoke(
                newExtractorLink("MusicBox", "Apple Music preview (30s)", url, ExtractorLinkType.VIDEO) {
                    this.quality = Qualities.Unknown.value
                    this.referer = "https://music.apple.com/"
                }
            )
            true
        } catch (e: Exception) {
            Log.w(TAG, "Apple preview failed: ${e.message}")
            false
        }
    }

    private suspend fun tryYouTubeMusic(
        query: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (!MusicBoxSettings.enableYouTubeFallback) return false
        try {
            val videoId = YouTubeMusicApi.searchVideoId(query)
            if (videoId != null) {
                val youtubeUrl = "https://music.youtube.com/watch?v=$videoId"

                // Cloudstream's own extractor first: it solves YouTube's signature itself and needs
                // no session, so this works even when the resolver does not
                val viaExtractor = mutableListOf<ExtractorLink>()
                try {
                    loadExtractor(youtubeUrl, "https://music.youtube.com/", subtitleCallback) {
                        viaExtractor.add(it)
                    }
                } catch (_: Exception) {
                }
                if (viaExtractor.isNotEmpty()) {
                    viaExtractor.forEach { link ->
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
                    }
                    return true
                }

                // then Cobalt (ZARZ API v1), for when the extractor is unavailable
                try {
                    val cobalt = zarzSession.resolveYouTubeDownload(youtubeUrl)
                    val url = cobalt.optString("url", "")
                    if (url.isNotBlank()) {
                        callback.invoke(
                            newExtractorLink("MusicBox", "YouTube Music", url, ExtractorLinkType.VIDEO) {
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        return true
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Cobalt failed, trying extractor: ${e.message}")
                }

                // Fall back to CloudStream's built-in YouTube extractor
                val watchUrl = "https://www.youtube.com/watch?v=$videoId"
                loadExtractor(watchUrl, subtitleCallback, callback)
                return true
            }
        } catch (e: Exception) {
            Log.d(TAG, "YouTube Music failed: ${e.message}")
        }
        return false
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

    /**
     * Extract a download URL from a ZARZ download descriptor response.
     * Different providers return the URL in different fields.
     */
    private fun extractDownloadUrl(descriptor: JSONObject): String? {
        // Direct URL field
        val url = descriptor.optString("url", "")
        if (url.isNotBlank()) return url

        // Sources array (Deezer style)
        val sources = descriptor.optJSONArray("sources")
        if (sources != null && sources.length() > 0) {
            for (i in 0 until sources.length()) {
                val src = sources.optJSONObject(i) ?: continue
                val srcUrl = src.optString("url", "")
                if (srcUrl.isNotBlank()) return srcUrl
            }
        }

        // Download URL field
        val dlUrl = descriptor.optString("download_url", descriptor.optString("downloadUrl", ""))
        if (dlUrl.isNotBlank()) return dlUrl

        // Manifest URL (Tidal HLS)
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
        ) {
            this.posterUrl = coverUrl
        }
    }

    private fun SpotifyClient.SpotifyTrack.toSearchResponse(): SearchResponse {
        // Find Deezer ID for this track via ISRC or title+artist
        return newMovieSearchResponse(
            "$name - $artist",
            "$mainUrl/track/spotify/$id/${name.encodeUrl()}|${artist.encodeUrl()}",
            TvType.Music,
        ) {
            this.posterUrl = coverUrl
        }
    }

    private fun SpotifyClient.SpotifyAlbum.toSearchResponse(): SearchResponse {
        return newMovieSearchResponse(
            "$name - $artist",
            "$mainUrl/album/spotify/$id/${name.encodeUrl()}",
            TvType.Music,
        ) {
            this.posterUrl = coverUrl
        }
    }

    private fun MusicAlbum.toSearchResponse(): SearchResponse {
        return newMovieSearchResponse(
            "$title - $artist",
            "$mainUrl/album/$source/${id.substringAfter(":")}/${title.encodeUrl()}",
            TvType.Music,
        ) {
            this.posterUrl = coverUrl
        }
    }

    // ========== Utilities ==========

    private fun String.encodeUrl(): String = java.net.URLEncoder.encode(this, "UTF-8")
    private fun String.decodeUrl(): String = java.net.URLDecoder.decode(this, "UTF-8")
}
