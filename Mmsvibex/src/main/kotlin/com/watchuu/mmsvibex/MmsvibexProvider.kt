package com.watchuu.mmsvibex

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class MmsvibexProvider : MainAPI() {
    override var mainUrl = "https://mmsvibex.life"
    override var name = "Mmsvibex"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    // Each row is a WordPress listing that pages with /page/N/. "" is the site root (latest videos).
    override val mainPage = mainPageOf(
        "" to "Latest Videos",
        "category/indian/" to "Indian",
        "category/desi/" to "Desi",
        "category/others/" to "Others",
        "photos/" to "Photos",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // page 1 is the bare path; later pages are /<path>/page/N/
        val base = mainUrl.trimEnd('/') + "/" + request.data.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        val url = if (page > 1) "${base}page/$page/" else base

        val document = app.get(url).document
        val videos = document.select("article.thumb-block.video-preview-item").mapNotNull { it.toSearchResult() }

        // keep loading while the page advertises a next page and still returns items
        val hasNext = videos.isNotEmpty() &&
            document.selectFirst("link[rel=next], a.next.page-numbers, .pagination a[href*=/page/]") != null

        return newHomePageResponse(request.name, videos, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Scrape paginated HTML search — site uses /?s=query&paged=N
        val results = mutableListOf<SearchResponse>()
        var page = 1
        while (true) {
            val document = app.get(
                mainUrl,
                params = mapOf("s" to query, "paged" to page.toString())
            ).document
            val items = document.select("article.thumb-block.video-preview-item").mapNotNull { it.toSearchResult() }
            results.addAll(items)
            // Stop when no more pages or no results
            if (items.isEmpty() || document.selectFirst("a.next.page-numbers") == null) break
            page++
        }
        return results
    }

    // Items saved from the old domain (mmsvibex.fit) are moved to the current one.
    private fun fixUrl2(u: String) = u.replace(Regex("^https?://(www\\.)?mmsvibex\\.(fit|com|net|xyz)"), mainUrl)

    override suspend fun load(url: String): LoadResponse {
        val url = fixUrl2(url)
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text() ?: "Unknown"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("meta[itemprop=thumbnailUrl]")?.attr("content")
            ?: document.selectFirst(".post-thumbnail img")?.attr("src")
        val plot = document.selectFirst("meta[itemprop=description]")?.attr("content")
            ?: document.selectFirst(".entry-content p")?.text()
        val tags = document.select(".tags-list a, .tag-link").map { it.text().trim() }.filter { it.isNotEmpty() }

        val episodes = document.select("a.mms-image-album-item").mapIndexed { index, element ->
            newEpisode(element.attr("href").takeIf { it.isNotEmpty() } ?: url) {
                name = element.attr("title").takeIf { it.isNotEmpty() } ?: "Episode ${index + 1}"
                season = 1
                episode = index + 1
                posterUrl = element.selectFirst("img")?.attr("src")
            }
        }

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.NSFW, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.NSFW, url) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val page = fixUrl2(data)
        val html = app.get(page).text

        // The player markup sits inside a <template>, so an HTML parser never sees the <video>;
        // read the raw page instead. Order: direct play button / <source> / schema contentUrl / any mp4|m3u8.
        val found = linkedSetOf<String>()
        val patterns = listOf(
            Regex("""<source[^>]+src=["']([^"']+)["']"""),
            Regex("""class=["']mvp-button[^"']*["'][^>]*href=["']([^"']+)["']"""),
            Regex("""itemprop=["']contentUrl["'][^>]*content=["']([^"']+)["']"""),
            Regex("""https?:(?:\\?/){2}[^"'\s<>\\]+?\.(?:mp4|m3u8)(?:\?[^"'\s<>\\]*)?"""),
        )
        for (re in patterns) {
            re.findAll(html).forEach { m ->
                val u = (m.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: m.value)
                    .replace("\\/", "/").replace("&amp;", "&")
                if (u.startsWith("http") &&
                    (u.contains(".mp4") || u.contains(".m3u8")) &&
                    !u.contains("/trailers/") && !u.contains("thumbnail")
                ) found.add(u)
            }
        }
        // the same file is often listed with and without ?v=; keep the versioned one
        val links = found.groupBy { it.substringBefore('?') }.map { (_, v) -> v.maxByOrNull { it.length }!! }

        links.forEachIndexed { i, link ->
            callback(
                newExtractorLink(name, if (links.size > 1) "$name ${i + 1}" else name, link,
                    if (link.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                }
            )
        }

        return links.isNotEmpty()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst(".title")?.text() ?: return null
        val url = selectFirst("a")?.attr("href") ?: return null
        val posterUrl = selectFirst("img")?.attr("src")
        return newMovieSearchResponse(title, url, TvType.NSFW) { this.posterUrl = posterUrl }
    }
}
