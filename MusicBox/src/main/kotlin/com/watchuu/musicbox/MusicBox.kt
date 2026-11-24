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
        Diag.log("plugin", "load, version 71")
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

    /**
     * What each search card was built from. Opening a card used to ask the provider for the item
     * again, and when that second lookup failed (Amazon's catalogue page, a rate limited session) the
     * app showed "Error loading" for a song it had just listed. The search result already carries the
     * track or album, so it is the fallback.
     */
    private val hitCache get() = HitCodec.cache

    private fun searchResponseFor(hit: Hit): SearchResponse {
        hitCache[Links.base(hit.url)] = hit
        return buildSearchResponse(hit)
    }

    private fun buildSearchResponse(hit: Hit): SearchResponse = newMovieSearchResponse(
        hit.title,
        HitCodec.cardUrl(hit),
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
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        Diag.log("load", "url=$url")
        val parsed = Links.parse(url)
        if (parsed == null) {
            Diag.log("load", "parse failed for: $url")
            return null
        }
        val provider = Providers.byId(parsed.provider)
        if (provider == null) {
            Diag.log("load", "unknown provider: ${parsed.provider}")
            return null
        }
        Diag.log("load", "${parsed.provider}/${parsed.kind}/${parsed.id}")
        // What the card was built from: carried in the link, else the in-memory cache.
        val cached = HitCodec.decode(url) ?: hitCache[Links.base(url)]
        return when (parsed.kind) {
            "track" -> {
                // The card already holds everything a one-song page needs, so a provider lookup is
                // only made when the card carries nothing (an old bookmark). That lookup is what
                // failed on Amazon and what spends a rate limited session elsewhere.
                val track = cached?.track
                    ?: runCatching { provider.track(parsed.id) }
                        .onFailure { Diag.log("load", "track fetch failed: ${it.message}") }
                        .getOrNull()
                    ?: run {
                        Diag.log("load", "track not found, hitCache ${if (cached == null) "miss" else "hit(kind=${cached.kind})"}")
                        return null
                    }
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
                val fetched = runCatching { provider.album(parsed.id) }
                if (fetched.isFailure) Diag.log("load", "album fetch failed: ${fetched.exceptionOrNull()?.message}")
                val album = fetched.getOrNull()?.takeIf { it.tracks.isNotEmpty() || cached?.album == null }
                    ?: cached?.album
                    ?: run {
                        Diag.log("load", "album not found, hitCache ${if (cached == null) "miss" else "hit(kind=${cached.kind})"}")
                        return null
                    }
                val episodes = album.tracks.mapIndexed { index, track ->
                    newEpisode(episodeData(track), {
                        this.name = "${index + 1}. ${track.title}"
                        this.posterUrl = track.cover ?: album.cover
                        this.episode = index + 1
                        this.runTime = ((track.durationMs ?: 0L) / 1000L).toInt()
                    }, false)
                }
                if (episodes.isEmpty()) {
                    Diag.log("load", "album has no tracks, trying single-track fallback")
                    // Some providers return album metadata without tracks list; build a minimal
                    // episode list from the cached hit's track when available.
                    val fallbackTrack = cached?.track
                    val fallbackEpisodes = if (fallbackTrack != null) {
                        listOf(newEpisode(episodeData(fallbackTrack), {
                            this.name = fallbackTrack.title
                            this.posterUrl = fallbackTrack.cover ?: album.cover
                            this.episode = 1
                            this.runTime = ((fallbackTrack.durationMs ?: 0L) / 1000L).toInt()
                        }, false))
                    } else emptyList()
                    newTvSeriesLoadResponse(album.title, url, TvType.Music, fallbackEpisodes) {
                        this.posterUrl = album.cover
                        this.plot = album.artists.joinToString(", ")
                        this.year = album.year
                        this.tags = listOfNotNull(album.albumType, album.label)
                    }
                } else {
                    newTvSeriesLoadResponse(album.title, url, TvType.Music, episodes) {
                        this.posterUrl = album.cover
                        this.plot = album.artists.joinToString(", ")
                        this.year = album.year
                        this.tags = listOfNotNull(album.albumType, album.label)
                    }
                }
            }
            "artist" -> {
                val fetched = runCatching { provider.artist(parsed.id) }
                if (fetched.isFailure) Diag.log("load", "artist fetch failed: ${fetched.exceptionOrNull()?.message}")
                val artist = fetched.getOrNull()
                    ?: cached?.artist
                    ?: run {
                        Diag.log("load", "artist not found, hitCache ${if (cached == null) "miss" else "hit(kind=${cached.kind})"}")
                        return null
                    }
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
            else -> {
                Diag.log("load", "unknown kind: ${parsed.kind}")
                null
            }
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

/**
 * Packs what a card was built from into its link (see [Links.withMeta]) and reads it back in load().
 * Only the fields a details page and playback need are kept, so links stay short.
 */
internal object HitCodec {
    val cache = java.util.concurrent.ConcurrentHashMap<String, Hit>()

    fun cardUrl(hit: Hit): String =
        runCatching { Links.withMeta(hit.url, encode(hit).toString()) }.getOrDefault(hit.url)

    private fun strings(list: List<String>) = org.json.JSONArray().apply { list.forEach { put(it) } }

    private fun stringList(arr: org.json.JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it, "").takeIf { s -> s.isNotBlank() } }
    }

    private fun org.json.JSONObject.str(key: String): String? =
        if (has(key) && !isNull(key)) optString(key, "").takeIf { it.isNotBlank() } else null

    private fun encode(hit: Hit): org.json.JSONObject {
        val o = org.json.JSONObject()
            .put("k", hit.kind.name)
            .put("t", hit.title)
            .put("s", hit.subtitle)
            .put("c", hit.cover)
        hit.track?.let { t ->
            o.put("tr", org.json.JSONObject()
                .put("p", t.provider).put("i", t.id).put("t", t.title)
                .put("a", strings(t.artists))
                .put("al", t.albumName).put("ai", t.albumId)
                .put("c", t.cover).put("d", t.durationMs)
                .put("y", t.year).put("isrc", t.isrc))
        }
        hit.album?.let { a ->
            o.put("ab", org.json.JSONObject()
                .put("p", a.provider).put("i", a.id).put("t", a.title)
                .put("a", strings(a.artists))
                .put("c", a.cover).put("y", a.year))
        }
        hit.artist?.let { a ->
            o.put("ar", org.json.JSONObject()
                .put("p", a.provider).put("i", a.id).put("n", a.name).put("c", a.image))
        }
        return o
    }

    fun decode(url: String): Hit? {
        val text = Links.meta(url) ?: return null
        val o = com.watchuu.musicbox.core.jsonOrNull(text) ?: return null
        return runCatching {
            val track = o.optJSONObject("tr")?.let { t ->
                Track(
                    provider = t.optString("p"),
                    id = t.optString("i"),
                    title = t.optString("t"),
                    artists = stringList(t.optJSONArray("a")),
                    albumName = t.str("al"),
                    albumId = t.str("ai"),
                    cover = t.str("c"),
                    durationMs = t.str("d")?.toLongOrNull(),
                    year = t.str("y")?.toIntOrNull(),
                    isrc = t.str("isrc"),
                )
            }?.takeIf { it.provider.isNotBlank() && it.id.isNotBlank() }
            val album = o.optJSONObject("ab")?.let { a ->
                com.watchuu.musicbox.core.Album(
                    provider = a.optString("p"),
                    id = a.optString("i"),
                    title = a.optString("t"),
                    artists = stringList(a.optJSONArray("a")),
                    cover = a.str("c"),
                    year = a.str("y")?.toIntOrNull(),
                )
            }
            val artist = o.optJSONObject("ar")?.let { a ->
                com.watchuu.musicbox.core.Artist(
                    provider = a.optString("p"),
                    id = a.optString("i"),
                    name = a.optString("n"),
                    image = a.str("c"),
                )
            }
            Hit(
                kind = Hit.Kind.valueOf(o.optString("k", "TRACK")),
                title = o.optString("t"),
                subtitle = o.str("s"),
                cover = o.str("c"),
                url = Links.base(url),
                track = track,
                album = album,
                artist = artist,
            )
        }.onFailure { Diag.log("load", "card data unreadable: ${it.message}") }.getOrNull()
    }
}
