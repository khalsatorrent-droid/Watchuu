package com.watchuu.oceanstreamz

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Ocean Streamz (Android app, v3.5.14) - rebuilt from the decompiled APK.
 *
 * What the app does, and what this extension reuses:
 *  - catalog / search / details : TMDB (api.themoviedb.org) with the app's own key
 *  - backend (Retrofit, http)    : http://165.227.118.144
 *      GET  /movies/scrapper/count                -> {success,message,data:{count}}
 *      GET  /movies/links/{n}?title&tmdbId&imdbId&year
 *      GET  /tv-shows/scrapper/count
 *      GET  /tv-shows/links/{n}?id&year&title&season&episode&epName&lastEpName
 *           -> {data:{scrapperName, url:[...], urlData:[...], scrapeInLoop}}
 *      GET  /live-tv/getLive24x7Channels_dlhd     -> {data:[ChannelData]}
 *      GET  /live-tv/getLiveSportsChannels_dlhd   -> {data:[{name,time,channelDataList}]}
 *      POST /live-tv/getListOfPlayers_dlhd  (ChannelData) -> {data:[Player]}
 *      POST /live-tv/extractPlayableLiveURL_dlhd (Player) -> {data:{playableLink,referer,origin,userAgent}}
 *      GET  /live-tv/getChannelList_tta, /live-tv/getEventList_tta, POST /live-tv/extractPlayableLiveURL_tta
 *  - links returned by the scrapers are host embeds (dood, vidmoly, mp4upload, ...) or direct
 *    files; embeds go through Cloudstream's extractors.
 */
class OceanStreamzProvider : MainAPI() {
    override var mainUrl = "http://165.227.118.144"
    override var name = "Ocean Streamz"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Live)

    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbKey = "eddaca1b110bc28310482915ab3466fb" // key shipped in the app
    private val tmdbImg = "https://image.tmdb.org/t/p/w500"
    private val tmdbBack = "https://image.tmdb.org/t/p/original"

    private val ua =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val jsonHeaders = mapOf("Content-Type" to "application/json", "Accept" to "application/json")

    override val mainPage = mainPageOf(
        "live:dlhd24" to "Live TV 24/7",
        "live:dlhdsports" to "Live Sports",
        "live:tta" to "Live TV (TTA)",
        "/trending/all/day" to "Trending",
        "/movie/popular" to "Popular Movies",
        "/tv/popular" to "Popular TV Shows",
        "/movie/now_playing" to "In Theaters",
        "/movie/top_rated" to "Top Rated Movies",
        "/tv/top_rated" to "Top Rated TV Shows",
        "/tv/on_the_air" to "Airing Now",
    )

    // ------------------------------------------------------------------ models

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Api<T>(val success: Boolean? = null, val message: String? = null, val data: T? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ScrapperCount(val count: Int? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PlayableLinks(
        val scrapperName: String? = null,
        val url: List<String>? = null,
        val urlData: List<Any?>? = null,
        val scrapeInLoop: Boolean? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PlayableChannelInfo(
        val playableLink: String? = null,
        val referer: String? = null,
        val origin: String? = null,
        val userAgent: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Player(
        val playerName: String? = null,
        val playerUrl: String? = null,
        val channelUrl: String? = null,
        val playerData: PlayableChannelInfo? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ChannelData(
        val title: String? = null,
        val url: String? = null,
        val poster: String? = null,
        val players: List<Player>? = null,
        val isSports: Boolean? = null,
        val isFavourite: Boolean? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EventData(
        val name: String? = null,
        val time: String? = null,
        val channelDataList: List<ChannelData>? = null,
    )

    /** What a live card carries: which backend family it belongs to, and the channel itself. */
    data class LiveData(val src: String, val channel: ChannelData, val event: String? = null)

    /** What a movie / episode carries into loadLinks. */
    data class LinkData(
        val type: String,
        val tmdbId: Int,
        val title: String,
        val year: String? = null,
        val imdbId: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val epName: String? = null,
        val lastEpName: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbItem(
        val id: Int? = null,
        val title: String? = null,
        val name: String? = null,
        @JsonProperty("media_type") val mediaType: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbResp(val results: List<TmdbItem>? = null, @JsonProperty("total_pages") val totalPages: Int? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbGenre(val name: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbSeason(
        @JsonProperty("season_number") val seasonNumber: Int = 0,
        @JsonProperty("episode_count") val episodeCount: Int? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbExternal(@JsonProperty("imdb_id") val imdbId: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbDetail(
        val title: String? = null,
        val name: String? = null,
        val overview: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("imdb_id") val imdbId: String? = null,
        @JsonProperty("external_ids") val externalIds: TmdbExternal? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        val runtime: Int? = null,
        val genres: List<TmdbGenre>? = null,
        val seasons: List<TmdbSeason>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbEpisode(
        @JsonProperty("episode_number") val episodeNumber: Int = 0,
        @JsonProperty("season_number") val seasonNumber: Int = 0,
        val name: String? = null,
        val overview: String? = null,
        @JsonProperty("still_path") val stillPath: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbSeasonDetail(val episodes: List<TmdbEpisode>? = null)

    // ----------------------------------------------------------------- helpers

    private suspend fun tmdb(path: String): String {
        val sep = if (path.contains("?")) "&" else "?"
        return app.get("$tmdbApi$path${sep}api_key=$tmdbKey&language=en-US", headers = mapOf("User-Agent" to ua)).text
    }

    private suspend fun apiGet(path: String): String =
        app.get("$mainUrl$path", headers = jsonHeaders, timeout = 25_000L).text

    private suspend fun apiPost(path: String, body: Any): String =
        app.post(
            "$mainUrl$path",
            headers = jsonHeaders,
            requestBody = body.toJson().toRequestBody("application/json".toMediaType()),
            timeout = 25_000L
        ).text

    private fun enc(s: String?) = URLEncoder.encode(s ?: "", "UTF-8")

    // --------------------------------------------------------------- main page

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data.startsWith("live:")) {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
            val items = runCatching { liveChannels(request.data.removePrefix("live:")) }.getOrNull().orEmpty()
            return newHomePageResponse(request.name, items, hasNext = false)
        }
        val defType = when {
            request.data.startsWith("/movie") -> "movie"
            request.data.startsWith("/tv") -> "tv"
            else -> null
        }
        val resp = runCatching { parseJson<TmdbResp>(tmdb("${request.data}?page=$page")) }.getOrNull()
        val items = resp?.results?.mapNotNull { it.toSearch(defType) }.orEmpty()
        return newHomePageResponse(request.name, items, hasNext = (resp?.totalPages ?: 1) > page)
    }

    private suspend fun liveChannels(kind: String): List<SearchResponse> {
        val pairs: List<Pair<ChannelData, String?>> = when (kind) {
            "dlhd24" -> parseJson<Api<List<ChannelData>>>(apiGet("/live-tv/getLive24x7Channels_dlhd")).data.orEmpty()
                .map { it to null }
            "dlhdsports" -> parseJson<Api<List<EventData>>>(apiGet("/live-tv/getLiveSportsChannels_dlhd")).data.orEmpty()
                .flatMap { ev -> ev.channelDataList.orEmpty().map { it to listOfNotNull(ev.time, ev.name).joinToString(" ") } }
            "tta" -> parseJson<Api<List<ChannelData>>>(apiGet("/live-tv/getChannelList_tta")).data.orEmpty()
                .map { it to null }
            else -> emptyList()
        }
        val src = if (kind == "tta") "tta" else "dlhd"
        return pairs.mapNotNull { (ch, ev) ->
            val t = ch.title?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val label = if (ev.isNullOrBlank()) t else "$ev - $t"
            newLiveSearchResponse(label, "live:" + LiveData(src, ch, ev).toJson(), TvType.Live) {
                this.posterUrl = ch.poster
            }
        }.distinctBy { it.url }
    }

    private fun TmdbItem.toSearch(defType: String?): SearchResponse? {
        val type = mediaType ?: defType ?: return null
        if (type != "movie" && type != "tv") return null
        val id = id ?: return null
        val t = (title ?: name)?.takeIf { it.isNotBlank() } ?: return null
        val url = "$mainUrl/$type/$id"
        val y = (releaseDate ?: firstAirDate)?.take(4)?.toIntOrNull()
        return if (type == "movie") newMovieSearchResponse(t, url, TvType.Movie) {
            posterUrl = posterPath?.let { tmdbImg + it }; year = y
        } else newTvSeriesSearchResponse(t, url, TvType.TvSeries) {
            posterUrl = posterPath?.let { tmdbImg + it }; year = y
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val tmdbHits = runCatching {
            parseJson<TmdbResp>(tmdb("/search/multi?query=${enc(query)}&include_adult=false")).results
                ?.mapNotNull { it.toSearch(null) }
        }.getOrNull().orEmpty()
        // live channels matching the query, too
        val live = runCatching {
            (liveChannels("dlhd24") + liveChannels("tta")).filter { it.name.contains(query, ignoreCase = true) }
        }.getOrNull().orEmpty()
        return live + tmdbHits
    }

    // -------------------------------------------------------------------- load

    override suspend fun load(url: String): LoadResponse? {
        if (url.startsWith("live:")) {
            val live = parseJson<LiveData>(url.removePrefix("live:"))
            val t = live.channel.title ?: "Live"
            return newLiveStreamLoadResponse(if (live.event.isNullOrBlank()) t else "${live.event} - $t", url, url) {
                this.posterUrl = live.channel.poster
                this.plot = live.event
            }
        }

        val path = url.removePrefix(mainUrl).trim('/')
        val type = path.substringBefore("/")
        val id = path.substringAfter("/").toIntOrNull() ?: return null
        val d = parseJson<TmdbDetail>(tmdb("/$type/$id?append_to_response=external_ids"))
        val title = d.title ?: d.name ?: return null
        val yearStr = (d.releaseDate ?: d.firstAirDate)?.take(4)
        val imdb = d.imdbId ?: d.externalIds?.imdbId
        val poster = d.posterPath?.let { tmdbImg + it }
        val bg = d.backdropPath?.let { tmdbBack + it }
        val tags = d.genres?.mapNotNull { it.name }.orEmpty()
        val score = d.voteAverage?.let { Score.from10(it.toInt()) }

        if (type == "movie") {
            val data = LinkData("movie", id, title, yearStr, imdb).toJson()
            return newMovieLoadResponse(title, url, TvType.Movie, data) {
                posterUrl = poster; backgroundPosterUrl = bg; year = yearStr?.toIntOrNull()
                plot = d.overview; this.tags = tags; this.score = score; duration = d.runtime
                addImdbId(imdb); addTMDbId(id.toString())
            }
        }

        val seasons = coroutineScope {
            d.seasons.orEmpty().filter { it.seasonNumber > 0 && (it.episodeCount ?: 0) > 0 }.map { s ->
                async {
                    try {
                        parseJson<TmdbSeasonDetail>(tmdb("/tv/$id/season/${s.seasonNumber}"))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
            }.awaitAll()
        }
        val episodes = seasons.filterNotNull().flatMap { sd ->
            val eps = sd.episodes.orEmpty()
            val lastName = eps.lastOrNull()?.name
            eps.map { ep ->
                val data = LinkData(
                    "tv", id, title, yearStr, imdb, ep.seasonNumber, ep.episodeNumber, ep.name, lastName
                ).toJson()
                newEpisode(data) {
                    name = ep.name; season = ep.seasonNumber; episode = ep.episodeNumber
                    posterUrl = ep.stillPath?.let { tmdbImg + it }; description = ep.overview
                }
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster; backgroundPosterUrl = bg; year = yearStr?.toIntOrNull()
            plot = d.overview; this.tags = tags; this.score = score
            addImdbId(imdb); addTMDbId(id.toString())
        }
    }

    // ------------------------------------------------------------------- links

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.startsWith("live:")) return liveLinks(parseJson<LiveData>(data.removePrefix("live:")), callback)

        val ld = parseJson<LinkData>(data)
        val isMovie = ld.type == "movie"
        val seen = HashSet<String>()
        var found = false
        val emit: (ExtractorLink) -> Unit = { link ->
            val fresh = synchronized(seen) { seen.add(link.url) }
            if (fresh) { found = true; callback(link) }
        }

        // current public TMDB sources run alongside the app's own backend scrapers
        coroutineScope {
            val extra = async {
                ExtraSources.all(
                    ld.tmdbId, if (isMovie) null else ld.season, if (isMovie) null else ld.episode,
                    subtitleCallback, emit
                )
            }
            val count = runCatching {
                parseJson<Api<ScrapperCount>>(apiGet(if (isMovie) "/movies/scrapper/count" else "/tv-shows/scrapper/count"))
                    .data?.count
            }.getOrNull()?.coerceIn(1, 40) ?: 12

            // scraper numbers are 0- or 1-based depending on the backend build: ask 0..count
            coroutineScope {
                (0..count).map { n ->
                    async {
                        try {
                            val path = if (isMovie)
                                "/movies/links/$n?title=${enc(ld.title)}&tmdbId=${ld.tmdbId}&imdbId=${enc(ld.imdbId)}&year=${enc(ld.year)}"
                            else
                                "/tv-shows/links/$n?id=${ld.tmdbId}&year=${enc(ld.year)}&title=${enc(ld.title)}" +
                                    "&season=${ld.season}&episode=${ld.episode}&epName=${enc(ld.epName)}&lastEpName=${enc(ld.lastEpName)}"
                            val res = parseJson<Api<PlayableLinks>>(apiGet(path)).data ?: return@async
                            val server = res.scrapperName?.takeIf { it.isNotBlank() } ?: "Server $n"
                            res.url.orEmpty().map { it.trim() }.filter { it.startsWith("http") }.distinct().forEach { u ->
                                emitUrl(u, server, subtitleCallback, emit)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                        }
                    }
                }.awaitAll()
            }
            extra.await()
        }
        return found
    }

    private suspend fun emitUrl(
        u: String,
        server: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        emit: (ExtractorLink) -> Unit
    ) {
        val low = u.lowercase().substringBefore("?")
        val direct = low.endsWith(".m3u8") || low.endsWith(".mp4") || low.endsWith(".mkv") || low.contains(".m3u8")
        if (direct) {
            emit(
                newExtractorLink(name, "$name - $server", u,
                    if (low.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    quality = Qualities.Unknown.value
                }
            )
        } else {
            loadExtractor(u, "$mainUrl/", subtitleCallback, emit)
        }
    }

    private suspend fun liveLinks(live: LiveData, callback: (ExtractorLink) -> Unit): Boolean {
        val dlhd = live.src != "tta"
        val players = live.channel.players?.takeIf { it.isNotEmpty() }
            ?: if (dlhd) runCatching {
                parseJson<Api<List<Player>>>(apiPost("/live-tv/getListOfPlayers_dlhd", live.channel)).data
            }.getOrNull().orEmpty() else emptyList()

        // tta channels may come without a player list: let the backend resolve the channel url itself
        val candidates = players.ifEmpty {
            listOf(Player(playerName = live.channel.title, playerUrl = live.channel.url, channelUrl = live.channel.url))
        }

        var found = false
        coroutineScope {
            candidates.mapIndexed { i, p ->
                async {
                    val info = p.playerData?.takeIf { !it.playableLink.isNullOrBlank() } ?: runCatching {
                        parseJson<Api<PlayableChannelInfo>>(
                            apiPost(if (dlhd) "/live-tv/extractPlayableLiveURL_dlhd" else "/live-tv/extractPlayableLiveURL_tta", p)
                        ).data
                    }.getOrNull()
                    val link = info?.playableLink?.takeIf { it.startsWith("http") } ?: return@async
                    val headers = buildMap {
                        info.userAgent?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
                        info.origin?.takeIf { it.isNotBlank() }?.let { put("Origin", it) }
                    }
                    callback(
                        newExtractorLink(name, p.playerName?.takeIf { it.isNotBlank() } ?: "Player ${i + 1}", link,
                            if (link.contains(".mp4", true)) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8) {
                            referer = info.referer ?: ""
                            quality = Qualities.Unknown.value
                            this.headers = headers
                        }
                    )
                    found = true
                }
            }.awaitAll()
        }
        return found
    }
}
