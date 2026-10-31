package com.watchuu.pornxday

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * pornxday.com — built from the capture of 2026-10-31 16:20.
 *
 * A WordPress tube on the same theme as latestpornvideo, so the shapes are familiar:
 *
 *   listing   article.loop-video.thumb-block, <a href="/{slug}/" title="…">, img src
 *   paging    path: /page/N/ , and /category/{cat}/page/N/?filter=latest
 *   detail    /{slug}/   with an iframe to the embed host
 *   embed     https://lulust.com/e/{code}
 *   player    a Dean Edwards packed jwplayer setup inside a <script>:
 *               eval(function(p,a,c,k,e,d){…}('…',36,224,'word|table'.split('|'),0,{}))
 *             which unpacks to
 *               jwplayer("vplayer").setup({sources:[{file:"https://….tnmr.org/hls2/…/master.m3u8?t=…"}]})
 *   stream    HLS on *.tnmr.org, Referer https://lulust.com/  (that referer is required)
 *
 * The home page answers 403 to a cold client and 200 once Cloudflare has handed out cf_clearance,
 * so fetching goes through PornxdayBrowser: plain HTTP with whatever cookies exist, then the
 * CloudstreamKiller, then a hidden WebView. The gear icon takes a pasted cookie for the case
 * where none of those get through.
 */
class PornxdayProvider : MainAPI() {
    override var mainUrl = "https://www.pornxday.com"
    override var name = "Pornxday"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    // "path|query" - the part after the pipe is the fixed query for that row.
    override val mainPage = mainPageOf(
        "|?filter=latest" to "Latest",
        "category/porn-videos|?filter=latest" to "Porn Videos"
    )

    private suspend fun fetch(url: String, referer: String = "$mainUrl/"): String? =
        PornxdayBrowser.html(url, referer)

    // ── Listings ───────────────────────────────────────────────────────────────

    private fun pageUrl(path: String, query: String, page: Int): String {
        val base = "$mainUrl/" + path.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        val paged = if (page > 1) "${base}page/$page/" else base
        return if (query.isBlank()) paged else "$paged$query"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.substringBefore('|')
        val query = request.data.substringAfter('|', "")

        val doc = fetch(pageUrl(path, query, page))?.let { Jsoup.parse(it) }
            ?: return newHomePageResponse(request.name, emptyList(), false)
        val items = cards(doc)
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        if (q.isBlank()) return emptyList()

        val first = fetch("$mainUrl/?s=$q")?.let { Jsoup.parse(it) }?.let { cards(it) }.orEmpty()
        if (first.isEmpty()) return first
        val second = fetch("$mainUrl/page/2/?s=$q")?.let { Jsoup.parse(it) }?.let { cards(it) }.orEmpty()
        return (first + second).distinctBy { it.url }
    }

    private fun cards(doc: org.jsoup.nodes.Document): List<SearchResponse> =
        doc.select("article.loop-video, article.thumb-block")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a[href]") ?: return null
        val href = a.attr("href").trim()
        // Post urls are the site root plus a slug; skip categories, pages, feeds and the like.
        if (href.contains("/category/") || href.contains("/page/") ||
            href.contains("/feed") || href.contains("?s=")
        ) return null
        if (!href.startsWith(mainUrl) && !href.startsWith("/")) return null

        val title = a.attr("title").trim().ifBlank { null }
            ?: selectFirst("h2, h3, .entry-title")?.text()?.trim()?.ifBlank { null }
            ?: selectFirst("img[alt]")?.attr("alt")?.trim()?.ifBlank { null }
            ?: return null

        val poster = selectFirst("img[src], img[data-src]")
            ?.let { it.attr("data-src").ifBlank { it.attr("src") } }
            ?.takeIf { it.isNotBlank() }
            ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }

        return newMovieSearchResponse(title, fixUrl(href), TvType.NSFW) {
            this.posterUrl = poster
        }.withPosterHeaders()
    }

    // ── Detail ─────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val html = fetch(url) ?: return null
        val doc = Jsoup.parse(html)

        val title = doc.selectFirst("h1")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Video"
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
            ?: doc.selectFirst("article img[src], .entry-content img[src]")?.attr("src")
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")?.ifBlank { null }
            ?: doc.selectFirst("meta[name=description]")?.attr("content")
        val tags = doc.select("a[rel=tag], a[href*=/tag/]").map { it.text().trim() }
            .filter { it.isNotBlank() }.distinct().take(12)

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
        }.withPosterHeaders()
    }

    // ── Streams ────────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = fetch(data) ?: return false
        val doc = Jsoup.parse(html)

        val fromDom = doc.select("iframe[src]")
            .map { it.attr("src").replace("&amp;", "&").trim() }
            .filter { it.startsWith("http") && !it.contains("pornxday") }

        // Players are sometimes injected by JS, so the embed urls are also taken from the raw
        // source, where the slashes may be escaped.
        val fromSource = Regex(
            """(?:https?:)?(?:(?:\\?/){2})([a-z0-9.\-]*(?:lulust|lulucdn|luluvdo|luluvido)[a-z0-9.\-]*)(?:\\?/)(?:e|embed|video)(?:\\?/)([A-Za-z0-9]{6,})""",
            RegexOption.IGNORE_CASE
        ).findAll(html).map { m -> "https://${m.groupValues[1]}/e/${m.groupValues[2]}" }

        var found = false
        for (embed in (fromDom + fromSource).distinct()) {
            if (isAdHost(embed)) continue

            val playlists = resolveEmbed(embed)
            if (playlists.isEmpty()) continue

            // The CDN gets Referer, Origin and a browser UA from the real player; Cloudstream
            // reuses these headers for the segments too, so all three go on the link.
            val referer = originOf(embed)
            val origin = referer.trimEnd('/')
            val linkHeaders = mapOf(
                "Referer" to referer,
                "Origin" to origin,
                "User-Agent" to PLAYER_UA,
                "Accept" to "*/*"
            )

            playlists.forEachIndexed { index, url ->
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = if (index == 0) "$name (HLS)" else "$name (HLS ${index + 1})",
                        url = cleanUrl(url),
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = referer
                        this.quality = Qualities.Unknown.value
                        this.headers = linkHeaders
                    }
                )
            }
            found = true
        }

        if (!found) {
            // Last resort: a playlist sitting directly in the page.
            Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""")
                .findAll(html).map { it.value }
                .filter { !isAdHost(it) }
                .distinct()
                .forEach { url ->
                    callback.invoke(
                        newExtractorLink(name, name, cleanUrl(url), ExtractorLinkType.M3U8) {
                            this.referer = "$mainUrl/"
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf(
                                "Referer" to "$mainUrl/",
                                "User-Agent" to PLAYER_UA
                            )
                        }
                    )
                    found = true
                }
        }
        return found
    }

    /**
     * Attach the headers the thumbnail requests need, by reflection so that an app whose API does
     * not expose posterHeaders still loads the plugin and just shows plain posters.
     */
    private fun <T : Any> T.withPosterHeaders(): T {
        runCatching {
            val headers = PornxdayBrowser.posterHeaders()
            javaClass.methods.firstOrNull { m ->
                m.name == "setPosterHeaders" && m.parameterTypes.size == 1
            }?.invoke(this, headers)
        }
        return this
    }

    /** Urls from the page are occasionally html-escaped; a stray &amp; breaks every request. */
    private fun cleanUrl(url: String): String =
        url.trim().replace("&amp;", "&").replace("\\/", "/")

    private fun originOf(url: String): String =
        Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.get(1)?.plus("/") ?: "$mainUrl/"

    private fun isAdHost(url: String): Boolean {
        val u = url.lowercase()
        return listOf(
            "a-ads.com", "doubleclick", "googlesyndication", "onclickads", "popads", "exoclick",
            "juicyads", "google-analytics", "cloudflareinsights", "youtube.com", "google.com",
            "bobsleddomesticglandular", "ay267.com", "winbet200", "meatusbyway", "rstrix",
            "adsco.re", "handskeeping", "organic-package"
        ).any { u.contains(it) }
    }

    /** Unpack the jwplayer config, then read the playlist out of it. */
    private suspend fun resolveEmbed(embedUrl: String): List<String> {
        val html = fetch(embedUrl, "$mainUrl/") ?: return emptyList()
        val js = unpackJs(html)
        val out = LinkedHashSet<String>()

        // jwplayer(...).setup({sources:[{file:"https://…/master.m3u8?t=…"}]})
        Regex("""sources\s*:\s*\[[^]]*?file\s*:\s*["'](https?://[^"']+)["']""", RegexOption.DOT_MATCHES_ALL)
            .findAll(js).forEach { out.add(it.groupValues[1]) }

        Regex("""["']?file["']?\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""")
            .findAll(js).forEach { out.add(it.groupValues[1]) }

        Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""").findAll(js).forEach { out.add(it.value) }

        return out.filter { it.startsWith("http") }.distinct()
    }

    /**
     * Dean Edwards packer, the same one luluvdo and luluvido use:
     * eval(function(p,a,c,k,e,d){…}('payload',36,224,'word|table'.split('|'),0,{}))
     * Substitutes each table word back into the payload, which is where the playlist url lives.
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

        fun encode(number: Int): String {
            val digits = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
            var n = number
            val sb = StringBuilder()
            while (true) {
                sb.insert(0, digits[n % base])
                n /= base
                if (n == 0) return sb.toString()
            }
        }

        while (count-- > 0) {
            if (count < words.size && words[count].isNotEmpty()) {
                val word = words[count]
                payload = Regex("\\b" + Regex.escape(encode(count)) + "\\b")
                    .replace(payload) { word }
            }
        }
        return payload
    }

    private companion object {
        /** What the site's player sends the CDN on playlist and segment requests. */
        const val PLAYER_UA = "Mozilla/5.0 (Linux; Android 16; SM-S911B Build/BP4A.251205.006) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.47 Mobile Safari/537.36"
    }
}
