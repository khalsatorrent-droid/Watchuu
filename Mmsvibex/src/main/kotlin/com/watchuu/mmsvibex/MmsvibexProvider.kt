package com.watchuu.mmsvibex

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class MmsvibexProvider : MainAPI() {
    override var mainUrl = "https://mmsvibex.fit"
    override var name = "Mmsvibex"
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<HomePageList>()
        val document = app.get("$mainUrl/page/$page/").document

        val sections = document.select("section")
        sections.forEach { section ->
            val title = section.selectFirst("h2")?.text() ?: return@forEach
            val videos = section.select("article.thumb-block.video-preview-item").mapNotNull { element ->
                element.toSearchResult()
            }
            if (videos.isNotEmpty()) {
                items.add(HomePageList(title, videos))
            }
        }

        return HomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get(
            "$mainUrl/wp-json/mmsvibe/v1/search-suggest",
            params = mapOf("q" to query)
        )

        val results = mutableListOf<SearchResponse>()
        val jsonArray = response.parsed<List<Map<String, Any>>>()

        jsonArray.forEach { item ->
            val title = (item["title"] as? String) ?: return@forEach
            val url = (item["url"] as? String) ?: return@forEach
            val poster = (item["thumbnail"] as? String)

            results.add(
                MovieSearchResponse(
                    name = title,
                    url = url,
                    apiName = this.name,
                    type = TvType.Movie,
                    posterUrl = poster,
                )
            )
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text() ?: "Unknown"
        val poster = document.selectFirst(".post-thumbnail img")?.attr("src")
        val plot = document.selectFirst(".entry-content p")?.text()
        val tags = document.select(".tag-link").map { it.text() }

        val episodes = document.select("a.mms-image-album-item").mapIndexed { index, element ->
            Episode(
                name = element.attr("title").takeIf { it.isNotEmpty() } ?: "Episode ${index + 1}",
                season = 1,
                episode = index + 1,
                data = element.attr("href").takeIf { it.isNotEmpty() } ?: url,
                posterUrl = element.selectFirst("img")?.attr("src"),
            )
        }

        return if (episodes.isNotEmpty()) {
            TvSeriesLoadResponse(
                name = title,
                url = url,
                apiName = this.name,
                type = TvType.TvSeries,
                episodes = episodes,
                posterUrl = poster,
                plot = plot,
                tags = tags,
            )
        } else {
            MovieLoadResponse(
                name = title,
                url = url,
                apiName = this.name,
                type = TvType.Movie,
                dataUrl = url,
                posterUrl = poster,
                plot = plot,
                tags = tags,
            )
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        val videoLinks = document.select("iframe").mapNotNull { iframe ->
            iframe.attr("src").takeIf { it.isNotEmpty() }
        } + document.select("video source").mapNotNull { source ->
            source.attr("src").takeIf { it.isNotEmpty() }
        } + document.select("[src*='cdn.mmsvibex.fit']").mapNotNull { elem ->
            elem.attr("src").takeIf { it.isNotEmpty() }
        }

        videoLinks.forEach { link ->
            if (link.contains("mmsvibex.fit")) {
                val isM3u8 = link.contains(".m3u8")
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = link,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
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
        val title = this.selectFirst(".title")?.text() ?: return null
        val url = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")

        return MovieSearchResponse(
            name = title,
            url = url,
            apiName = this@MmsvibexProvider.name,
            type = TvType.Movie,
            posterUrl = posterUrl,
        )
    }
}
