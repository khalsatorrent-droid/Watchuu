package com.watchuu.movienerds

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * movienerds.site — built from the capture of 2026-10-31 11:24.
 *
 * The site is a TMDB front end: its own pages render from api.themoviedb.org (156 of the 875
 * captured requests), so the catalogue, artwork and search all come straight from TMDB and
 * nothing has to be scraped from the HTML.
 *
 * Playback goes through its own JSON API, which is the part worth writing down:
 *
 *   GET https://api.movienerds.online/api/stream
 *       ?tmdbId=155&type=movie&lang=en&fast=1&originalLanguage=en
 *       [&season=1&episode=1]            for tv
 *   headers: x-client-app: movienerds
 *            x-app-key:   mn_sec_2026_stream_auth
 *            Origin:      https://movienerds.site
 *            Accept:      application/json
 *
 * and answers:
 *   { "success": true, "sources": [ { "url": ".../proxy.m3u8?b64=…", "type": "hls",
 *                                     "quality": "1080p FHD (Master HLS)",
 *                                     "providerName": "Apex Prism", "language": "English",
 *                                     "headers": { "Referer": "https://vidrock.net/", … } } ],
 *     "subtitles": [ { "label": "English", "url": "/api/subtitles/vtt?url=…" } ] }
 *
 * Each source carries the headers its own CDN wants, so those are passed through per link
 * rather than applied globally — that is what makes the proxy m3u8 actually play.
 */
class MovieNerdsProvider : MainAPI() {
    override var mainUrl = "https://movienerds.site"
    override var name = "MovieNerds"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override val mainPage = mainPageOf(
        "movie/popular" to "Movies",
        "tv/popular" to "TV Shows",
        "trending/all/day" to "Trending",
        "discover/movie?with_genres=16" to "Animation",
        "movie/upcoming" to "Upcoming"
    )

    // ── Catalogue (TMDB) ───────────────────────────────────────────────────────

    private fun tmdbUrl(path: String, page: Int): String {
        val sep = if (path.contains('?')) "&" else "?"
        return "https://api.themoviedb.org/3/$path${sep}api_key=$TMDB_KEY&language=en-US&page=$page"
    }

    private fun posterUrl(path: String?): String? =
        path?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w500$it" }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = runCatching { fetchList(tmdbUrl(request.data, page), request.data) }
            .getOrDefault(emptyList())
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        if (q.isBlank()) return emptyList()
        val url = "https://api.themoviedb.org/3/search/multi?api_key=$TMDB_KEY&language=en-US&page=1&query=$q"
        return runCatching { fetchList(url, "") }.getOrDefault(emptyList())
    }

    /** Reads a TMDB list response. [path] tells movie from tv when the payload does not. */
    private suspend fun fetchList(url: String, path: String): List<SearchResponse> {
        val body = app.get(url, timeout = 25L).text
        val results = JSONObject(body).optJSONArray("results") ?: return emptyList()
        val out = ArrayList<SearchResponse>(results.length())

        for (i in 0 until results.length()) {
            val o = results.optJSONObject(i) ?: continue
            val media = o.optString("media_type").ifBlank {
                if (path.startsWith("tv")) "tv" else "movie"
            }
            if (media == "person") continue

            val id = o.optInt("id", 0)
            if (id == 0) continue

            if (media == "tv") {
                val title = o.optString("name").ifBlank { o.optString("original_name") }
                if (title.isBlank()) continue
                out.add(
                    newTvSeriesSearchResponse(title, fixUrl("/tv/$id"), TvType.TvSeries) {
                        this.posterUrl = posterUrl(o.optString("poster_path"))
                    }
                )
            } else {
                val title = o.optString("title").ifBlank { o.optString("original_title") }
                if (title.isBlank()) continue
                out.add(
                    newMovieSearchResponse(title, fixUrl("/movie/$id"), TvType.Movie) {
                        this.posterUrl = posterUrl(o.optString("poster_path"))
                    }
                )
            }
        }
        return out
    }

    // ── Detail ─────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val type = if (url.contains("/tv/")) "tv" else "movie"
        val id = url.substringAfterLast('/').substringBefore('?').filter { it.isDigit() }
        if (id.isBlank()) return null

        val body = app.get(
            "https://api.themoviedb.org/3/$type/$id?api_key=$TMDB_KEY&language=en-US" +
                "&append_to_response=credits",
            timeout = 25L
        ).text
        val o = JSONObject(body)

        val title = o.optString("title").ifBlank { o.optString("name") }
            .ifBlank { o.optString("original_title") }.ifBlank { "Unknown" }
        val originalLanguage = o.optString("original_language").ifBlank { "en" }
        val year = (o.optString("release_date").ifBlank { o.optString("first_air_date") })
            .take(4).ifBlank { null }
        val poster = posterUrl(o.optString("poster_path"))
        val plot = o.optString("overview").ifBlank { null }

        val tags = ArrayList<String>()
        o.optJSONArray("genres")?.let { g ->
            for (i in 0 until g.length()) {
                g.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let { tags.add(it) }
            }
        }
        o.optJSONObject("credits")?.optJSONArray("cast")?.let { cast ->
            for (i in 0 until minOf(cast.length(), 6)) {
                cast.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let { tags.add(it) }
            }
        }

        if (type == "movie") {
            return newMovieLoadResponse(title, url, TvType.Movie, "$type|$id|$originalLanguage") {
                this.posterUrl = poster
                this.plot = plot
                this.year = year?.toIntOrNull()
                this.tags = tags
            }
        }

        // TV: episodes come from the season endpoints, so the stream API can be asked for a
        // specific season and episode.
        val episodes = ArrayList<Episode>()
        val seasons = o.optJSONArray("seasons")
        var fetched = 0
        if (seasons != null) {
            for (i in 0 until seasons.length()) {
                val season = seasons.optJSONObject(i) ?: continue
                val number = season.optInt("season_number", 0)
                val count = season.optInt("episode_count", 0)
                if (count <= 0 || number <= 0) continue
                if (fetched >= 10) break

                val seasonBody = runCatching {
                    app.get(
                        "https://api.themoviedb.org/3/tv/$id/season/$number?api_key=$TMDB_KEY&language=en-US",
                        timeout = 25L
                    ).text
                }.getOrNull() ?: continue
                fetched++

                val list = runCatching {
                    JSONObject(seasonBody).optJSONArray("episodes")
                }.getOrNull() ?: continue

                for (e in 0 until list.length()) {
                    val ep = list.optJSONObject(e) ?: continue
                    val epNumber = ep.optInt("episode_number", 0)
                    if (epNumber <= 0) continue
                    episodes.add(
                        newEpisode("$type|$id|$originalLanguage|$number|$epNumber") {
                            this.name = ep.optString("name").ifBlank { "Episode $epNumber" }
                            this.season = number
                            this.episode = epNumber
                            this.posterUrl = posterUrl(ep.optString("still_path"))
                            this.description = ep.optString("overview").ifBlank { null }
                            this.rating = ep.optDouble("vote_average", 0.0).takeIf { it > 0 }
                        }
                    )
                }
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year?.toIntOrNull()
            this.tags = tags
        }
    }

    // ── Streams ────────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split('|')
        if (parts.size < 2) return false
        val type = parts[0]
        val id = parts[1]
        val originalLanguage = parts.getOrElse(2) { "en" }.ifBlank { "en" }

        val query = buildList {
            add("tmdbId=$id")
            add("type=$type")
            add("lang=en")
            add("fast=1")
            add("originalLanguage=$originalLanguage")
            if (type == "tv") {
                add("season=${parts.getOrNull(3) ?: "1"}")
                add("episode=${parts.getOrNull(4) ?: "1"}")
            }
        }.joinToString("&")

        val headers = mapOf(
            "Accept" to "application/json",
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/",
            "x-client-app" to "movienerds",
            "x-app-key" to STREAM_KEY
        )

        val body = runCatching {
            app.get("$API/api/stream?$query", headers = headers, timeout = 40L).text
        }.getOrNull() ?: return false

        val root = runCatching { JSONObject(body) }.getOrNull() ?: return false

        var found = false
        val sources = root.optJSONArray("sources")
        val list = ArrayList<JSONObject>()
        if (sources != null) {
            for (i in 0 until sources.length()) sources.optJSONObject(i)?.let { list.add(it) }
        }
        if (list.isEmpty()) root.optJSONObject("activeSource")?.let { list.add(it) }

        for (s in list) {
            val url = s.optString("url").ifBlank { s.optString("rawUrl") }
            if (!url.startsWith("http")) continue

            val label = s.optString("displayName")
                .ifBlank { s.optString("name") }
                .ifBlank { s.optString("providerName") }
            val quality = s.optString("quality")
            val language = s.optString("language")

            val linkHeaders = HashMap<String, String>()
            s.optJSONObject("headers")?.let { h ->
                for (key in h.keys()) {
                    val value = h.optString(key)
                    if (key.isNotBlank() && value.isNotBlank()) linkHeaders[key] = value
                }
            }
            val referer = linkHeaders["Referer"] ?: "$mainUrl/"

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = buildString {
                        append(name)
                        if (label.isNotBlank()) append(" - ").append(label)
                        if (quality.isNotBlank()) append(" [").append(quality).append("]")
                        if (language.isNotBlank()) append(" (").append(language).append(")")
                    },
                    url = url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = referer
                    this.quality = getQualityFromName(quality)
                    this.headers = linkHeaders
                }
            )
            found = true
        }

        root.optJSONArray("subtitles")?.let { subs ->
            for (i in 0 until subs.length()) {
                val sub = subs.optJSONObject(i) ?: continue
                val subUrl = sub.optString("url")
                if (subUrl.isBlank()) continue
                val absolute = if (subUrl.startsWith("http")) subUrl else "$API$subUrl"
                subtitleCallback.invoke(
                    newSubtitleFile(
                        sub.optString("label").ifBlank { sub.optString("language").ifBlank { "Subtitle" } },
                        absolute
                    )
                )
            }
        }

        return found
    }

    private companion object {
        /**
         * The site's own TMDB key, embedded in its public web client - the whole catalogue is
         * built from these calls. Not a secret of yours, but worth knowing it is third party:
         * if TMDB ever rotates it, catalogue and search stop while playback still works.
         */
        const val TMDB_KEY = "43dcff37851866de14d8acdce668a509"

        /** Sent by the site on every stream request; the API rejects calls without it. */
        const val STREAM_KEY = "mn_sec_2026_stream_auth"

        const val API = "https://api.movienerds.online"
    }
}
