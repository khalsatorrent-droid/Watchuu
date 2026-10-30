package com.watchuu.latestpornvideo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * latestpornvideo.com — WordPress + Cloudflare, built from the network log (2026-10-30).
 *
 * Listing   : article.loop-video.thumb-block, 20 per page
 * Paging    : path based — /page/N/ , /category/<slug>/page/N/
 * Search    : /?s=<query> and /page/N/?s=<query>   (capture: /page/2/?s=Chinese)
 * Detail    : /<post_id>/   (numeric id, e.g. /1198656/)
 *
 * STREAMS — this is what was broken.
 * The detail page embeds two players as iframes:
 *     luluvido.com/e/<code>   and   hanerix.com/e/<code>
 * Both serve a page whose player config is hidden behind a Dean Edwards
 * `eval(function(p,a,c,k,e,d){…})` packer, so the old code — which regexed the raw HTML for a
 * literal .m3u8 — could never find anything and the video never played.
 *
 * Unpacking gives the real configs:
 *     luluvido : jwplayer("vplayer").setup({sources:[{file:"https://<cdn>/hls2/…/master.m3u8?t=…"}]})
 *     hanerix  : var links={"hls3":"…/master.txt","hls2":"…/master.m3u8?t=…"}; … sources:[{file:links.hls4||links.hls3||links.hls2}]
 * The player uses hls2 (the cdn-centaurus master.m3u8); hls3 is a .txt variant. So hls2 is
 * preferred and hls3/hls4 are only added when they are real .m3u8 URLs.
 * Streams need `Referer: https://luluvido.com/` (or hanerix.com) — verified in the capture.
 *
 * Cloudflare: document pages answer plain OkHttp with 503 + bot challenge, so every fetch falls
 * back to CloudflareKiller. Cookies/UA come from the capture.
 */
class LatestPornVideoProvider : MainAPI() {
    override var mainUrl = "https://latestpornvideo.com"
    override var name = "LatestPornVideo"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val cf by lazy { CloudflareKiller() }
    private val ua =
        "Mozilla/5.0 (Linux; Android 16; SM-S911B Build/BP4A.251205.006) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/156.0.8078.47 Mobile Safari/537.36"

    //
    // The capture shows Cloudflare answering plain OkHttp with a 503 bot challenge on the detail
    // pages (/1188551/ is challenged, the same URL is 200 for the browser), while listings are
    // served directly. Cloudflare scores the whole header set, so send the full browser one.
    private val headers = mapOf(
        "User-Agent" to ua,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Upgrade-Insecure-Requests" to "1",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin",
        "Sec-Fetch-User" to "?1"
    )

    // Home sections observed in the capture.
    override val mainPage = mainPageOf(
        "" to "Latest",
        "category/porn-movie/" to "Porn Movie",
        "category/jav/" to "JAV",
        "category/webcam/" to "Webcam"
    )

    // ── Transport ──────────────────────────────────────────────────────────────

    /** Plain request first, CloudflareKiller only when Cloudflare answers with a challenge. */
    private suspend fun fetchHtml(url: String, referer: String = "$mainUrl/"): String? {
        val plain = runCatching {
            app.get(url, headers = headers, referer = referer).text
        }.getOrNull()
        if (!looksBroken(plain)) return plain

        val solved = runCatching {
            app.get(url, headers = headers, referer = referer, interceptor = cf).text
        }.getOrNull()
        if (!looksBroken(solved)) return solved

        // Still a challenge: hand the caller the real page if there was one, otherwise null.
        // Returning the challenge HTML used to make the parser see a page with no cards and no
        // iframes, which reads as "search broken / videos do not play" instead of a clear failure.
        return if (!looksBroken(plain)) plain else null
    }

    private fun looksBroken(body: String?): Boolean {
        if (body == null || body.length < 600) return true
        val head = body.take(6000)
        return head.contains("Just a moment", ignoreCase = true) ||
            head.contains("challenge-platform") ||
            head.contains("cf-browser-verification") ||
            head.contains("cf_chl_opt") ||
            head.contains("Attention Required", ignoreCase = true) ||
            !head.contains("<html", ignoreCase = true)
    }

    // ── Main page / category listings ──────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = mainUrl.trimEnd('/') + "/" +
            request.data.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        val url = if (page > 1) "${base}page/$page/" else base

        val html = fetchHtml(url) ?: return newHomePageResponse(request.name, emptyList(), false)
        val items = parseCards(html)
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // ── Search (paginated) ─────────────────────────────────────────────────────

    /**
     * Only the long-standing search(query) API is used: the paginated overload returns
     * SearchResponseList, a type missing from older Cloudstream builds, and a provider that
     * declares it cannot be loaded there at all. Two pages are merged instead.
     */
    override suspend fun search(query: String): List<SearchResponse> {
        val first = searchPage(query, 1)
        if (first.isEmpty()) return first
        val second = runCatching { searchPage(query, 2) }.getOrNull().orEmpty()
        return (first + second).distinctBy { it.url }
    }

    /**
     * The site pages search as /?s=q , /page/2/?s=q , /page/3/?s=q … — the page segment sits
     * before the query string (capture: /page/2/?s=Chinese). Paginated so the app can scroll on.
     */

    private suspend fun searchPage(query: String, page: Int): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        if (q.isBlank()) return emptyList()

        val url = if (page <= 1) "$mainUrl/?s=$q" else "$mainUrl/page/$page/?s=$q"
        var items = fetchHtml(url)?.let { parseCards(it) } ?: emptyList()

        // Some WordPress setups only honour the query on an explicit /page/1/ path.
        if (items.isEmpty() && page <= 1) {
            items = fetchHtml("$mainUrl/page/1/?s=$q")?.let { parseCards(it) } ?: emptyList()
        }
        return items
    }

    // ── Detail ─────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val html = fetchHtml(url) ?: return null
        val doc = Jsoup.parse(html)

        val title = (
            doc.selectFirst("h1.entry-title, h1")?.text()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: "Video"
            ).trim()

        val poster = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))

        val plot = (
            doc.selectFirst("[class*=description] p, [class*=description]")?.text()
                ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
                ?: doc.selectFirst("meta[name=description]")?.attr("content")
            )?.trim()

        val tags = doc.select("[class*=categor] a, a[href*=/tag/]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && it.length < 40 }
            .distinct()

        val recommendations = parseCards(html).filter { it.url != url }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    // ── Links ──────────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = fetchHtml(data) ?: return false
        val doc = Jsoup.parse(html)
        var found = false

        val iframes = doc.select("iframe[src]")
            .map { it.attr("src").replace("&amp;", "&").trim() }
            .filter { it.startsWith("http") && !it.contains("latestpornvideo") }
            .distinct()

        for (embed in iframes) {
            if (isAdHost(embed)) continue

            // The site alternates between luluvdo.com and luluvido.com for the same 12-char code;
            // if one host is down or challenged, try the same code on the other before giving up.
            val playlists = resolveEmbed(embed).ifEmpty {
                mirrorEmbed(embed)?.let { resolveEmbed(it) }.orEmpty()
            }
            if (playlists.isNotEmpty()) {
                val referer = originOf(embed)
                playlists.forEachIndexed { index, url ->
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = if (index == 0) "$name (HLS)" else "$name (HLS ${index + 1})",
                            url = url,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = referer
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf("Referer" to referer, "User-Agent" to ua)
                        }
                    )
                }
                found = true
                continue
            }

            // Unknown host — hand it to CloudStream's built-in extractors.
            runCatching {
                if (loadExtractor(embed, "$mainUrl/", subtitleCallback, callback)) found = true
            }
        }

        // Last resort: a plain playlist / mp4 sitting in the page source.
        if (!found) {
            val direct = Regex(
                """https?://[^\s"'<>\\]+\.(?:m3u8|mp4)[^\s"'<>\\]*""",
                RegexOption.IGNORE_CASE
            ).findAll(html).map { it.value }
                .filter { !it.contains("spicy-box") && !isAdHost(it) }
                .distinct()
                .toList()

            direct.forEach { url ->
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = url,
                        type = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to ua)
                    }
                )
                found = true
            }
        }

        return found
    }

    /** luluvdo.com/e/<code> -> luluvido.com/e/<code> and back. */
    private fun mirrorEmbed(embedUrl: String): String? {
        val code = Regex("/e/([A-Za-z0-9]+)").find(embedUrl)?.groupValues?.get(1) ?: return null
        val other = when {
            embedUrl.contains("luluvdo.com") -> "https://luluvido.com/e/$code"
            embedUrl.contains("luluvido.com") -> "https://luluvdo.com/e/$code"
            else -> return null
        }
        return other
    }

    /**
     * Resolve an embed page to playlist URLs. The player config is packed with the classic
     * `eval(function(p,a,c,k,e,d){…})` packer, so unpack it before looking for the sources.
     */
    private suspend fun resolveEmbed(embedUrl: String): List<String> {
        // The capture fetches the embed with the site root as referer, not the detail page.
        val html = fetchHtml(embedUrl, "$mainUrl/") ?: return emptyList()
        val js = unpackJs(html)
        val out = LinkedHashSet<String>()

        // luluvido / luluvdo style: sources:[{file:"https://…/master.m3u8?t=…"}]
        Regex(
            """sources\s*:\s*\[[^]]*?file\s*:\s*["'](https?://[^"']+)["']""",
            RegexOption.DOT_MATCHES_ALL
        ).find(js)?.groupValues?.get(1)?.let { out.add(it) }

        // hanerix / hgcloud style: var links={"hls3":"…","hls2":"…"}
        // hls2 is the real master.m3u8 the player used in the capture; hls3 is a .txt variant.
        for (key in listOf("hls2", "hls4", "hls3")) {
            val value = Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"").find(js)?.groupValues?.get(1) ?: continue
            if (value.contains(".m3u8") || key == "hls2") out.add(value)
        }

        // Anything else that is literally a playlist URL.
        Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""")
            .findAll(js).forEach { out.add(it.value) }

        return out.filter { it.startsWith("http") }.distinct()
    }

    // ── Dean Edwards packer ────────────────────────────────────────────────────

    /**
     * Unpacks `eval(function(p,a,c,k,e,d){…}('payload',base,count,'word|table'.split('|'),0,{}))`.
     * Returns the input unchanged when there is nothing packed in it.
     */
    private fun unpackJs(source: String): String {
        val match = Regex(
            """eval\(function\(p,a,c,k,e,d\)\{.*?}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)""",
            RegexOption.DOT_MATCHES_ALL
        ).find(source) ?: return source

        var payload = match.groupValues[1]
        val base = match.groupValues[2].toIntOrNull() ?: return source
        var count = match.groupValues[3].toIntOrNull() ?: return source
        val words = match.groupValues[4].split("|")
        if (base < 2) return source

        fun encode(n0: Int): String {
            val digits = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
            var n = n0
            val sb = StringBuilder()
            do {
                sb.insert(0, digits[n % base])
                n /= base
            } while (n > 0)
            return sb.toString()
        }

        while (count-- > 0) {
            if (count < words.size && words[count].isNotEmpty()) {
                val token = Regex("\\b" + Regex.escape(encode(count)) + "\\b")
                val word = words[count]
                payload = token.replace(payload) { word }
            }
        }
        return payload
    }

    // ── Cards ──────────────────────────────────────────────────────────────────

    private fun parseCards(html: String): List<SearchResponse> {
        val doc = Jsoup.parse(html)
        val cards = doc.select(
            "article.loop-video.thumb-block, article.loop-video, article.thumb-block, " +
                "div.loop-video.thumb-block, div.thumb-block.loop-video"
        )
        val list = cards.ifEmpty { doc.select("article[class*=loop-video], div[class*=loop-video]") }
        return list.mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    /** Video posts are the numeric ones — /1198656/ — which filters out tags, dates and categories. */
    private val videoUrlRegex = Regex("""/\d{4,}/?$""")

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a[href]") ?: return null
        val rawHref = a.attr("href").trim()
        if (rawHref.isBlank() || rawHref.startsWith("#") || rawHref.startsWith("javascript")) return null

        val href = fixUrl(rawHref)
        if (!videoUrlRegex.containsMatchIn(href)) return null

        // Every step is filtered with takeIf — an empty attribute must not stop the chain early.
        val title = sequenceOf(
            selectFirst(".entry-title, .title, h3, h2")?.text()?.trim(),
            a.attr("title").trim(),
            a.attr("aria-label").trim(),
            selectFirst("img[alt]")?.attr("alt")?.trim()
        ).firstOrNull { !it.isNullOrBlank() }
            ?: "Video ${href.trimEnd('/').substringAfterLast('/')}"

        val poster = fixUrlNull(
            selectFirst("img[data-src]")?.attr("data-src")?.takeIf { isRealImage(it) }
                ?: selectFirst("img[src]")?.attr("src")?.takeIf { isRealImage(it) }
        )

        return newMovieSearchResponse(title.trim(), href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    private fun isRealImage(url: String): Boolean =
        url.isNotBlank() && !url.startsWith("data:") &&
            !url.contains("favicon") && !url.contains("logo") && !url.contains("cropped-")

    // ── Misc ───────────────────────────────────────────────────────────────────

    private fun originOf(url: String): String =
        Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.get(1)?.plus("/") ?: "$mainUrl/"

    private fun isAdHost(url: String): Boolean {
        val u = url.lowercase()
        return listOf(
            "a-ads.com", "nucankaktop", "spleniidizzy", "doubleclick", "googlesyndication",
            "onclickads", "popads", "exoclick", "juicyads", "google-analytics", "cloudflareinsights"
        ).any { u.contains(it) }
    }
}
