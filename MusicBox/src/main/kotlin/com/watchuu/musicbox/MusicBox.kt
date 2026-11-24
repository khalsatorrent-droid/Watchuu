package com.watchuu.musicbox

import android.content.Context
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.watchuu.musicbox.core.Creds
import com.watchuu.musicbox.core.Diag
import com.watchuu.musicbox.core.Hit
import com.watchuu.musicbox.core.Links
import com.watchuu.musicbox.core.Stream
import com.watchuu.musicbox.core.Track
import com.watchuu.musicbox.providers.Providers
import com.watchuu.musicbox.ui.SettingsScreen

@CloudstreamPlugin
class MusicBoxPlugin : Plugin() {
    override fun load(context: Context) {
        Creds.init(context)
        Diag.log("plugin", "load, version 64")
        registerMainAPI(MusicBoxProvider())
        openSettings = { ctx -> SettingsScreen.show(ctx) }
    }
}

/**
 * CloudStream surface. Catalogue work is delegated to the provider ports; every URL this
 * class emits is a musicbox:// deep link that carries the provider id and the item id.
 */
class MusicBoxProvider : MainAPI() {
    override var name = "MusicBox"
    override var mainUrl = "https://musicbox.local"
    override val supportedTypes = setOf(TvType.Music, TvType.Others)
    override val hasQuickSearch = true
    override val hasMainPage = true
    override val hasDownloadSupport = true

    private fun catalogueId(): String = Providers.catalogueId()

    private fun quality(): String = Creds.get("musicbox.quality") ?: "AAC_320"

    private fun searchResponseFor(hit: Hit): SearchResponse = newMovieSearchResponse(
        hit.title,
        hit.url,
        TvType.Music,
        false
    ) {
        posterUrl = hit.cover
        this.year = null
    }

    // A provider without a main page is left out of the app's provider picker, so MusicBox has a small
    // home: a few rows made by searching the chosen catalogue.
    override val mainPage = mainPageOf(
        "top hits" to "Top hits",
        "new releases" to "New releases",
        "love songs" to "Love songs",
        "workout" to "Workout",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val hits = ArrayList<SearchResponse>()
        val seen = HashSet<String>()
        if (page <= 1) {
            for (provider in Providers.catalogue(catalogueId())) {
                if (!provider.canCatalogue) continue
                val found = try {
                    provider.search(request.data, 20)
                } catch (e: Throwable) {
                    Diag.log("home", "${provider.id} failed: ${e.message}")
                    continue
                }
                for (hit in found) {
                    if (hit.kind == Hit.Kind.TRACK || hit.kind == Hit.Kind.ALBUM) {
                        if (seen.add("${hit.kind}|${hit.title.lowercase()}|${hit.subtitle?.lowercase()}")) hits.add(searchResponseFor(hit))
                    }
                }
                if (hits.size >= 20) break
            }
        }
        return newHomePageResponse(HomePageList(request.name, hits, false), hasNext = false)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun search(query: String): List<SearchResponse>? {
        val results = ArrayList<SearchResponse>()
        val seen = HashSet<String>()
        for (provider in Providers.catalogue(catalogueId())) {
            if (!provider.canCatalogue) continue
            val hits = try {
                provider.search(query, 20)
            } catch (e: Throwable) {
                Diag.log("search", "${provider.id} failed: ${e.message}")
                continue
            }
            for (hit in hits) {
                val key = "${hit.kind}|${hit.title.lowercase()}|${hit.subtitle?.lowercase()}"
                if (seen.add(key)) results.add(searchResponseFor(hit))
            }
        }
        return results.ifEmpty { null }
    }

    override suspend fun load(url: String): LoadResponse? {
        val parsed = Links.parse(url) ?: return null
        val provider = Providers.byId(parsed.provider) ?: return null
        return when (parsed.kind) {
            "track" -> {
                val track = provider.track(parsed.id) ?: return null
                val episode = newEpisode(episodeData(track), {
                    this.name = track.title
                    this.posterUrl = track.cover
                    this.episode = 1
                    this.runTime = ((track.durationMs ?: 0L) / 1000L).toInt()
                }, false)
                newTvSeriesLoadResponse(track.title, url, TvType.Music, listOf(episode)) {
                    this.posterUrl = track.cover
                    this.plot = buildString {
                        append(track.artistLine)
                        track.albumName?.let { append(" — $it") }
                    }
                    this.year = track.year
                    this.tags = listOfNotNull(track.albumName, track.isrc)
                }
            }
            "album" -> {
                val album = provider.album(parsed.id) ?: return null
                val episodes = album.tracks.mapIndexed { index, track ->
                    newEpisode(episodeData(track), {
                        this.name = "${index + 1}. ${track.title}"
                        this.posterUrl = track.cover ?: album.cover
                        this.episode = index + 1
                        this.runTime = ((track.durationMs ?: 0L) / 1000L).toInt()
                    }, false)
                }
                newTvSeriesLoadResponse(album.title, url, TvType.Music, episodes) {
                    this.posterUrl = album.cover
                    this.plot = album.artists.joinToString(", ")
                    this.year = album.year
                    this.tags = listOfNotNull(album.albumType, album.label)
                }
            }
            "artist" -> {
                val artist = provider.artist(parsed.id) ?: return null
                val episodes = artist.topTracks.mapIndexed { index, track ->
                    newEpisode(episodeData(track), {
                        this.name = track.title
                        this.posterUrl = track.cover ?: artist.image
                        this.episode = index + 1
                        this.runTime = ((track.durationMs ?: 0L) / 1000L).toInt()
                    }, false)
                }
                newTvSeriesLoadResponse(artist.name, url, TvType.Music, episodes) {
                    this.posterUrl = artist.image
                    this.plot = "Artist"
                    this.recommendations = artist.albums.map { album ->
                        newMovieSearchResponse(album.title, Links.album(provider.id, album.id), TvType.Music, false) {
                            this.posterUrl = album.cover
                        }
                    }
                }
            }
            else -> null
        }
    }

    /**
     * Episode data carries what playback needs: provider, id, title and artist. Keeping the
     * identity here means loadLinks never has to ask a provider for metadata again, which
     * matters because a signed session is rate limited.
     */
    private fun episodeData(track: Track): String = org.json.JSONObject()
        .put("p", track.provider)
        .put("i", track.id)
        .put("t", track.title)
        .put("a", track.artistLine)
        .toString()

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val track = parseEpisodeData(data) ?: return false
        var emitted = 0

        for (provider in Providers.streamOrder(track.provider)) {
            val streams: List<Stream> = try {
                if (provider.id == track.provider) {
                    provider.streams(track, quality())
                } else {
                    // Cross-source match: the free providers are searched by name, and a
                    // signed source is only asked when the user selected it.
                    val match = provider.search("${track.title} ${track.artistLine}", 5)
                        .firstOrNull { it.kind == Hit.Kind.TRACK && it.track != null }
                        ?.track
                    if (match == null) emptyList() else provider.streams(match, quality())
                }
            } catch (e: com.watchuu.musicbox.core.Zarz.VerificationRequired) {
                Diag.log("links", "${provider.label} needs authorization - open MusicBox settings and authorize it")
                emptyList()
            } catch (e: Throwable) {
                Diag.log("links", "${provider.id} streams failed: ${e.message}")
                emptyList()
            }
            for (stream in streams) {
                callback(
                    newExtractorLink(
                        "MusicBox · ${provider.label}",
                        "${provider.label} · ${stream.quality}",
                        stream.url,
                        if (stream.hls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.quality = qualityToInt(stream.quality)
                        this.headers = stream.headers
                        this.referer = stream.headers["Referer"] ?: ""
                    }
                )
                emitted++
            }
            if (emitted > 0) break
        }
        return emitted > 0
    }

    /** Accepts the JSON payload above and the older "track|provider|id" form. */
    private fun parseEpisodeData(data: String): Track? {
        if (data.trimStart().startsWith("{")) {
            val obj = com.watchuu.musicbox.core.jsonOrNull(data) ?: return null
            val providerId = obj.optString("p", "")
            val trackId = obj.optString("i", "")
            if (providerId.isBlank() || trackId.isBlank()) return null
            val artists = obj.optString("a", "")
            return Track(
                provider = providerId,
                id = trackId,
                title = obj.optString("t", trackId),
                artists = if (artists.isBlank()) emptyList() else artists.split(",").map { it.trim() },
            )
        }
        val parts = data.split("|")
        if (parts.size < 3) return null
        return Track(provider = parts[1], id = parts.drop(2).joinToString("|"), title = parts.drop(2).joinToString("|"))
    }

    private fun qualityToInt(quality: String): Int {
        val digits = quality.filter { it.isDigit() }
        val value = digits.toIntOrNull() ?: return Qualities.Unknown.value
        return value
    }
}
