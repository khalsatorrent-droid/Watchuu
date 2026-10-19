package com.watchuu.cinelite

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/**
 * CineLite (cinelite.xyz) - a TMDB front-end with an embed player.
 *
 * Built from a captured network log:
 *  - catalog / search / details : api.themoviedb.org (same calls the site makes)
 *  - player                     : https://embed.cinelite.xyz/{tmdbId}
 *  - stream api                 : https://api.cinelite.xyz/provider/{name}?id={tmdbId}
 *                                 (gated by a Cloudflare Turnstile check on the site)
 *  - streams                    : HLS, served through a rotating proxy host
 *
 * Link resolution (v8):
 *  - movies / tv : a hidden WebView loads the embed player (passes Turnstile), hooks fetch/XHR,
 *                  then asks EVERY server (/servers + known provider names) from inside the page
 *                  with the player's own request headers, and lists all streams found.
 *  - anime       : megaplay + vidnest + 4animo directly, plus whatever the real player requests
 *                  through the site proxy.
 *  - every HLS master playlist is read and each resolution is listed as its own link
 *    (plus one "Auto" link with the master).
 */
class CineLiteProvider : MainAPI() {
    override var mainUrl = "https://cinelite.xyz"
    override var name = "CineLite"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val embedUrl = "https://embed.cinelite.xyz"
    private val apiUrl = "https://api.cinelite.xyz"

    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbApiKey = "4e44d9029b1270a757cddc766a1bcb63" // public key used by cinelite.xyz itself
    private val tmdbImg = "https://image.tmdb.org/t/p/w500"
    private val tmdbBack = "https://image.tmdb.org/t/p/original"

    // provider ids seen in the log (vault is requested first by the player)
    private val providers = listOf("vault", "movy-miami", "movy-boise", "movy-phoenix", "movy-portland")

    private val ua =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val mapper = ObjectMapper()

    // The home page of the site: trending, top rated movies, popular tv, sci-fi
    override val mainPage = mainPageOf(
        "/trending/all/day" to "Trending",
        "/movie/top_rated" to "Top Rated Movies",
        "/tv/popular" to "Popular TV Shows",
        "/discover/movie?with_genres=878&sort_by=popularity.desc" to "Sci-Fi Movies",
        "/movie/popular" to "Popular Movies",
        "/tv/top_rated" to "Top Rated TV Shows",
        "/tv/on_the_air" to "Airing Now",
        "/movie/now_playing" to "In Theaters",
    )

    // ---------------------------------------------------------------- TMDB models

    data class TmdbItem(
        val id: Int? = null,
        val title: String? = null,
        val name: String? = null,
        @JsonProperty("media_type") val mediaType: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("genre_ids") val genreIds: List<Int>? = null,
        @JsonProperty("original_language") val originalLanguage: String? = null,
    )

    data class TmdbResp(
        val page: Int? = null,
        val results: List<TmdbItem>? = null,
        @JsonProperty("total_pages") val totalPages: Int? = null,
    )

    data class TmdbGenre(val id: Int? = null, val name: String? = null)

    data class TmdbSeason(
        @JsonProperty("season_number") val seasonNumber: Int = 0,
        @JsonProperty("episode_count") val episodeCount: Int? = null,
    )

    data class TmdbDetail(
        val id: Int? = null,
        val title: String? = null,
        val name: String? = null,
        val overview: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        val runtime: Int? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        val genres: List<TmdbGenre>? = null,
        val status: String? = null,
        @JsonProperty("original_language") val originalLanguage: String? = null,
        val seasons: List<TmdbSeason>? = null,
    )

    data class TmdbEpisode(
        @JsonProperty("episode_number") val episodeNumber: Int = 0,
        @JsonProperty("season_number") val seasonNumber: Int = 0,
        val name: String? = null,
        val overview: String? = null,
        @JsonProperty("still_path") val stillPath: String? = null,
        val runtime: Int? = null,
    )

    data class TmdbSeasonDetail(val episodes: List<TmdbEpisode>? = null)

    private suspend fun tmdbGet(path: String): String {
        val sep = if (path.contains("?")) "&" else "?"
        return app.get(
            "$tmdbApi$path${sep}api_key=$tmdbApiKey&language=en-US",
            headers = mapOf("Accept" to "application/json", "User-Agent" to ua),
            timeout = 30_000L
        ).text
    }

    // ------------------------------------------------------------ list / search

    // Payloads look like  movie|123|Title  or  tv|123|1|2|Title  (optionally prefixed with mainUrl)
    private fun payloadParts(raw: String): List<String> {
        var s = raw.trim()
        if (s.startsWith("$mainUrl/")) s = s.removePrefix("$mainUrl/")
        return s.split("|")
    }

    private fun TmdbItem.toSearch(defaultType: String?): SearchResponse? {
        val type = mediaType ?: defaultType ?: return null
        if (type != "movie" && type != "tv") return null
        val id = id ?: return null
        val t = (if (type == "movie") title ?: name else name ?: title) ?: return null
        if (t.isBlank()) return null
        val poster = posterPath?.let { tmdbImg + it }
        val year = (releaseDate ?: firstAirDate)?.take(4)?.toIntOrNull()
        val isAnime = type == "tv" && (genreIds ?: emptyList()).contains(16) && originalLanguage == "ja"
        val tvType = if (type == "movie") TvType.Movie else if (isAnime) TvType.Anime else TvType.TvSeries
        val token = if (isAnime) "atv" else type
        return newMovieSearchResponse(t, "$mainUrl/$token|$id|$t", tvType) {
            this.posterUrl = poster
            this.year = year
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val defType = when {
                request.data.startsWith("/movie") || request.data.startsWith("/discover/movie") -> "movie"
                request.data.startsWith("/tv") || request.data.startsWith("/discover/tv") -> "tv"
                else -> null
            }
            val sep = if (request.data.contains("?")) "&" else "?"
            val resp = parseJson<TmdbResp>(tmdbGet("${request.data}${sep}page=$page"))
            val items = resp.results?.mapNotNull { it.toSearch(defType) } ?: emptyList()
            newHomePageResponse(request.name, items, (resp.totalPages ?: 1) > page)
        } catch (_: Exception) {
            newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return try {
            val q = URLEncoder.encode(query, "UTF-8")
            val resp = parseJson<TmdbResp>(tmdbGet("/search/multi?query=$q&page=1&include_adult=false"))
            resp.results?.mapNotNull { it.toSearch(null) } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    // --------------------------------------------------------------------- load

    override suspend fun load(url: String): LoadResponse? {
        val parts = payloadParts(url)
        if (parts.size < 3) return null
        val type = parts[0]
        val tmdbId = parts[1].toIntOrNull() ?: return null
        val fallbackTitle = parts.drop(2).joinToString("|")

        return try {
            val tmdbType = if (type == "atv") "tv" else type
            val detail = parseJson<TmdbDetail>(tmdbGet("/$tmdbType/$tmdbId"))
            val isAnime = type == "atv" ||
                (tmdbType == "tv" && detail.originalLanguage == "ja" && (detail.genres?.any { it.id == 16 } == true))
            val epToken = if (isAnime) "atv" else "tv"
            val title = detail.title ?: detail.name ?: fallbackTitle
            val year = (detail.releaseDate ?: detail.firstAirDate)?.take(4)?.toIntOrNull()
            val poster = detail.posterPath?.let { tmdbImg + it }
            val bg = detail.backdropPath?.let { tmdbBack + it }
            val genres = detail.genres?.mapNotNull { it.name } ?: emptyList()
            val score = detail.voteAverage?.let { Score.from10(it.toInt()) }

            if (type == "movie") {
                newMovieLoadResponse(title, url, TvType.Movie, "$mainUrl/movie|$tmdbId|$title") {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = bg
                    this.year = year
                    this.plot = detail.overview
                    this.tags = genres
                    this.score = score
                    this.duration = detail.runtime
                }
            } else {
                val episodes = mutableListOf<Episode>()
                detail.seasons
                    ?.filter { it.seasonNumber > 0 && (it.episodeCount ?: 0) > 0 }
                    ?.forEach { season ->
                        try {
                            val sd = parseJson<TmdbSeasonDetail>(
                                tmdbGet("/tv/$tmdbId/season/${season.seasonNumber}")
                            )
                            sd.episodes?.forEach { ep ->
                                val epData = "$mainUrl/$epToken|$tmdbId|${ep.seasonNumber}|${ep.episodeNumber}|$title"
                                episodes.add(
                                    newEpisode(epData) {
                                        this.name = ep.name
                                        this.season = ep.seasonNumber
                                        this.episode = ep.episodeNumber
                                        this.posterUrl = ep.stillPath?.let { tmdbImg + it }
                                        this.description = ep.overview
                                        this.runTime = ep.runtime
                                    }
                                )
                            }
                        } catch (_: Exception) {
                        }
                    }

                newTvSeriesLoadResponse(title, url, if (isAnime) TvType.Anime else TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = bg
                    this.year = year
                    this.plot = detail.overview
                    this.tags = genres
                    this.score = score
                    this.showStatus = when (detail.status) {
                        "Returning Series" -> ShowStatus.Ongoing
                        "Ended", "Canceled" -> ShowStatus.Completed
                        else -> null
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    // -------------------------------------------------------------------- links

    private val embedHeaders = mapOf(
        "Origin" to embedUrl,
        "Referer" to "$embedUrl/",
        "User-Agent" to ua,
    )

    private val embedQuery = "nosuggestionui=true&quality=1080p&stremio_addons=%5B%5D"

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = payloadParts(data)
        val type = parts.getOrNull(0) ?: return false
        val tmdbId = parts.getOrNull(1)?.toIntOrNull() ?: return false

        var season: Int? = null
        var episode: Int? = null
        val title: String
        if (type == "movie") {
            title = parts.drop(2).joinToString("|")
        } else {
            season = parts.getOrNull(2)?.toIntOrNull()
            episode = parts.getOrNull(3)?.toIntOrNull()
            if (season == null || episode == null) return false
            title = parts.drop(4).joinToString("|")
        }

        // every link goes through here so the same url is never listed twice
        val emitted = HashSet<String>()
        var found = false
        val out: (ExtractorLink) -> Unit = { link ->
            if (emitted.add(link.url)) {
                found = true
                callback.invoke(link)
            }
        }
        val dbg = mutableListOf<String>()

        if (type == "atv") {
            try {
                animeLinks(title, season ?: 1, episode ?: 1, subtitleCallback, out, dbg)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                dbg.add("anime EXC ${e.message?.take(80)}")
            }
        } else {
            try {
                movieTvLinks(type, tmdbId, season, episode, title, subtitleCallback, out, dbg)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                dbg.add("web EXC ${e.message?.take(80)}")
            }
            if (!found) {
                dbg.add("multi-server path found nothing (see lines above)")
                // last resort: the old single-playlist capture on the watch page
                val pageUrl = if (type == "movie") "$mainUrl/watch/$tmdbId"
                else "$mainUrl/watch/tv/$tmdbId/$season/$episode"
                legacyCapture(pageUrl, out, dbg)
            }
        }

        if (!found || dbg.isNotEmpty()) {
            // something failed: show what happened so the next screenshot tells us why
            dbg.forEachIndexed { i, line ->
                callback.invoke(
                    newExtractorLink(name, "DBG${i + 1} $line", "https://example.com/debug$i.m3u8", ExtractorLinkType.M3U8) {
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }
        return true
    }

    // ---------------------------------------------------------- movies / tv shows

    private suspend fun movieTvLinks(
        type: String,
        tmdbId: Int,
        season: Int?,
        episode: Int?,
        title: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        out: (ExtractorLink) -> Unit,
        dbg: MutableList<String>
    ) {
        val isMovie = type == "movie"
        val defaultQuery = if (isMovie) "id=$tmdbId" else "id=$tmdbId&season=$season&episode=$episode"

        // The movie/anime embed urls are taken from the log. The tv one is inferred, so a second
        // pattern is tried when the first one never reaches the stream api.
        val embeds = if (isMovie) listOf("$embedUrl/$tmdbId?$embedQuery")
        else listOf(
            "$embedUrl/tv/$tmdbId/$season/$episode?$embedQuery",
            "$embedUrl/$tmdbId/$season/$episode?$embedQuery",
        )

        val year = try {
            val d = parseJson<TmdbDetail>(tmdbGet("/${if (isMovie) "movie" else "tv"}/$tmdbId"))
            (d.releaseDate ?: d.firstAirDate)?.take(4)
        } catch (_: Exception) {
            null
        }
        var subUrl = "$apiUrl/subtitles?id=$tmdbId&title=${URLEncoder.encode(title, "UTF-8")}" +
            (if (year != null) "&year=$year" else "")
        if (!isMovie) subUrl += "&season=$season&episode=$episode"

        for ((idx, embed) in embeds.withIndex()) {
            val s = CineLiteBrowser.open(embed, ua, "$mainUrl/")
            if (s == null) {
                dbg.add("webview unavailable")
                return
            }
            try {
                val ready = s.waitFor(if (idx == 0) 30_000L else 20_000L) {
                    s.has("template") ||
                        s.events.any { (it.kind == "provider" || it.kind == "servers") && it.status in 200..299 }
                }
                dbg.add("embed#$idx ready=$ready events=${s.events.size} verify=${s.events.firstOrNull { it.kind == "verify" }?.status}")
                if (!ready) continue
                delay(1500L) // let /servers land

                // same query the player used (works for tv even though the pattern is inferred)
                val query = s.events.firstOrNull { it.kind == "provider" }
                    ?.url?.substringAfter("?", "")?.takeIf { it.isNotBlank() } ?: defaultQuery

                // server list: the api's own list first, our known names as a safety net
                val names = LinkedHashSet<String>()
                names.addAll(providers)
                var serversBody = s.events.firstOrNull { it.kind == "servers" && it.status in 200..299 }?.body
                if (serversBody == null) {
                    val r = s.get("$apiUrl/servers", true)
                    if (r != null && r.first in 200..299) serversBody = r.second
                }
                serversBody?.let { names.addAll(parseServerNames(it)) }
                s.events.filter { it.kind == "provider" }.forEach { e -> providerName(e.url)?.let { names.add(it) } }

                // responses the player already got ...
                val bodies = LinkedHashMap<String, Pair<Int, String>>()
                s.events.filter { it.kind == "provider" && it.status in 200..299 && it.body.isNotBlank() }
                    .forEach { e ->
                        val n = providerName(e.url)
                        if (n != null && !bodies.containsKey(n)) bodies[n] = Pair(e.status, e.body)
                    }

                // ... plus every other server, asked from inside the page
                coroutineScope {
                    names.filter { it !in bodies }.take(12).map { n ->
                        async { n to s.get("$apiUrl/provider/$n?$query", true, 25_000L) }
                    }.awaitAll().forEach { (n, r) ->
                        if (r != null && r.first in 200..299 && r.second.isNotBlank()) {
                            bodies[n] = r
                        } else {
                            dbg.add("$n -> ${r?.first} ${r?.second?.take(60)}")
                        }
                    }
                }
                dbg.add("servers=${names.joinToString(",")} ok=${bodies.keys.joinToString(",")}")

                // subtitles
                try {
                    val sr = s.get(subUrl, true)
                    if (sr != null && sr.first in 200..299) {
                        mapper.readTree(sr.second)?.let { collectSubtitles(it, subtitleCallback) }
                    }
                } catch (_: Exception) {
                }

                s.quiet() // everything is collected: silence the player, block its other traffic
                for ((n, r) in bodies) {
                    val label = prettyServer(n)
                    val cands = parseCandidates(r.second)
                    if (cands.isEmpty()) dbg.add("$n: no stream in ${r.second.take(120)}")
                    for (c in cands) {
                        val h = embedHeaders + (c.headers ?: emptyMap())
                        emitStream(
                            listOfNotNull(label, c.label).joinToString(" "),
                            c.url, c.quality, c.kind, h, h["Referer"] ?: "$embedUrl/", s, out
                        )
                    }
                }
                return
            } finally {
                s.close()
            }
        }
    }

    private suspend fun legacyCapture(pageUrl: String, out: (ExtractorLink) -> Unit, dbg: MutableList<String>) {
        try {
            val resolver = WebViewResolver(
                interceptUrl = Regex("""(?i)\.m3u8"""),
                additionalUrls = listOf(Regex("""(?i)\.m3u8""")),
                userAgent = ua,
                useOkhttp = false,
                timeout = 20_000L
            )
            val m3u8 = app.get(pageUrl, referer = "$mainUrl/", interceptor = resolver).url
            if (m3u8.isNotBlank() && m3u8.contains(".m3u8", ignoreCase = true)) {
                emitStream("HLS", m3u8, null, MK.HLS, embedHeaders, "$embedUrl/", null, out)
            } else dbg.add("legacy -> ${m3u8.take(100)}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            dbg.add("legacy EXC ${e.message?.take(80)}")
        }
    }

    private fun providerName(url: String): String? =
        Regex("""/provider/([^?/#]+)""").find(url)?.groupValues?.get(1)

    private fun prettyServer(n: String): String =
        n.split("-", "_").filter { it.isNotBlank() }.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    /** Pulls server ids out of whatever shape /servers has (array of strings, or objects with id/name/slug). */
    private fun parseServerNames(body: String): List<String> {
        val root = try {
            mapper.readTree(body)
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        val ok = Regex("""^[a-z][a-z0-9_-]{1,40}\z""")
        val names = LinkedHashSet<String>()
        fun add(s: String?) {
            if (s != null && ok.matches(s)) names.add(s)
        }

        fun walk(n: JsonNode, depth: Int) {
            if (depth > 4) return
            if (n.isArray) {
                n.forEach { if (it.isTextual) add(it.asText()) else walk(it, depth + 1) }
            } else if (n.isObject) {
                for (k in listOf("id", "name", "slug", "provider", "key")) {
                    n.get(k)?.takeIf { it.isTextual }?.let { add(it.asText()) }
                }
                n.fields().forEach { if (it.value.isContainerNode) walk(it.value, depth + 1) }
            }
        }
        walk(root, 0)
        return names.toList()
    }

    // ------------------------------------------------------ generic stream parsing

    private enum class MK { HLS, VIDEO, UNKNOWN }

    private class Cand(
        val url: String,
        val quality: Int?,
        val kind: MK,
        val headers: Map<String, String>?,
        val label: String?
    )

    private val notMediaExt = listOf(
        ".vtt", ".srt", ".ass", ".ssa", ".sub", ".jpg", ".jpeg", ".png", ".webp", ".gif",
        ".ico", ".svg", ".css", ".js", ".json", ".html", ".htm", ".mpd"
    )

    private fun mediaKind(raw: String): MK? {
        val v = raw.trim()
        if (!(v.startsWith("http://") || v.startsWith("https://"))) return null
        val p = v.substringBefore("?").substringBefore("#").lowercase()
        if (notMediaExt.any { p.endsWith(it) }) return null
        if (p.contains(".m3u8")) return MK.HLS
        if (p.endsWith(".mp4") || p.endsWith(".mkv") || p.endsWith(".webm") || p.endsWith(".m4v")) return MK.VIDEO
        val l = v.lowercase()
        return if (l.contains("/hls") || l.contains("/proxy/") || l.contains("/pipe/") ||
            l.contains("master") || l.contains("playlist")
        ) MK.UNKNOWN else null
    }

    private fun qualityOf(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val t = text.lowercase()
        if (t.contains("4k") || t.contains("uhd")) return 2160
        Regex("""(\d{3,4})\s*p""").find(t)?.let { return it.groupValues[1].toInt() }
        Regex("""^\s*(\d{3,4})\s*\z""").find(t)?.let {
            val v = it.groupValues[1].toInt()
            if (v in 144..4320) return v
        }
        return null
    }

    private val skipKeys = setOf(
        "subtitles", "subtitle", "tracks", "captions", "thumbnails", "thumbnail", "poster",
        "image", "images", "intro", "outro", "headers"
    )

    /** Walks any json shape and collects every playable-looking url with the quality/label found next to it. */
    private fun parseCandidates(body: String): List<Cand> {
        val out = ArrayList<Cand>()
        val root = try {
            mapper.readTree(body)
        } catch (_: Exception) {
            null
        }
        if (root == null || !(root.isContainerNode)) {
            // not json: scan the text for urls
            Regex("""https?://[^\s"'\\<>]+""").findAll(body).forEach { m ->
                val u = m.value
                mediaKind(u)?.let { out.add(Cand(u, qualityOf(u), it, null, null)) }
            }
            return out.distinctBy { it.url }
        }

        fun walk(n: JsonNode, q: Int?, label: String?, hdr: Map<String, String>?, depth: Int) {
            if (depth > 12) return
            when {
                n.isTextual -> {
                    val k = mediaKind(n.asText()) ?: return
                    out.add(Cand(n.asText().trim(), q, k, hdr, label))
                }
                n.isArray -> n.forEach { walk(it, q, label, hdr, depth + 1) }
                n.isObject -> {
                    var q2 = q
                    var l2 = label
                    var h2 = hdr
                    for (k in listOf("quality", "resolution", "res", "height", "label", "name", "size")) {
                        val f = n.get(k) ?: continue
                        if (f.isTextual || f.isNumber) qualityOf(f.asText())?.let { q2 = it }
                    }
                    listOf("label", "server", "name", "title", "provider", "lang", "language")
                        .firstNotNullOfOrNull { k ->
                            n.get(k)?.takeIf { it.isTextual }?.asText()
                                ?.takeIf { it.isNotBlank() && !it.startsWith("http") && qualityOf(it) == null }
                        }?.let { l2 = it }
                    n.get("headers")?.takeIf { it.isObject }?.let { h ->
                        h2 = h.fields().asSequence()
                            .filter { it.value.isTextual }
                            .associate { it.key to it.value.asText() }
                    }
                    n.fields().forEach { e ->
                        if (e.key.lowercase() !in skipKeys) walk(e.value, q2, l2, h2, depth + 1)
                    }
                }
            }
        }
        walk(root, null, null, null, 0)
        return out.distinctBy { it.url }
    }

    // ------------------------------------------------------------ emit / resolutions

    private class Variant(val url: String, val quality: Int?, val bandwidth: Long?)

    private fun resolveUrl(base: String, ref: String): String {
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
        return try {
            java.net.URI(base).resolve(ref).toString()
        } catch (_: Exception) {
            if (ref.startsWith("/")) base.substringBefore("://") + "://" + base.substringAfter("://").substringBefore("/") + ref
            else base.substringBefore("?").substringBeforeLast("/") + "/" + ref
        }
    }

    private fun standardQuality(w: Int?, h: Int?): Int? {
        fun byH(x: Int) = when {
            x >= 2000 -> 2160
            x >= 1300 -> 1440
            x >= 1000 -> 1080
            x >= 700 -> 720
            x >= 460 -> 480
            x >= 340 -> 360
            x >= 230 -> 240
            else -> 144
        }

        fun byW(x: Int) = when {
            x >= 3600 -> 2160
            x >= 2400 -> 1440
            x >= 1800 -> 1080
            x >= 1200 -> 720
            x >= 800 -> 480
            x >= 600 -> 360
            x >= 400 -> 240
            else -> 144
        }
        if (w == null && h == null) return null
        return maxOf(w?.let { byW(it) } ?: 0, h?.let { byH(it) } ?: 0)
    }

    private fun parseMaster(text: String, base: String): List<Variant> {
        val lines = text.lines().map { it.trim() }
        val resRe = Regex("""RESOLUTION=(\d+)x(\d+)""", RegexOption.IGNORE_CASE)
        val bwRe = Regex("""(?:^|[:,])BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE)
        val res = ArrayList<Variant>()
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            if (l.startsWith("#EXT-X-STREAM-INF")) {
                val m = resRe.find(l)
                val bw = bwRe.find(l)?.groupValues?.get(1)?.toLongOrNull()
                var j = i + 1
                while (j < lines.size && (lines[j].isEmpty() || lines[j].startsWith("#"))) j++
                if (j < lines.size) {
                    val q = standardQuality(m?.groupValues?.get(1)?.toIntOrNull(), m?.groupValues?.get(2)?.toIntOrNull())
                    res.add(Variant(resolveUrl(base, lines[j]), q, bw))
                    i = j
                }
            }
            i++
        }
        return res.distinctBy { it.url }
    }

    /** Master playlist text: plain http first, then from inside the page (cookies / token bound urls). */
    private suspend fun fetchPlaylist(
        url: String,
        headers: Map<String, String>,
        session: CineLiteBrowser.Session?,
        sniff: Boolean
    ): String? {
        try {
            val h = if (sniff) headers + ("Range" to "bytes=0-1023") else headers
            val head = app.get(url, headers = h, timeout = 20_000L).text
            if (head.trimStart().startsWith("#EXTM3U")) {
                return if (sniff) app.get(url, headers = headers, timeout = 20_000L).text else head
            }
            if (sniff) return null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        if (session != null && !session.closed) {
            val r = session.get(url, false, 20_000L)
            if (r != null && r.first in 200..299 && r.second.trimStart().startsWith("#EXTM3U")) return r.second
        }
        return null
    }

    /**
     * Lists a stream. For HLS the master playlist is read and every resolution becomes its own
     * link (plus one "Auto" link with the master itself); other files are listed as they are.
     */
    private suspend fun emitStream(
        server: String,
        url: String,
        quality: Int?,
        kind: MK,
        headers: Map<String, String>,
        referer: String,
        session: CineLiteBrowser.Session?,
        out: (ExtractorLink) -> Unit
    ) {
        val base = "$name $server".trim()

        if (kind == MK.VIDEO) {
            out.invoke(
                newExtractorLink(name, base + (quality?.let { " ${it}p" } ?: ""), url, ExtractorLinkType.VIDEO) {
                    this.referer = referer
                    this.headers = headers
                    this.quality = quality ?: Qualities.Unknown.value
                }
            )
            return
        }

        val text = fetchPlaylist(url, headers, session, sniff = kind == MK.UNKNOWN)
        if (kind == MK.UNKNOWN && text == null) return // not a playlist, not something we can play

        val variants = if (text != null) parseMaster(text, url) else emptyList()
        for (v in variants.sortedWith(compareByDescending<Variant> { it.quality ?: 0 }.thenByDescending { it.bandwidth ?: 0L })) {
            val label = v.quality?.let { "${it}p" } ?: v.bandwidth?.let { "${it / 1000}kbps" } ?: "Stream"
            out.invoke(
                newExtractorLink(name, "$base $label", v.url, ExtractorLinkType.M3U8) {
                    this.referer = referer
                    this.headers = headers
                    this.quality = v.quality ?: Qualities.Unknown.value
                }
            )
        }
        // the master itself (adaptive); when it is a plain media playlist this is the only link
        out.invoke(
            newExtractorLink(
                name,
                if (variants.isEmpty()) base + (quality?.let { " ${it}p" } ?: " (Auto)") else "$base (Auto)",
                url,
                ExtractorLinkType.M3U8
            ) {
                this.referer = referer
                this.headers = headers
                this.quality = if (variants.isEmpty()) (quality ?: Qualities.Unknown.value) else Qualities.Unknown.value
            }
        )
    }

    // ---------------------------------------------------------------------- anime

    private val anilistCache = HashMap<String, Int?>()

    /** Best-effort TMDB title -> AniList id (AniList lists every season as its own entry). */
    private suspend fun findAniListId(title: String, season: Int): Int? {
        val search = if (season > 1) "$title Season $season" else title
        anilistCache[search]?.let { return it }
        val query = "query(\$s:String){Page(perPage:5){media(search:\$s,type:ANIME,format_in:[TV,TV_SHORT],sort:SEARCH_MATCH){id}}}"
        val res = app.post(
            "https://graphql.anilist.co",
            headers = mapOf("Accept" to "application/json", "User-Agent" to ua),
            json = mapOf("query" to query, "variables" to mapOf("s" to search)),
            timeout = 20_000L
        )
        val id = mapper.readTree(res.text)?.get("data")?.get("Page")?.get("media")?.get(0)?.get("id")?.asInt()
        anilistCache[search] = id
        return id
    }

    private fun jsonHeaders(referer: String) = mapOf(
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "Referer" to referer,
        "X-Requested-With" to "XMLHttpRequest",
        "User-Agent" to ua,
    )

    private fun playHeaders(referer: String) = mapOf(
        "Referer" to referer,
        "Origin" to referer.trimEnd('/'),
        "User-Agent" to ua,
    )

    private suspend fun animeLinks(
        title: String,
        season: Int,
        ep: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        out: (ExtractorLink) -> Unit,
        dbg: MutableList<String>
    ) {
        val aid = findAniListId(title, season)
        if (aid == null) {
            dbg.add("anilist: no match for $title")
            return
        }
        dbg.add("anilist id=$aid ep=$ep")

        // the three public sources the player itself uses (see log): megaplay, vidnest, 4animo
        for (audio in listOf("sub", "dub")) {
            val tag = audio.uppercase()
            try {
                megaplay(aid, ep, audio, tag, subtitleCallback, out, dbg)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                dbg.add("megaplay $tag EXC ${e.message?.take(80)}")
            }
            try {
                vidnest(aid, ep, audio, tag, subtitleCallback, out, dbg)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                dbg.add("vidnest $tag EXC ${e.message?.take(80)}")
            }
            try {
                fourAnimo(aid, ep, audio, tag, subtitleCallback, out, dbg)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                dbg.add("4animo $tag EXC ${e.message?.take(80)}")
            }
        }

        // catch-all: let the real player run and take every source response it gets through the
        // site proxy, whatever the json looks like
        try {
            animeFromPlayer(aid, ep, subtitleCallback, out, dbg)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            dbg.add("player EXC ${e.message?.take(80)}")
        }
    }

    private suspend fun megaplay(
        aid: Int, ep: Int, audio: String, tag: String,
        subtitleCallback: (SubtitleFile) -> Unit, out: (ExtractorLink) -> Unit, dbg: MutableList<String>
    ) {
        val page = "https://megaplay.buzz/stream/ani/$aid/$ep/$audio"
        val html = app.get(
            page,
            headers = mapOf("Referer" to "https://megaplay.buzz/", "User-Agent" to ua),
            timeout = 20_000L
        ).text
        val dataId = Regex("""data-id=["'](\d+)["']""").find(html)?.groupValues?.get(1)
        if (dataId == null) {
            dbg.add("megaplay $tag: no data-id")
            return
        }
        val json = app.get(
            "https://megaplay.buzz/stream/getSources?id=$dataId&id=$dataId",
            headers = jsonHeaders(page),
            timeout = 20_000L
        ).text
        val h = playHeaders("https://megaplay.buzz/")
        for (c in parseCandidates(json)) {
            emitStream("Megaplay $tag", c.url, c.quality, c.kind, h, "https://megaplay.buzz/", null, out)
        }
        if (audio == "sub") mapper.readTree(json)?.get("tracks")?.let { collectSubtitles(it, subtitleCallback) }
    }

    private suspend fun vidnest(
        aid: Int, ep: Int, audio: String, tag: String,
        subtitleCallback: (SubtitleFile) -> Unit, out: (ExtractorLink) -> Unit, dbg: MutableList<String>
    ) {
        val res = app.get(
            "https://new.vidnest.fun/hianime/anime/$aid/$ep/$audio",
            headers = mapOf(
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "Referer" to "https://vidnest.fun/",
                "User-Agent" to ua,
            ),
            timeout = 20_000L
        )
        if (res.code !in 200..299) {
            dbg.add("vidnest $tag ${res.code}")
            return
        }
        val h = playHeaders("https://vidnest.fun/")
        val cands = parseCandidates(res.text)
        if (cands.isEmpty()) dbg.add("vidnest $tag: no stream in ${res.text.take(100)}")
        for (c in cands) {
            val ch = h + (c.headers ?: emptyMap())
            emitStream(listOfNotNull("Vidnest $tag", c.label).joinToString(" "), c.url, c.quality, c.kind, ch,
                ch["Referer"] ?: "https://vidnest.fun/", null, out)
        }
        if (audio == "sub") {
            val root = mapper.readTree(res.text)
            (root?.get("subtitles") ?: root?.get("tracks"))?.let { collectSubtitles(it, subtitleCallback) }
        }
    }

    private suspend fun fourAnimo(
        aid: Int, ep: Int, audio: String, tag: String,
        subtitleCallback: (SubtitleFile) -> Unit, out: (ExtractorLink) -> Unit, dbg: MutableList<String>
    ) {
        val embed = "https://cdn.4animo.xyz/embed/ani/$aid/$ep/$audio?k=1"
        val html = app.get(
            embed,
            headers = mapOf("Referer" to "https://cdn.4animo.xyz/", "User-Agent" to ua),
            timeout = 20_000L
        ).text
        // the page carries a signed token that getSources wants as ?t=
        val token = Regex("""getSources\?t=([A-Za-z0-9._~%\-]+)""").find(html)?.groupValues?.get(1)
            ?: Regex("""["']t["']\s*[:=]\s*["']([A-Za-z0-9._~%\-]{20,})["']""").find(html)?.groupValues?.get(1)
        if (token == null) {
            dbg.add("4animo $tag: no token")
            return
        }
        val json = app.get(
            "https://cdn.4animo.xyz/stream/getSources?t=$token",
            headers = mapOf(
                "Accept" to "application/json, text/plain, */*",
                "Referer" to embed,
                "User-Agent" to ua,
            ),
            timeout = 20_000L
        ).text
        val h = playHeaders("https://cdn.4animo.xyz/")
        for (c in parseCandidates(json)) {
            emitStream("4Animo $tag", c.url, c.quality, c.kind, h, "https://cdn.4animo.xyz/", null, out)
        }
        if (audio == "sub") mapper.readTree(json)?.let { r ->
            (r.get("tracks") ?: r.get("subtitles"))?.let { collectSubtitles(it, subtitleCallback) }
        }
    }

    private fun refererForHost(host: String): String = when {
        host.contains("megaplay") -> "https://megaplay.buzz/"
        host.contains("vidnest") -> "https://vidnest.fun/"
        host.contains("4animo") -> "https://cdn.4animo.xyz/"
        else -> "https://$host/"
    }

    private fun serverLabelForHost(host: String): String = when {
        host.contains("megaplay") -> "Megaplay"
        host.contains("vidnest") -> "Vidnest"
        host.contains("4animo") -> "4Animo"
        else -> host.substringBefore(".").replaceFirstChar { it.uppercase() }
    }

    private suspend fun animeFromPlayer(
        aid: Int, ep: Int,
        subtitleCallback: (SubtitleFile) -> Unit, out: (ExtractorLink) -> Unit, dbg: MutableList<String>
    ) {
        val s = CineLiteBrowser.open("$embedUrl/anime/$aid/$ep?$embedQuery", ua, "$mainUrl/") ?: return
        try {
            s.waitFor(25_000L) { s.events.count { it.kind == "proxy" && it.status in 200..299 } >= 4 }
            delay(3000L)
            s.quiet()
            val seen = s.events.filter { it.kind == "proxy" && it.status in 200..299 && it.body.isNotBlank() }
            dbg.add("player proxy responses=${seen.size}")
            for (ev in seen) {
                val dec = try {
                    URLDecoder.decode(ev.url, "UTF-8")
                } catch (_: Exception) {
                    ev.url
                }
                val inner = Regex("""[?&]url=([^&]+)""").find(ev.url)?.groupValues?.get(1)
                    ?.let { try { URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it } } ?: continue
                val host = inner.substringAfter("://").substringBefore("/")
                val audio = Regex("""/(sub|dub)(?:[/?&"]|\z)""").find(dec)?.groupValues?.get(1)?.uppercase()
                val label = listOfNotNull(serverLabelForHost(host), audio).joinToString(" ")
                val ref = refererForHost(host)
                val h = playHeaders(ref)
                for (c in parseCandidates(ev.body)) {
                    emitStream(label, c.url, c.quality, c.kind, h + (c.headers ?: emptyMap()), ref, s, out)
                }
                if (audio == "SUB") {
                    try {
                        mapper.readTree(ev.body)?.let { r ->
                            (r.get("tracks") ?: r.get("subtitles"))?.let { collectSubtitles(it, subtitleCallback) }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        } finally {
            s.close()
        }
    }

    private suspend fun collectSubtitles(node: JsonNode?, cb: (SubtitleFile) -> Unit) {
        if (node == null) return
        if (node.isArray) {
            node.forEach { collectSubtitles(it, cb) }
        } else if (node.isObject) {
            if (node.get("kind")?.asText().equals("thumbnails", true)) return
            val url = listOf("url", "file", "src").firstNotNullOfOrNull { node.get(it)?.asText()?.takeIf { s -> s.startsWith("http") } }
            if (url != null) {
                val lang = listOf("lang", "language", "label", "name")
                    .firstNotNullOfOrNull { node.get(it)?.asText()?.takeIf { s -> s.isNotBlank() } } ?: "Unknown"
                cb.invoke(newSubtitleFile(lang, url))
            } else {
                node.fields().forEach { collectSubtitles(it.value, cb) }
            }
        }
    }
}
