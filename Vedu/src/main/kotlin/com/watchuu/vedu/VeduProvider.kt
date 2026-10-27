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
import com.lagradost.cloudstream3.utils.loadExtractor
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
 * plus the resolvers that key titles by their own site ids, each found by searching the site:
 *  - MovieBox (signed aoneroom api, see [VeduMovieBox]) incl. language variants and ShortTV
 *  - 9Anime (Anikoto/MegaPlay), AnimePahe, Dramika, NetMirror (Netflix/Prime/Hotstar), ReelShort ([VeduSites])
 *  - Flixer, VidFast and vidsrc.mov (WASM / packet-cipher players) through a hidden WebView ([VeduBrowser])
 * Catalog, search and metadata come from TMDB; ShortTV and ReelShort short dramas have their own rows.
 */
class VeduProvider : MainAPI() {
    override var mainUrl = "https://moviesbox.net.co"
    override var name = "Vedu"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AnimeMovie, TvType.AsianDrama)

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
        "shorttv" to "MovieBox ShortTV",
        "reelshort" to "ReelShort",
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
        val lang: String = "",
        val kind: String = "tmdb",   // tmdb | shorttv | reelshort
        val ref: String = "",        // ShortTV subject id / ReelShort book id
        val chapter: String = "",    // ReelShort chapter id
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

    private fun VeduMovieBox.Subject.toShortSearch(): SearchResponse =
        newTvSeriesSearchResponse(title, "$mainUrl/shorttv/$id", TvType.TvSeries) { this.posterUrl = cover }

    private fun VeduSites.Book.toReelSearch(): SearchResponse =
        newTvSeriesSearchResponse(title, "$mainUrl/reelshort/$id", TvType.TvSeries) { this.posterUrl = cover }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == "shorttv") {
            val items = VeduMovieBox.trendingShorts(page).map { it.toShortSearch() }
            return newHomePageResponse(request.name, items, items.isNotEmpty())
        }
        if (request.data == "reelshort") {
            val items = if (page == 1) VeduSites.reelHome().map { it.toReelSearch() } else emptyList()
            return newHomePageResponse(request.name, items, false)
        }
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
            val tmdb = ((first.results ?: emptyList()) + rest.filterNotNull().flatten())
                .mapNotNull { it.toSearch(null) }
                .distinctBy { it.url }
            // short dramas live outside TMDB: MovieBox ShortTV (subjectType 7) and ReelShort books
            val shorts = coroutineScope {
                val mb = async { safeList { VeduMovieBox.search(query.trim()).filter { it.type == 7 }.map { it.toShortSearch() } } }
                val rs = async { safeList { VeduSites.reelSearch(query.trim()).map { it.toReelSearch() } } }
                mb.await() + rs.await()
            }
            tmdb + shorts
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun <T> safeList(block: suspend () -> List<T>): List<T> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }

    // -------------------------------------------------------------------- load

    private suspend fun loadShortTv(url: String, id: String): LoadResponse? {
        val d = try { VeduMovieBox.subjectDetail(id) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        val subject = (d?.optJSONObject("subject") ?: d) ?: return null
        val title = subject.optString("title").takeIf { it.isNotBlank() } ?: return null
        val cover = subject.optJSONObject("cover")?.optString("url")
        // episode count: detail fields first, else count what mini-list returns
        var total = listOf("totalEpisode", "episodeCount", "totalEp", "maxEp").firstNotNullOfOrNull { k -> subject.optInt(k).takeIf { it > 0 } }
            ?: d?.optJSONObject("resource")?.optJSONArray("seasons")?.optJSONObject(0)?.optInt("maxEp")?.takeIf { it > 0 }
        if (total == null) {
            val (info, eps) = try { VeduMovieBox.shortEpisodes(id, 1, 300) } catch (e: CancellationException) { throw e } catch (_: Exception) { null to emptyList() }
            total = info?.optInt("totalEpisode")?.takeIf { it > 0 } ?: eps.maxOfOrNull { it.ep }
        }
        val episodes = (1..(total ?: 1)).map { ep ->
            newEpisode(LinkData("tv", 0, 1, ep, title = title, kind = "shorttv", ref = id).toJson()) {
                this.name = "Episode $ep"; this.season = 1; this.episode = ep
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = cover
            this.plot = subject.optString("description").takeIf { it.isNotBlank() }
            this.tags = listOf("Short Drama")
        }
    }

    private suspend fun loadReelShort(url: String, id: String): LoadResponse? {
        val book = try { VeduSites.reelBook(id) } catch (e: CancellationException) { throw e } catch (_: Exception) { null } ?: return null
        val episodes = book.chapters.map { c ->
            newEpisode(LinkData("tv", 0, 1, c.serial, title = book.title, kind = "reelshort", ref = id, chapter = c.id).toJson()) {
                this.name = "Episode ${c.serial}" + if (book.paidStart != null && c.serial >= book.paidStart) " (paid)" else ""
                this.season = 1; this.episode = c.serial
            }
        }
        return newTvSeriesLoadResponse(book.title, url, TvType.TvSeries, episodes) {
            this.posterUrl = book.cover
            this.plot = book.description
            this.tags = listOf("Short Drama")
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val path = url.removePrefix(mainUrl).trim('/').split("/")
        if (path.getOrNull(0) == "shorttv") return loadShortTv(url, path.getOrNull(1) ?: return null)
        if (path.getOrNull(0) == "reelshort") return loadReelShort(url, path.getOrNull(1) ?: return null)
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
            val data = LinkData("movie", tmdbId, title = title, originalTitle = originalTitle, year = yearStr, imdbId = imdb, anime = anime,
                lang = detail.originalLanguage ?: "")
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
                totalSeasons = detail.numberOfSeasons, anime = anime, lang = detail.originalLanguage ?: "",
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
            // every link is checked against its own manifest before it is marked (see VeduQuality);
            // adaptive HLS comes back as its best variant
            coroutineScope {
                result.sources.map { src ->
                    async {
                        val fresh = synchronized(lock) { emittedLinks.add(src.url) }
                        if (!fresh) return@async
                        val headers = LinkedHashMap(src.headers)
                        if (headers.keys.none { it.equals("User-Agent", true) }) headers["User-Agent"] = ua
                        val checked = VeduQuality.check(src.url, VeduSources.sourceKind(src.type, src.url), headers, src.quality)
                        val again = synchronized(lock) {
                            (checked.url == src.url || emittedLinks.add(checked.url)).also { if (it) found = true }
                        }
                        if (!again) return@async
                        val type = when (checked.kind) {
                            "dash" -> ExtractorLinkType.DASH
                            "mp4" -> ExtractorLinkType.VIDEO
                            else -> ExtractorLinkType.M3U8
                        }
                        // audio language: what the manifest declares, else what the source says
                        val audio = checked.audio.ifEmpty { listOfNotNull(VeduQuality.languageName(src.audio)) }
                        // a label that is itself a quality claim ("4K", "1080p") or just the language is dropped
                        val extra = src.label.takeIf {
                            it.isNotBlank() && VeduQuality.strictQuality(it) == null &&
                                VeduQuality.languageName(it)?.let { l -> l in audio } != true
                        }?.let { " ($it)" } ?: ""
                        val audioTag = if (audio.isNotEmpty()) " [${audio.joinToString(", ")}]" else ""
                        val referer = headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value ?: ""
                        callback.invoke(
                            newExtractorLink(name, "$group · $server$extra$audioTag", checked.url, type) {
                                this.referer = referer
                                this.quality = checked.quality ?: Qualities.Unknown.value
                                this.headers = headers
                            }
                        )
                    }
                }.awaitAll()
            }
        }

        // ---- short dramas (own catalogs) ---------------------------------------------------
        if (d.kind == "shorttv") {
            val ep = d.episode ?: 1
            val (_, eps) = VeduMovieBox.shortEpisodes(d.ref, ep, ep)
            val hit = eps.firstOrNull { it.ep == ep } ?: eps.firstOrNull()
            emit("MovieBox", "ShortTV", VeduSources.Result(hit?.streams?.map { VeduSources.Src(it.url, it.type, it.quality, it.headers) } ?: emptyList()))
            return found
        }
        if (d.kind == "reelshort") {
            emit("ReelShort", "Free", VeduSites.reelChapter(d.ref, d.chapter))
            return found
        }

        val isMovie = d.type == "movie"
        val season = d.season ?: 1
        val episode = d.episode ?: 1
        // (label, timeout, work)
        val jobs = ArrayList<Triple<String, Long, suspend () -> Unit>>()
        fun add(label: String, timeout: Long = 45_000L, block: suspend () -> Unit) { jobs.add(Triple(label, timeout, block)) }

        // ---- Orbit (67Movies) and Nova (Bingr): TMDB-keyed APIs ------------------------------
        for (srv in VeduSources.orbitServers) add(srv.name) { emit("Orbit", srv.name, srv.resolve(target)) }
        if (d.title.isNotBlank()) {
            for (srv in VeduSources.novaServers) add(srv) { emit("Nova", srv.uppercase(), VeduSources.bingr(srv, target)) }
        }

        // ---- WASM / packet-cipher players: let a hidden WebView play them and keep the manifest
        val coords = if (isMovie) "movie/${d.tmdbId}" else "tv/${d.tmdbId}/$season/$episode"
        val sniffed = listOf(
            Triple("Flixer", "https://flixer.gd/watch/$coords", "https://flixer.gd/"),
            Triple("VidFast", "https://vidfast.vc/$coords", "https://vidfast.vc/"),
            Triple("VidSrc", "https://vsembed.ru/embed/$coords", "https://vsembed.ru/"),
        )
        for ((label, embed, referer) in sniffed) add(label, 30_000L) {
            val hits = VeduBrowser.sniff(embed, referer, ua, 22_000L)
            val best = hits.firstOrNull { it.url.contains("master", true) } ?: hits.firstOrNull { it.url.contains(".mpd", true) } ?: hits.firstOrNull()
            if (best != null) {
                val origin = best.origin?.takeIf { it.startsWith("http") } ?: best.referer?.let { VeduSources.originOf(it) } ?: VeduSources.originOf(embed) ?: referer
                emit("Orbit", label, VeduSources.Result(listOf(VeduSources.Src(best.url, "", "", mapOf("Referer" to (best.referer ?: "$origin/"), "Origin" to origin)))))
            }
        }

        // ---- MovieBox (original + dubbed variants) -------------------------------------------
        if (d.title.isNotBlank()) add("MovieBox", 40_000L) {
            val subjects = VeduMovieBox.match(d.title, d.year, isMovie)
            coroutineScope {
                subjects.map { subj ->
                    async {
                        try {
                            val se = if (isMovie) 0 else season
                            val ep = if (isMovie) 0 else episode
                            val streams = VeduMovieBox.play(subj.id, se, ep)
                            val variant = Regex("""\[([^\]]+)]|\(([^)]+)\)""").find(subj.title)?.let { m -> m.groupValues[1].ifBlank { m.groupValues[2] } }
                            val subs = streams.firstOrNull()?.let { VeduMovieBox.captions(subj.id, it.streamId) } ?: emptyList()
                            // a "[Hindi]"-style variant is that dub; the plain subject carries the original audio
                            val audio = VeduQuality.languageName(variant) ?: if (variant == null) d.lang else ""
                            emit("MovieBox", variant ?: "Original", VeduSources.Result(
                                streams.map { VeduSources.Src(it.url, it.type, it.quality, it.headers, audio = audio) }, subs))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                        }
                    }
                }.awaitAll()
            }
        }

        // ---- NetMirror (Netflix / Prime / Hotstar catalogues) ---------------------------------
        if (d.title.isNotBlank()) add("NetMirror", 75_000L) {
            emit("NetMirror", "HLS", VeduSites.netMirror(d.title, isMovie, season, episode))
        }

        // ---- Asian dramas: Dramika -------------------------------------------------------------
        if (!d.anime && d.lang in setOf("ko", "zh", "ja", "th", "tl", "id", "vi", "ms", "cn", "tw")) add("Dramika") {
            val r = VeduSites.dramika(d.title, isMovie, episode) { url, referer ->
                loadExtractor(url, referer, subtitleCallback) { link -> found = true; callback.invoke(link) }
            }
            if (r.sources.isEmpty() && d.originalTitle.isNotBlank() && d.originalTitle != d.title) {
                emit("Dramika", "", VeduSites.dramika(d.originalTitle, isMovie, episode) { url, referer ->
                    loadExtractor(url, referer, subtitleCallback) { link -> found = true; callback.invoke(link) }
                })
            } else emit("Dramika", "", VeduSources.Result(r.sources.map { it.copy(audio = it.audio.ifBlank { d.lang }) }, r.subs))
        }

        // ---- Anime: Nova adapters, 9Anime (Anikoto), AnimePahe --------------------------------
        if (d.anime) {
            val absEp = if (isMovie) 1 else (d.absEpisode ?: episode)
            val seasonTitle = if (!isMovie && season > 1) "${d.title} Season $season" else d.title
            add("multi") {
                emit("Nova Anime", "Multi", VeduSources.animeMulti(listOf(d.title, d.originalTitle), absEp, isMovie))
            }
            add("subdub") {
                // AniList splits seasons; try "<title> Season N" first for later seasons.
                val seasonal = if (!isMovie && season > 1) anilistId(seasonTitle, false) else null
                val aid = seasonal ?: anilistId(d.title, isMovie) ?: anilistId(d.originalTitle, isMovie)
                if (aid != null) {
                    val epNum = if (isMovie) 1 else if (seasonal != null) episode else absEp
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
            }
            add("9anime") {
                // Anikoto has a separate entry per season: seasonal title first, then whole-show numbering
                var r = VeduSites.nineAnime(seasonTitle, if (season > 1) episode else absEp)
                if (r.sources.isEmpty() && seasonTitle != d.title) r = VeduSites.nineAnime(d.title, absEp)
                emit("9Anime", "MegaPlay", r)
            }
            add("animepahe") {
                var r = VeduSites.animePahe(seasonTitle, if (season > 1) episode else absEp)
                if (r.sources.isEmpty() && seasonTitle != d.title) r = VeduSites.animePahe(d.title, absEp)
                emit("AnimePahe", "", r)
            }
        }

        val gate = Semaphore(10)
        coroutineScope {
            jobs.map { (_, timeout, work) ->
                async {
                    gate.withPermit {
                        try {
                            withTimeoutOrNull(timeout) { work() }
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
