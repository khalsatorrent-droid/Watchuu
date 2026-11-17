package com.watchuu.tadkax

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject

/**
 * TadkaX - desi videos, OTT films and web series.
 *
 * Card urls are just /watch/<id>; the detail call returns everything the card needs, so nothing is
 * carried in the url beyond the id.
 */
class TadkaXProvider : MainAPI() {
    override var mainUrl = TadkaXApi.SITE
    override var name = "TadkaX"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW, TvType.Others)

    // ------------------------------------------------------------------ helpers

    private fun JSONObject.str(vararg keys: String): String? {
        for (k in keys) {
            val v = optString(k, "")
            if (v.isNotBlank() && v != "null") return v
        }
        return null
    }

    private fun JSONArray?.objs(): List<JSONObject> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optJSONObject(it) }
    }

    /** "27:25" or "1:02:11" -> minutes */
    private fun minutes(duration: String?): Int? {
        val parts = duration?.split(":")?.mapNotNull { it.trim().toIntOrNull() } ?: return null
        return when (parts.size) {
            3 -> parts[0] * 60 + parts[1] + if (parts[2] > 30) 1 else 0
            2 -> parts[0] + if (parts[1] > 30) 1 else 0
            1 -> parts[0]
            else -> null
        }
    }

    private fun card(o: JSONObject): SearchResponse? {
        val id = o.optInt("id", 0).takeIf { it > 0 } ?: return null
        val title = o.str("title") ?: return null
        return newMovieSearchResponse(title, "$mainUrl/watch/$id", TvType.NSFW) {
            this.posterUrl = o.str("poster_url")
        }
    }

    // ------------------------------------------------------------------ home

    override val mainPage = mainPageOf(
        "featured" to "Featured",
        "latest" to "Latest",
        "recommended" to "Recommended",
        "daily_top" to "Daily top",
        "cat:Indian" to "Indian",
        "cat:Desi" to "Desi",
        "cat:Homemade" to "Homemade",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), false)

        val items = runCatching {
            when {
                request.data == "featured" || request.data == "recommended" -> {
                    val root = TadkaXApi.get("home", mapOf("latest_limit" to "1"))
                        ?: return@runCatching emptyList<SearchResponse>()
                    root.optJSONObject("data")?.optJSONArray(request.data).objs().mapNotNull { card(it) }
                }
                request.data == "latest" -> TadkaXApi.list("latest", mapOf("page" to "1", "limit" to "30"))
                    .first.mapNotNull { card(it) }
                request.data == "daily_top" -> TadkaXApi.list("daily_top", mapOf("limit" to "30"))
                    .first.mapNotNull { card(it) }
                request.data.startsWith("cat:") -> TadkaXApi.list(
                    "by_category",
                    mapOf("category" to request.data.removePrefix("cat:"), "page" to "1", "limit" to "30")
                ).first.mapNotNull { card(it) }
                else -> emptyList()
            }
        }.getOrElse { emptyList() }

        return newHomePageResponse(HomePageList(request.name, items), false)
    }

    // ------------------------------------------------------------------ search

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val (items, _) = TadkaXApi.list("search", mapOf("q" to query.trim(), "page" to "1", "limit" to "50"))
        return items.mapNotNull { card(it) }
    }

    // ------------------------------------------------------------------ load

    override suspend fun load(url: String): LoadResponse? {
        val id = Regex("/watch/(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val root = TadkaXApi.get("post", mapOf("id" to id.toString())) ?: return null
        val v = root.optJSONObject("data") ?: return null

        val title = v.str("title") ?: return null
        val poster = v.str("poster_url")
        val plot = buildString {
            v.str("description")?.let { append(it).append("\n\n") }
            val bits = listOfNotNull(
                v.str("actress")?.let { "Actress: $it" },
                v.str("actor")?.let { "Actor: $it" },
                v.str("studio")?.let { "Studio: $it" },
                v.str("ott")?.let { "OTT: $it" },
                v.str("duration")?.let { "Duration: $it" },
                v.str("created_at")?.take(10)?.let { "Added: $it" },
            )
            if (bits.isNotEmpty()) append(bits.joinToString(" · "))
        }.trim()

        return newMovieLoadResponse(title, url, TvType.NSFW, "$mainUrl/watch/$id") {
            this.posterUrl = poster
            this.plot = plot.ifBlank { null }
            this.year = v.str("created_at")?.take(4)?.toIntOrNull()
            this.duration = minutes(v.str("duration"))
            this.tags = (v.str("tags") ?: v.str("category"))
                ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.take(12)
        }
    }

    // ------------------------------------------------------------------ playback

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = Regex("/watch/(\\d+)").find(data)?.groupValues?.get(1)?.toIntOrNull() ?: return false
        val root = TadkaXApi.get("post", mapOf("id" to id.toString())) ?: return false
        val v = root.optJSONObject("data") ?: return false

        // every url is already signed, so they are played as they come; highest first
        val sources = listOf(
            Triple(v.str("video_url_1080p"), "1080p", Qualities.P1080.value),
            Triple(v.str("video_url_720p"), "720p", Qualities.P720.value),
            Triple(v.str("video_url_480p"), "480p", Qualities.P480.value),
            Triple(v.str("video_url"), "original", Qualities.Unknown.value),
        )

        var added = false
        val seen = HashSet<String>()
        for ((url, label, quality) in sources) {
            if (url.isNullOrBlank() || !url.startsWith("http") || !seen.add(url)) continue
            callback.invoke(
                newExtractorLink(name, "$name $label", url, ExtractorLinkType.VIDEO) {
                    this.referer = "${TadkaXApi.SITE}/"
                    this.quality = quality
                }
            )
            added = true
        }
        return added
    }
}
