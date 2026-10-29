package com.watchuu.desihub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import org.jsoup.nodes.Element

/**
 * desihub.sh - Indian adult content site (Next.js based).
 *
 * Built from a captured network log (CS3 Extension Builder):
 *  - listing cards    : a.transform.overflow-hidden -> title h3, poster img[src*="images.downloaddirect"]
 *  - home             : / (latest content)
 *  - explore pages    : /explore/{page} with pagination
 *  - feed             : /feed (main content feed)
 *  - search           : GET /x?q=<query>
 *  - detail           : /post/{slug} -> title h1/h2, poster og:image
 *  - streams          : direct MP4 from video.downloaddirect.xyz/uuid.mp4 (Referer: downloaddirect.xyz)
 *  - images           : WebP posters from images.downloaddirect.xyz/timestamp-id.webp
 */
class DesiHubProvider : MainAPI() {
    override var mainUrl = "https://desihub.sh"
    override var name = "Desihub"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val imageHost = "https://images.downloaddirect.xyz"
    private val videoHost = "https://video.downloaddirect.xyz"
    private val referer = "https://downloaddirect.xyz/"

    // Home sections with path and name
    override val mainPage = mainPageOf(
        "" to "Latest",
        "explore/1" to "Explore Page 1",
        "feed" to "Feed",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.trim('/')
        val url = when {
            path.isEmpty() -> "$mainUrl/"
            path.contains("explore") -> "$mainUrl/explore/${page}"
            else -> "$mainUrl/$path"
        }

        val doc = app.get(url).document
        val items = doc.select("a.transform.overflow-hidden")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        // Check for next page by looking for pagination indicators
        val hasNext = items.isNotEmpty() && page < 50 // reasonable pagination limit

        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$mainUrl/x?q=$q"
        val doc = app.get(url).document

        return doc.select("a.transform.overflow-hidden")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        // Extract title
        val title = (doc.selectFirst("h1")?.text()
            ?: doc.selectFirst("h2")?.text()
            ?: doc.selectFirst("meta[name=og:title]")?.attr("content")
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.selectFirst("title")?.text()
            ?: "Video").trim()

        // Extract poster/cover image
        val poster = doc.selectFirst("img[src*=\"$imageHost\"]")?.attr("src")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("meta[name=og:image]")?.attr("content")

        // Extract description/plot
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst("meta[name=description]")?.attr("content")

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data, referer = mainUrl).text

        // Extract all video streams from the HTML
        val videoRegex = Regex("""https?://video\.downloaddirect\.xyz/([a-f0-9\-]+\.mp4)""", RegexOption.IGNORE_CASE)
        val videoMatches = videoRegex.findAll(html)

        var foundStreams = false

        videoMatches.forEach { match ->
            val fullUrl = match.value
            val filename = match.groupValues[1]

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = fullUrl,
                    referer = referer,
                    quality = 720, // Default quality - adjust based on available info
                    type = ExtractorLinkType.VIDEO
                )
            )
            foundStreams = true
        }

        return foundStreams
    }

    /**
     * Convert a listing card element to a SearchResponse
     */
    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("href")
            .takeIf { it.startsWith("/post/") || it.contains("/post/") }
            ?: return null

        val url = fixUrl(href)

        // Extract title from h3 tag
        val title = selectFirst("h3")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: href.substringAfterLast("/").replace("-", " ").trim()
                .takeIf { it.isNotBlank() }
            ?: return null

        // Extract poster image - prefer images.downloaddirect.xyz
        val posterUrl = selectFirst("img[src*=\"$imageHost\"]")?.attr("src")
            ?: selectFirst("img[src*=\"images.downloaddirect\"]")?.attr("src")
            ?: selectFirst("img")?.attr("src")
            ?: selectFirst("img")?.attr("data-src")

        return newMovieSearchResponse(
            title = title,
            url = url,
            type = TvType.NSFW
        ) {
            this.posterUrl = posterUrl
        }
    }
}
