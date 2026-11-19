package com.watchuu.atlantic

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Atlantic - TMDB catalogue, played through the app's own resolvers.
 *
 * Card urls carry the tmdb id plus what the resolvers need:
 *   /movie/<tmdbId>/<title>
 *   /tv/<tmdbId>/<season>/<title>        (a show card; every season is listed when it is opened)
 *   /tv/<tmdbId>/<season>/<episode>      (an episode, the data handed to loadLinks)
 * The title is url-encoded so a "?" or "/" in it cannot break the address.
 */
class AtlanticProvider : MainAPI() {
    override var mainUrl = "https://atlantic.bz"
    override var name = "Atlantic"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private companion object {
        const val TAG = "Atlantic"
    }

    // ------------------------------------------------------------------ helpers

    private fun arr(o: JSONObject?, key: String): List<JSONObject> {
        val a: JSONArray = o?.optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun dec(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private fun card(o: JSONObject): SearchResponse? {
        val mediaType = o.optString("media_type")
        // search/multi also returns people
        if (mediaType == "person") return null
        val id = o.optInt("id", 0).takeIf { it > 0 } ?: return null
        val isShow = mediaType == "tv" || (mediaType.isBlank() && o.has("first_air_date"))
        val title = o.optString("title").ifBlank { o.optString("name") }
        if (title.isBlank()) return null
        val poster = AtlanticTmdb.poster(o.optString("poster_path").ifBlank { null })
        val url = if (isShow) "$mainUrl/tv/$id/1/${enc(title)}" else "$mainUrl/movie/$id/${enc(title)}"
        return if (isShow) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = poster }
        }
    }

    private fun scoreOf(d: JSONObject): Score? =
        d.optDouble("vote_average", 0.0).takeIf { it > 0 }?.let { Score.from10(it.toString()) }

    // ------------------------------------------------------------------ home

    override val mainPage = mainPageOf(
        "trending" to "Trending this week",
        "movies" to "Popular movies",
        "tv" to "Popular series",
        "top" to "Top rated",
        "new" to "In cinemas",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val json = when (request.data) {
            "trending" -> AtlanticTmdb.trending(page)
            "movies" -> AtlanticTmdb.popularMovies(page)
            "tv" -> AtlanticTmdb.popularTv(page)
            "top" -> AtlanticTmdb.topRated(page)
            else -> AtlanticTmdb.nowPlaying(page)
        }
        val items = arr(json, "results").mapNotNull { card(it) }
        val totalPages = json?.optInt("total_pages", 0) ?: 0
        return newHomePageResponse(HomePageList(request.name, items), totalPages > page)
    }

    // ------------------------------------------------------------------ search

    private suspend fun searchPage(query: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        val q = query.trim()
        if (q.isBlank()) return emptyList<SearchResponse>() to false
        val json = AtlanticTmdb.search(q, page) ?: return emptyList<SearchResponse>() to false
        val items = arr(json, "results").mapNotNull { card(it) }
        return items to (json.optInt("total_pages", 0) > page)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val (items, hasNext) = searchPage(query, page)
        return newSearchResponseList(items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> = searchPage(query, 1).first

    // ------------------------------------------------------------------ load

    override suspend fun load(url: String): LoadResponse? {
        Regex("/movie/(\\d+)/(.*)").find(url)?.let { m ->
            val id = m.groupValues[1]
            val d = AtlanticTmdb.details(id, isShow = false) ?: return null
            val title = d.optString("title").ifBlank { dec(m.groupValues[2]) }
            return newMovieLoadResponse(title, url, TvType.Movie, "$mainUrl/movie/$id/${enc(title)}") {
                this.posterUrl = AtlanticTmdb.poster(d.optString("poster_path").ifBlank { null })
                this.backgroundPosterUrl = AtlanticTmdb.poster(d.optString("backdrop_path").ifBlank { null })
                this.plot = d.optString("overview").ifBlank { null }
                this.year = d.optString("release_date").take(4).toIntOrNull()
                this.tags = arr(d, "genres").map { it.optString("name") }.filter { it.isNotBlank() }
                this.score = scoreOf(d)
                this.duration = d.optInt("runtime", 0).takeIf { it > 0 }
            }
        }

        Regex("/tv/(\\d+)/(\\d+)/(.*)").find(url)?.let { m ->
            val id = m.groupValues[1]
            val d = AtlanticTmdb.details(id, isShow = true) ?: return null
            val title = d.optString("name").ifBlank { dec(m.groupValues[3]) }

            // the whole run of the show, not only the season the card url names. Season 0 is
            // "Specials" and is left out so it does not sort in front of season 1.
            val seasonNumbers = arr(d, "seasons")
                .map { it.optInt("season_number", -1) }
                .filter { it > 0 }
                .distinct()
                .sorted()
                .ifEmpty { listOf(1) }

            val seasons = coroutineScope {
                seasonNumbers.map { s -> async { s to AtlanticTmdb.season(id, s) } }.awaitAll()
            }

            val eps = seasons.flatMap { (seasonNo, season) ->
                arr(season, "episodes").mapNotNull { e ->
                    val n = e.optInt("episode_number", 0)
                    if (n <= 0) return@mapNotNull null
                    newEpisode("$mainUrl/tv/$id/$seasonNo/$n") {
                        this.name = e.optString("name").ifBlank { "Episode $n" }
                        this.season = seasonNo
                        this.episode = n
                        this.posterUrl = AtlanticTmdb.poster(e.optString("still_path").ifBlank { null })
                        this.description = e.optString("overview").ifBlank { null }
                        this.runTime = e.optInt("runtime", 0).takeIf { it > 0 }
                    }
                }
            }
            if (eps.isEmpty()) return null

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
                this.posterUrl = AtlanticTmdb.poster(d.optString("poster_path").ifBlank { null })
                this.backgroundPosterUrl = AtlanticTmdb.poster(d.optString("backdrop_path").ifBlank { null })
                this.plot = d.optString("overview").ifBlank { null }
                this.year = d.optString("first_air_date").take(4).toIntOrNull()
                this.tags = arr(d, "genres").map { it.optString("name") }.filter { it.isNotBlank() }
                this.score = scoreOf(d)
            }
        }
        return null
    }

    // ------------------------------------------------------------------ playback

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // an episode url ends in /<season>/<episode>; a movie url carries only the id
        val show = Regex("/tv/(\\d+)/(\\d+)/(\\d+)$").find(data)
        val movie = Regex("/movie/(\\d+)").find(data)
        val tmdbId = show?.groupValues?.get(1) ?: movie?.groupValues?.get(1) ?: return false
        val isShow = show != null
        val season = show?.groupValues?.get(2).orEmpty()
        val ep = show?.groupValues?.get(3).orEmpty()

        // nothing below can answer until the library is in; this is what loads it on first play
        if (!AtlanticNative.ensureLoaded()) {
            Log.w(TAG, "resolver library not available: ${AtlanticNative.error}")
            return false
        }

        // several resolvers match on title and year instead of the id, and nepu wants the poster
        val details = AtlanticTmdb.details(tmdbId, isShow)
        val request = AtlanticNative.Request(
            tmdbId = tmdbId,
            title = details?.optString(if (isShow) "name" else "title").orEmpty(),
            year = (details?.optString(if (isShow) "first_air_date" else "release_date") ?: "").take(4),
            posterPath = details?.optString("poster_path").orEmpty(),
            isShow = isShow,
            season = season,
            ep = ep,
            febboxKey = AtlanticTmdb.febboxKey,
        )

        val wanted = AtlanticTmdb.enabledSources.split(',').map { it.trim().lowercase() }.filter { it.isNotBlank() }
        val sources = AtlanticNative.SOURCES.filter { wanted.isEmpty() || it.first in wanted }

        val seen = ConcurrentHashMap.newKeySet<String>()
        val emitted = AtomicInteger(0)
        // each resolver can spend up to its network timeout, so they run side by side rather than
        // one after another; the gate keeps the native library from being hit by all 21 at once
        val gate = Semaphore(6)

        coroutineScope {
            sources.map { (id, label) ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val raw = AtlanticNative.resolve(id, request)
                        val json = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
                        if (json != null) emitSource(label, json, subtitleCallback, callback, seen, emitted)
                    }
                }
            }.awaitAll()
        }

        Log.d(TAG, "loadLinks: ${emitted.get()} links from ${sources.size} sources")
        return emitted.get() > 0
    }

    /** turns one source's answer into links and subtitles */
    private suspend fun emitSource(
        label: String,
        json: JSONObject,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        seen: MutableSet<String>,
        emitted: AtomicInteger,
    ) {
        val streamType = json.optString("streamType")
        val topHeaders = json.optJSONObject("headers")

        suspend fun emit(url: String, display: String, type: String, quality: Int, headers: JSONObject?) {
            // the same address can come back as the top-level stream and again as a variant
            if (!url.startsWith("http") || !seen.add(url)) return
            val h = LinkedHashMap<String, String>()
            headers?.let { o -> o.keys().forEach { k -> h[k] = o.optString(k) } }
            // a source that names its own referer knows what its CDN wants
            val ref = h.entries.firstOrNull { it.key.equals("referer", true) }?.value ?: "$mainUrl/"
            val hls = type.equals("hls", true) || url.contains(".m3u8", true)
            callback.invoke(
                newExtractorLink(name, display, url, if (hls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    this.referer = ref
                    this.headers = h
                    this.quality = quality
                }
            )
            emitted.incrementAndGet()
        }

        // variants are the quality ladder, when the source offers one
        val variants = json.optJSONArray("variants")
        if (variants != null) {
            for (i in 0 until variants.length()) {
                val v = variants.optJSONObject(i) ?: continue
                val q = v.optString("quality").ifBlank { v.optString("name") }
                emit(
                    v.optString("streamUrl"),
                    if (q.isBlank()) label else "$label · $q",
                    v.optString("streamType").ifBlank { streamType },
                    qualityOf(q),
                    v.optJSONObject("headers") ?: topHeaders,
                )
            }
        }
        emit(json.optString("streamUrl"), label, streamType, Qualities.Unknown.value, topHeaders)

        val captions = json.optJSONArray("captions")
        if (captions != null) {
            for (i in 0 until captions.length()) {
                val c = captions.optJSONObject(i) ?: continue
                val url = listOf("url", "file", "src", "link")
                    .firstNotNullOfOrNull { k -> c.optString(k).takeIf { it.startsWith("http") } } ?: continue
                val language = listOf("language", "lang", "label", "name")
                    .firstNotNullOfOrNull { k -> c.optString(k).takeIf { it.isNotBlank() } } ?: "Unknown"
                if (seen.add("sub:$url")) subtitleCallback.invoke(newSubtitleFile(language, url))
            }
        }
    }

    /** "1080p" -> 1080, "4K" -> 2160; anything else is left for the player to work out */
    private fun qualityOf(text: String): Int {
        Regex("(\\d{3,4})\\s*p", RegexOption.IGNORE_CASE).find(text)?.let { return it.groupValues[1].toInt() }
        if (text.contains("4k", true) || text.contains("2160")) return 2160
        return Qualities.Unknown.value
    }
}
