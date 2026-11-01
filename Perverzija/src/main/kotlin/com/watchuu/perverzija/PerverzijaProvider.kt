package com.watchuu.perverzija

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * tube.perverzija.com — built from the capture of 2026-11-01 10:30.
 *
 * WordPress tube. Browsing side is ordinary:
 *
 *   listing   div.item-thumbnail > a[href], img[src] for the poster, img alt for the title
 *   paging    path: /page/N/   - and /page/N/?s=… for a search
 *   search    /?s=<query>
 *   sections  /featured-scenes/, /full-movie/, /studio/<name>/
 *   detail    h1, og:image, [class*=description], a[rel=tag]
 *
 * The playing side is the interesting part, and it is not solvable from outside:
 *
 *   every video iframe points at the xtremestream player (perv./pervl1./pervl3./pervl5./pervl6./
 *   pervl7./j2.xtremestream.xyz/player/index.php?data=<hash>). That player loads videojs with a
 *   custom hls.js loader (XSFTK.fLoader) and its segments are not ordinary hls:
 *     - proto_api.php?op=key returns a 16-byte key
 *     - proto_api.php?op=manifest returns application/octet-stream
 *     - the CDN serves /cdn/down/<hash>/480_enc/4800.html, which is 600 KB of binary behind a
 *       .html content type
 *   so the media is encrypted and decrypted inside their javascript. There is no plain
 *   m3u8/mp4 anywhere in the capture, and the site offers no download or alternate server.
 *
 * What this provider does about it: the player url is handed to Cloudstream's own extractors, so
 * it plays if one of them ever handles xtremestream, and any plain media url that does appear on
 * a page is used directly. That is the honest ceiling without reimplementing their crypto.
 */
class PerverzijaProvider : MainAPI() {
    override var mainUrl = "https://tube.perverzija.com"
    override var name = "Perverzija"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "" to "Latest",
        "featured-scenes" to "Featured Scenes",
        "full-movie" to "Full Movies",
        "studio/adulttime" to "AdultTime",
        "studio/missax" to "Missax",
        "studio/nubiles" to "Nubiles",
        "studio/vxn" to "VXN",
        "studio/vip4k" to "VIP4K"
    )

    private fun userAgent(): String = UA

    private suspend fun fetch(url: String, referer: String = "$mainUrl/"): String? {
        val headers = mapOf(
            "User-Agent" to userAgent(),
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Referer" to referer
        )
        val plain = runCatching { app.get(url, headers = headers, timeout = 25L).text }.getOrNull()
        if (plain != null && looksLikePage(plain)) return plain

        // the capture carries a cf_clearance cookie, so a refusal is worth one more rung
        return runCatching {
            app.get(url, headers = headers, interceptor = CloudflareKiller(), timeout = 40L).text
        }.getOrNull()?.takeIf { looksLikePage(it) }
    }

    /** Judge a page by the markup we need, not by the absence of challenge markers. */
    private fun looksLikePage(html: String): Boolean =
        html.contains("<html", true) && (
            html.contains("item-thumbnail") || html.contains("video-item") ||
                html.contains("<iframe") || html.contains("wp-content")
            )

    private fun pageUrl(path: String, page: Int, query: String? = null): String {
        val base = "$mainUrl/" + path.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        val paged = if (page > 1) "${base}page/$page/" else base
        return if (query.isNullOrBlank()) paged else "${paged}?s=$query"
    }

    // ── Listings ───────────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val body = fetch(pageUrl(request.data, page))
            ?: return newHomePageResponse(request.name, emptyList(), false)
        val items = cards(Jsoup.parse(body))
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        if (q.isBlank()) return emptyList()

        val first = fetch(pageUrl("", 1, q))?.let { Jsoup.parse(it) }?.let { cards(it) }.orEmpty()
        if (first.isEmpty()) return first
        val second = fetch(pageUrl("", 2, q))?.let { Jsoup.parse(it) }?.let { cards(it) }.orEmpty()
        return (first + second).distinctBy { it.url }
    }

    private fun cards(doc: org.jsoup.nodes.Document): List<SearchResponse> =
        doc.select("div.item-thumbnail, div.video-item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a[href]") ?: return null
        val href = a.attr("href").trim()
        if (href.isBlank()) return null
        if (href.contains("/page/") || href.contains("?s=")) return null
        if (href.contains("/studio/") || href.contains("/studios/") || href.contains("/stars/") ||
            href.contains("/tags/")
        ) return null

        val img = selectFirst("img[src], img[data-src]")
        val title = img?.attr("alt")?.trim()?.ifBlank { null }
            ?: selectFirst(".item-title, h2, h3")?.text()?.trim()?.ifBlank { null }
            ?: a.attr("title").trim().ifBlank { null }
            ?: return null

        val poster = img?.let { it.attr("data-src").ifBlank { it.attr("src") } }
            ?.takeIf { it.isNotBlank() }
            ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }

        return newMovieSearchResponse(title, fixUrl(href), TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    // ── Detail ─────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val html = fetch(url) ?: return null
        val doc = Jsoup.parse(html)

        val title = doc.selectFirst("h1")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Video"
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
        val plot = doc.selectFirst("[class*=description]")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[name=description]")?.attr("content")
        val tags = doc.select("a[rel=tag], a[href*=/tags/], a[href*=/studio/]").map { it.text().trim() }
            .filter { it.isNotBlank() && it.length < 40 }.distinct().take(12)

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
        }
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

        var found = false

        // 1. a plain file in the page, if the site ever serves one
        Regex("""https?://[^\s"'<>\\]+\.(?:m3u8|mp4)[^\s"'<>\\]*""")
            .findAll(html).map { it.value.replace("&amp;", "&") }
            .filter { !isAdHost(it) }
            .distinct()
            .forEach { url ->
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = url,
                        type = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf(
                            "Referer" to "$mainUrl/",
                            "User-Agent" to userAgent()
                        )
                    }
                )
                found = true
            }

        // 2. the player iframes, handed to Cloudstream's extractors. This is the only route to
        //    an xtremestream video, and it works whenever one of them learns the host.
        doc.select("iframe[src]")
            .map { it.attr("src").replace("&amp;", "&").trim() }
            .filter { it.startsWith("http") && !isAdHost(it) }
            .distinct()
            .forEach { embed ->
                val got = mutableListOf<ExtractorLink>()
                runCatching {
                    loadExtractor(embed, "$mainUrl/", subtitleCallback) { got.add(it) }
                }
                if (got.isNotEmpty()) {
                    got.forEach { callback.invoke(it) }
                    found = true
                }
            }

        return found
    }

    private fun isAdHost(url: String): Boolean {
        val u = url.lowercase()
        return listOf(
            "discord.com", "doubleclick", "googlesyndication", "exoclick", "juicyads",
            "popads", "onclickads", "addtoany", "cloudflareinsights", "clips4sale",
            "hydrax.net", "duckduckgo"
        ).any { u.contains(it) }
    }

    private companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 16; SM-S911B Build/BP4A.251205.006) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.47 Mobile Safari/537.36"
    }
}
