package com.watchuu.cinesrc

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import org.json.JSONObject

/**
 * CineSrc — a full TMDB frontend on top of the site's servers.
 *
 * The site itself is only an embed api: it takes a TMDB id and plays it from one of thirteen
 * servers. So the catalogue comes from TMDB directly, and every id it hands out is exactly what
 * CineSrc wants - the two fit together with nothing in between.
 *
 *   browse   /trending/all/day, /movie/popular, /tv/popular, /movie/now_playing,
 *            /tv/on_the_air, top rated, and language/network discover rows
 *   search   /search/multi?query=<q>
 *   detail   /movie/<id> or /tv/<id>, plus /tv/<id>/season/<n> for real episode lists
 *   play     https://cinesrc.st/embed/movie/<id>  or  /embed/tv/<id>?s=&e=
 *
 * Playback is the part CineSrc protects: a wasm proof of work, Next.js server-action hashes that
 * change per deploy, and a per-server encrypted token. None of that is reimplemented here - the
 * embed page is opened in a WebView, which solves it itself, and the /api/playlist/ requests it
 * makes are collected, one per server it is driven to try.
 *
 * The TMDB key is the one FlixVision already carries in this repo.
 */
class CineSrcProvider : MainAPI() {
    override var mainUrl = "https://cinesrc.st"
    override var name = "CineSrc"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbKey = "2f3cb5763db1117fcba3948632f8aad9"
    private val tmdbImg = "https://image.tmdb.org/t/p/w500"

    override val mainPage = mainPageOf(
        "/trending/all/day" to "Trending Today",
        "/trending/all/week" to "Trending This Week",
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
        "/discover/tv?with_networks=2739" to "Disney+"
    )

    private fun userAgent(): String = UA

    // ── TMDB ───────────────────────────────────────────────────────────────────

    private suspend fun tmdb(path: String): JSONObject? {
        val sep = if (path.contains('?')) "&" else "?"
        val url = "$tmdbApi$path${sep}api_key=$tmdbKey&language=en-US"
        return runCatching {
            JSONObject(app.get(url, headers = mapOf("User-Agent" to userAgent()), timeout = 30L).text)
        }.getOrNull()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sep = if (request.data.contains('?')) "&" else "?"
        val json = tmdb("${request.data}${sep}page=$page") ?: return newHomePageResponse(
            request.name, emptyList(), false
        )

        val items = resultsToCards(json.optJSONArray("results"))
        val totalPages = json.optInt("total_pages", 1)
        return newHomePageResponse(request.name, items, hasNext = page < totalPages)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        if (q.isBlank()) return emptyList()

        val out = ArrayList<SearchResponse>()
        for (page in 1..2) {
            val json = tmdb("/search/multi?query=$q&page=$page") ?: continue
            out.addAll(resultsToCards(json.optJSONArray("results")))
        }
        return out.distinctBy { it.url }
    }

    private fun resultsToCards(arr: org.json.JSONArray?): List<SearchResponse> {
        if (arr == null) return emptyList()

        val out = ArrayList<SearchResponse>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optInt("id", 0)
            if (id == 0) continue

            val mediaType = o.optString("media_type").ifBlank {
                if (o.has("first_air_date") || o.has("name")) "tv" else "movie"
            }
            if (mediaType == "person") continue

            val title = o.optString("title").ifBlank { o.optString("name") }.ifBlank { continue }
            val poster = o.optString("poster_path").ifBlank { null }?.let { tmdbImg + it }
            val year = (o.optString("release_date").ifBlank { o.optString("first_air_date") }).take(4)
            val url = "$mainUrl/detail/$mediaType/$id"

            val card = if (mediaType == "tv") {
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) { this.posterUrl = poster }
            } else {
                newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = poster }
            }
            out.add(card)
        }
        return out.distinctBy { it.url }
    }

    // ── Detail ─────────────────────────────────────────────────────────────────

    private fun parts(url: String): Pair<String, Int>? {
        val m = Regex("""/detail/(movie|tv)/(\d+)""").find(url) ?: return null
        return m.groupValues[1] to (m.groupValues[2].toIntOrNull() ?: return null)
    }

    override suspend fun load(url: String): LoadResponse? {
        val (mediaType, id) = parts(url) ?: return null
        val o = tmdb("/$mediaType/$id") ?: return null

        val title = o.optString("title").ifBlank { o.optString("name") }.ifBlank { "TMDB $id" }
        val poster = o.optString("poster_path").ifBlank { null }?.let { tmdbImg + it }
        val plot = o.optString("overview").ifBlank { null }
        val year = (o.optString("release_date").ifBlank { o.optString("first_air_date") }).take(4)

        val tags = ArrayList<String>()
        year.ifBlank { null }?.let { tags.add(it) }
        o.optString("tagline").ifBlank { null }?.let { tags.add(it) }
        o.optJSONArray("genres")?.let { g ->
            for (i in 0 until g.length()) {
                g.optJSONObject(i)?.optString("name")?.ifBlank { null }?.let { tags.add(it) }
            }
        }
        o.optDouble("vote_average", 0.0).takeIf { it > 0 }
            ?.let { tags.add("%.1f/10".format(it)) }

        if (mediaType == "tv") {
            val episodes = ArrayList<Episode>()
            val seasons = o.optJSONArray("seasons")

            if (seasons != null) {
                for (i in 0 until seasons.length()) {
                    val s = seasons.optJSONObject(i) ?: continue
                    val seasonNumber = s.optInt("season_number", 0)
                    if (seasonNumber <= 0) continue // specials

                    val sJson = tmdb("/tv/$id/season/$seasonNumber") ?: continue
                    val eps = sJson.optJSONArray("episodes") ?: continue

                    for (j in 0 until eps.length()) {
                        val e = eps.optJSONObject(j) ?: continue
                        val number = e.optInt("episode_number", j + 1)
                        val epName = e.optString("name").ifBlank { "Episode $number" }
                        val still = e.optString("still_path").ifBlank { null }

                        episodes.add(
                            newEpisode("$mediaType|$id|$seasonNumber|$number") {
                                this.name = epName
                                this.season = seasonNumber
                                this.episode = number
                                this.posterUrl = still?.let { tmdbImg + it } ?: poster
                            }
                        )
                    }
                }
            }

            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.tags = tags
                }
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, "$mediaType|$id|0|0") {
            this.posterUrl = poster
            this.plot = plot
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
        val p = data.split('|')
        val mediaType = p.getOrNull(0).orEmpty()
        val id = p.getOrNull(1).orEmpty()
        val season = p.getOrNull(2)?.toIntOrNull() ?: 0
        val episode = p.getOrNull(3)?.toIntOrNull() ?: 0
        if (mediaType.isBlank() || id.isBlank()) return false

        val embed = if (mediaType == "tv") {
            "$mainUrl/embed/tv/$id?s=$season&e=$episode"
        } else {
            "$mainUrl/embed/movie/$id"
        }

        val playlists = CineSrcBrowser.playlists(embed)
        if (playlists.isEmpty()) return false

        var found = false
        playlists.forEachIndexed { index, link ->
            val type = if (link.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name · Server ${index + 1}",
                    url = link,
                    type = type
                ) {
                    this.referer = embed
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "Referer" to embed,
                        "Origin" to mainUrl,
                        "User-Agent" to userAgent(),
                        "Accept" to "*/*"
                    )
                }
            )
            found = true
        }
        return found
    }

    private companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 16; SM-S911B Build/BP4A.251205.006) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.47 Mobile Safari/537.36"
    }
}
