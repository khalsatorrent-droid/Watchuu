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
import java.net.URLEncoder

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
 * The log has no response bodies, so link resolution is defensive:
 *  1. try the provider API directly and scan the JSON for stream urls,
 *  2. otherwise load the embed page in a WebView (which solves Turnstile) and
 *     capture the .m3u8 request the player makes.
 */
class CineLiteProvider : MainAPI() {
    override var mainUrl = "https://cinelite.xyz"
    override var name = "CineLite"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

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
        val tvType = if (type == "movie") TvType.Movie else TvType.TvSeries
        return newMovieSearchResponse(t, "$mainUrl/$type|$id|$t", tvType) {
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
            val detail = parseJson<TmdbDetail>(tmdbGet("/$type/$tmdbId"))
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
                                val epData = "$mainUrl/tv|$tmdbId|${ep.seasonNumber}|${ep.episodeNumber}|$title"
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

                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = payloadParts(data)
        val type = parts.getOrNull(0) ?: return false
        val tmdbId = parts.getOrNull(1)?.toIntOrNull() ?: return false

        val season: Int?
        val episode: Int?
        val title: String
        val pageUrl: String
        val apiQuery: String
        if (type == "movie") {
            season = null; episode = null
            title = parts.drop(2).joinToString("|")
            pageUrl = "$embedUrl/$tmdbId"
            apiQuery = "id=$tmdbId"
        } else {
            season = parts.getOrNull(2)?.toIntOrNull()
            episode = parts.getOrNull(3)?.toIntOrNull()
            if (season == null || episode == null) return false
            title = parts.drop(4).joinToString("|")
            // NOTE: the log only contains a movie embed (/{id}) and an anime embed
            // (/anime/{id}/{ep}); the TV pattern below is inferred, not observed.
            pageUrl = "$embedUrl/tv/$tmdbId/$season/$episode"
            apiQuery = "id=$tmdbId&season=$season&episode=$episode"
        }

        var found = false
        val dbg = mutableListOf<String>()

        // 1) provider API (best effort - may be rejected without a Turnstile session)
        val apiHeaders = embedHeaders + mapOf("Accept" to "application/json, text/plain, */*")
        try {
            val r = app.get("$apiUrl/servers", headers = apiHeaders, timeout = 15_000L)
            dbg.add("servers ${r.code}: ${r.text.take(120)}")
        } catch (e: Exception) {
            dbg.add("servers EXC ${e.message?.take(80)}")
        }
        for (p in providers) {
            try {
                val res = app.get("$apiUrl/provider/$p?$apiQuery", headers = apiHeaders, timeout = 20_000L)
                dbg.add("$p ${res.code}: ${res.text.take(160)}")
                if (res.code !in 200..299) continue
                val root = mapper.readTree(res.text) ?: continue
                found = collectStreams(root, p, callback) || found
            } catch (e: Exception) {
                dbg.add("$p EXC ${e.message?.take(80)}")
            }
        }

        // subtitles endpoint seen in the log: /subtitles?id=&title=&year=
        try {
            val t = URLEncoder.encode(title, "UTF-8")
            val res = app.get("$apiUrl/subtitles?$apiQuery&title=$t", headers = apiHeaders, timeout = 15_000L)
            if (res.code in 200..299) collectSubtitles(mapper.readTree(res.text), subtitleCallback)
        } catch (_: Exception) {
        }

        if (found) return true

        // 2) WebView: let the real player solve Turnstile and capture the playlist request
        try {
            val resolver = WebViewResolver(
                interceptUrl = Regex("""(?i)\.m3u8"""),
                additionalUrls = listOf(Regex("""(?i)\.m3u8""")),
                script = """document.querySelector('button,[role="button"],.jw-icon-display,.vds-play-button')?.click();""",
                useOkhttp = false,
                timeout = 40_000L
            )
            val m3u8 = app.get(
                "$pageUrl?t=0&reset=1&nosuggestionui=true&color=e50914&quality=1080p",
                referer = "$mainUrl/",
                interceptor = resolver
            ).url
            dbg.add("webview -> ${m3u8.take(140)}")
            if (m3u8.isNotBlank() && m3u8.contains(".m3u8", ignoreCase = true)) {
                callback.invoke(
                    newExtractorLink(name, "$name HLS", m3u8, ExtractorLinkType.M3U8) {
                        this.referer = "$embedUrl/"
                        this.headers = embedHeaders
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }
        } catch (e: Exception) {
            dbg.add("webview EXC ${e.message?.take(100)}")
        }

        // TEMPORARY diagnostics: show what each step returned as fake "links"
        dbg.forEachIndexed { i, line ->
            callback.invoke(
                newExtractorLink(name, "DBG${i + 1} $line", "https://example.com/debug$i.m3u8", ExtractorLinkType.M3U8) {
                    this.quality = Qualities.Unknown.value
                }
            )
        }
        return true
    }

    /** Walks any JSON shape and emits every http(s) .m3u8/.mp4 string it finds. */
    private suspend fun collectStreams(
        node: JsonNode,
        provider: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var any = false
        val seen = HashSet<String>()
        suspend fun walk(n: JsonNode) {
            when {
                n.isTextual -> {
                    val v = n.asText()
                    if (v.startsWith("http") && seen.add(v)) {
                        val isHls = v.contains(".m3u8", true) || v.contains("/hls", true)
                        val isMp4 = v.substringBefore("?").endsWith(".mp4", true)
                        if (isHls || isMp4) {
                            any = true
                            callback.invoke(
                                newExtractorLink(
                                    name,
                                    "$name $provider",
                                    v,
                                    if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = "$embedUrl/"
                                    this.headers = embedHeaders
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                        }
                    }
                }
                n.isArray -> n.forEach { walk(it) }
                n.isObject -> n.fields().forEach { walk(it.value) }
            }
        }
        walk(node)
        return any
    }

    private suspend fun collectSubtitles(node: JsonNode?, cb: (SubtitleFile) -> Unit) {
        if (node == null) return
        if (node.isArray) {
            node.forEach { collectSubtitles(it, cb) }
        } else if (node.isObject) {
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
