package com.xfantazy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder

/**
 * XFantazy — xfantazy.com (Next.js / Cloudflare)
 *
 * Built from captured network log (CS3 Extension Builder):
 *  - Listing cards   : RSC JSON embedded in spans — {"id","title","duration","userFileId","hru"}
 *  - Thumbnail       : https://s.xfantazy.com/thumbnail/{userFileId}/w320h180/0
 *  - Video URL       : /video/{hru} (slug) or /video/{id} (mongoId)
 *  - Detail/JSON-LD  : og:title, og:description, JSON-LD (VideoObject) with name/thumbnailUrl/keywords
 *  - Video sources   : GET https://api.xfantazy.com/pub/video-sources/{id}
 *                      → sources[].src  (skip blocked:true entries)
 *  - Referer needed  : https://xfantazy.com/ for stream requests
 *  - Cloudflare      : CloudflareKiller fallback on 403/503
 *
 * Pages:
 *  - Home (New)      : /
 *  - Trending        : /trending
 *  - Top Rated       : /top
 *  - Categories      : /categories (TvSeries listing)
 *  - Channels        : /channels
 *  - Search          : /search/{query}?page={N}
 */
class XfantazyProvider : MainAPI() {
    override var mainUrl = "https://xfantazy.com"
    override var name = "XFantazy"
    override var lang = "en"
    override val hasMainPage = true
    override val hasSearch = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val apiUrl = "https://api.xfantazy.com"
    private val thumbBase = "https://s.xfantazy.com/thumbnail"

    private val cfInterceptor by lazy { CloudflareKiller() }

    private suspend fun safeGet(url: String, referer: String = mainUrl): String {
        val resp = app.get(
            url,
            headers = mapOf("Referer" to referer),
            allowRedirects = true
        )
        if (resp.code in listOf(403, 503)) {
            return app.get(
                url,
                headers = mapOf("Referer" to referer),
                interceptor = cfInterceptor
            ).text
        }
        return resp.text
    }

    override val mainPage = mainPageOf(
        "" to "New",
        "trending" to "Trending",
        "top" to "Top Rated",
        "categories" to "Categories",
        "channels" to "Channels"
    )

    // ── RSC card JSON: {"id":"...","title":"...","duration":"...","userFileId":"...","hru":"..."?}
    // The RSC payload embeds card data as component props. We regex-parse them.
    private val rscThumbRegex = Regex(
        """"thumb"\s*:\s*\{[^}]*?"id"\s*:\s*"([^"]+)"[^}]*?"title"\s*:\s*"([^"]+)"[^}]*?"userFileId"\s*:\s*"([^"]+)"(?:[^}]*?"hru"\s*:\s*"([^"]+)")?"""
    )

    // Category / channel cards from RSC: href + label + thumbnail
    private val rscHrefRegex = Regex(
        """"href"\s*:\s*"(/(?:category|channel)/[^"]+)"[^}]*?"label"\s*:\s*"([^"]+)"[^}]*?"thumbnail"\s*:\s*"([^"]+)""""
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val section = request.data  // "", "trending", "top", "categories", "channels"
        val isCategories = section == "categories"
        val isChannels = section == "channels"
        val isListing = isCategories || isChannels

        val url = if (page == 1) {
            if (section.isEmpty()) mainUrl else "$mainUrl/$section"
        } else {
            if (section.isEmpty()) "$mainUrl?page=$page"
            else "$mainUrl/$section?page=$page"
        }

        val html = safeGet(url)

        val items: List<SearchResponse>
        if (isListing) {
            // Categories and Channels: parse RSC href/label/thumbnail
            items = rscHrefRegex.findAll(html).map { m ->
                val href = fixUrl(m.groupValues[1])
                val label = m.groupValues[2].htmlUnescape()
                val thumb = m.groupValues[3].takeIf { it.startsWith("http") }
                newTvSeriesSearchResponse(label, href, TvType.TvSeries) {
                    this.posterUrl = thumb
                }
            }.distinctBy { it.url }.toList()
        } else {
            // Video listings (New, Trending, Top): parse RSC thumb props
            items = rscThumbRegex.findAll(html).map { m ->
                val id = m.groupValues[1]
                val title = m.groupValues[2].htmlUnescape()
                val userFileId = m.groupValues[3]
                val hru = m.groupValues[4].takeIf { it.isNotBlank() }
                val videoUrl = if (hru != null) fixUrl("/video/$hru") else fixUrl("/video/$id")
                val thumb = "$thumbBase/$userFileId/w320h180/0"
                newMovieSearchResponse(title, videoUrl, TvType.NSFW) {
                    this.posterUrl = thumb
                }
            }.distinctBy { it.url }.toList()
        }

        return newHomePageResponse(
            name = request.name,
            list = items,
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val results = mutableListOf<SearchResponse>()
        var page = 1
        while (true) {
            val url = "$mainUrl/search/$encoded?page=$page"
            val html = safeGet(url, mainUrl)
            val pageItems = rscThumbRegex.findAll(html).map { m ->
                val id = m.groupValues[1]
                val title = m.groupValues[2].htmlUnescape()
                val userFileId = m.groupValues[3]
                val hru = m.groupValues[4].takeIf { it.isNotBlank() }
                val videoUrl = if (hru != null) fixUrl("/video/$hru") else fixUrl("/video/$id")
                val thumb = "$thumbBase/$userFileId/w320h180/0"
                newMovieSearchResponse(title, videoUrl, TvType.NSFW) {
                    this.posterUrl = thumb
                }
            }.distinctBy { it.url }.toList()
            if (pageItems.isEmpty()) break
            results.addAll(pageItems)
            if (page >= 5) break  // cap search depth
            page++
        }
        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val html = safeGet(url, mainUrl)

        // Check if this is a category/channel (TvSeries) page
        val isCategoryOrChannel = url.contains("/category/") || url.contains("/channel/")

        if (isCategoryOrChannel) {
            return loadCategoryOrChannel(url, html)
        }

        // Video page — extract metadata from og:/JSON-LD
        val title = extractOgTitle(html)
            ?: extractJsonLdField(html, "name")
            ?: url.substringAfterLast("/").replace("-", " ").trim()

        val description = extractOgDescription(html)
            ?: extractJsonLdField(html, "description")

        // Poster from JSON-LD thumbnailUrl or og:image
        val poster = extractJsonLdThumbnail(html)
            ?: extractOgImage(html)

        val tags = buildList {
            // From JSON-LD keywords array
            val kwMatch = Regex(""""keywords"\s*:\s*\[([^\]]+)]""").find(html)
            kwMatch?.groupValues?.get(1)
                ?.split(",")
                ?.map { it.trim().trim('"') }
                ?.filter { it.isNotBlank() }
                ?.let { addAll(it) }
            // From RSC videoInfo tags/categories
            Regex(""""name"\s*:\s*"([^"]+)"\s*,\s*"slug"""").findAll(html)
                .map { it.groupValues[1].htmlUnescape() }
                .filter { it.isNotBlank() }
                .let { addAll(it) }
        }.distinct()

        // Extract mongoId from the URL or from the RSC payload
        val videoId = extractVideoId(url, html)

        return newMovieLoadResponse(title, url, TvType.NSFW, videoId ?: url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
        }
    }

    private suspend fun loadCategoryOrChannel(url: String, html: String): LoadResponse {
        val rawTitle = Regex("<h1[^>]*>([^<]+)</h1>").find(html)?.groupValues?.get(1)?.trim()
            ?: Regex(""""label"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)?.htmlUnescape()
            ?: url.substringAfterLast("/").replace("-", " ").trim()
        val description = extractOgDescription(html)

        val episodes = mutableListOf<Episode>()
        val seenUrls = mutableSetOf<String>()

        // Parse first page (already fetched)
        addEpisodesFromRsc(html, episodes, seenUrls)

        // Up to 3 more pages
        for (page in 2..4) {
            val pageUrl = "$url?page=$page"
            val pageHtml = runCatching { safeGet(pageUrl, url) }.getOrNull() ?: break
            val before = episodes.size
            addEpisodesFromRsc(pageHtml, episodes, seenUrls)
            if (episodes.size == before) break
        }

        val mainPoster = episodes.firstOrNull()?.posterUrl
            ?: extractOgImage(html)

        return newTvSeriesLoadResponse(rawTitle, url, TvType.TvSeries, episodes) {
            this.posterUrl = mainPoster
            this.plot = description
        }
    }

    private fun addEpisodesFromRsc(html: String, episodes: MutableList<Episode>, seen: MutableSet<String>) {
        rscThumbRegex.findAll(html).forEach { m ->
            val id = m.groupValues[1]
            val title = m.groupValues[2].htmlUnescape()
            val userFileId = m.groupValues[3]
            val hru = m.groupValues[4].takeIf { it.isNotBlank() }
            val videoUrl = if (hru != null) fixUrl("/video/$hru") else fixUrl("/video/$id")
            if (!seen.add(videoUrl)) return@forEach
            val thumb = "$thumbBase/$userFileId/w320h180/0"
            episodes.add(newEpisode(id) {  // pass the mongoId as data for loadLinks
                this.name = title
                this.posterUrl = thumb
                this.episode = episodes.size + 1
            })
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data is either a mongoId (24-char hex) from episodes, or a full video URL
        val videoId = if (data.matches(Regex("[0-9a-f]{24}"))) {
            data
        } else {
            // Extract from URL or fetch the page
            val idFromUrl = Regex("/video/([0-9a-f]{24})").find(data)?.groupValues?.get(1)
            idFromUrl ?: run {
                val html = safeGet(data, mainUrl)
                extractVideoId(data, html)
            }
        } ?: return false

        val apiEndpoint = "$apiUrl/pub/video-sources/$videoId"
        val json = runCatching {
            safeGet(apiEndpoint, mainUrl)
        }.getOrNull() ?: return false

        // Parse: {"sources":[{"type":"video/mp4","label":"360p","src":"https://..."},{"blocked":true,...}]}
        var found = false
        val srcRegex = Regex(""""src"\s*:\s*"([^"]+)"""")
        val labelRegex = Regex(""""label"\s*:\s*"([^"]+)"""")
        val blockedRegex = Regex(""""blocked"\s*:\s*true""")

        // Split by source objects
        val sourceObjects = Regex("""\{[^{}]+\}""").findAll(json)
        for (obj in sourceObjects) {
            val objText = obj.value
            if (blockedRegex.containsMatchIn(objText)) continue  // skip blocked sources
            val src = srcRegex.find(objText)?.groupValues?.get(1) ?: continue
            if (src == "blocked" || src.isBlank()) continue
            val label = labelRegex.find(objText)?.groupValues?.get(1) ?: "360p"

            callback(
                newExtractorLink(
                    source = name,
                    name = "$name ($label)",
                    url = src,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = getQualityFromName(label)
                    this.headers = mapOf("Referer" to "$mainUrl/")
                }
            )
            found = true
        }
        return found
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun extractVideoId(url: String, html: String): String? {
        // Try direct mongoId in URL
        val fromUrl = Regex("/video/([0-9a-f]{24})(?:[/?#]|$)").find(url)?.groupValues?.get(1)
        if (fromUrl != null) return fromUrl

        // Try RSC videoInfo.id
        val fromRsc = Regex(""""videoInfo"\s*:\s*\{[^}]*?"id"\s*:\s*"([0-9a-f]{24})"""").find(html)?.groupValues?.get(1)
        if (fromRsc != null) return fromRsc

        // Try RSC children segment key pattern: ["id","<mongoId>","d",null]
        val fromSegment = Regex(""""id"\s*,\s*"([0-9a-f]{24})"\s*,\s*"d"""").find(html)?.groupValues?.get(1)
        return fromSegment
    }

    private fun extractOgTitle(html: String): String? =
        Regex("""<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+property=["']og:title["']""").find(html)?.groupValues?.get(1)

    private fun extractOgDescription(html: String): String? =
        Regex("""<meta[^>]+property=["']og:description["'][^>]+content=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+property=["']og:description["']""").find(html)?.groupValues?.get(1)
            ?: Regex("""<meta[^>]+name=["']description["'][^>]+content=["']([^"']+)["']""").find(html)?.groupValues?.get(1)

    private fun extractOgImage(html: String): String? =
        Regex("""<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+property=["']og:image["']""").find(html)?.groupValues?.get(1)

    private fun extractJsonLdField(html: String, field: String): String? =
        Regex(""""$field"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)

    private fun extractJsonLdThumbnail(html: String): String? =
        Regex(""""thumbnailUrl"\s*:\s*\["([^"]+)"]""").find(html)?.groupValues?.get(1)

    private fun String.htmlUnescape(): String =
        this.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
}
