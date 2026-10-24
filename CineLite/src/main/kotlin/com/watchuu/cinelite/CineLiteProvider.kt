package com.watchuu.cinelite

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

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
 * Link resolution (v8):
 *  - movies / tv : a hidden WebView loads the embed player (passes Turnstile), hooks fetch/XHR,
 *                  then asks EVERY server (/servers + known provider names) from inside the page
 *                  with the player's own request headers, and lists all streams found.
 *  - anime       : megaplay + vidnest + 4animo directly, plus whatever the real player requests
 *                  through the site proxy.
 *  - every HLS master playlist is read and each resolution is listed as its own link
 *    (plus one "Auto" link with the master).
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

    // the servers the site's player offers (log of 2026-10-24; vault is requested first by the player)
    private val providers = listOf("vault", "cinecat", "hyper", "vidup", "xdownloader")

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
                // all seasons are fetched at the same time (shows with many seasons opened slowly)
                val seasonData = coroutineScope {
                    detail.seasons
                        ?.filter { it.seasonNumber > 0 && (it.episodeCount ?: 0) > 0 }
                        ?.map { season ->
                            async {
                                try {
                                    parseJson<TmdbSeasonDetail>(tmdbGet("/tv/$tmdbId/season/${season.seasonNumber}"))
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    null
                                }
                            }
                        }?.awaitAll() ?: emptyList()
                }
                seasonData.filterNotNull()
                    .forEach { sd ->
                        try {
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

    private val embedQuery = "nosuggestionui=true&quality=1080p&stremio_addons=%5B%5D"

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = payloadParts(data)
        val type = parts.getOrNull(0) ?: return false
        val tmdbId = parts.getOrNull(1)?.toIntOrNull() ?: return false

        var season: Int? = null
        var episode: Int? = null
        val title: String
        if (type == "movie") {
            title = parts.drop(2).joinToString("|")
        } else {
            season = parts.getOrNull(2)?.toIntOrNull()
            episode = parts.getOrNull(3)?.toIntOrNull()
            if (season == null || episode == null) return false
            title = parts.drop(4).joinToString("|")
        }

        // every link goes through here so the same url is never listed twice
        val emitted = HashSet<String>()
        var found = false
        // servers now answer in parallel, so the shared state is guarded
        val out: (ExtractorLink) -> Unit = { link ->
            val isNew = synchronized(emitted) {
                if (emitted.add(link.url)) { found = true; true } else false
            }
            if (isNew) callback.invoke(link)
        }
        val dbg: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

        if (type == "atv") {
            // Anime uses the same WebView + all-servers path as movies/TV.
            // We find the AniList id and open the cinelite anime embed; Turnstile is
            // passed by the hidden WebView just like for movies/shows.
            val aid = try { findAniListId(title, season ?: 1) } catch (_: Exception) { null }
            if (aid != null) {
                try {
                    // Reuse movieTvLinks with a custom embed url list and dummy tmdbId.
                    // We pass the embed url directly and skip the TMDB subtitle path (no season/ep for anime).
                    animeViaPlayer(aid, episode ?: 1, title, subtitleCallback, out, dbg)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    dbg.add("anime EXC ${e.message?.take(80)}")
                }
            } else {
                dbg.add("anime: AniList id not found for $title")
            }
        } else {
            try {
                movieTvLinks(type, tmdbId, season, episode, title, subtitleCallback, out, dbg)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                dbg.add("web EXC ${e.message?.take(80)}")
            }
            if (!found) {
                dbg.add("multi-server path found nothing (see lines above)")
                // last resort: the old single-playlist capture on the watch page
                val pageUrl = if (type == "movie") "$mainUrl/watch/$tmdbId"
                else "$mainUrl/watch/tv/$tmdbId/$season/$episode"
                legacyCapture(pageUrl, out, dbg)
            }
        }

        // diagnostics are kept in dbg for debugging but never shown as fake links
        return found
    }

    // ---------------------------------------------------------- movies / tv shows

    private suspend fun movieTvLinks(
        type: String,
        tmdbId: Int,
        season: Int?,
        episode: Int?,
        title: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        out: (ExtractorLink) -> Unit,
        dbg: MutableList<String>
    ) {
        val isMovie = type == "movie"
        val defaultQuery = if (isMovie) "id=$tmdbId" else "id=$tmdbId&season=$season&episode=$episode"

        // The movie/anime embed urls are taken from the log. The tv one is inferred, so a second
        // pattern is tried when the first one never reaches the stream api.
        val embeds = if (isMovie) listOf("$embedUrl/$tmdbId?$embedQuery")
        else listOf(
            "$embedUrl/tv/$tmdbId/$season/$episode?$embedQuery",
            "$embedUrl/$tmdbId/$season/$episode?$embedQuery",
        )

        coroutineScope {
            // the year (only needed for the subtitle query) is looked up while the player loads
            val yearJob = async {
                try {
                    val d = parseJson<TmdbDetail>(tmdbGet("/${if (isMovie) "movie" else "tv"}/$tmdbId"))
                    (d.releaseDate ?: d.firstAirDate)?.take(4)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            }

            for ((idx, embed) in embeds.withIndex()) {
                val s = CineLiteBrowser.open(embed, ua, "$mainUrl/")
                if (s == null) {
                    dbg.add("webview unavailable")
                    yearJob.cancel()
                    return@coroutineScope
                }
                try {
                    val ready = s.waitFor(if (idx == 0) 30_000L else 20_000L) {
                        s.has("template") ||
                            s.events.any { (it.kind == "provider" || it.kind == "servers") && it.status in 200..299 }
                    }
                    dbg.add("embed#$idx ready=$ready events=${s.events.size} verify=${s.events.firstOrNull { it.kind == "verify" }?.status}")
                    if (!ready) continue
                    // /servers usually lands right after the first provider call: wait for it, but only briefly
                    s.waitFor(1_200L) { s.events.any { it.kind == "servers" } }
                    // the player itself is not needed any more: stop it so it doesn't eat bandwidth
                    s.quiet()

                    val query = s.events.firstOrNull { it.kind == "provider" }
                        ?.url?.substringAfter("?", "")?.takeIf { it.isNotBlank() } ?: defaultQuery

                    // subtitles in the background, alongside the servers
                    val subJob = async {
                        try {
                            val year = yearJob.await()
                            var subUrl = "$apiUrl/subtitles?id=$tmdbId&title=${URLEncoder.encode(title, "UTF-8")}" +
                                (if (year != null) "&year=$year" else "")
                            if (!isMovie) subUrl += "&season=$season&episode=$episode"
                            val sr = s.get(subUrl, true, 15_000L)
                            if (sr != null && sr.first in 200..299) {
                                mapper.readTree(sr.second)?.let { collectSubtitles(it, subtitleCallback) }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                        }
                    }

                    // server list: the api's own list first, our known names as a safety net
                    val names = LinkedHashSet<String>()
                    names.addAll(providers)
                    var serversBody = s.events.firstOrNull { it.kind == "servers" && it.status in 200..299 }?.body
                    if (serversBody == null) {
                        val r = s.get("$apiUrl/servers", true, 8_000L)
                        if (r != null && r.first in 200..299) serversBody = r.second
                    }
                    serversBody?.let { names.addAll(parseServerNames(it)) }
                    s.events.filter { it.kind == "provider" }.forEach { e -> providerName(e.url)?.let { names.add(it) } }

                    // responses the player already got
                    val have = LinkedHashMap<String, String>()
                    s.events.filter { it.kind == "provider" && it.status in 200..299 && it.body.isNotBlank() }
                        .forEach { e ->
                            val n = providerName(e.url)
                            if (n != null && !have.containsKey(n)) have[n] = e.body
                        }

                    // every server is handled on its own: links are listed as soon as that server
                    // answers, instead of waiting for the slowest one
                    val jobs = have.map { (n, body) -> async { emitServer(n, body, s, out, dbg) } } +
                        names.filter { it !in have }.take(12).map { n ->
                            async {
                                val r = s.get("$apiUrl/provider/$n?$query", true, 15_000L)
                                if (r != null && r.first in 200..299 && r.second.isNotBlank()) {
                                    emitServer(n, r.second, s, out, dbg)
                                } else {
                                    dbg.add("$n -> ${r?.first} ${r?.second?.take(60)}")
                                }
                            }
                        }
                    jobs.awaitAll()
                    subJob.await()
                    return@coroutineScope
                } finally {
                    s.close()
                }
            }
            yearJob.cancel()
        }
    }

    /** Parses one server's answer and lists all its streams (playlists fetched in parallel). */
    private suspend fun emitServer(
        n: String,
        body: String,
        s: CineLiteBrowser.Session,
        out: (ExtractorLink) -> Unit,
        dbg: MutableList<String>
    ) {
        val label = prettyServer(n)
        val cands = parseCandidates(body)
        if (cands.isEmpty()) dbg.add("$n: no stream in ${body.take(120)}")
        coroutineScope {
            cands.map { c ->
                async {
                    val h = embedHeaders + (c.headers ?: emptyMap())
                    emitStream(
                        listOfNotNull(label, c.label).joinToString(" "),
                        c.url, c.quality, c.kind, h, h["Referer"] ?: "$embedUrl/", s, out
                    )
                }
            }.awaitAll()
        }
    }

    /**
     * Anime link resolution: opens the cinelite anime embed in a hidden WebView so Cloudflare
     * Turnstile is satisfied automatically, then collects the vault/proxy responses that the embed
     * fires for each backend (megaplay / vidnest / 4animo) and the HLS playlists it requests.
     *
     * The anime embed does NOT call /servers or /provider — it goes directly through vault/proxy,
     * so the movie/TV WebView path is wrong for anime. We wait for proxy events instead.
     */
    private suspend fun animeViaPlayer(
        aid: Int,
        ep: Int,
        title: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        out: (ExtractorLink) -> Unit,
        dbg: MutableList<String>
    ) {
        val embed = "$embedUrl/anime/$aid/$ep?$embedQuery"

        val s = CineLiteBrowser.open(embed, ua, "$mainUrl/") ?: run {
            dbg.add("anime: webview unavailable")
            return
        }
        try {
            // Wait until at least one vault/proxy call succeeds or we see HLS playlists.
            // The Turnstile verify typically takes 3-8 s; vault/proxy and vault/hls responses follow.
            val ready = s.waitFor(35_000L) {
                s.events.any { it.kind == "proxy" && it.status in 200..299 && it.body.isNotBlank() } ||
                    s.events.any { it.kind == "hls" } ||
                    s.playlists.isNotEmpty()
            }
            dbg.add("anime embed ready=$ready events=${s.events.size} playlists=${s.playlists.size} hls=${s.events.count { it.kind == "hls" }}")
            if (!ready) return
            // the other backends answer shortly after the first: wait until things go quiet
            // (no new request for 1.2 s), but never more than 3 s
            run {
                val start = System.currentTimeMillis()
                var last = s.events.size + s.playlists.size
                var lastChange = System.currentTimeMillis()
                while (System.currentTimeMillis() - start < 3_000L) {
                    delay(200L)
                    val now = s.events.size + s.playlists.size
                    if (now != last) { last = now; lastChange = System.currentTimeMillis() }
                    else if (System.currentTimeMillis() - lastChange > 1_200L) break
                }
            }

            s.quiet()

            // every stream is prepared in parallel (each one may need its playlist fetched)
            val seenUrls = LinkedHashSet<String>()
            val tasks = mutableListOf<suspend () -> Unit>()
            for (ev in s.events.toList()) {
                if (ev.kind != "proxy" || ev.status !in 200..299 || ev.body.isBlank()) continue
                val backend = animeServerLabelFromProxyUrl(ev.url)
                val cands = parseCandidates(ev.body)
                dbg.add("anime proxy $backend status=${ev.status} cands=${cands.size}")
                for (c in cands) {
                    if (!seenUrls.add(c.url)) continue
                    val h = embedHeaders + (c.headers ?: emptyMap())
                    tasks.add {
                        emitStream(
                            listOfNotNull(backend, c.label).joinToString(" "),
                            c.url, c.quality, c.kind, h, h["Referer"] ?: "$embedUrl/", s, out
                        )
                    }
                }
            }
            // vault/hls.m3u8 streams captured by the JS hook (kind='hls')
            for (ev in s.events.toList()) {
                if (ev.kind != "hls" || !seenUrls.add(ev.url)) continue
                val label = animeServerLabelFromHlsUrl(ev.url)
                dbg.add("anime hls $label: ${ev.url.take(80)}")
                tasks.add { emitStream(label, ev.url, null, MK.HLS, embedHeaders, "$embedUrl/", s, out) }
            }
            // fallback: HLS playlists captured by shouldInterceptRequest that aren't listed yet
            for (pl in s.playlists.toList()) {
                if (!seenUrls.add(pl)) continue
                val label = if (pl.contains("/vault/hls")) animeServerLabelFromHlsUrl(pl) else "Stream"
                dbg.add("anime playlist $label: ${pl.take(80)}")
                tasks.add { emitStream(label, pl, null, MK.HLS, embedHeaders, "$embedUrl/", s, out) }
            }
            coroutineScope { tasks.map { t -> async { t() } }.awaitAll() }
        } finally {
            s.close()
        }
    }

    /** Label from vault/proxy?url=https://megaplay.buzz/... → "MegaPlay" */
    private fun animeServerLabelFromProxyUrl(vaultUrl: String): String {
        val host = try {
            val proxyParam = vaultUrl.substringAfter("vault/proxy?url=").substringBefore("&")
            java.net.URI(URLDecoder.decode(proxyParam, "UTF-8")).host?.lowercase() ?: ""
        } catch (_: Exception) { "" }
        return animeHostToLabel(host)
    }

    /** Label from vault/hls.m3u8?url=...&ref=https://megaplay.buzz/... → "MegaPlay" */
    private fun animeServerLabelFromHlsUrl(vaultUrl: String): String {
        val qs = vaultUrl.substringAfter("?", "")
        fun hostFromParam(param: String): String? {
            val prefix = "$param="
            val i = qs.indexOf(prefix).takeIf { it >= 0 } ?: return null
            val raw = qs.substring(i + prefix.length).substringBefore("&")
            return try {
                URLDecoder.decode(raw, "UTF-8")
                    .substringAfter("://").substringBefore("/").substringBefore(":").lowercase()
                    .removePrefix("www.")
            } catch (_: Exception) { null }
        }
        val host = hostFromParam("ref") ?: hostFromParam("origin") ?: ""
        return animeHostToLabel(host)
    }

    private fun animeHostToLabel(host: String): String = when {
        host.contains("megaplay") -> "MegaPlay"
        host.contains("vidnest") -> "VidNest"
        host.contains("4animo") -> "4Animo"
        host.contains("nexabloom") -> "MegaPlay" // megaplay CDN
        host.isNotBlank() -> host.substringBefore(".").replaceFirstChar { it.uppercase() }
        else -> "Anime"
    }

    private suspend fun legacyCapture(pageUrl: String, out: (ExtractorLink) -> Unit, dbg: MutableList<String>) {
        try {
            val resolver = WebViewResolver(
                interceptUrl = Regex("""(?i)\.m3u8"""),
                additionalUrls = listOf(Regex("""(?i)\.m3u8""")),
                userAgent = ua,
                useOkhttp = false,
                timeout = 20_000L
            )
            val m3u8 = app.get(pageUrl, referer = "$mainUrl/", interceptor = resolver).url
            if (m3u8.isNotBlank() && m3u8.contains(".m3u8", ignoreCase = true)) {
                emitStream("HLS", m3u8, null, MK.HLS, embedHeaders, "$embedUrl/", null, out)
            } else dbg.add("legacy -> ${m3u8.take(100)}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            dbg.add("legacy EXC ${e.message?.take(80)}")
        }
    }

    private fun providerName(url: String): String? =
        Regex("""/provider/([^?/#]+)""").find(url)?.groupValues?.get(1)

    private fun prettyServer(n: String): String =
        n.split("-", "_").filter { it.isNotBlank() }.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    /** Pulls server ids out of whatever shape /servers has (array of strings, or objects with id/name/slug). */
    private fun parseServerNames(body: String): List<String> {
        val root = try {
            mapper.readTree(body)
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        val ok = Regex("""^[a-z][a-z0-9_-]{1,40}\z""")
        val names = LinkedHashSet<String>()

        // entries the site marks as switched off are not shown by its player, so skip them too
        fun isOff(n: JsonNode): Boolean {
            fun f(k: String) = n.get(k)
            if (f("enabled")?.isBoolean == true && !f("enabled").asBoolean()) return true
            if (f("active")?.isBoolean == true && !f("active").asBoolean()) return true
            if (f("disabled")?.asBoolean() == true || f("hidden")?.asBoolean() == true) return true
            val st = f("status")?.asText()?.lowercase()
            return st != null && st in setOf("off", "offline", "down", "disabled", "inactive", "maintenance")
        }

        fun walk(n: JsonNode, depth: Int) {
            if (depth > 3) return
            if (n.isArray) {
                n.forEach { if (it.isTextual) it.asText().takeIf { t -> ok.matches(t) }?.let { t -> names.add(t) } else walk(it, depth + 1) }
            } else if (n.isObject) {
                if (isOff(n)) return
                // the server's id only (not display names / nested urls)
                val id = listOf("id", "slug", "provider", "key").firstNotNullOfOrNull { k ->
                    n.get(k)?.takeIf { it.isTextual }?.asText()?.takeIf { ok.matches(it) }
                }
                if (id != null) {
                    names.add(id)
                } else {
                    n.fields().forEach { if (it.value.isContainerNode) walk(it.value, depth + 1) }
                }
            }
        }
        walk(root, 0)
        return names.toList()
    }

    // ------------------------------------------------------ generic stream parsing

    private enum class MK { HLS, VIDEO, UNKNOWN }

    private class Cand(
        val url: String,
        val quality: Int?,
        val kind: MK,
        val headers: Map<String, String>?,
        val label: String?
    )

    private val notMediaExt = listOf(
        ".vtt", ".srt", ".ass", ".ssa", ".sub", ".jpg", ".jpeg", ".png", ".webp", ".gif",
        ".ico", ".svg", ".css", ".js", ".json", ".html", ".htm", ".mpd"
    )

    private fun mediaKind(raw: String): MK? {
        val v = raw.trim()
        if (!(v.startsWith("http://") || v.startsWith("https://"))) return null
        val p = v.substringBefore("?").substringBefore("#").lowercase()
        if (notMediaExt.any { p.endsWith(it) }) return null
        if (p.contains(".m3u8")) return MK.HLS
        if (p.endsWith(".mp4") || p.endsWith(".mkv") || p.endsWith(".webm") || p.endsWith(".m4v")) return MK.VIDEO
        val l = v.lowercase()
        return if (l.contains("/hls") || l.contains("/proxy/") || l.contains("/pipe/") ||
            l.contains("master") || l.contains("playlist")
        ) MK.UNKNOWN else null
    }

    private fun qualityOf(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val t = text.lowercase()
        if (t.contains("4k") || t.contains("uhd")) return 2160
        Regex("""(\d{3,4})\s*p""").find(t)?.let { return it.groupValues[1].toInt() }
        Regex("""^\s*(\d{3,4})\s*\z""").find(t)?.let {
            val v = it.groupValues[1].toInt()
            if (v in 144..4320) return v
        }
        return null
    }

    private val skipKeys = setOf(
        "subtitles", "subtitle", "tracks", "captions", "thumbnails", "thumbnail", "poster",
        "image", "images", "intro", "outro", "headers"
    )

    /** Walks any json shape and collects every playable-looking url with the quality/label found next to it. */
    private fun parseCandidates(body: String): List<Cand> {
        val out = ArrayList<Cand>()
        val root = try {
            mapper.readTree(body)
        } catch (_: Exception) {
            null
        }
        if (root == null || !(root.isContainerNode)) {
            // not json: scan the text for urls
            Regex("""https?://[^\s"'\\<>]+""").findAll(body).forEach { m ->
                val u = m.value
                mediaKind(u)?.let { out.add(Cand(u, qualityOf(u), it, null, null)) }
            }
            return out.distinctBy { it.url }
        }

        fun walk(n: JsonNode, q: Int?, label: String?, hdr: Map<String, String>?, depth: Int) {
            if (depth > 12) return
            when {
                n.isTextual -> {
                    val k = mediaKind(n.asText()) ?: return
                    out.add(Cand(n.asText().trim(), q, k, hdr, label))
                }
                n.isArray -> n.forEach { walk(it, q, label, hdr, depth + 1) }
                n.isObject -> {
                    var q2 = q
                    var l2 = label
                    var h2 = hdr
                    for (k in listOf("quality", "resolution", "res", "height", "label", "name", "size")) {
                        val f = n.get(k) ?: continue
                        if (f.isTextual || f.isNumber) qualityOf(f.asText())?.let { q2 = it }
                    }
                    listOf("label", "server", "name", "title", "provider", "lang", "language")
                        .firstNotNullOfOrNull { k ->
                            n.get(k)?.takeIf { it.isTextual }?.asText()
                                ?.takeIf { it.isNotBlank() && !it.startsWith("http") && qualityOf(it) == null }
                        }?.let { l2 = it }
                    n.get("headers")?.takeIf { it.isObject }?.let { h ->
                        h2 = h.fields().asSequence()
                            .filter { it.value.isTextual }
                            .associate { it.key to it.value.asText() }
                    }
                    n.fields().forEach { e ->
                        if (e.key.lowercase() !in skipKeys) walk(e.value, q2, l2, h2, depth + 1)
                    }
                }
            }
        }
        walk(root, null, null, null, 0)
        return out.distinctBy { it.url }
    }

    // ------------------------------------------------------------ emit / resolutions

    private class Variant(val url: String, val quality: Int?, val bandwidth: Long?)

    private fun resolveUrl(base: String, ref: String): String {
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
        return try {
            java.net.URI(base).resolve(ref).toString()
        } catch (_: Exception) {
            if (ref.startsWith("/")) base.substringBefore("://") + "://" + base.substringAfter("://").substringBefore("/") + ref
            else base.substringBefore("?").substringBeforeLast("/") + "/" + ref
        }
    }

    private fun standardQuality(w: Int?, h: Int?): Int? {
        fun byH(x: Int) = when {
            x >= 2000 -> 2160
            x >= 1300 -> 1440
            x >= 1000 -> 1080
            x >= 700 -> 720
            x >= 460 -> 480
            x >= 340 -> 360
            x >= 230 -> 240
            else -> 144
        }

        fun byW(x: Int) = when {
            x >= 3600 -> 2160
            x >= 2400 -> 1440
            x >= 1800 -> 1080
            x >= 1200 -> 720
            x >= 800 -> 480
            x >= 600 -> 360
            x >= 400 -> 240
            else -> 144
        }
        if (w == null && h == null) return null
        return maxOf(w?.let { byW(it) } ?: 0, h?.let { byH(it) } ?: 0)
    }

    private fun parseMaster(text: String, base: String): List<Variant> {
        val lines = text.lines().map { it.trim() }
        val resRe = Regex("""RESOLUTION=(\d+)x(\d+)""", RegexOption.IGNORE_CASE)
        val bwRe = Regex("""(?:^|[:,])BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE)
        val res = ArrayList<Variant>()
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            if (l.startsWith("#EXT-X-STREAM-INF")) {
                val m = resRe.find(l)
                val bw = bwRe.find(l)?.groupValues?.get(1)?.toLongOrNull()
                var j = i + 1
                while (j < lines.size && (lines[j].isEmpty() || lines[j].startsWith("#"))) j++
                if (j < lines.size) {
                    val q = standardQuality(m?.groupValues?.get(1)?.toIntOrNull(), m?.groupValues?.get(2)?.toIntOrNull())
                    res.add(Variant(resolveUrl(base, lines[j]), q, bw))
                    i = j
                }
            }
            i++
        }
        return res.distinctBy { it.url }
    }

    /** Master playlist text: plain http first, then from inside the page (cookies / token bound urls). */
    private suspend fun fetchPlaylist(
        url: String,
        headers: Map<String, String>,
        session: CineLiteBrowser.Session?,
        sniff: Boolean
    ): String? {
        try {
            val h = if (sniff) headers + ("Range" to "bytes=0-1023") else headers
            val head = app.get(url, headers = h, timeout = 10_000L).text
            if (head.trimStart().startsWith("#EXTM3U")) {
                return if (sniff) app.get(url, headers = headers, timeout = 10_000L).text else head
            }
            if (sniff) return null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        if (session != null && !session.closed) {
            val r = session.get(url, false, 10_000L)
            if (r != null && r.first in 200..299 && r.second.trimStart().startsWith("#EXTM3U")) return r.second
        }
        return null
    }

    /**
     * Lists a stream. For HLS the master playlist is read and every resolution becomes its own
     * link (plus one "Auto" link with the master itself); other files are listed as they are.
     */
    private suspend fun emitStream(
        server: String,
        url: String,
        quality: Int?,
        kind: MK,
        headers: Map<String, String>,
        referer: String,
        session: CineLiteBrowser.Session?,
        out: (ExtractorLink) -> Unit
    ) {
        val base = "$name $server".trim()

        if (kind == MK.VIDEO) {
            out.invoke(
                newExtractorLink(name, base + (quality?.let { " ${it}p" } ?: ""), url, ExtractorLinkType.VIDEO) {
                    this.referer = referer
                    this.headers = headers
                    this.quality = quality ?: Qualities.Unknown.value
                }
            )
            return
        }

        val text = fetchPlaylist(url, headers, session, sniff = kind == MK.UNKNOWN)
        if (kind == MK.UNKNOWN && text == null) return // not a playlist, not something we can play

        val variants = if (text != null) parseMaster(text, url) else emptyList()
        for (v in variants.sortedWith(compareByDescending<Variant> { it.quality ?: 0 }.thenByDescending { it.bandwidth ?: 0L })) {
            val label = v.quality?.let { "${it}p" } ?: v.bandwidth?.let { "${it / 1000}kbps" } ?: "Stream"
            out.invoke(
                newExtractorLink(name, "$base $label", v.url, ExtractorLinkType.M3U8) {
                    this.referer = referer
                    this.headers = headers
                    this.quality = v.quality ?: Qualities.Unknown.value
                }
            )
        }
        // the master itself (adaptive); when it is a plain media playlist this is the only link
        out.invoke(
            newExtractorLink(
                name,
                if (variants.isEmpty()) base + (quality?.let { " ${it}p" } ?: " (Auto)") else "$base (Auto)",
                url,
                ExtractorLinkType.M3U8
            ) {
                this.referer = referer
                this.headers = headers
                this.quality = if (variants.isEmpty()) (quality ?: Qualities.Unknown.value) else Qualities.Unknown.value
            }
        )
    }

    // ---------------------------------------------------------------------- anime

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
