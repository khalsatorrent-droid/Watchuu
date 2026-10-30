package com.watchuu.latestpornvideo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import org.jsoup.nodes.Element

/**
 * latestpornvideo.com — WordPress site confirmed from network log (2026-10-30).
 *
 * Structure (from log analysis):
 *  - Listing cards: article.loop-video.thumb-block  (20 per page)
 *  - Pagination:    /page/N/  (path-based)
 *  - Search:        /?s=<query>  →  same article card grid
 *  - Detail page:   /<post_id>/
 *      · title    : h1
 *      · poster   : meta[property=og:image]
 *      · plot     : [class*=description]  or  meta[property=og:description]
 *      · tags     : [class*=categor] a
 *      · iframes  : luluvido.com/e/<code>  and  hanerix.com/e/<code>
 *
 *  Video delivery (from log):
 *      luluvido.com/e/<code>  →  JW Player  →  m3u8 on tnmr.org CDN
 *      hanerix.com/e/<code>   →  player     →  m3u8 on cdn-centaurus.com
 *
 *  Cloudflare is present (cf_clearance cookie observed), so CloudflareKiller is used.
 */
class LatestPornVideoProvider : MainAPI() {
    override var mainUrl = "https://latestpornvideo.com"
    override var name = "LatestPornVideo"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val cf = CloudflareKiller()
    private val ua =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    // Home sections observed in the log: Latest, Porn Movie, JAV, Webcam
    override val mainPage = mainPageOf(
        ""                       to "Latest",
        "category/porn-movie/"   to "Porn Movie",
        "category/jav/"          to "JAV",
        "category/webcam/"       to "Webcam",
    )

    // -------------------------------------------------------------------------
    // Helpers

    private suspend fun fetch(url: String, referer: String = "$mainUrl/"): String =
        app.get(url, referer = referer, interceptor = cf, headers = mapOf("User-Agent" to ua)).text

    /** Extract cards from a listing page HTML. */
    private fun parseCards(html: String): List<SearchResponse> {
        val doc = org.jsoup.Jsoup.parse(html)
        return doc.select("article.loop-video.thumb-block").mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    /** Does this listing page have a next page? */
    private fun hasNext(html: String): Boolean {
        val doc = org.jsoup.Jsoup.parse(html)
        return doc.selectFirst(
            "link[rel=next], .nav-links a.next, a.next.page-numbers, a[rel=next]"
        ) != null
    }

    // -------------------------------------------------------------------------
    // Main page / category listings

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = mainUrl.trimEnd('/') + "/" +
            request.data.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        val url = if (page > 1) "${base}page/$page/" else base

        val html = fetch(url)
        val items = parseCards(html)
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty() && hasNext(html))
    }

    // -------------------------------------------------------------------------
    // Search

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val html = fetch("$mainUrl/?s=$q")
        return parseCards(html)
    }

    // -------------------------------------------------------------------------
    // Detail (load)

    override suspend fun load(url: String): LoadResponse {
        val html = fetch(url)
        val doc = org.jsoup.Jsoup.parse(html)

        val title = (
            doc.selectFirst("h1.entry-title, h1")?.text()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: "Video"
        ).trim()

        val poster = fixUrlNull(
            doc.selectFirst("meta[property=og:image]")?.attr("content")
        )

        val plot = (
            doc.selectFirst("[class*=description] p, [class*=description]")?.text()
                ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
                ?: doc.selectFirst("meta[name=description]")?.attr("content")
        )?.trim()

        val tags = doc.select("[class*=categor] a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
        }
    }

    // -------------------------------------------------------------------------
    // Links (loadLinks)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = fetch(data)
        var found = false

        // ---- 1. Collect iframe src values from the page ----
        val iframeSrcRe = Regex(
            """<iframe[^>]+\bsrc=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        val embedUrls = iframeSrcRe.findAll(html)
            .map { it.groupValues[1].replace("&amp;", "&").trim() }
            .filter { it.startsWith("http") && !it.contains("latestpornvideo") }
            .distinct()
            .toList()

        // ---- 2. Handle known embed hosts: luluvido & hanerix ----
        for (embedUrl in embedUrls) {
            val host = embedUrl.lowercase()
            when {
                "luluvido.com" in host -> {
                    // luluvido.com/e/<code> → packed JS contains sources[{file: "...master.m3u8"}]
                    val m3u8 = resolveLuluvido(embedUrl, data)
                    if (m3u8 != null) {
                        callback.invoke(
                            newExtractorLink(name, "$name [luluvido]", m3u8, ExtractorLinkType.M3U8) {
                                this.referer = "https://luluvido.com/"
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        found = true
                        continue
                    }
                }
                "hanerix.com" in host || "hgcloud.to" in host -> {
                    // hanerix.com/e/<code> → JS source object with m3u8 on cdn-centaurus.com
                    val m3u8 = resolveHanerix(embedUrl, data)
                    if (m3u8 != null) {
                        callback.invoke(
                            newExtractorLink(name, "$name [hanerix]", m3u8, ExtractorLinkType.M3U8) {
                                this.referer = "https://hanerix.com/"
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        found = true
                        continue
                    }
                }
            }
            // ---- 3. Fallback: hand unknown iframes to CloudStream's built-in extractors ----
            if (!found) {
                runCatching {
                    if (loadExtractor(embedUrl, "$mainUrl/", subtitleCallback, callback)) found = true
                }
            }
        }

        // ---- 4. If no iframes found, scan for raw m3u8 in the page source ----
        if (!found) {
            val m3u8Re = Regex("""https?://[^\s"'\\<>]+\.m3u8[^\s"'\\<>]*""", RegexOption.IGNORE_CASE)
            m3u8Re.findAll(html).map { it.value }.distinct().forEach { m3u8 ->
                callback.invoke(
                    newExtractorLink(name, name, m3u8, ExtractorLinkType.M3U8) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
            }
        }

        return found
    }

    // -------------------------------------------------------------------------
    // luluvido resolver
    //
    // luluvido.com/e/<code> serves a page with a packed JS (eval/p,a,c,k,e,d obfuscation).
    // After unpacking the source array contains  {file:"https://.../master.m3u8?t=..."}
    // We look for the m3u8 URL directly (packed strings are split('|') and indexed),
    // and fall back to a regex over the raw (still obfuscated) page if unpacking isn't needed.

    private suspend fun resolveLuluvido(embedUrl: String, pageRef: String): String? = runCatching {
        val html = app.get(
            embedUrl,
            referer = pageRef,
            interceptor = cf,
            headers = mapOf("User-Agent" to ua)
        ).text

        // The packed script ultimately contains master.m3u8 urls; they appear in the |split table
        // or become visible once we look at the raw obfuscated source (the CDN token parameter
        // is still split across the base64-looking strings). A simpler approach: run the packed
        // JS through a regex for anything that looks like a CDN m3u8 link after splitting the
        // string table.  The split table (after 'split('|')') is the last single-quoted string
        // before '))'  in the eval(...) call.

        // Try direct regex first (works if the URL survives as a literal in the script):
        val directM3u8 = Regex(
            """["'](https?://[^"']+\.m3u8[^"']*)["']""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.get(1)
        if (directM3u8 != null) return@runCatching directM3u8

        // Unpack p,a,c,k,e,d: extract word table from last split('|') argument
        val packed = Regex(
            """eval\(function\(p,a,c,k,e,d\)\{.*?'([\w|]+)'\)""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        ).find(html)?.groupValues?.get(1)?.split("|") ?: return@runCatching null

        // Look for an m3u8 fragment in the word table (CDN path parts live there)
        val cdnFrag = packed.firstOrNull { it.contains("m3u8") || it.contains("master") }
        if (cdnFrag != null && cdnFrag.startsWith("http")) return@runCatching cdnFrag

        // Reconstruct: find index of a CDN keyword and piece together the URL
        val hls2Idx = packed.indexOf("hls2")
        if (hls2Idx >= 0) {
            // Walk back from hls2 to find the host segment, forward to find the file code & token
            // This is heuristic — the actual structure depends on the obfuscation round.
            val hostIdx = packed.indexOfFirst { it.contains(".org") || it.contains(".net") || it.contains(".com") && it.length > 10 }
            val tokenIdx = packed.indexOfFirst { Regex("[A-Za-z0-9_-]{20,}").matches(it) && it != packed[hls2Idx] }
            if (hostIdx >= 0 && tokenIdx >= 0) {
                return@runCatching "https://${packed[hostIdx]}/${packed[hls2Idx]}/master.m3u8?t=${packed[tokenIdx]}"
            }
        }
        null
    }.getOrNull()

    // -------------------------------------------------------------------------
    // hanerix resolver
    //
    // hanerix.com/e/<code> embeds a JW/video.js player. The source file URL appears
    // as a literal string in the script or in a JSON sources array.

    private suspend fun resolveHanerix(embedUrl: String, pageRef: String): String? = runCatching {
        val html = app.get(
            embedUrl,
            referer = pageRef,
            interceptor = cf,
            headers = mapOf("User-Agent" to ua)
        ).text

        // Direct m3u8 URL literal in script
        val directM3u8 = Regex(
            """["'](https?://[^"']+\.m3u8[^"']*)["']""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.get(1)
        if (directM3u8 != null) return@runCatching directM3u8

        // JSON sources array: {"file":"..."}
        Regex(
            """"file"\s*:\s*"(https?://[^"]+\.m3u8[^"]*)"""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.get(1)
    }.getOrNull()

    // -------------------------------------------------------------------------
    // Card parser

    private fun Element.toSearchResult(): SearchResponse? {
        // Link wraps the thumbnail; title attribute or aria-label carries the name
        val a = selectFirst("a[href]") ?: return null
        val href = a.attr("href").takeIf { it.isNotBlank() } ?: return null

        val title = (
            selectFirst(".entry-title, h3, h2")?.text()?.trim()
                ?: a.attr("title").trim()
                ?: a.attr("aria-label").trim()
                ?: "Video ${href.trimEnd('/').substringAfterLast('/')}"
        ).ifBlank { return null }

        // Thumbnail: prefer data-src (lazy load), fall back to src; skip data-URIs and logos
        val poster = fixUrlNull(
            selectFirst("img[data-src]")?.attr("data-src")?.takeIf { isRealImage(it) }
                ?: selectFirst("img[src]")?.attr("src")?.takeIf { isRealImage(it) }
        )

        return newMovieSearchResponse(title, fixUrl(href), TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    private fun isRealImage(url: String): Boolean =
        url.isNotBlank() && !url.startsWith("data:") &&
            !url.contains("favicon") && !url.contains("logo") && !url.contains("cropped-")
}
