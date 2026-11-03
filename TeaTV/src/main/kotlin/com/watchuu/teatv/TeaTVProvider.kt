package com.watchuu.teatv

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

/**
 * TeaTV (Android app, v11.1.7) - rebuilt from the decompiled APK.
 *
 * Catalog is TMDB (the app pulls its key from remote config, so a public key is used here).
 * Stream sources ported from the app's scrapers (com.oe.photocollage.o3 / l2):
 *  - PrimeSrc   : primesrc.me/api/v1/s?type=movie|tv&tmdb=..[&season&episode] -> {servers:[{name,key}]}
 *                 then /api/v1/l?key=.. -> {link} (a host embed: voe, dood, streamwish, filelions, mixdrop, ...)
 *  - Rivestream : rivestream.org/api/backendfetch?requestID=movieVideoProvider|tvVideoProvider
 *                 &service=flowcast|halo|kage|volt|zenith&secretKey=..&proxyMode=noProxy
 *                 -> {data:{sources:[{quality,url}]}} (urls may be wrapped in a ?url=...&header proxy)
 *  - VidSrc.vip : api2.vidsrc.vip/movie/{enc} | /tv/{enc} -> {source1:{url},source2:{url},...}
 *                 movie enc = b64(b64(reverse(digits mapped to a-j))), tv enc = b64(b64(reverse("id-s-e")))
 *  - AutoEmbed  : tom.autoembed.cc/api/getVideoSource?type=movie&id=.. | type=tv&id=../s/e -> {videoSource}
 */
class TeaTVProvider : MainAPI() {
    override var mainUrl = "https://teatv.net"
    override var name = "TeaTV"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbKey = "eddaca1b110bc28310482915ab3466fb"
    private val tmdbImg = "https://image.tmdb.org/t/p/w500"
    private val tmdbBack = "https://image.tmdb.org/t/p/original"

    private val ua =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    override val mainPage = mainPageOf(
        "/trending/all/day" to "Trending",
        "/movie/popular" to "Popular Movies",
        "/tv/popular" to "Popular TV Shows",
        "/movie/now_playing" to "In Theaters",
        "/movie/top_rated" to "Top Rated Movies",
        "/tv/top_rated" to "Top Rated TV Shows",
        "/tv/on_the_air" to "Airing Now",
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PrimeServer(val name: String? = null, val key: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PrimeServers(val servers: List<PrimeServer>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PrimeLink(val link: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class RiveSource(val quality: Any? = null, val url: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class RiveData(val sources: List<RiveSource>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class RiveResp(val data: RiveData? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AutoEmbed(val videoSource: String? = null)

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
            request.data.startsWith("/movie") -> "movie"
            request.data.startsWith("/tv") -> "tv"
            else -> null
        }
        val resp = runCatching { parseJson<TmdbResp>(tmdb("${request.data}?page=$page")) }.getOrNull()
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
        val d = parseJson<TmdbDetail>(tmdb("/$type/$id?append_to_response=external_ids"))
        val title = d.title ?: d.name ?: return null
        val year = (d.releaseDate ?: d.firstAirDate)?.take(4)?.toIntOrNull()
        val imdb = d.imdbId ?: d.externalIds?.imdbId
        val poster = d.posterPath?.let { tmdbImg + it }
        val bg = d.backdropPath?.let { tmdbBack + it }
        val tags = d.genres?.mapNotNull { it.name }.orEmpty()
        val score = d.voteAverage?.let { Score.from10(it.toInt()) }

        if (type == "movie") {
            return newMovieLoadResponse(title, url, TvType.Movie, LinkData("movie", id, imdb).toJson()) {
                posterUrl = poster; backgroundPosterUrl = bg; this.year = year
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
            sd.episodes.orEmpty().map { ep ->
                newEpisode(LinkData("tv", id, imdb, ep.seasonNumber, ep.episodeNumber).toJson()) {
                    name = ep.name; season = ep.seasonNumber; episode = ep.episodeNumber
                    posterUrl = ep.stillPath?.let { tmdbImg + it }; description = ep.overview
                }
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster; backgroundPosterUrl = bg; this.year = year
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
        val ld = parseJson<LinkData>(data)
        val seen = HashSet<String>()
        var found = false
        val emit: (ExtractorLink) -> Unit = { link ->
            val fresh = synchronized(seen) { seen.add(link.url) }
            if (fresh) { found = true; callback(link) }
        }
        coroutineScope {
            listOf(
                async { safe { primeSrc(ld, subtitleCallback, emit) } },
                async { safe { rivestream(ld, emit) } },
                async { safe { vidsrcVip(ld, emit) } },
                async { safe { autoEmbed(ld, emit) } },
            ).awaitAll()
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

    private fun qualityOf(q: String?): Int = Regex("""(\d{3,4})""").find(q ?: "")?.groupValues?.get(1)?.toIntOrNull()
        ?.let { getQualityFromName("${it}p") } ?: Qualities.Unknown.value

    private suspend fun direct(src: String, url: String, referer: String, quality: String?, emit: (ExtractorLink) -> Unit) {
        val m3u8 = url.contains(".m3u8") || !url.substringBefore("?").let { it.endsWith(".mp4") || it.endsWith(".mkv") }
        emit(
            newExtractorLink(src, src, url, if (m3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                this.referer = referer
                this.quality = qualityOf(quality)
                this.headers = mapOf("User-Agent" to ua)
            }
        )
    }

    // PrimeSrc: list of host servers, each resolved to an embed link
    private suspend fun primeSrc(ld: LinkData, subs: (SubtitleFile) -> Unit, emit: (ExtractorLink) -> Unit) {
        val api = "https://primesrc.me/api/v1"
        val listUrl = if (ld.type == "movie") "$api/s?type=movie&tmdb=${ld.tmdbId}"
        else "$api/s?type=tv&tmdb=${ld.tmdbId}&episode=${ld.episode}&season=${ld.season}"
        val servers = app.get(listUrl, referer = listUrl, headers = mapOf("User-Agent" to ua))
            .parsedSafe<PrimeServers>()?.servers.orEmpty()
        coroutineScope {
            servers.mapNotNull { it.key }.distinct().map { key ->
                async {
                    safe {
                        var link = app.get("$api/l?key=$key", referer = listUrl, headers = mapOf("User-Agent" to ua))
                            .parsedSafe<PrimeLink>()?.link ?: return@safe
                        if ((link.contains("mixdrop") || link.contains("mxdrop")) && link.contains("/f/")) link = link.replace("/f/", "/e/")
                        if (link.contains("streamwish.to")) link = link.replace("streamwish.to", "hgplaycdn.com")
                        loadExtractor(link, "https://primesrc.me/", subs, emit)
                    }
                }
            }.awaitAll()
        }
    }

    // Rivestream backend: five services, each returning direct sources
    private suspend fun rivestream(ld: LinkData, emit: (ExtractorLink) -> Unit) {
        val services = listOf("flowcast", "halo", "kage", "volt", "zenith")
        coroutineScope {
            services.map { svc ->
                async {
                    safe {
                        val url = if (ld.type == "movie")
                            "https://rivestream.org/api/backendfetch?requestID=movieVideoProvider&id=${ld.tmdbId}&service=$svc&secretKey=LTViC1c1MGQ0YzBl&proxyMode=noProxy"
                        else
                            "https://rivestream.org/api/backendfetch?requestID=tvVideoProvider&id=${ld.tmdbId}&season=${ld.season}&episode=${ld.episode}&service=$svc&secretKey=LTM1ViC1OTM1YTk0&proxyMode=noProxy"
                        val res = app.get(url, referer = "https://rivestream.org/", headers = mapOf("User-Agent" to ua))
                            .parsedSafe<RiveResp>()
                        res?.data?.sources.orEmpty().forEach { s ->
                            var u = s.url ?: return@forEach
                            if (u.contains("?url=") && u.contains("&header")) {
                                u = URLDecoder.decode(u.substringAfter("?url=").substringBefore("&header"), "UTF-8")
                            }
                            if (!u.startsWith("http")) return@forEach
                            direct("Rivestream ${svc.replaceFirstChar { it.uppercase() }}", u, "https://rivestream.org/", s.quality?.toString(), emit)
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private fun b64(s: String) = Base64.encodeToString(s.toByteArray(), Base64.NO_WRAP)

    // VidSrc.vip: id encoded the same way the app does it
    private suspend fun vidsrcVip(ld: LinkData, emit: (ExtractorLink) -> Unit) {
        val enc = if (ld.type == "movie") {
            val letters = ld.tmdbId.toString().map { "abcdefghij"[it - '0'] }.joinToString("")
            b64(b64(letters.reversed()))
        } else {
            b64(b64("${ld.tmdbId}-${ld.season}-${ld.episode}".reversed()))
        }
        val url = "https://api2.vidsrc.vip/${if (ld.type == "movie") "movie" else "tv"}/$enc"
        val json = JSONObject(app.get(url, referer = "https://vidsrc.vip/", headers = mapOf("User-Agent" to ua)).text)
        for (i in 1..20) {
            val src = json.optJSONObject("source$i") ?: continue
            var u = src.optString("url").takeIf { it.isNotBlank() } ?: continue
            if (u.contains("proxy?url=https")) {
                u = URLDecoder.decode(u.substring(u.indexOf("proxy?url=https")).removePrefix("proxy?url="), "UTF-8")
            } else if (u.contains("proxy/http")) {
                u = URLDecoder.decode(u.substring(u.indexOf("proxy/http")).removePrefix("proxy/"), "UTF-8")
            }
            if (!u.startsWith("http")) continue
            direct("VidSrc.vip $i", u, "https://vidsrc.vip/", src.optString("quality"), emit)
        }
    }

    // AutoEmbed (tom server): one direct stream
    private suspend fun autoEmbed(ld: LinkData, emit: (ExtractorLink) -> Unit) {
        val ids = listOfNotNull(ld.imdbId?.takeIf { it.isNotBlank() }, ld.tmdbId.toString()).distinct()
        for (id in ids) {
            val url = if (ld.type == "movie") "https://tom.autoembed.cc/api/getVideoSource?type=movie&id=$id"
            else "https://tom.autoembed.cc/api/getVideoSource?type=tv&id=$id/${ld.season}/${ld.episode}"
            val embed = if (ld.type == "movie") "https://player.autoembed.cc/embed/movie/$id"
            else "https://player.autoembed.cc/embed/tv/$id/${ld.season}/${ld.episode}"
            val src = runCatching {
                app.get(url, referer = embed, headers = mapOf("User-Agent" to ua)).parsedSafe<AutoEmbed>()?.videoSource
            }.getOrNull()
            if (!src.isNullOrBlank() && src.startsWith("http")) {
                direct("AutoEmbed", src, "https://player.autoembed.cc/", null, emit)
                return
            }
        }
    }
}
