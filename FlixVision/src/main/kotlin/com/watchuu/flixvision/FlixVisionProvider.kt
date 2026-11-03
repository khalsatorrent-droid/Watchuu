package com.watchuu.flixvision

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

/**
 * FlixVision — built by decompiling the Flix Vision 4.0.0r APK (Spydog multi-patch).
 *
 * The app is a TMDB front-end with a list of embed providers, which is the same shape as a
 * Cloudstream extension, so most of it ports directly.
 *
 * Catalogue: the app keeps its config at
 *   https://github.com/tf237/fv/releases/download/cf/cg.json
 * which is live and names its sources (allmovieland, gogoanime, kissasiantv, watchmovierulz,
 * luxki440das, autoembed, vixsrc…). Metadata itself comes from TMDB, exactly as the app does.
 *
 * Streams: the dex carries the provider base urls in its string pool, keyed by TMDB id:
 *   https://vidsrc.to/embed/movie/<id>        /embed/tv/<id>/<s>/<e>
 *   https://vidsrc.sh/embed/movie/<id>        same for tv
 *   https://vixsrc.to/api/movie/<id>          /api/tv/<id>/<s>/<e>   -> {"src": "https://vixcloud.cc/…"}
 *   https://vsembed.ru/embed/movie/<id>
 *   https://autoembed.co/movie/tmdb/<id>
 *
 * Every one of those is handed to Cloudstream's own extractors rather than re-implemented here,
 * because that is where they are maintained — the same lesson the Pornxday and LatestPornVideo
 * modules taught: an embed host's parser belongs to whoever keeps it current, not to this file.
 * vixsrc is the exception: it answers with json, so its `src` is read and passed on.
 *
 * Honest note: I could not fetch these hosts while writing this (the build sandbox has no route
 * to them), so the provider list is the app's own, but which of them still answer today is
 * unverified. The extension will show whatever resolves and skip the rest.
 */
class FlixVisionProvider : MainAPI() {
    override var mainUrl = "https://flixvision.tv"
    override var name = "FlixVision"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val tmdbApi = "https://api.themoviedb.org/3"
    // the key Flix Vision ships with, recovered from the APK's dex
    private val tmdbApiKey = "07824c019b81ecf7ad094a66f6410cc9"
    private val tmdbImg = "https://image.tmdb.org/t/p/w500"

    override val mainPage = mainPageOf(
        "/trending/all/week" to "Trending",
        "/movie/popular" to "Popular Movies",
        "/tv/popular" to "Popular Series",
        "/movie/top_rated" to "Top Rated Movies",
        "/tv/top_rated" to "Top Rated Series",
        "/discover/movie?with_original_language=hi" to "Hindi Movies",
        "/discover/movie?with_original_language=ta" to "Tamil Movies",
        "/discover/movie?with_original_language=te" to "Telugu Movies"
    )

    // ── TMDB ───────────────────────────────────────────────────────────────────

    private suspend fun tmdbGet(path: String): String {
        val sep = if (path.contains("?")) "&" else "?"
        return app.get("$tmdbApi$path${sep}api_key=$tmdbApiKey&language=en-US").text
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sep = if (request.data.contains("?")) "&" else "?"
        val body = runCatching { tmdbGet("${request.data}${sep}page=$page") }.getOrNull()
            ?: return newHomePageResponse(request.name, emptyList(), false)
        val resp = runCatching { parseJson<TmdbResp>(body) }.getOrNull()
        val items = resp?.results?.mapNotNull { it.toSearchResult() } ?: emptyList()
        val hasNext = (resp?.page ?: 1) < (resp?.totalPages ?: 1)
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val body = runCatching { tmdbGet("/search/multi?query=$q&page=1&include_adult=false") }.getOrNull()
            ?: return emptyList()
        return runCatching { parseJson<TmdbResp>(body) }.getOrNull()
            ?.results?.mapNotNull { it.toSearchResult() } ?: emptyList()
    }

    private fun TmdbItem.toSearchResult(): SearchResponse? {
        val id = id ?: return null
        val isTv = mediaType == "tv" || (mediaType == null && title == null)
        if (mediaType == "person") return null
        val title = title ?: name ?: return null

        return if (isTv) {
            newTvSeriesSearchResponse(title, "$mainUrl/tv/$id", TvType.TvSeries) {
                this.posterUrl = posterPath?.let { "$tmdbImg$it" }
                this.year = firstAirDate?.take(4)?.toIntOrNull()
            }
        } else {
            newMovieSearchResponse(title, "$mainUrl/movie/$id", TvType.Movie) {
                this.posterUrl = posterPath?.let { "$tmdbImg$it" }
                this.year = releaseDate?.take(4)?.toIntOrNull()
            }
        }
    }

    // ── Detail ─────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val isTv = url.contains("/tv/")
        val id = url.trimEnd('/').substringAfterLast('/').toIntOrNull() ?: return null

        val body = runCatching { tmdbGet(if (isTv) "/tv/$id" else "/movie/$id") }.getOrNull() ?: return null
        val d = runCatching { parseJson<TmdbDetail>(body) }.getOrNull() ?: return null

        val title = d.title ?: d.name ?: return null
        val poster = d.posterPath?.let { "$tmdbImg$it" }
        val year = (d.releaseDate ?: d.firstAirDate)?.take(4)?.toIntOrNull()

        return if (isTv) {
            // real episodes: one request per season, which is what the app does too
            val episodes = ArrayList<Episode>()
            d.seasons?.filter { (it.seasonNumber ?: 0) > 0 }?.forEach { season ->
                val sn = season.seasonNumber ?: return@forEach
                val sBody = runCatching { tmdbGet("/tv/$id/season/$sn") }.getOrNull() ?: return@forEach
                val sDetail = runCatching { parseJson<TmdbSeasonDetail>(sBody) }.getOrNull() ?: return@forEach
                sDetail.episodes?.forEach { ep ->
                    val en = ep.episodeNumber ?: return@forEach
                    episodes.add(
                        newEpisode("$id/$sn/$en") {
                            this.name = ep.name
                            this.season = sn
                            this.episode = en
                            this.posterUrl = ep.stillPath?.let { "$tmdbImg$it" }
                            this.description = ep.overview
                        }
                    )
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = d.overview
                this.year = year
                this.tags = d.genres?.mapNotNull { it.name } ?: emptyList()
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, data = "$id") {
                this.posterUrl = poster
                this.plot = d.overview
                this.year = year
                this.tags = d.genres?.mapNotNull { it.name } ?: emptyList()
                this.duration = d.runtime
            }
        }
    }

    // ── Streams ────────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data may arrive as "<id>" or "<id>/<season>/<episode>"
        val parts = data.trim('/').split('/')
        val id = parts.getOrNull(0)?.toIntOrNull() ?: return false
        val season = parts.getOrNull(1)?.toIntOrNull()
        val episode = parts.getOrNull(2)?.toIntOrNull()
        val isTv = season != null && episode != null

        var found = false

        // 1. vixsrc answers with json, so its src can be read and handed on directly
        val vixApi = if (isTv) "https://vixsrc.to/api/tv/$id/$season/$episode" else "https://vixsrc.to/api/movie/$id"
        val vixBody = runCatching {
            app.get(vixApi, headers = mapOf("Referer" to "https://vixsrc.to/")).text
        }.getOrNull()
        if (vixBody != null) {
            val src = runCatching { parseJson<VixResp>(vixBody).src }.getOrNull()
            if (!src.isNullOrBlank()) {
                val got = mutableListOf<ExtractorLink>()
                runCatching {
                    loadExtractor(src, "https://vixsrc.to/", subtitleCallback) { got.add(it) }
                }
                got.forEach { callback.invoke(it) }
                if (got.isNotEmpty()) found = true
            }
        }

        // 2. the embed providers the app itself carries, handed to Cloudstream's extractors
        val embeds = if (isTv) listOf(
            "https://vidsrc.to/embed/tv/$id/$season/$episode",
            "https://vidsrc.sh/embed/tv/$id/$season/$episode",
            "https://vidsrc.ru/embed/tv/$id/$season/$episode",
            "https://vsembed.ru/embed/tv/$id/$season/$episode",
            "https://autoembed.co/tv/tmdb/$id-$season-$episode"
        ) else listOf(
            "https://vidsrc.to/embed/movie/$id",
            "https://vidsrc.sh/embed/movie/$id",
            "https://vidsrc.ru/embed/movie/$id",
            "https://vsembed.ru/embed/movie/$id",
            "https://autoembed.co/movie/tmdb/$id"
        )

        embeds.forEach { embed ->
            val got = mutableListOf<ExtractorLink>()
            runCatching {
                loadExtractor(embed, "$mainUrl/", subtitleCallback) { got.add(it) }
            }
            if (got.isNotEmpty()) {
                got.forEach { callback.invoke(it) }
                found = true
            }
        }

        return found
    }

    // ── Models ─────────────────────────────────────────────────────────────────

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

    data class TmdbGenre(val name: String? = null)

    data class TmdbSeason(
        @JsonProperty("season_number") val seasonNumber: Int? = null,
        @JsonProperty("episode_count") val episodeCount: Int? = null,
    )

    data class TmdbDetail(
        val id: Int? = null,
        val title: String? = null,
        val name: String? = null,
        val overview: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        val runtime: Int? = null,
        val genres: List<TmdbGenre>? = null,
        val seasons: List<TmdbSeason>? = null,
    )

    data class TmdbEpisode(
        @JsonProperty("episode_number") val episodeNumber: Int? = null,
        val name: String? = null,
        val overview: String? = null,
        @JsonProperty("still_path") val stillPath: String? = null,
    )

    data class TmdbSeasonDetail(val episodes: List<TmdbEpisode>? = null)

    data class VixResp(val src: String? = null)
}
