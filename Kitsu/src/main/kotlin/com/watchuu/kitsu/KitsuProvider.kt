package com.watchuu.kitsu

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import org.json.JSONObject

/**
 * Kitsu — anime catalogue from kitsu.io, built as a CloudStream extension.
 *
 * Kitsu is metadata only: it knows titles, episodes, posters and ratings, and nothing about where
 * a file lives. So this is a catalogue in front of an embed resolver, and it says so rather than
 * pretending otherwise.
 *
 *   browse    https://kitsu.io/api/edge/anime?sort=-userCount&page[limit]=20&page[offset]=0
 *   search    …?filter[text]=<query>
 *   detail    …/anime/<id>            attributes: canonicalTitle, posterImage, synopsis,
 *                                     episodeCount, status, startDate, averageRating
 *   episodes  …/anime/<id>/episodes
 *   mapping   …/anime/<id>/mappings   -> externalSite "themoviedb" / "imdb" ids
 *
 * The mapping is what makes playback possible: a TMDB or IMDb id is something the embed resolvers
 * understand, so links are built from it and handed to Cloudstream's extractors. A title with no
 * mapping, or one the resolver does not carry, yields no links - which is honest, and is the same
 * trade FlixVision makes.
 *
 * Kitsu wants the json:api media type; a plain Accept: application/json is refused.
 */
class KitsuProvider : MainAPI() {
    override var mainUrl = "https://kitsu.io/api/edge"
    override var name = "Kitsu"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.Movie)

    override val mainPage = mainPageOf(
        "sort=-userCount" to "Trending",
        "sort=-averageRating&filter[averageRatingGreaterThan]=70" to "Top Rated",
        "filter[status]=current&sort=-userCount" to "Currently Airing",
        "sort=-startDate&filter[status]=finished" to "Recently Finished"
    )

    private fun headers(): Map<String, String> = mapOf(
        "Accept" to "application/vnd.api+json",
        "Content-Type" to "application/vnd.api+json",
        "User-Agent" to UA
    )

    private suspend fun api(path: String): JSONObject? = runCatching {
        JSONObject(app.get("$mainUrl/$path", headers = headers(), timeout = 30L).text)
    }.getOrNull()

    // ── Listings ───────────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val offset = (page - 1) * 20
        val json = api("anime?${request.data}&page[limit]=20&page[offset]=$offset")
            ?: return newHomePageResponse(request.name, emptyList(), false)

        val items = json.optJSONArray("data")?.let { toSearchResults(it) }.orEmpty()
        val total = json.optJSONObject("meta")?.optInt("count", 0) ?: 0
        return newHomePageResponse(request.name, items, hasNext = offset + 20 < total || items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        if (q.isBlank()) return emptyList()

        val out = ArrayList<SearchResponse>()
        for (offset in listOf(0, 20)) {
            val json = api("anime?filter[text]=$q&page[limit]=20&page[offset]=$offset") ?: continue
            json.optJSONArray("data")?.let { out.addAll(toSearchResults(it)) }
        }
        return out.distinctBy { it.url }
    }

    private fun toSearchResults(arr: org.json.JSONArray): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").ifBlank { continue }
            val a = o.optJSONObject("attributes") ?: continue

            val title = a.optString("canonicalTitle").ifBlank {
                a.optJSONObject("titles")?.optString("en")?.ifBlank { null } ?: "Anime $id"
            }
            val poster = a.optJSONObject("posterImage")
                ?.optString("medium")?.ifBlank { null }
                ?: a.optJSONObject("posterImage")?.optString("large")?.ifBlank { null }
            val episodes = a.optString("episodeCount").toIntOrNull() ?: 0
            val subtype = a.optString("subtype")

            val realUrl = "https://kitsu.io/anime/$id"

            val response = if (episodes > 1 || subtype == "TV") {
                newTvSeriesSearchResponse(title, realUrl, TvType.Anime) { this.posterUrl = poster }
            } else {
                newMovieSearchResponse(title, realUrl, TvType.AnimeMovie) { this.posterUrl = poster }
            }
            out.add(response)
        }
        return out
    }

    // ── Detail ─────────────────────────────────────────────────────────────────

    private fun idFrom(url: String): String? =
        Regex("""/anime/(\d+)""").find(url)?.groupValues?.get(1)

    override suspend fun load(url: String): LoadResponse? {
        val id = idFrom(url) ?: return null
        val json = api("anime/$id") ?: return null
        val a = json.optJSONObject("data")?.optJSONObject("attributes") ?: return null

        val title = a.optString("canonicalTitle").ifBlank { "Anime $id" }
        val poster = a.optJSONObject("posterImage")?.optString("large")?.ifBlank { null }
            ?: a.optJSONObject("posterImage")?.optString("medium")?.ifBlank { null }
        val plot = a.optString("synopsis")
            .replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().ifBlank { null }
        val episodeCount = a.optString("episodeCount").toIntOrNull() ?: 0
        val status = a.optString("status").replace("_", " ")
        val rating = a.optString("averageRating").toDoubleOrNull()
        val year = a.optString("startDate").take(4).ifBlank { null }

        val tags = ArrayList<String>()
        a.optString("subtype").ifBlank { null }?.let { tags.add(it) }
        status.ifBlank { null }?.let { tags.add(it) }
        year?.let { tags.add(it) }
        rating?.let { tags.add("%.1f/100".format(it)) }

        if (episodeCount > 1) {
            val episodes = ArrayList<Episode>()
            for (n in 1..episodeCount) {
                episodes.add(
                    newEpisode("$id|$n") {
                        this.name = "Episode $n"
                        this.season = 1
                        this.episode = n
                        this.posterUrl = poster
                    }
                )
            }
            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.tags = tags
                }
            }
        }

        return newMovieLoadResponse(title, url, TvType.AnimeMovie, "$id|1") {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
        }
    }

    // ── Streams ────────────────────────────────────────────────────────────────

    /**
     * Kitsu has no files, so an id the resolvers understand is looked up first: TMDB preferred,
     * IMDb as a fallback. The embed urls are then handed to Cloudstream's extractors, which is the
     * only part of this that can produce something playable.
     */
    private suspend fun mappingFor(id: String): Pair<String, String>? {
        val json = api("anime/$id/mappings") ?: return null
        val arr = json.optJSONArray("data") ?: return null

        var tmdb: String? = null
        var imdb: String? = null
        for (i in 0 until arr.length()) {
            val attr = arr.optJSONObject(i)?.optJSONObject("attributes") ?: continue
            val site = attr.optString("externalSite")
            val ext = attr.optString("externalId").ifBlank { continue }
            when (site) {
                "themoviedb" -> if (tmdb == null) tmdb = ext
                "imdb" -> if (imdb == null) imdb = ext
            }
        }
        return when {
            tmdb != null -> "tmdb" to tmdb
            imdb != null -> "imdb" to imdb
            else -> null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = data.substringBefore('|')
        val episode = data.substringAfter('|', "1").toIntOrNull() ?: 1
        if (id.isBlank()) return false

        val mapping = mappingFor(id) ?: return false
        val (kind, value) = mapping

        val embeds = ArrayList<String>()
        if (kind == "tmdb") {
            embeds.add("https://vidsrc.to/embed/movie/$value")
            embeds.add("https://vidsrc.to/embed/tv/$value/1/$episode")
            embeds.add("https://vidsrc.xyz/embed/tv?tmdb=$value&season=1&episode=$episode")
            embeds.add("https://vidsrc.xyz/embed/movie?tmdb=$value")
        } else {
            embeds.add("https://vidsrc.xyz/embed/tv?imdb=$value&season=1&episode=$episode")
            embeds.add("https://vidsrc.xyz/embed/movie?imdb=$value")
        }

        var found = false
        for (embed in embeds) {
            val got = mutableListOf<ExtractorLink>()
            runCatching {
                loadExtractor(embed, "https://kitsu.io/", subtitleCallback) { got.add(it) }
            }
            got.forEach { link ->
                callback.invoke(
                    newExtractorLink(
                        source = link.source ?: name,
                        name = link.name.ifBlank { name },
                        url = link.url,
                        type = link.type
                    ) {
                        this.referer = link.referer.ifBlank { "https://kitsu.io/" }
                        this.quality = link.quality
                        this.headers = link.headers
                    }
                )
                found = true
            }
        }
        return found
    }

    private companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 16; SM-S911B Build/BP4A.251205.006) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.47 Mobile Safari/537.36"
    }
}
