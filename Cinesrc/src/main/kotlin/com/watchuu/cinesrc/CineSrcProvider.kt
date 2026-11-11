package com.watchuu.cinesrc

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import org.json.JSONObject

/**
 * CineSrc (cinesrc.st) — built from the capture of 2026-11-11 11:39.
 *
 * The site is not a catalogue: its front page is a box that takes a TMDB id and an embed url of
 * the shape /embed/movie/<id> or /embed/tv/<id>?s=<season>&e=<episode>. What it adds is servers -
 * the capture lists thirteen of them by name:
 *
 *   nebula ⭐, light, wave, orbit, storm, aurora, flux, river, surge, mist, tempest, thunder,
 *   paris (FR&ENG), brisa (ES/LAT)
 *
 * So this extension is search-first: find a title, open it, and get whatever servers answer.
 *
 *   search    /api/tmdb/search?q=<query>        -> {results:[{id, media_type, title, …}]}
 *   movie     /api/tmdb/movie/<id>
 *   series    /api/tmdb/tv/<id>  +  /api/tmdb/tv/<id>/season/<n>   (episode lists)
 *
 * Those answered in the capture with only the DDoS-Guard cookies - no proof of work - which is why
 * they can be called directly here.
 *
 * The servers themselves cannot be. Playback is protected by a wasm proof of work (/api/c endpoints, with
 * the solution in x-cs-* headers), Next.js server-action hashes that change on every deploy, and a
 * per-server encrypted token. Rather than reimplement that and break on their next release, the
 * embed page is opened in a WebView, which solves all of it itself, and the /api/playlist/ requests
 * it makes are collected - one per server it is driven to try. Each of those is offered as a link,
 * labelled with the server it came from where the page tells us, and by order otherwise.
 */
class CineSrcProvider : MainAPI() {
    override var mainUrl = "https://cinesrc.st"
    override var name = "CineSrc"
    override var lang = "en"
    override val hasMainPage = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val tmdbImage = "https://image.tmdb.org/t/p/w500"

    private fun userAgent(): String = UA

    private suspend fun json(path: String): JSONObject? = runCatching {
        JSONObject(
            app.get(
                "$mainUrl$path",
                headers = mapOf(
                    "User-Agent" to userAgent(),
                    "Accept" to "application/json",
                    "Referer" to "$mainUrl/"
                ),
                timeout = 30L
            ).text
        )
    }.getOrNull()

    // ── Search ─────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        if (q.isBlank()) return emptyList()

        val json = json("/api/tmdb/search?q=$q") ?: return emptyList()
        val arr = json.optJSONArray("results") ?: return emptyList()

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
            val poster = o.optString("poster_path").ifBlank { null }?.let { tmdbImage + it }

            val url = "$mainUrl/detail/$mediaType/$id"
            out.add(
                if (mediaType == "tv") {
                    newTvSeriesSearchResponse(title, url, TvType.TvSeries) { this.posterUrl = poster }
                } else {
                    newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = poster }
                }
            )
        }
        return out.distinctBy { it.url }
    }

    // ── Detail ─────────────────────────────────────────────────────────────────

    /** "/detail/<mediaType>/<id>" - the media type decides which tmdb proxy route to read */
    private fun parts(url: String): Pair<String, Int>? {
        val m = Regex("""/detail/(movie|tv)/(\d+)""").find(url) ?: return null
        return m.groupValues[1] to (m.groupValues[2].toIntOrNull() ?: return null)
    }

    override suspend fun load(url: String): LoadResponse? {
        val (mediaType, id) = parts(url) ?: return null

        val json = json("/api/tmdb/$mediaType/$id") ?: return null
        // the proxy may hand back tmdb's object directly or wrapped
        val o = json.optJSONObject("data") ?: json

        val title = o.optString("title").ifBlank { o.optString("name") }.ifBlank { "TMDB $id" }
        val posterPath = o.optString("poster_path").ifBlank { null }
        val poster = posterPath?.let { tmdbImage + it }
        val plot = o.optString("overview").ifBlank { null }
        val year = (o.optString("release_date").ifBlank { o.optString("first_air_date") }).take(4)

        val tags = ArrayList<String>()
        year.ifBlank { null }?.let { tags.add(it) }
        o.optJSONArray("genres")?.let { g ->
            for (i in 0 until g.length()) {
                g.optJSONObject(i)?.optString("name")?.ifBlank { null }?.let { tags.add(it) }
            }
        }

        if (mediaType == "tv") {
            val episodes = ArrayList<Episode>()
            val seasons = o.optInt("number_of_seasons", 1).coerceIn(1, 50)

            for (season in 1..seasons) {
                val sJson = json("/api/tmdb/tv/$id/season/$season") ?: continue
                val sObj = sJson.optJSONObject("data") ?: sJson
                val eps = sObj.optJSONArray("episodes") ?: continue

                for (i in 0 until eps.length()) {
                    val e = eps.optJSONObject(i) ?: continue
                    val number = e.optInt("episode_number", i + 1)
                    val epName = e.optString("name").ifBlank { "Episode $number" }
                    episodes.add(
                        newEpisode("$mediaType|$id|$season|$number") {
                            this.name = epName
                            this.season = season
                            this.episode = number
                            this.posterUrl = e.optString("still_path").ifBlank { null }
                                ?.let { tmdbImage + it } ?: poster
                        }
                    )
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
            // the playlist url is an m3u8 endpoint; the page's own player fetches it with the
            // referer that earned its cookies, so that is passed on
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
