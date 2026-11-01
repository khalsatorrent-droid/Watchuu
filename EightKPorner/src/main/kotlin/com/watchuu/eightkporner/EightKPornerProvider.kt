package com.watchuu.eightkporner

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * 8kporner.com — built from the capture of 2026-11-01 20:03.
 *
 * The site is behind Cloudflare, so all traffic goes through [EightKPornerBrowser], which is the
 * same ladder the other modules in this repo use: plain http, then CloudflareKiller, then a
 * hidden WebView, and a solver window when there is no clearance at all. Its gear menu holds the
 * bypass modes, the manual cookie, the custom UA, Test connection and Clear cookies.
 *
 * Browsing:
 *   home rows   /videos/category/<Name>          Amateur, Anal, Asian, BBC, Big, Creampie, …
 *   paging      ?page_id=N                       the same parameter the site's own search uses
 *   search      /search?keyword=<q>&page_id=N
 *   cards       a[href*=/watch/] with img; title from h4, or the img alt on search results
 *   detail      /watch/<slug>_<id>.html — h1, og:image, meta description, category links
 *
 * Playing: the watch page configures JW Player inline, and that config is the whole answer:
 *
 *     jwplayer_config = { "file": "https://vd423.okcdn.ru/?expires=…&sig=…",
 *                         "sources": [ { "file": "https://vd423.okcdn.ru/?…", "label": "1080p",
 *                                        "type": "video/mp4" }, … ] }
 *
 * so each source is read from there, labelled by its own quality. Those urls are signed and
 * short-lived (expires/sig, and srcIp inside), which is why they are resolved when you press
 * play rather than stored.
 */
class EightKPornerProvider : MainAPI() {
    override var mainUrl = "https://8kporner.com"
    override var name = "8KPorner"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "videos/category/Amateur" to "Amateur",
        "videos/category/Anal" to "Anal",
        "videos/category/Asian" to "Asian",
        "videos/category/BBC" to "BBC",
        "videos/category/Big" to "Big Tits",
        "videos/category/Creampie" to "Creampie",
        "videos/category/Incest" to "Incest",
        "videos/category/MILF" to "MILF",
        "videos/category/Threesome" to "Threesome",
        "videos/category/4K" to "4K"
    )

    private fun categoryUrl(path: String, page: Int): String {
        val base = "$mainUrl/" + path.trim('/')
        return if (page > 1) "$base?page_id=$page" else base
    }

    // ── Listings ───────────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val html = EightKPornerBrowser.html(categoryUrl(request.data, page), "$mainUrl/")
            ?: return newHomePageResponse(request.name, emptyList(), false)
        val items = cards(Jsoup.parse(html))
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        if (q.isBlank()) return emptyList()

        val first = searchPage(q, 1)
        if (first.isEmpty()) return first
        val second = searchPage(q, 2)
        return (first + second).distinctBy { it.url }
    }

    private suspend fun searchPage(q: String, page: Int): List<SearchResponse> {
        val url = if (page > 1) {
            "$mainUrl/search?keyword=$q&page_id=$page"
        } else {
            "$mainUrl/search?keyword=$q"
        }
        val html = EightKPornerBrowser.html(url, "$mainUrl/") ?: return emptyList()
        return cards(Jsoup.parse(html))
    }

    private fun cards(doc: org.jsoup.nodes.Document): List<SearchResponse> =
        doc.select("a[href*=/watch/]")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("href").trim()
        if (!href.contains("/watch/")) return null

        // the image sits inside the anchor; the heading is usually its sibling in the card
        val img = selectFirst("img[src], img[data-src]")
            ?: parent()?.selectFirst("img[src]")
        val title = parent()?.selectFirst("h4, h3, .video-title")?.text()?.trim()?.ifBlank { null }
            ?: img?.attr("alt")?.trim()?.ifBlank { null }
            ?: attr("title").trim().ifBlank { null }
            ?: return null

        val poster = img?.let { it.attr("data-src").ifBlank { it.attr("src") } }
            ?.takeIf { it.isNotBlank() }
            ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    // ── Detail ─────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val html = EightKPornerBrowser.html(url, "$mainUrl/") ?: return null
        val doc = Jsoup.parse(html)

        val title = doc.selectFirst("h1")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Video"
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")
        val tags = doc.select("a[href*=/videos/category/], [class*=categor] a").map { it.text().trim() }
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
        val html = EightKPornerBrowser.html(data, "$mainUrl/") ?: return false

        val sources = jwSources(html)
        if (sources.isEmpty()) return false

        sources.forEach { (url, label) ->
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = if (label.isBlank()) name else "$name ($label)",
                    url = url,
                    type = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "Referer" to "$mainUrl/",
                        "User-Agent" to EightKPornerBrowser.userAgent()
                    )
                }
            )
        }
        return true
    }

    /**
     * Reads jwplayer_config's sources. The config is inline in the watch page and its sources
     * array is the only place the signed urls appear, so it is parsed rather than regexed for
     * file extensions - those urls have no extension at all.
     */
    private fun jwSources(html: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val start = html.indexOf("jwplayer_config")
        if (start < 0) return out

        // the config runs to the first "};" after it
        val end = html.indexOf("};", start).takeIf { it > start } ?: (start + 20000).coerceAtMost(html.length)
        val config = html.substring(start, end)

        val sourcesIdx = config.indexOf("\"sources\"")
        val block = if (sourcesIdx >= 0) config.substring(sourcesIdx) else config

        Regex("\"file\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(block).map { it.groupValues[1] }
            .filter { it.startsWith("http") }
            .forEach { url ->
                val label = Regex("\"label\"\\s*:\\s*\"([^\"]*)\"")
                    .find(block)?.groupValues?.get(1).orEmpty()
                out.add(url.replace("\\/", "/") to label)
            }

        // labels are per source, so pair them in order when the counts line up
        val labels = Regex("\"label\"\\s*:\\s*\"([^\"]*)\"")
            .findAll(block).map { it.groupValues[1] }.toList()
        if (labels.size == out.size) {
            return out.mapIndexed { i, (u, _) -> u to labels[i] }.distinctBy { it.first }
        }
        return out.distinctBy { it.first }
    }
}
