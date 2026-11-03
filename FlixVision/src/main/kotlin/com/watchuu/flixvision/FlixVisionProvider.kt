package com.watchuu.flixvision

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

/**
 * FlixVision (Android app, AF 4.0.0r) - rebuilt from the decompiled APK (v2).
 *
 * Catalog: TMDB with the app's own key (E4/a.java).
 * Sources, ported from LinksActivity / e5 / flix.com.vision.extractors:
 *  - FVStream Dexter  : https://dexter.pw/api/s/21/movie/{tmdb}?v=3 | /tv/{tmdb}/{s}/{e}?v=3  (direct stream)
 *  - FVStream (AML)   : {ml_host}/play/{imdb} (Referer allmovieland.you) -> page json {"file","key",...}
 *                       POST file (X-Csrf-Token=key) -> [{file,id,title}] (tv: seasons -> episodes -> folder)
 *                       POST {ml_host}/playlist/{file}.txt -> m3u8 url. One link per audio language.
 *  - FVStream 3 VixSrc: vixsrc.to/api/movie/{id} -> {"src"} -> page window.masterPlaylist {url, params{token,expires,asn}}
 *  - FVStream 4 Nestra: vidsrc.sh/embed/movie/{id} -> iframe cloudnestra /rcp/.. -> src:'/prorcp/..' -> file:'a or b'
 *                       with {vN} placeholders filled from the page's own vars.
 *  - embeds (WebView) : embed.vidrift.net, embed.smashystream.com - only tried when nothing above answered.
 */
class FlixVisionProvider : MainAPI() {
    override var mainUrl = "https://flixvision.tv"
    override var name = "FlixVision"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbKey = "2f3cb5763db1117fcba3948632f8aad9" // E4/a.java in the APK
    private val tmdbImg = "https://image.tmdb.org/t/p/w500"
    private val tmdbBack = "https://image.tmdb.org/t/p/w1280"

    private val mlHost = "https://trinstor297undoint.com" // R.string.ml_host
    private val mlReferer = "https://allmovieland.you/"

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    override val mainPage = mainPageOf(
        "/trending/all/day" to "Trending Today",
        "/movie/popular" to "Popular Movies",
        "/tv/popular" to "Popular Series",
        "/movie/now_playing" to "In Theaters",
        "/tv/on_the_air" to "Airing Now",
        "/movie/top_rated" to "Top Rated Movies",
        "/tv/top_rated" to "Top Rated Series",
        "/discover/movie?with_original_language=hi&sort_by=popularity.desc" to "Hindi Movies",
        "/discover/tv?with_original_language=hi&sort_by=popularity.desc" to "Hindi Series",
        "/discover/movie?with_original_language=ta&sort_by=popularity.desc" to "Tamil Movies",
        "/discover/movie?with_original_language=te&sort_by=popularity.desc" to "Telugu Movies",
        "/discover/tv?with_networks=213" to "Netflix",
        "/discover/tv?with_networks=1024" to "Amazon Prime",
        "/discover/tv?with_networks=2739" to "Disney+",
    )

    // ------------------------------------------------------------------ models

    data class LinkData(
        val type: String,
        val tmdbId: Int,
        val imdbId: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
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
    data class TmdbCast(val name: String? = null, @JsonProperty("profile_path") val profilePath: String? = null, val character: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbCredits(val cast: List<TmdbCast>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbVideo(val key: String? = null, val site: String? = null, val type: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbVideos(val results: List<TmdbVideo>? = null)

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
        val status: String? = null,
        val genres: List<TmdbGenre>? = null,
        val seasons: List<TmdbSeason>? = null,
        val credits: TmdbCredits? = null,
        val videos: TmdbVideos? = null,
        val recommendations: TmdbResp? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbEpisode(
        @JsonProperty("episode_number") val episodeNumber: Int = 0,
        @JsonProperty("season_number") val seasonNumber: Int = 0,
        val name: String? = null,
        val overview: String? = null,
        @JsonProperty("still_path") val stillPath: String? = null,
        @JsonProperty("air_date") val airDate: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbSeasonDetail(val episodes: List<TmdbEpisode>? = null)

    // ----------------------------------------------------------------- catalog

    private suspend fun tmdb(path: String): String {
        val sep = if (path.contains("?")) "&" else "?"
        return app.get("$tmdbApi$path${sep}api_key=$tmdbKey&language=en-US", headers = mapOf("User-Agent" to ua)).text
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val defType = when {
            request.data.startsWith("/movie") || request.data.startsWith("/discover/movie") -> "movie"
            request.data.startsWith("/tv") || request.data.startsWith("/discover/tv") -> "tv"
            else -> null
        }
        val sep = if (request.data.contains("?")) "&" else "?"
        val resp = runCatching { parseJson<TmdbResp>(tmdb("${request.data}${sep}page=$page")) }.getOrNull()
        val items = resp?.results?.mapNotNull { it.toSearch(defType) }.orEmpty()
        return newHomePageResponse(request.name, items, hasNext = (resp?.totalPages ?: 1) > page)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val q = URLEncoder.encode(query, "UTF-8")
        return runCatching {
            parseJson<TmdbResp>(tmdb("/search/multi?query=$q&include_adult=false")).results?.mapNotNull { it.toSearch(null) }
        }.getOrNull().orEmpty()
    }

    override suspend fun load(url: String): LoadResponse? {
        val path = url.removePrefix(mainUrl).trim('/')
        val type = path.substringBefore("/")
        val id = path.substringAfter("/").toIntOrNull() ?: return null
        val d = parseJson<TmdbDetail>(tmdb("/$type/$id?append_to_response=external_ids,credits,videos,recommendations"))
        val title = d.title ?: d.name ?: return null
        val year = (d.releaseDate ?: d.firstAirDate)?.take(4)?.toIntOrNull()
        val imdb = d.imdbId ?: d.externalIds?.imdbId
        val poster = d.posterPath?.let { tmdbImg + it }
        val bg = d.backdropPath?.let { tmdbBack + it }
        val tags = d.genres?.mapNotNull { it.name }.orEmpty()
        val score = d.voteAverage?.let { Score.from10(it.toInt()) }
        val actors = d.credits?.cast.orEmpty().take(20).mapNotNull { c ->
            c.name?.let { ActorData(Actor(it, c.profilePath?.let { p -> tmdbImg + p }), roleString = c.character) }
        }
        val trailer = d.videos?.results?.firstOrNull { it.site == "YouTube" && it.type == "Trailer" }?.key
            ?.let { "https://www.youtube.com/watch?v=$it" }
        val recs = d.recommendations?.results?.mapNotNull { it.toSearch(type) }.orEmpty()

        if (type == "movie") {
            return newMovieLoadResponse(title, url, TvType.Movie, LinkData("movie", id, imdb).toJson()) {
                posterUrl = poster; backgroundPosterUrl = bg; this.year = year
                plot = d.overview; this.tags = tags; this.score = score; duration = d.runtime
                this.actors = actors; recommendations = recs
                addTrailer(trailer)
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
            sd.episodes.orEmpty().map { ep ->
                newEpisode(LinkData("tv", id, imdb, ep.seasonNumber, ep.episodeNumber).toJson()) {
                    name = ep.name; season = ep.seasonNumber; episode = ep.episodeNumber
                    posterUrl = ep.stillPath?.let { tmdbImg + it }; description = ep.overview
                    addDate(ep.airDate)
                }
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster; backgroundPosterUrl = bg; this.year = year
            plot = d.overview; this.tags = tags; this.score = score
            this.actors = actors; recommendations = recs
            showStatus = when (d.status) {
                "Returning Series" -> ShowStatus.Ongoing
                "Ended", "Canceled" -> ShowStatus.Completed
                else -> null
            }
            addTrailer(trailer)
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
        val ld = parseJson<LinkData>(data)
        val seen = HashSet<String>()
        var found = false
        val emit: (ExtractorLink) -> Unit = { link ->
            val fresh = synchronized(seen) { seen.add(link.url) }
            if (fresh) { found = true; callback(link) }
        }
        coroutineScope {
            listOf(
                async { safe { dexter(ld, emit) } },
                async { safe { allMovieLand(ld, emit) } },
                async { safe { vixSrc(ld, emit) } },
                async { safe { cloudnestra(ld, emit) } },
            ).awaitAll()
        }
        if (!found) {
            // the app plays these in a WebView; do the same and catch the playlist
            val embeds = if (ld.type == "movie") listOf(
                "https://embed.vidrift.net/embed/movie/${ld.tmdbId}",
                "https://embed.smashystream.com/playere.php?tmdb=${ld.tmdbId}",
            ) else listOf(
                "https://embed.vidrift.net/embed/tv/${ld.tmdbId}/${ld.season}/${ld.episode}",
                "https://embed.smashystream.com/playere.php?tmdb=${ld.tmdbId}&season=${ld.season}&episode=${ld.episode}",
            )
            for (e in embeds) {
                if (found) break
                safe { webSniff(e, emit) }
            }
        }
        return found
    }

    private suspend fun safe(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private fun origin(u: String) = u.substringBefore("://") + "://" + u.substringAfter("://").substringBefore("/")

    private suspend fun hls(source: String, url: String, referer: String, quality: Int, emit: (ExtractorLink) -> Unit) {
        emit(
            newExtractorLink(source, source, url, ExtractorLinkType.M3U8) {
                this.referer = referer
                this.quality = quality
                this.headers = mapOf("User-Agent" to ua, "Origin" to origin(referer))
            }
        )
    }

    // FVSTREAM 4 (Dexter): the url itself is the stream. Peek at it to tell HLS from a file.
    private suspend fun dexter(ld: LinkData, emit: (ExtractorLink) -> Unit) {
        val url = if (ld.type == "movie") "https://dexter.pw/api/s/21/movie/${ld.tmdbId}?v=3"
        else "https://dexter.pw/api/s/21/tv/${ld.tmdbId}/${ld.season}/${ld.episode}?v=3"
        val res = app.get(url, headers = mapOf("User-Agent" to ua, "Range" to "bytes=0-2047"), timeout = 20_000L)
        if (res.code >= 400) return
        val finalUrl = res.url
        val head = runCatching { res.text.take(64) }.getOrDefault("")
        val ctype = res.headers["Content-Type"].orEmpty()
        val isHls = head.startsWith("#EXTM3U") || ctype.contains("mpegurl", true) || finalUrl.contains(".m3u8")
        val isVideo = ctype.startsWith("video") || finalUrl.substringBefore("?").endsWith(".mp4")
        if (!isHls && !isVideo) return
        emit(
            newExtractorLink("FVStream Dexter", "FVStream Dexter", finalUrl,
                if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                quality = Qualities.P1080.value
                headers = mapOf("User-Agent" to ua)
            }
        )
    }

    // FVSTREAM (AllMovieLand): one stream per audio language (English, Hindi, ...)
    private suspend fun allMovieLand(ld: LinkData, emit: (ExtractorLink) -> Unit) {
        val imdb = ld.imdbId?.takeIf { it.startsWith("tt") } ?: return
        val playUrl = "$mlHost/play/$imdb"
        val page = app.get(playUrl, referer = mlReferer, headers = mapOf("User-Agent" to ua)).text
        val json = Regex("""(\{"file".*?\})\)?\s*;""", RegexOption.DOT_MATCHES_ALL).find(page)?.groupValues?.get(1) ?: return
        val cfg = JSONObject(json)
        val key = cfg.optString("key").ifBlank { return }
        var file = cfg.optString("file").replace("\\/", "/").ifBlank { return }
        if (!file.startsWith("http")) file = mlHost + (if (file.startsWith("/")) "" else "/") + file
        val headers = mapOf("User-Agent" to ua, "X-Csrf-Token" to key, "Referer" to playUrl)

        val list = JSONArray(app.post(file, headers = headers).text)
        val items = mutableListOf<Pair<String, String>>() // file, language
        if (ld.type == "movie") {
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                o.optString("file").takeIf { it.isNotBlank() }?.let { items += it to o.optString("title") }
            }
        } else {
            for (i in 0 until list.length()) {
                val s = list.optJSONObject(i) ?: continue
                if (s.optString("id") != ld.season.toString()) continue
                val eps = s.optJSONArray("folder") ?: continue
                for (j in 0 until eps.length()) {
                    val e = eps.optJSONObject(j) ?: continue
                    if (e.optString("episode") != ld.episode.toString()) continue
                    val langs = e.optJSONArray("folder") ?: continue
                    for (k in 0 until langs.length()) {
                        val l = langs.optJSONObject(k) ?: continue
                        l.optString("file").takeIf { it.isNotBlank() }?.let { items += it to l.optString("title") }
                    }
                }
            }
        }
        coroutineScope {
            items.map { (f, lang) ->
                async {
                    safe {
                        val m3u8 = app.post("$mlHost/playlist/${f.removePrefix("~")}.txt", headers = headers).text.trim()
                        if (m3u8.startsWith("http")) {
                            val label = "FVStream" + if (lang.isNotBlank()) " - $lang" else ""
                            hls(label, m3u8, "$mlHost/", Qualities.P1080.value, emit)
                        }
                    }
                }
            }.awaitAll()
        }
    }

    // FVSTREAM 3 (VixSrc): api -> embed page -> window.masterPlaylist
    private suspend fun vixSrc(ld: LinkData, emit: (ExtractorLink) -> Unit) {
        val ids = listOfNotNull(ld.tmdbId.toString(), ld.imdbId?.takeIf { it.isNotBlank() }).distinct()
        for (id in ids) {
            val api = if (ld.type == "movie") "https://vixsrc.to/api/movie/$id"
            else "https://vixsrc.to/api/tv/$id/${ld.season}/${ld.episode}"
            val src = runCatching {
                JSONObject(app.get(api, referer = "https://vixsrc.to/", headers = mapOf("User-Agent" to ua)).text).optString("src")
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: continue
            val embed = if (src.startsWith("http")) src else "https://vixsrc.to$src"
            val html = app.get(embed, referer = "https://vixsrc.to/", headers = mapOf("User-Agent" to ua)).text
            val block = Regex("""window\.masterPlaylist\s*=\s*(\{[\s\S]*?\})\s*(?:window\.|;|</script>)""").find(html)?.groupValues?.get(1)
                ?: continue
            val url = Regex("""url\s*:\s*['"]([^'"]+)['"]""").find(block)?.groupValues?.get(1) ?: continue
            fun p(k: String) = Regex("""['"]?$k['"]?\s*:\s*['"]([^'"]*)['"]""").find(block)?.groupValues?.get(1).orEmpty()
            val sep = if (url.contains("?")) "&" else "?"
            val stream = "$url${sep}token=${p("token")}&expires=${p("expires")}&asn=${p("asn")}&h=1&lang=en"
            hls("FVStream 3 (VixSrc)", stream, embed, Qualities.P1080.value, emit)
            return
        }
    }

    // FVSTREAM 4 (vidsrc.sh -> cloudnestra rcp -> prorcp)
    private suspend fun cloudnestra(ld: LinkData, emit: (ExtractorLink) -> Unit) {
        val ids = listOfNotNull(ld.imdbId?.takeIf { it.isNotBlank() }, ld.tmdbId.toString()).distinct()
        for (id in ids) {
            val embed = if (ld.type == "movie") "https://vidsrc.sh/embed/movie/$id"
            else "https://vidsrc.sh/embed/tv/$id/${ld.season}-${ld.episode}"
            val page = runCatching { app.get(embed, headers = mapOf("User-Agent" to ua)).text }.getOrNull() ?: continue
            var rcp = Regex("""<iframe[^>]*src=["']([^"']+)["']""").find(page)?.groupValues?.get(1) ?: continue
            if (rcp.startsWith("//")) rcp = "https:$rcp"
            val base = origin(rcp)
            var rcpHtml = app.get(rcp, referer = embed, headers = mapOf("User-Agent" to ua)).text
            Regex(""""playerUrl":"([^"]+)"""").find(rcpHtml)?.groupValues?.get(1)?.let { pu ->
                val u = if (pu.startsWith("//")) "https:$pu" else if (pu.startsWith("/")) base + pu else pu
                rcpHtml = app.get(u, referer = rcp, headers = mapOf("User-Agent" to ua)).text
            }
            val prorcp = Regex("""src:\s*['"](/prorcp/[^'"]+)['"]""").find(rcpHtml)?.groupValues?.get(1)
            val player = if (prorcp != null) app.get(base + prorcp, referer = rcp, headers = mapOf("User-Agent" to ua)).text else rcpHtml
            val file = Regex("""file\s*:\s*['"]([^'"]+)['"]""").find(player)?.groupValues?.get(1) ?: continue

            val vars = HashMap<String, String>()
            Regex("""(?:var\s+)?(\w+)\s*=\s*["']([^"']*)["']""").findAll(player).forEach { vars[it.groupValues[1]] = it.groupValues[2] }
            if (!vars.containsKey("v1")) {
                val host = base.substringAfter("://")
                for (i in 1..5) vars["v$i"] = host
            }
            var any = false
            file.split(" or ").map { it.trim() }.forEachIndexed { i, cand ->
                var u = cand
                Regex("""\{(\w+)\}""").findAll(cand).forEach { m -> vars[m.groupValues[1]]?.let { u = u.replace(m.value, it) } }
                if (u.startsWith("http") && !u.contains("{")) {
                    hls("FVStream 4 (Nestra ${i + 1})", u, "$base/", Qualities.P1080.value, emit)
                    any = true
                }
            }
            if (any) return
        }
    }

    private suspend fun webSniff(embed: String, emit: (ExtractorLink) -> Unit) {
        val got = app.get(
            embed, referer = "$mainUrl/",
            interceptor = WebViewResolver(Regex("""\.m3u8"""), timeout = 25_000L),
        ).url
        if (got.contains(".m3u8")) {
            val host = embed.substringAfter("://").substringBefore("/").removePrefix("embed.")
            hls("FlixVision ($host)", got, origin(embed) + "/", Qualities.Unknown.value, emit)
        }
    }
}
