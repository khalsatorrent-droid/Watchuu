package com.watchuu.beeg

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import java.net.URLEncoder
import org.jsoup.nodes.Element

/**
 * beeg.porn - Adult video site (WordPress/Cloudflare-protected).
 *
 * Built from a captured network log (CS3 Extension Builder):
 *  - listing cards : span.item -> img[src] poster, a[title] or .title text
 *  - home sections : / (latest), /trends/{page}/, /best/{page}/, /new/{page}/
 *  - search        : GET https://api-search.xv-cdn.net/search/?format=ajax&k={query}&p={page}
 *  - detail URL    : https://beeg.porn/xid/{video_id}/?e={video_id_encode}
 *  - metadata API  : GET https://api-search.xv-cdn.net/id/{video_id}/?format=ajax&e={encode}
 *  - streams       : HLS M3U8 from e.xv-cdn.net embed page (html5player.setVideoHLS)
 *  - thumbnails    : evocdn.net CDN
 *  - Cloudflare    : interceptor = CloudflareKiller()
 */
class BeegProvider : MainAPI() {
    override var mainUrl = "https://beeg.porn"
    override var name = "Beeg"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val searchApiBase = "https://api-search.xv-cdn.net"
    private val embedBase = "https://e.xv-cdn.net"

    override val mainPage = mainPageOf(
        "latest"  to "Latest",
        "trends"  to "Trending",
        "best"    to "Best",
        "new"     to "New",
    )

    private val cfInterceptor = CloudflareKiller()

    // ── Home page ────────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when (request.data) {
            "latest" -> if (page == 1) "$mainUrl/" else "$mainUrl/page/$page/"
            else     -> "$mainUrl/${request.data}/$page/"
        }

        val doc = app.get(url, interceptor = cfInterceptor).document
        val items = doc.select("span.item").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // ── Search ───────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$searchApiBase/search/?format=ajax&k=$q&p=1"

        val json = app.get(url).parsedSafe<SearchApiResponse>() ?: return emptyList()

        return json.items.mapNotNull { it.toSearchResponse() }
    }

    // ── Load detail page ─────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        // URL format: https://beeg.porn/xid/{video_id}/?e={encode}
        val doc = app.get(url, interceptor = cfInterceptor).document

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("h2")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.selectFirst("title")?.text()
            ?: "Video"

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("meta[name=og:image]")?.attr("content")

        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst("meta[name=description]")?.attr("content")

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // ── Load links ────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Extract video_id and encode param from the stored URL
        // e.g. https://beeg.porn/xid/46827657/?e=icalllv04c8
        val videoId = Regex("""/xid/(\d+)/""").find(data)?.groupValues?.get(1) ?: return false
        val encode  = Regex("""[?&]e=([^&]+)""").find(data)?.groupValues?.get(1) ?: return false

        // 1. Try the metadata API for a preview MP4 (always available)
        val metaUrl = "$searchApiBase/id/$videoId/?format=ajax&e=$encode"
        val meta = app.get(metaUrl).parsedSafe<VideoMetaResponse>()

        // 2. Fetch the embed page to extract the real HLS URL
        val embedUrl = "$embedBase/?id=$videoId&e=$encode"
        val embedHtml = try {
            app.get(embedUrl, referer = "$mainUrl/", interceptor = cfInterceptor).text
        } catch (e: Exception) { "" }

        // Extract html5player.setVideoHLS('...')
        val hlsRegex = Regex("""html5player\.setVideoHLS\(['"]([^'"]+)['"]\)""")
        val hlsMatch = hlsRegex.find(embedHtml)

        if (hlsMatch != null) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name HLS",
                    url = hlsMatch.groupValues[1],
                    referer = embedUrl,
                    quality = Qualities.Unknown.value,
                    type = ExtractorLinkType.M3U8
                )
            )
            return true
        }

        // Fallback: preview MP4 from metadata API
        val previewUrl = meta?.preview_url
        if (!previewUrl.isNullOrBlank()) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name Preview",
                    url = previewUrl,
                    referer = mainUrl,
                    quality = Qualities.Unknown.value,
                    type = ExtractorLinkType.VIDEO
                )
            )
            return true
        }

        return false
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a") ?: return null
        val href = a.attr("href").takeIf { it.isNotBlank() } ?: return null

        val title = a.attr("title").trim()
            .takeIf { it.isNotBlank() }
            ?: selectFirst(".title")?.text()?.trim()
            ?: selectFirst("span.title")?.text()?.trim()
            ?: href.substringAfterLast("/").replace("-", " ").trim()
                .takeIf { it.isNotBlank() }
            ?: return null

        val poster = selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
            ?: selectFirst("img")?.attr("data-src")

        val url = if (href.startsWith("http")) href else fixUrl(href)

        return newMovieSearchResponse(title, url, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    private fun SearchApiItem.toSearchResponse(): SearchResponse? {
        val id     = video_id?.toString()     ?: return null
        val encode = video_id_encode          ?: return null
        val title  = title?.trim()            ?: return null
        val url    = "$mainUrl/xid/$id/?e=$encode"
        val poster = thumb

        return newMovieSearchResponse(title, url, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    // ── Data classes for JSON parsing ─────────────────────────────────────────

    data class SearchApiResponse(
        val items: List<SearchApiItem> = emptyList()
    )

    data class SearchApiItem(
        val video_id: Long? = null,
        val video_id_encode: String? = null,
        val title: String? = null,
        val thumb: String? = null,
        val slug: String? = null,
    )

    data class VideoMetaResponse(
        val video_id: Long? = null,
        val title: String? = null,
        val thumb: String? = null,
        val preview_url: String? = null,
        val quality: String? = null,
    )
}
