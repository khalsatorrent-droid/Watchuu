package com.watchuu.taboodaddy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * taboodaddy.com — built from the capture of 2026-10-31 23:08.
 *
 * A WordPress tube that answers a plain client directly (the capture's category page is a 200
 * with no challenge), so there is no browser machinery here on purpose.
 *
 *   listing   div.video-block[data-post-id] > a.thumb[href], img[data-src], .title
 *   paging    path: /page/N/
 *   detail    /{slug}_{id} or /{slug}
 *   video     the page carries it in a meta tag, which is the whole trick:
 *               <meta itemprop="contentUrl" content="https://video.twimg.com/amplify_video/…/pl/….m3u8">
 *             with thumbnailUrl and duration alongside it. The videos are hosted by Twitter's CDN,
 *             so what plays is that HLS master, not anything the page's own player builds.
 *   player    an iframe to /wp-content/plugins/clean-tube-player/public/player-x.php?q=<base64>
 *             which is only a wrapper around the same url - kept as a fallback for the odd post
 *             that has no contentUrl.
 */
class TaboodaddyProvider : MainAPI() {
    override var mainUrl = "https://taboodaddy.com"
    override var name = "Taboodaddy"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "" to "Latest",
        "category/taboo" to "Taboo",
        "category/brazzers" to "Brazzers",
        "category/bangbros" to "BangBros",
        "category/naughtyamerica" to "Naughty America",
        "category/realitykings" to "Reality Kings",
        "category/mom-son-porn" to "Mom & Son",
        "category/brother-sister-porn" to "Brother & Sister"
    )

    private fun userAgent(): String = UA

    private suspend fun fetch(url: String, referer: String = "$mainUrl/"): String? =
        runCatching {
            app.get(
                url,
                headers = mapOf(
                    "User-Agent" to userAgent(),
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "en-US,en;q=0.9",
                    "Referer" to referer
                ),
                timeout = 25L
            ).text
        }.getOrNull()?.takeIf { it.contains("<html", true) }

    // ── Listings ───────────────────────────────────────────────────────────────

    private fun pageUrl(path: String, page: Int): String {
        val base = "$mainUrl/" + path.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        return if (page > 1) "${base}page/$page/" else base
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = fetch(pageUrl(request.data, page))?.let { Jsoup.parse(it) }
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
        doc.select("div.video-block, div.thumb-block, article.loop-video")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a.thumb[href], a[href]") ?: return null
        val href = a.attr("href").trim()
        if (href.isBlank()) return null
        if (href.contains("/category/") || href.contains("/page/") || href.contains("/tag/")) return null
        if (href.contains("?s=") || href.contains("/feed")) return null

        val title = selectFirst(".title, h2, h3, .entry-title")?.text()?.trim()?.ifBlank { null }
            ?: a.attr("title").trim().ifBlank { null }
            ?: selectFirst("img[alt]")?.attr("alt")?.trim()?.ifBlank { null }
            ?: return null

        val poster = selectFirst("img[data-src], img[src], img[data-lazy-src]")
            ?.let {
                it.attr("data-src").ifBlank {
                    it.attr("data-lazy-src").ifBlank { it.attr("src") }
                }
            }
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
        val poster = doc.selectFirst("meta[itemprop=thumbnailUrl]")?.attr("content")?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
        val plot = doc.selectFirst("meta[itemprop=description]")?.attr("content")?.ifBlank { null }
            ?: doc.selectFirst("[class*=description]")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[name=description]")?.attr("content")
        val tags = doc.select("a[rel=tag], a[href*=/tag/], [class*=categor] a").map { it.text().trim() }
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

        // 1. the video the page declares about itself
        val contentUrl = doc.selectFirst("meta[itemprop=contentUrl]")?.attr("content")
            ?.replace("&amp;", "&")?.trim()
            ?.takeIf { it.startsWith("http") }

        var found = false
        if (contentUrl != null) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name (HLS)",
                    url = contentUrl,
                    type = if (contentUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
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

        // 2. any other playlist or file sitting in the page
        Regex("""https?://[^\s"'<>\\]+\.(?:m3u8|mp4)[^\s"'<>\\]*""")
            .findAll(html).map { it.value.replace("&amp;", "&") }
            .filter { it != contentUrl && !isAdHost(it) }
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

        // 3. the site's own player wrapper, for a post with no contentUrl of its own
        if (!found) {
            doc.select("iframe[src]")
                .map { it.attr("src").replace("&amp;", "&").trim() }
                .filter { it.startsWith("http") && !isAdHost(it) }
                .distinct()
                .forEach { embed ->
                    val viaExtractor = mutableListOf<ExtractorLink>()
                    runCatching {
                        loadExtractor(embed, "$mainUrl/", subtitleCallback) { viaExtractor.add(it) }
                    }
                    if (viaExtractor.isNotEmpty()) {
                        viaExtractor.forEach { callback.invoke(it) }
                        found = true
                    }
                }
        }

        return found
    }

    private fun isAdHost(url: String): Boolean {
        val u = url.lowercase()
        return listOf(
            "doubleclick", "googlesyndication", "google-analytics", "googletagmanager",
            "exoclick", "juicyads", "popads", "onclickads", "adsco.re", "magsrv",
            "gammacdn.com/500x281", "cloudflareinsights", "facebook.net", "twitter.com/i/ads"
        ).any { u.contains(it) }
    }

    private companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 16; SM-S911B Build/BP4A.251205.006) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.47 Mobile Safari/537.36"
    }
}
