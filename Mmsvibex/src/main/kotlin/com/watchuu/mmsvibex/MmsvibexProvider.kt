package com.watchuu.mmsvibex

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class MmsvibexProvider : MainAPI() {
    override var mainUrl = "https://mmsvibex.fit"
    override var name = "Mmsvibex"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<HomePageList>()
        val document = app.get("$mainUrl/page/$page/").document

        val sections = document.select("section")
        sections.forEach { section ->
            val title = section.selectFirst("h2")?.text() ?: return@forEach
            val videos = section.select("article.thumb-block.video-preview-item").mapNotNull { it.toSearchResult() }
            if (videos.isNotEmpty()) items.add(HomePageList(title, videos))
        }

        return newHomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get(
            "$mainUrl/wp-json/mmsvibe/v1/search-suggest",
            params = mapOf("q" to query)
        )
        return response.parsed<List<Map<String, Any>>>().mapNotNull { item ->
            val title = item["title"] as? String ?: return@mapNotNull null
            val url = item["url"] as? String ?: return@mapNotNull null
            val poster = item["thumbnail"] as? String
            newMovieSearchResponse(title, url, TvType.NSFW) { posterUrl = poster }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text() ?: "Unknown"
        val poster = document.selectFirst(".post-thumbnail img")?.attr("src")
        val plot = document.selectFirst(".entry-content p")?.text()
        val tags = document.select(".tag-link").map { it.text() }

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
        val document = app.get(data).document

        val videoLinks = document.select("iframe").mapNotNull { it.attr("src").takeIf { s -> s.isNotEmpty() } } +
            document.select("video source").mapNotNull { it.attr("src").takeIf { s -> s.isNotEmpty() } } +
            document.select("[src*='cdn.mmsvibex.fit']").mapNotNull { it.attr("src").takeIf { s -> s.isNotEmpty() } }

        videoLinks.forEach { link ->
            if (link.contains("mmsvibex.fit")) {
                callback(
                    newExtractorLink(name, name, link,
                        if (link.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }

        return videoLinks.isNotEmpty()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst(".title")?.text() ?: return null
        val url = selectFirst("a")?.attr("href") ?: return null
        val posterUrl = selectFirst("img")?.attr("src")
        return newMovieSearchResponse(title, url, TvType.NSFW) { this.posterUrl = posterUrl }
    }
}
