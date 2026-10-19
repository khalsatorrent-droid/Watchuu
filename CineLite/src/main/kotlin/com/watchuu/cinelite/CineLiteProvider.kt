package com.watchuu.cinelite

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
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
            pageUrl = "$mainUrl/watch/$tmdbId"
            apiQuery = "id=$tmdbId"
        } else {
            season = parts.getOrNull(2)?.toIntOrNull()
            episode = parts.getOrNull(3)?.toIntOrNull()
            if (season == null || episode == null) return false
            title = parts.drop(4).joinToString("|")
            // NOTE: the log only contains a movie embed (/{id}) and an anime embed
            // (/anime/{id}/{ep}) and watch pages /watch/{id} and /watch/anime/{id}/{ep}; the TV pattern below is inferred, not observed.
            pageUrl = "$mainUrl/watch/tv/$tmdbId/$season/$episode"
            apiQuery = "id=$tmdbId&season=$season&episode=$episode"
        }

        // The provider API answers 403 TURNSTILE_REQUIRED without a solved Cloudflare
        // Turnstile check, so everything goes through a WebView on the real watch page.
        var found = false
        val dbg = mutableListOf<String>()
        var pageUrlFinal = pageUrl

        // Anime: the player calls public sources (megaplay / vidnest) keyed by the AniList id,
        // so no Turnstile is involved and several sub/dub servers can be listed.
        if (type == "atv") {
            try {
                val aid = findAniListId(title, season ?: 1)
                if (aid != null) {
                    found = animeSources(aid, episode ?: 1, subtitleCallback, callback)
                    pageUrlFinal = "$mainUrl/watch/anime/$aid/${episode ?: 1}"
                } else dbg.add("anilist: no match for $title")
            } catch (e: Exception) {
                dbg.add("anime EXC ${e.message?.take(80)}")
            }
            if (found) return true
        }

        // Phase 1: capture the first playlist the player requests (known to work).
        try {
            val resolver = WebViewResolver(
                interceptUrl = Regex("""(?i)\.m3u8"""),
                additionalUrls = listOf(Regex("""(?i)\.m3u8""")),
                userAgent = ua,
                useOkhttp = false,
                timeout = 20_000L
            )
            val m3u8 = app.get(pageUrlFinal, referer = "$mainUrl/", interceptor = resolver).url
            if (m3u8.isNotBlank() && m3u8.contains(".m3u8", ignoreCase = true)) {
                found = true
                callback.invoke(
                    newExtractorLink(name, "$name HLS (Auto)", m3u8, ExtractorLinkType.M3U8) {
                        this.referer = "$embedUrl/"
                        this.headers = embedHeaders
                        this.quality = Qualities.Unknown.value
                    }
                )
                // also list each quality variant of the master playlist separately
                try {
                    M3u8Helper.generateM3u8(name, m3u8, "$embedUrl/", headers = embedHeaders)
                        .forEach(callback)
                } catch (_: Exception) {
                }
            } else dbg.add("p1 -> ${m3u8.take(100)}")
        } catch (e: Exception) {
            dbg.add("p1 EXC ${e.message?.take(80)}")
        }

        if (found) return true

        // TEMPORARY diagnostics
        dbg.forEachIndexed { i, line ->
            callback.invoke(
                newExtractorLink(name, "DBG${i + 1} $line", "https://example.com/debug$i.m3u8", ExtractorLinkType.M3U8) {
                    this.quality = Qualities.Unknown.value
                }
            )
        }
        return true
    }

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

    private suspend fun animeSources(
        aid: Int,
        ep: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var any = false
        for (audio in listOf("sub", "dub")) {
            val tag = audio.uppercase()

            // megaplay: embed page carries a data-id, getSources returns the playlist
            try {
                val page = "https://megaplay.buzz/stream/ani/$aid/$ep/$audio"
                val html = app.get(
                    page,
                    headers = mapOf("Referer" to "https://megaplay.buzz/", "User-Agent" to ua),
                    timeout = 20_000L
                ).text
                val dataId = Regex("""data-id=["'](\d+)["']""").find(html)?.groupValues?.get(1)
                if (dataId != null) {
                    val json = app.get(
                        "https://megaplay.buzz/stream/getSources?id=$dataId&id=$dataId",
                        headers = mapOf(
                            "Accept" to "application/json, text/javascript, */*; q=0.01",
                            "Referer" to page,
                            "X-Requested-With" to "XMLHttpRequest",
                            "User-Agent" to ua,
                        ),
                        timeout = 20_000L
                    ).text
                    val root = mapper.readTree(json)
                    if (root != null) {
                        if (collectStreams(root, "Megaplay $tag", callback, "https://megaplay.buzz/")) any = true
                        if (audio == "sub") collectSubtitles(root.get("tracks"), subtitleCallback)
                    }
                }
            } catch (_: Exception) {
            }

            // vidnest (hianime mirror)
            try {
                val json = app.get(
                    "https://new.vidnest.fun/hianime/anime/$aid/$ep/$audio",
                    headers = mapOf(
                        "Accept" to "application/json, text/javascript, */*; q=0.01",
                        "Referer" to "https://vidnest.fun/",
                        "User-Agent" to ua,
                    ),
                    timeout = 20_000L
                ).text
                val root = mapper.readTree(json)
                if (root != null) {
                    if (collectStreams(root, "Vidnest $tag", callback, "https://vidnest.fun/")) any = true
                    if (audio == "sub") collectSubtitles(root, subtitleCallback)
                }
            } catch (_: Exception) {
            }
        }
        return any
    }

    /** Walks any JSON shape and emits every http(s) .m3u8/.mp4 string it finds. */
    private suspend fun collectStreams(
        node: JsonNode,
        provider: String,
        callback: (ExtractorLink) -> Unit,
        referer: String = "$embedUrl/"
    ): Boolean {
        val linkHeaders = mapOf(
            "Referer" to referer,
            "Origin" to referer.trimEnd('/'),
            "User-Agent" to ua,
        )
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
                                    this.referer = referer
                                    this.headers = linkHeaders
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
