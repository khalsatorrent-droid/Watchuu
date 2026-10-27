package com.watchuu.vedu

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
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
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Vedu (MovieBox-style Android app, v1.0.24). The app ships its stream resolvers as JavaScript
 * bundles in assets/resolvers; the TMDB-keyed ones are ported to Kotlin in [VeduSources]:
 *  - Orbit (67Movies network): VidRock, Vidnest, VidKing, VidLink, VidUp, Videasy, 111Movies,
 *    2Embed, Peachify, Cinezo, Vidlove, Vidsuper
 *  - Nova (Bingr): 11 api.bingr.one stream servers, plus Multi/Sub/Dub anime adapters
 * Catalog, search and metadata come from TMDB.
 */
class VeduProvider : MainAPI() {
    override var mainUrl = "https://moviesbox.net.co"
    override var name = "Vedu"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val tmdbApi = "https://api.tmdb.org/3"
    private val tmdbApiKey = "fca1c3fa675f74cf4aa8d24b72dd4807"
    private val tmdbImg = "https://image.tmdb.org/t/p/w500"
    private val tmdbBack = "https://image.tmdb.org/t/p/original"
    private val ua = VeduSources.UA

    override val mainPage = mainPageOf(
        "/trending/all/day" to "Trending Today",
        "/movie/popular" to "Popular Movies",
        "/tv/popular" to "Popular TV Shows",
        "/movie/now_playing" to "Now Playing",
        "/discover/tv?with_genres=16&with_original_language=ja&sort_by=popularity.desc" to "Anime",
        "/movie/top_rated" to "Top Rated Movies",
        "/tv/top_rated" to "Top Rated TV Shows",
        "/discover/tv?with_original_language=ko&sort_by=popularity.desc" to "K-Drama",
    )

    // ---------------------------------------------------------------- models

    data class LinkData(
        val type: String,
        val tmdbId: Int,
        val season: Int? = null,
        val episode: Int? = null,
        val absEpisode: Int? = null,
        val title: String = "",
        val originalTitle: String = "",
        val year: String = "",
        val imdbId: String = "",
        val totalSeasons: Int? = null,
        val anime: Boolean = false,
    )

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
    data class TmdbExternal(@JsonProperty("imdb_id") val imdbId: String? = null)

    data class TmdbDetail(
        val id: Int? = null,
        val title: String? = null,
        val name: String? = null,
        @JsonProperty("original_title") val originalTitle: String? = null,
        @JsonProperty("original_name") val originalName: String? = null,
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
        @JsonProperty("number_of_seasons") val numberOfSeasons: Int? = null,
        @JsonProperty("imdb_id") val imdbId: String? = null,
        val seasons: List<TmdbSeason>? = null,
        val videos: TmdbVideos? = null,
        val credits: TmdbCredits? = null,
        val recommendations: TmdbResp? = null,
        @JsonProperty("external_ids") val externalIds: TmdbExternal? = null,
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
            timeout = 30L
        ).text
    }

    // ---------------------------------------------------------- list / search

    private fun isAnime(genreIds: List<Int>?, lang: String?) =
        (genreIds ?: emptyList()).contains(16) && lang == "ja"

    private fun TmdbItem.toSearch(defaultType: String?): SearchResponse? {
        val type = mediaType ?: defaultType ?: return null
        if (type != "movie" && type != "tv") return null
        val id = id ?: return null
        val t = (if (type == "movie") title ?: name else name ?: title)?.takeIf { it.isNotBlank() } ?: return null
        val poster = posterPath?.let { tmdbImg + it }
        val year = (releaseDate ?: firstAirDate)?.take(4)?.toIntOrNull()
        val anime = isAnime(genreIds, originalLanguage)
        val score = voteAverage?.takeIf { it > 0 }?.let { Score.from10(it.toString()) }
        val url = "$mainUrl/$type/$id"
        return if (type == "movie") {
            newMovieSearchResponse(t, url, TvType.Movie) {
                this.posterUrl = poster; this.year = year; this.score = score
            }
        } else {
            newTvSeriesSearchResponse(t, url, if (anime) TvType.Anime else TvType.TvSeries) {
                this.posterUrl = poster; this.year = year; this.score = score
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

    // -------------------------------------------------------------------- load

    override suspend fun load(url: String): LoadResponse? {
        val path = url.removePrefix(mainUrl).trim('/').split("/")
        val tmdbType = path.getOrNull(0)?.takeIf { it == "movie" || it == "tv" } ?: return null
        val tmdbId = path.getOrNull(1)?.toIntOrNull() ?: return null

        val detail = try {
            parseJson<TmdbDetail>(tmdbGet("/$tmdbType/$tmdbId?append_to_response=credits,videos,recommendations,external_ids"))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }

        val title = (if (tmdbType == "movie") detail.title else detail.name) ?: detail.title ?: detail.name ?: return null
        val originalTitle = (if (tmdbType == "movie") detail.originalTitle else detail.originalName) ?: ""
        val yearStr = (detail.releaseDate ?: detail.firstAirDate)?.take(4) ?: ""
        val imdb = detail.imdbId ?: detail.externalIds?.imdbId ?: ""
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
        val anime = isAnime(detail.genres?.mapNotNull { it.id }, detail.originalLanguage)
        val recs = detail.recommendations?.results?.mapNotNull { it.toSearch(tmdbType) }

        if (tmdbType == "movie") {
            val data = LinkData("movie", tmdbId, title = title, originalTitle = originalTitle, year = yearStr, imdbId = imdb, anime = anime)
            return newMovieLoadResponse(title, url, if (anime) TvType.AnimeMovie else TvType.Movie, data.toJson()) {
                this.posterUrl = poster
                this.backgroundPosterUrl = bg
                this.year = yearStr.toIntOrNull()
                this.plot = detail.overview
                this.tags = genres
                this.score = score
                this.duration = detail.runtime
                this.actors = actors
                this.recommendations = recs
                addTrailer(trailer)
                addTMDbId(tmdbId.toString())
                addImdbId(imdb.takeIf { it.isNotBlank() })
            }
        }

        val seasons = detail.seasons?.filter { it.seasonNumber > 0 && (it.episodeCount ?: 0) > 0 }?.sortedBy { it.seasonNumber } ?: emptyList()
        val offsets = HashMap<Int, Int>()
        var running = 0
        for (s in seasons) {
            offsets[s.seasonNumber] = running
            running += s.episodeCount ?: 0
        }
        val episodes = coroutineScope {
            seasons.map { season ->
                async {
                    try {
                        parseJson<TmdbSeasonDetail>(tmdbGet("/tv/$tmdbId/season/${season.seasonNumber}")).episodes ?: emptyList()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        (1..(season.episodeCount ?: 0)).map { TmdbEpisode(episodeNumber = it, seasonNumber = season.seasonNumber) }
                    }
                }
            }.awaitAll().flatten()
        }.map { ep ->
            val data = LinkData(
                "tv", tmdbId, ep.seasonNumber, ep.episodeNumber,
                absEpisode = (offsets[ep.seasonNumber] ?: 0) + ep.episodeNumber,
                title = title, originalTitle = originalTitle, year = yearStr, imdbId = imdb,
                totalSeasons = detail.numberOfSeasons, anime = anime,
            )
            newEpisode(data.toJson()) {
                this.name = ep.name
                this.season = ep.seasonNumber
                this.episode = ep.episodeNumber
                this.posterUrl = ep.stillPath?.let { tmdbImg + it }
                this.description = ep.overview
                this.runTime = ep.runtime
                addDate(ep.airDate)
            }
        }

        return newTvSeriesLoadResponse(title, url, if (anime) TvType.Anime else TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = bg
            this.year = yearStr.toIntOrNull()
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
            addTMDbId(tmdbId.toString())
            addImdbId(imdb.takeIf { it.isNotBlank() })
        }
    }

    // ------------------------------------------------------------------- links

    private val anilistCache = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private suspend fun anilistId(search: String, isMovie: Boolean): Int? {
        if (search.isBlank()) return null
        val key = "$search|$isMovie"
        anilistCache[key]?.let { return it }
        return try {
            val format = if (isMovie) ", format: MOVIE" else ""
            val body = JSONObject()
                .put("query", "query (\$search: String) { Media(search: \$search, type: ANIME$format) { id } }")
                .put("variables", JSONObject().put("search", search))
                .toString()
            val r = app.post(
                "https://graphql.anilist.co/",
                headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json", "User-Agent" to ua),
                requestBody = body.toRequestBody("application/json".toMediaType()),
                timeout = 20L
            )
            JSONObject(r.text).optJSONObject("data")?.optJSONObject("Media")?.optInt("id")?.takeIf { it > 0 }
                ?.also { anilistCache[key] = it }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun job(block: suspend () -> Unit): suspend () -> Unit = block

    private fun qualityOf(label: String): Int =
        Regex("""(?i)(2160|1440|1080|720|576|540|480|360|240)""").find(label)?.groupValues?.get(1)?.toIntOrNull()
            ?: when {
                label.contains("4k", true) -> Qualities.P2160.value
                else -> Qualities.Unknown.value
            }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val d = try { parseJson<LinkData>(data) } catch (_: Exception) { return false }
        val target = VeduSources.Target(
            type = d.type, tmdbId = d.tmdbId.toString(), season = d.season, episode = d.episode,
            title = d.title, year = d.year, imdbId = d.imdbId, totalSeasons = d.totalSeasons,
        )

        val emittedLinks = HashSet<String>()
        val emittedSubs = HashSet<String>()
        val lock = Any()
        var found = false

        suspend fun emit(group: String, server: String, result: VeduSources.Result) {
            for (sub in result.subs) {
                val fresh = synchronized(lock) { emittedSubs.add(sub.url) }
                if (fresh) subtitleCallback.invoke(newSubtitleFile(sub.lang, sub.url))
            }
            for (src in result.sources) {
                val fresh = synchronized(lock) { emittedLinks.add(src.url).also { if (it) found = true } }
                if (!fresh) continue
                val kind = VeduSources.sourceKind(src.type, src.url)
                val type = when (kind) {
                    "dash" -> ExtractorLinkType.DASH
                    "mp4" -> ExtractorLinkType.VIDEO
                    else -> ExtractorLinkType.M3U8
                }
                val extra = src.label.takeIf { it.isNotBlank() && !it.equals(src.quality, true) }?.let { " ($it)" } ?: ""
                val headers = LinkedHashMap(src.headers)
                if (headers.keys.none { it.equals("User-Agent", true) }) headers["User-Agent"] = ua
                val referer = headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value ?: ""
                callback.invoke(
                    newExtractorLink(name, "$group · $server$extra", src.url, type) {
                        this.referer = referer
                        this.quality = qualityOf(src.quality.ifBlank { src.url })
                        this.headers = headers
                    }
                )
            }
        }

        val jobs = ArrayList<Pair<String, suspend () -> Unit>>()
        for (srv in VeduSources.orbitServers) {
            jobs.add(srv.name to job { emit("Orbit", srv.name, srv.resolve(target)) })
        }
        if (d.title.isNotBlank()) {
            for (srv in VeduSources.novaServers) {
                jobs.add(srv to job { emit("Nova", srv.uppercase(), VeduSources.bingr(srv, target)) })
            }
        }
        if (d.anime) {
            val isMovie = d.type == "movie"
            val ep = if (isMovie) 1 else (d.absEpisode ?: d.episode ?: 1)
            jobs.add("multi" to job {
                emit("Nova Anime", "Multi", VeduSources.animeMulti(listOf(d.title, d.originalTitle), ep, isMovie))
            })
            jobs.add("subdub" to job {
                // AniList splits seasons; try "<title> Season N" first for later seasons.
                val season = d.season ?: 1
                val seasonal = if (!isMovie && season > 1) anilistId("${d.title} Season $season", false) else null
                val aid = seasonal ?: anilistId(d.title, isMovie) ?: anilistId(d.originalTitle, isMovie)
                if (aid != null) {
                    val epNum = if (isMovie) 1 else if (seasonal != null) (d.episode ?: 1) else ep
                    coroutineScope {
                        listOf(false, true).map { dub ->
                            async {
                                try {
                                    emit("Nova Anime", if (dub) "Dub" else "Sub", VeduSources.animeSubDub(aid, epNum, dub))
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                }
                            }
                        }.awaitAll()
                    }
                }
            })
        }

        val gate = Semaphore(8)
        coroutineScope {
            jobs.map { (_, job) ->
                async {
                    gate.withPermit {
                        try {
                            withTimeoutOrNull(45_000L) { job() }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                        }
                    }
                }
            }.awaitAll()
        }
        return found
    }
}
