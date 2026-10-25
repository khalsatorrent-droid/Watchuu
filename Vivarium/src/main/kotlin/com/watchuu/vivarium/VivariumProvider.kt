package com.watchuu.vivarium

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Vivarium (vivarium.su) - a Next.js TMDB front-end whose streams sit behind Cloudflare and a
 * request-signing scheme (x-ts / x-sig / x-cv headers, encrypted /api/sources response). That
 * signing + decryption is done entirely in the site's own JavaScript, so rather than reproduce it,
 * the player page is opened in a hidden WebView that runs the site as a real browser would, and the
 * HLS manifest it ends up loading is captured.
 *
 * Built from a captured network log:
 *  - catalog / search / details : TMDB directly (the site's /api/find is just TMDB multi-search)
 *  - title page                 : https://vivarium.su/s/{slug}-{tmdbId}
 *  - player                     : https://vivarium.su/s/{slug}-{tmdbId}?w=1[&s=S&e=E]
 *  - streams                    : master.m3u8 on *.itsmeowenthankyouforyourservice.online
 *                                 (Referer https://vivarium.su/)
 */
class VivariumProvider : MainAPI() {
    override var mainUrl = "https://vivarium.su"
    override var name = "Vivarium"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbApiKey = "4e44d9029b1270a757cddc766a1bcb63"
    private val tmdbImg = "https://image.tmdb.org/t/p/w500"
    private val tmdbBack = "https://image.tmdb.org/t/p/original"

    private val ua =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private fun site() = VivariumSettings.siteUrl

    // ---------------------------------------------------------------- home page

    override val mainPage = mainPageOf(
        "/trending/all/day" to "Trending Today",
        "/movie/popular" to "Popular Movies",
        "/tv/popular" to "Popular TV Shows",
        "/movie/top_rated" to "Top Rated Movies",
        "/tv/top_rated" to "Top Rated TV Shows",
        "/discover/tv?with_genres=16&with_original_language=ja&sort_by=popularity.desc" to "Anime",
        "/discover/movie?with_original_language=hi&sort_by=popularity.desc" to "Bollywood",
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
        @JsonProperty("vote_average") val voteAverage: Double? = null,
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

    data class TmdbVideo(val key: String? = null, val site: String? = null, val type: String? = null)
    data class TmdbVideos(val results: List<TmdbVideo>? = null)
    data class TmdbCast(val name: String? = null, val character: String? = null, @JsonProperty("profile_path") val profilePath: String? = null)
    data class TmdbCredits(val cast: List<TmdbCast>? = null)

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
        val videos: TmdbVideos? = null,
        val credits: TmdbCredits? = null,
        val recommendations: TmdbResp? = null,
    )

    data class TmdbEpisode(
        @JsonProperty("episode_number") val episodeNumber: Int = 0,
        @JsonProperty("season_number") val seasonNumber: Int = 0,
        val name: String? = null,
        val overview: String? = null,
        @JsonProperty("still_path") val stillPath: String? = null,
        val runtime: Int? = null,
        @JsonProperty("air_date") val airDate: String? = null,
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

    // Payloads:  movie|123|Title   tv|123|Title   ep|123|S|E|Title   (Cloudstream may prefix mainUrl)
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
        val score = voteAverage?.takeIf { it > 0 }?.let { Score.from10(it.toString()) }
        return if (type == "movie") {
            newMovieSearchResponse(t, "$mainUrl/movie|$id|$t", TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                this.score = score
            }
        } else {
            newTvSeriesSearchResponse(t, "$mainUrl/tv|$id|$t", if (isAnime) TvType.Anime else TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
                this.score = score
            }
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
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return try {
            val q = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
            val first = parseJson<TmdbResp>(tmdbGet("/search/multi?query=$q&page=1&include_adult=false"))
            val pages = (first.totalPages ?: 1).coerceAtMost(3)
            val rest = coroutineScope {
                (2..pages).map { p ->
                    async {
                        try {
                            parseJson<TmdbResp>(tmdbGet("/search/multi?query=$q&page=$p&include_adult=false")).results
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                    }
                }.awaitAll()
            }
            ((first.results ?: emptyList()) + rest.filterNotNull().flatten())
                .mapNotNull { it.toSearch(null) }
                .distinctBy { it.url }
        } catch (e: CancellationException) {
            throw e
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
        val tmdbType = if (type == "movie") "movie" else "tv"

        val detail = try {
            parseJson<TmdbDetail>(tmdbGet("/$tmdbType/$tmdbId?append_to_response=credits,videos,recommendations"))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }

        val title = detail.title ?: detail.name ?: fallbackTitle
        val year = (detail.releaseDate ?: detail.firstAirDate)?.take(4)?.toIntOrNull()
        val poster = detail.posterPath?.let { tmdbImg + it }
        val bg = detail.backdropPath?.let { tmdbBack + it }
        val genres = detail.genres?.mapNotNull { it.name } ?: emptyList()
        val score = detail.voteAverage?.takeIf { it > 0 }?.let { Score.from10(it.toString()) }
        val trailer = detail.videos?.results
            ?.firstOrNull { it.site == "YouTube" && it.type == "Trailer" && it.key != null }
            ?.key?.let { "https://www.youtube.com/watch?v=$it" }
        val actors = detail.credits?.cast?.take(15)?.mapNotNull { c ->
            val n = c.name ?: return@mapNotNull null
            ActorData(Actor(n, c.profilePath?.let { tmdbImg + it }), roleString = c.character)
        }
        val recs = detail.recommendations?.results?.mapNotNull { it.toSearch(tmdbType) }

        if (tmdbType == "movie") {
            return newMovieLoadResponse(title, url, TvType.Movie, "$mainUrl/movie|$tmdbId|$title") {
                this.posterUrl = poster
                this.backgroundPosterUrl = bg
                this.year = year
                this.plot = detail.overview
                this.tags = genres
                this.score = score
                this.duration = detail.runtime
                this.actors = actors
                this.recommendations = recs
                addTrailer(trailer)
            }
        }

        val isAnime = detail.originalLanguage == "ja" && (detail.genres?.any { it.id == 16 } == true)
        val episodes = coroutineScope {
            detail.seasons
                ?.filter { it.seasonNumber > 0 && (it.episodeCount ?: 0) > 0 }
                ?.map { season ->
                    async {
                        try {
                            parseJson<TmdbSeasonDetail>(tmdbGet("/tv/$tmdbId/season/${season.seasonNumber}"))
                                .episodes ?: emptyList()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            (1..(season.episodeCount ?: 0)).map {
                                TmdbEpisode(episodeNumber = it, seasonNumber = season.seasonNumber)
                            }
                        }
                    }
                }?.awaitAll()?.flatten() ?: emptyList()
        }.map { ep ->
            newEpisode("$mainUrl/ep|$tmdbId|${ep.seasonNumber}|${ep.episodeNumber}|$title") {
                this.name = ep.name
                this.season = ep.seasonNumber
                this.episode = ep.episodeNumber
                this.posterUrl = ep.stillPath?.let { tmdbImg + it }
                this.description = ep.overview
                this.runTime = ep.runtime
                addDate(ep.airDate)
            }
        }

        return newTvSeriesLoadResponse(title, url, if (isAnime) TvType.Anime else TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = bg
            this.year = year
            this.plot = detail.overview
            this.tags = genres
            this.score = score
            this.actors = actors
            this.recommendations = recs
            this.showStatus = when (detail.status) {
                "Returning Series" -> ShowStatus.Ongoing
                "Ended", "Canceled" -> ShowStatus.Completed
                else -> null
            }
            addTrailer(trailer)
        }
    }

    // -------------------------------------------------------------------- links

    /** vivarium.su/s/{slug}-{tmdbId}  - the slug is cosmetic (Next.js routes on the id). */
    private fun slug(title: String): String =
        title.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "title" }

    private fun playerUrl(tmdbId: String, title: String, s: Int?, e: Int?): String {
        val base = "${site()}/s/${slug(title)}-$tmdbId?w=1"
        return if (s != null && e != null) "$base&s=$s&e=$e" else base
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = payloadParts(data)
        val type = parts.getOrNull(0) ?: return false
        val tmdbId = parts.getOrNull(1) ?: return false
        val isMovie = type == "movie"
        val s = if (isMovie) null else parts.getOrNull(2)?.toIntOrNull() ?: return false
        val e = if (isMovie) null else parts.getOrNull(3)?.toIntOrNull() ?: return false
        val title = parts.drop(if (isMovie) 2 else 4).joinToString("|").ifBlank { tmdbId }

        val url = playerUrl(tmdbId, title, s, e)
        val sniffed = try {
            VivariumBrowser.sniffPlayer(url)
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            VivariumSettings.lastLinkReport = "Error: ${ex.message}\nTrace: ${VivariumBrowser.lastTrace}"
            return false
        }

        val referer = "${site()}/"
        val headers = mapOf("Referer" to referer, "Origin" to site(), "User-Agent" to VivariumBrowser.userAgent())

        var subs = 0
        sniffed.subs.forEach { sub ->
            subtitleCallback.invoke(newSubtitleFile(sub.lang, sub.url))
            subs++
        }

        // one link per provider/quality the /api/es stream listed, best quality first
        val ordered = sniffed.streams.sortedByDescending { it.quality ?: 0 }
        ordered.forEach { st ->
            val label = buildString {
                append(name)
                if (st.provider.isNotBlank() && !st.provider.equals(name, true)) append(" ").append(st.provider)
                st.quality?.let { append(" ${it}p") }
            }
            callback.invoke(
                newExtractorLink(name, label, st.url, ExtractorLinkType.M3U8) {
                    this.referer = referer
                    this.quality = st.quality ?: Qualities.Unknown.value
                    this.headers = headers
                }
            )
        }

        VivariumSettings.lastLinkReport = buildString {
            append("Player: ").append(url).append('\n')
            append("Streams: ").append(ordered.size).append(", subtitles: ").append(subs).append('\n')
            append("Via: ").append(VivariumBrowser.lastMethod).append('\n')
            if (ordered.isNotEmpty()) {
                append("Providers: ").append(ordered.joinToString(", ") { "${it.provider}${it.quality?.let { q -> " ${q}p" } ?: ""}" }.take(200))
            } else {
                append("No streams captured.")
            }
            append("\nTrace: ").append(VivariumBrowser.lastTrace)
        }
        return ordered.isNotEmpty()
    }
}
