package com.watchuu.almostott

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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Almostott (almostott.aethoflix.world) - a TMDB front-end (SPA) with a dozen embed servers,
 * including anime-specific ones. Catalog/metadata is TMDB; links come from opening each server's
 * embed in a hidden WebView and capturing the HLS/DASH manifest it loads (same as RedFlix).
 *
 * Built from a captured network log:
 *  - catalog / search / details : TMDB directly (the site uses api_key fca1c3fa675f74cf4aa8d24b72dd4807)
 *  - player route               : /#/watch/{type}/{tmdbId}/{movie|series|anime}
 *  - tmdb-id servers            : tgvid / vidstuck / nxsha / bingr / vidly / vidfast / vidlink / zxcstream
 *  - anime servers              : megaplay / 4animo / vumeto, keyed by the AniList id
 *                                 (the site resolves it via graphql.anilist.co: Media(search){id})
 */
class AlmostottProvider : MainAPI() {
    override var mainUrl = "https://almostott.aethoflix.world"
    override var name = "Almostott"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val tmdbApi = "https://api.tmdb.org/3"
    private val tmdbApiKey = "fca1c3fa675f74cf4aa8d24b72dd4807"
    private val tmdbImg = "https://image.tmdb.org/t/p/w500"
    private val tmdbBack = "https://image.tmdb.org/t/p/original"

    private val ua =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    private val embedReferer = "$mainUrl/"

    // ------------------------------------------------------------------ servers

    /** A server that takes TMDB ids. Templates use {id}, {s}, {e}. */
    private class TmdbServer(val name: String, val movie: String, val tv: String)

    private val tmdbServers = listOf(
        TmdbServer("TgVid", "https://tgvid.lovable.app/embed/movie/{id}", "https://tgvid.lovable.app/embed/tv/{id}/{s}/{e}"),
        TmdbServer("VidStuck", "https://vidstuck.cc/embed/movie/{id}", "https://vidstuck.cc/embed/tv/{id}/{s}/{e}"),
        TmdbServer("Nxsha", "https://nxsha.space/embed/movie/{id}", "https://nxsha.space/embed/tv/{id}/{s}/{e}"),
        TmdbServer("Bingr", "https://bingr.one/watch/movie/{id}", "https://bingr.one/watch/tv/{id}/{s}/{e}"),
        TmdbServer("Vidly", "https://vidly.pro/watch/movie/{id}", "https://vidly.pro/watch/tv/{id}/{s}/{e}"),
        TmdbServer("VidFast", "https://vidfast.vc/movie/{id}", "https://vidfast.vc/tv/{id}/{s}/{e}"),
        TmdbServer("VidLink", "https://vidlink.pro/movie/{id}", "https://vidlink.pro/tv/{id}/{s}/{e}"),
        TmdbServer("ZxcStream", "https://zxcstream.cc/embed/movie/{id}", "https://zxcstream.cc/embed/tv/{id}/{s}/{e}"),
    )

    /** An anime server that takes the AniList id. Templates use {aid}, {ep}. */
    private class AnimeServer(val name: String, val template: String)

    private val animeServers = listOf(
        AnimeServer("MegaPlay (Sub)", "https://megaplay.buzz/stream/ani/{aid}/{ep}/sub"),
        AnimeServer("MegaPlay (Dub)", "https://megaplay.buzz/stream/ani/{aid}/{ep}/dub"),
        AnimeServer("4Animo HD-1", "https://cdn.4animo.xyz/embed/hd-1/ani/{aid}/{ep}/sub?k=1"),
        AnimeServer("4Animo HD-2", "https://cdn.4animo.xyz/embed/hd-2/ani/{aid}/{ep}/sub?k=1"),
        AnimeServer("Vumeto", "https://flix.vumeto.xyz/embed/anime/{aid}/{ep}?id_type=anilist&dub=sub"),
    )

    // ---------------------------------------------------------------- home page

    override val mainPage = mainPageOf(
        "/trending/all/day" to "Trending Today",
        "/movie/popular" to "Popular Movies",
        "/tv/popular" to "Popular TV Shows",
        "/discover/tv?with_genres=16&with_original_language=ja&sort_by=popularity.desc" to "Anime",
        "/movie/top_rated" to "Top Rated Movies",
        "/tv/top_rated" to "Top Rated TV Shows",
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

    // Payloads:  movie|id|anime?|title   tv|id|anime?|title   ep|id|S|E|anime?|title
    private fun payloadParts(raw: String): List<String> {
        var s = raw.trim()
        if (s.startsWith("$mainUrl/")) s = s.removePrefix("$mainUrl/")
        return s.split("|")
    }

    private fun isAnime(genreIds: List<Int>?, lang: String?) =
        (genreIds ?: emptyList()).contains(16) && lang == "ja"

    private fun TmdbItem.toSearch(defaultType: String?): SearchResponse? {
        val type = mediaType ?: defaultType ?: return null
        if (type != "movie" && type != "tv") return null
        val id = id ?: return null
        val t = (if (type == "movie") title ?: name else name ?: title) ?: return null
        if (t.isBlank()) return null
        val poster = posterPath?.let { tmdbImg + it }
        val year = (releaseDate ?: firstAirDate)?.take(4)?.toIntOrNull()
        val anime = type == "tv" && isAnime(genreIds, originalLanguage)
        val score = voteAverage?.takeIf { it > 0 }?.let { Score.from10(it.toString()) }
        val animeFlag = if (anime) "1" else "0"
        return if (type == "movie") {
            newMovieSearchResponse(t, "$mainUrl/movie|$id|0|$t", TvType.Movie) {
                this.posterUrl = poster; this.year = year; this.score = score
            }
        } else {
            newTvSeriesSearchResponse(t, "$mainUrl/tv|$id|$animeFlag|$t", if (anime) TvType.Anime else TvType.TvSeries) {
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

    // --------------------------------------------------------------------- load

    override suspend fun load(url: String): LoadResponse? {
        val parts = payloadParts(url)
        if (parts.size < 2) return null
        val type = parts[0]
        val tmdbId = parts[1].toIntOrNull() ?: return null
        val tmdbType = if (type == "movie") "movie" else "tv"

        val detail = try {
            parseJson<TmdbDetail>(tmdbGet("/$tmdbType/$tmdbId?append_to_response=credits,videos,recommendations"))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }

        val title = detail.title ?: detail.name ?: parts.drop(3).joinToString("|")
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
        val anime = tmdbType == "tv" && isAnime(detail.genres?.mapNotNull { it.id }, detail.originalLanguage)
        val animeFlag = if (anime) "1" else "0"
        val recs = detail.recommendations?.results?.mapNotNull { it.toSearch(tmdbType) }

        if (tmdbType == "movie") {
            return newMovieLoadResponse(title, url, TvType.Movie, "$mainUrl/movie|$tmdbId|0|$title") {
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

        // the AniList-keyed servers need the original (romaji/english) title; keep it in the payload
        val animeTitle = detail.name ?: detail.originalName ?: title
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
            newEpisode("$mainUrl/ep|$tmdbId|${ep.seasonNumber}|${ep.episodeNumber}|$animeFlag|$animeTitle") {
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

    private val anilistCache = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** The site maps a title to an AniList id via graphql.anilist.co Media(search){id}. */
    private suspend fun anilistId(title: String): Int? {
        if (title.isBlank()) return null
        anilistCache[title]?.let { return it }
        return try {
            val body = JSONObject()
                .put("query", "query (\$search: String) { Media(search: \$search, type: ANIME) { id } }")
                .put("variables", JSONObject().put("search", title))
                .toString()
            val r = app.post(
                "https://graphql.anilist.co/",
                headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json", "User-Agent" to ua),
                requestBody = body.toRequestBody("application/json".toMediaType()),
                timeout = 20_000L
            )
            val id = JSONObject(r.text).optJSONObject("data")?.optJSONObject("Media")?.optInt("id")?.takeIf { it > 0 }
            if (id != null) anilistCache[title] = id
            id
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun originOf(u: String): String? {
        val scheme = u.substringBefore("://", "")
        if (scheme.isBlank()) return null
        val host = u.substringAfter("://").substringBefore("/").substringBefore("?")
        return if (host.isBlank()) null else "$scheme://$host"
    }

    private fun pickBest(hits: List<AlmostottBrowser.Hit>): AlmostottBrowser.Hit? {
        if (hits.isEmpty()) return null
        return hits.firstOrNull { it.url.contains("master", true) }
            ?: hits.firstOrNull { it.url.contains(".mpd", true) }
            ?: hits.firstOrNull { it.url.contains("playlist", true) || it.url.contains("index.m3u8", true) }
            ?: hits.first()
    }

    private suspend fun toLink(server: String, hit: AlmostottBrowser.Hit, embed: String): ExtractorLink {
        val origin = hit.origin?.takeIf { it.startsWith("http") }
            ?: hit.referer?.let { originOf(it) }
            ?: originOf(embed)
            ?: mainUrl
        val referer = "$origin/"
        val isDash = hit.url.substringBefore("?").endsWith(".mpd", true) ||
            (hit.url.contains(".mpd", true) && !hit.url.contains(".m3u8", true))
        val q = Regex("""(?i)(2160|1440|1080|720|540|480|360)p?""").find(hit.url)?.groupValues?.get(1)?.toIntOrNull()
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
        val s = if (isMovie) null else parts.getOrNull(2)?.toIntOrNull()
        val e = if (isMovie) null else parts.getOrNull(3)?.toIntOrNull()
        val animeFlag = if (isMovie) parts.getOrNull(2) else parts.getOrNull(4)
        val title = (if (isMovie) parts.drop(3) else parts.drop(5)).joinToString("|")
        val isAnimeTitle = animeFlag == "1"

        // build the list of embeds to try
        val embeds = LinkedHashMap<String, String>() // label -> url
        for (srv in tmdbServers) {
            val tmpl = if (isMovie) srv.movie else srv.tv
            embeds[srv.name] = tmpl.replace("{id}", tmdbId)
                .replace("{s}", s?.toString() ?: "").replace("{e}", e?.toString() ?: "")
        }
        if (isAnimeTitle && !isMovie) {
            val aid = anilistId(title)
            if (aid != null) {
                val ep = e ?: 1
                for (srv in animeServers) {
                    embeds[srv.name] = srv.template.replace("{aid}", aid.toString()).replace("{ep}", ep.toString())
                }
            }
        }

        val emitted = HashSet<String>()
        var found = false
        val lock = Any()
        val gate = Semaphore(6)
        coroutineScope {
            embeds.entries.map { (label, embed) ->
                async {
                    gate.withPermit {
                        val hits = try {
                            AlmostottBrowser.sniff(embed, embedReferer, ua, 15_000L)
                        } catch (ex: CancellationException) {
                            throw ex
                        } catch (_: Exception) {
                            emptyList()
                        }
                        val best = pickBest(hits) ?: return@withPermit
                        val link = toLink(label, best, embed)
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
}
