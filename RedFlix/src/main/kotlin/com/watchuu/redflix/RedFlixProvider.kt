package com.watchuu.redflix

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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * RedFlix (redflix.one) - a TMDB front-end with a dozen embed servers.
 *
 * Built from a captured network log:
 *  - catalog / search / details : https://redflix.one/api/tmdb/{tmdb path}?api_key=&language=en-US
 *                                 (the site's own TMDB proxy; direct TMDB is the fallback)
 *  - player                     : https://redflix.one/play?id={tmdbId}&type=tv&season=S&episode=E&server={name}
 *                                 which only iframes a third-party embed, e.g. vidfast.vc/tv/{id}/{s}/{e}
 *  - streams                    : each embed requests an HLS (.m3u8) or DASH (.mpd) manifest
 *
 * Link resolution: every server's embed page is opened in a hidden WebView (RedFlixBrowser), the
 * manifest it requests is captured with its Referer, and listed under the server's name.
 * Anime uses the same TMDB ids, so it goes down the same path as TV.
 */
class RedFlixProvider : MainAPI() {
    override var mainUrl = "https://redflix.one"
    override var name = "RedFlix"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val siteTmdb = "$mainUrl/api/tmdb"
    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbApiKey = "4e44d9029b1270a757cddc766a1bcb63"
    private val tmdbImg = "https://image.tmdb.org/t/p/w500"
    private val tmdbBack = "https://image.tmdb.org/t/p/original"

    private val ua =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    // ------------------------------------------------------------------ servers

    /** One embed server. Templates use {id}, {s}, {e}. */
    private class Server(val name: String, val movie: String, val tv: String)

    // Order = the order RedFlix lists them / how reliable they were in the log.
    private val servers = listOf(
        Server("Videasy", "https://player.videasy.to/movie/{id}", "https://player.videasy.to/tv/{id}/{s}/{e}?nextEpisode=true"),
        Server("VidFast", "https://vidfast.vc/movie/{id}?autoPlay=true", "https://vidfast.vc/tv/{id}/{s}/{e}?autoPlay=true"),
        Server("VidLink", "https://vidlink.pro/movie/{id}?autoplay=true", "https://vidlink.pro/tv/{id}/{s}/{e}?autoplay=true"),
        Server("VidUp", "https://vidup.to/movie/{id}", "https://vidup.to/tv/{id}/{s}/{e}"),
        Server("VidBolt", "https://vidbolt.xyz/movie/{id}?autoPlay=true", "https://vidbolt.xyz/tv/{id}/{s}/{e}?autoPlay=true"),
        Server("VidCore", "https://vidcore.net/movie/{id}", "https://vidcore.net/tv/{id}/{s}/{e}"),
        Server("Cinezo", "https://player.cinezo.live/embed/movie/{id}?autoplay=true", "https://player.cinezo.live/embed/tv/{id}/{s}/{e}?autoplay=true"),
        Server("VidStuck", "https://vidstuck.cc/embed/movie/{id}", "https://vidstuck.cc/embed/tv/{id}/{s}/{e}"),
        Server("VidRift", "https://embed.vidrift.in/embed/movie/{id}", "https://embed.vidrift.in/embed/tv/{id}/{s}/{e}"),
        Server("Filmu", "https://embed.filmu.in/movie/{id}", "https://embed.filmu.in/tv/{id}/{s}/{e}"),
        Server("PlayAPI", "https://playapi.co/movie/{id}", "https://playapi.co/tv/{id}/{s}/{e}"),
        Server("Hindi (ScreenScape)", "https://screenscape.me/embed?tmdb={id}&type=movie&lan=hindi", "https://screenscape.me/embed?tmdb={id}&type=tv&lan=hindi&s={s}&e={e}"),
        Server("Hindi (New)", "https://embed.reelsdownload.online/player/{id}?key=k_885c7f4e1997d1d9c4e42aff", "https://embed.reelsdownload.online/player/{id}/{s}/{e}?key=k_885c7f4e1997d1d9c4e42aff"),
    )

    // ---------------------------------------------------------------- home page

    override val mainPage = mainPageOf(
        "/trending/all/day" to "Trending Today",
        "/movie/popular" to "Popular Movies",
        "/tv/popular" to "Popular TV Shows",
        "/discover/movie?with_watch_providers=8&watch_region=US&sort_by=popularity.desc" to "Netflix Movies",
        "/discover/tv?with_watch_providers=8&watch_region=US&sort_by=popularity.desc" to "Netflix Shows",
        "/discover/movie?with_watch_providers=9&watch_region=US&sort_by=popularity.desc" to "Prime Video Movies",
        "/discover/tv?with_watch_providers=337&watch_region=US&sort_by=popularity.desc" to "Disney+ Shows",
        "/discover/tv?with_genres=16&with_original_language=ja&sort_by=popularity.desc" to "Anime",
        "/discover/movie?with_original_language=hi&sort_by=popularity.desc" to "Bollywood",
        "/movie/top_rated" to "Top Rated Movies",
        "/tv/top_rated" to "Top Rated TV Shows",
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

    /** The site's own TMDB proxy first (same calls as the site), then TMDB directly. */
    private suspend fun tmdbGet(path: String): String {
        val sep = if (path.contains("?")) "&" else "?"
        try {
            val r = app.get(
                "$siteTmdb$path${sep}api_key=&language=en-US",
                headers = mapOf("Accept" to "application/json", "User-Agent" to ua, "Referer" to "$mainUrl/"),
                timeout = 20_000L
            )
            val t = r.text
            if (r.code in 200..299 && t.trimStart().startsWith("{")) return t
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
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
            // the site loads more pages as you scroll; one page is only 20 mixed results (incl.
            // people), so the first 3 pages are fetched at once
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
                            // season details failed: still list numbered episodes
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

        val emitted = HashSet<String>()
        var found = false
        val lock = Any()

        // 7 servers load at the same time (13 servers = 2 rounds of at most 15 s each).
        // Links are handed to Cloudstream as soon as each server answers.
        val gate = Semaphore(7)
        coroutineScope {
            servers.map { srv ->
                async {
                    gate.withPermit {
                        val embed = (if (isMovie) srv.movie else srv.tv)
                            .replace("{id}", tmdbId)
                            .replace("{s}", s?.toString() ?: "")
                            .replace("{e}", e?.toString() ?: "")
                        val hits = try {
                            RedFlixBrowser.sniff(embed, "$mainUrl/", ua, 15_000L)
                        } catch (ex: CancellationException) {
                            throw ex
                        } catch (_: Exception) {
                            emptyList()
                        }
                        val best = pickBest(hits) ?: return@withPermit
                        val link = toLink(srv.name, best, embed)
                        synchronized(lock) {
                            if (!emitted.add(best.url)) return@withPermit
                            found = true
                        }
                        callback.invoke(link)
                    }
                }
            }.awaitAll()
        }
        return found
    }

    /** Master playlist / DASH manifest first, a single-quality playlist only if that is all there is. */
    private fun pickBest(hits: List<RedFlixBrowser.Hit>): RedFlixBrowser.Hit? {
        if (hits.isEmpty()) return null
        return hits.firstOrNull { it.url.contains("master", true) }
            ?: hits.firstOrNull { it.url.contains(".mpd", true) }
            ?: hits.firstOrNull { it.url.contains("playlist", true) || it.url.contains("index.m3u8", true) }
            ?: hits.first()
    }

    private fun originOf(u: String): String? {
        val scheme = u.substringBefore("://", "")
        if (scheme.isBlank()) return null
        val host = u.substringAfter("://").substringBefore("/").substringBefore("?")
        return if (host.isBlank()) null else "$scheme://$host"
    }

    private suspend fun toLink(server: String, hit: RedFlixBrowser.Hit, embed: String): ExtractorLink {
        // segments in the log were requested with the player's origin as Origin and Referer
        val origin = hit.origin?.takeIf { it.startsWith("http") }
            ?: hit.referer?.let { originOf(it) }
            ?: originOf(embed)
            ?: mainUrl
        val referer = "$origin/"
        val isDash = hit.url.substringBefore("?").endsWith(".mpd", true) ||
            (hit.url.contains(".mpd", true) && !hit.url.contains(".m3u8", true))
        val q = Regex("""(?i)(2160|1440|1080|720|480|360)p?""").find(hit.url)?.groupValues?.get(1)?.toIntOrNull()
        return newExtractorLink(
            name,
            "$name $server",
            hit.url,
            if (isDash) ExtractorLinkType.DASH else ExtractorLinkType.M3U8
        ) {
            this.referer = referer
            this.quality = q ?: Qualities.Unknown.value
            this.headers = mapOf("Origin" to origin, "Referer" to referer, "User-Agent" to ua)
        }
    }
}
