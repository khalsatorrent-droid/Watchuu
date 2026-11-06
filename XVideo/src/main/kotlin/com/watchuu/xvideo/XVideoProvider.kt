package com.watchuu.xvideo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * x-video.tube — built from the capture of 2026-11-06 22:04.
 *
 * A KVS site (its cookies are kt_referer / kt_acctoken / kt_tcookie, and the player is kt_player),
 * so the layout follows that family:
 *
 *   listing   div.item.thumb > a[href=/video/<id>/<slug>/], img[data-src], title from h3 / a[title]
 *   paging    path: /video/<page>/
 *   search    /search/<query>/   (page 2 as /search/<query>/2/ in this family)
 *   detail    /video/<id>/<slug>/
 *
 * Links come from the detail page's player config, which KVS writes one of two ways:
 *
 *   var tf1a2b3c = { video_url: '...mp4/?v-acctoken=<token>', video_alt_url: '...', ... }
 *   flashvars = { ... }                     (older skins)
 *
 * and the file itself always lives under /get_file/. Both are read here, in quality order, with
 * the v-acctoken variant preferred because the bare file url is refused without it. Hover
 * previews also use /get_file/ - they are named *_preview_*.mp4 and are filtered out, since
 * offering one as a stream is how a "plays someone else's clip" bug happens.
 *
 * Note: the capture browsed listings and search but never opened a video, so the detail side is
 * written from the KVS pattern rather than from a page in the log. If a title comes back with no
 * link, the detail page's player config is the thing to capture next.
 */
class XVideoProvider : MainAPI() {
    override var mainUrl = "https://x-video.tube"
    override var name = "XVideo"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW, TvType.Movie)

    override val mainPage = mainPageOf(
        "" to "Latest",
        "video" to "Videos",
        "albums" to "Albums"
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
        if (plain != null && plain.contains("<html", true) && looksLikePage(plain)) return plain

        return runCatching {
            app.get(url, headers = headers, interceptor = CloudflareKiller(), timeout = 40L).text
        }.getOrNull()?.takeIf { looksLikePage(it) }
    }

    private fun looksLikePage(body: String): Boolean =
        body.contains("item thumb", true) || body.contains("kt_player", true) ||
            body.contains("video_url", true) || body.contains("get_file", true)

    private fun pageUrl(path: String, page: Int): String {
        val base = path.trim('/')
        return when {
            page <= 1 && base.isEmpty() -> "$mainUrl/"
            page <= 1 -> "$mainUrl/$base/"
            base.isEmpty() -> "$mainUrl/video/$page/"
            else -> "$mainUrl/$base/$page/"
        }
    }

    // ── Listings ───────────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val html = fetch(pageUrl(request.data, page))
            ?: return newHomePageResponse(request.name, emptyList(), false)
        val items = cards(Jsoup.parse(html))
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim().replace(' ', '-'), "UTF-8")
        if (q.isBlank()) return emptyList()

        val first = fetch("$mainUrl/search/$q/")?.let { Jsoup.parse(it) }?.let { cards(it) }.orEmpty()
        if (first.isEmpty()) return first
        val second = fetch("$mainUrl/search/$q/2/")?.let { Jsoup.parse(it) }?.let { cards(it) }.orEmpty()
        return (first + second).distinctBy { it.url }
    }

    private fun cards(doc: org.jsoup.nodes.Document): List<SearchResponse> =
        doc.select("div.item.thumb, div.item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a[href]") ?: return null
        val href = a.attr("href").trim()
        if (href.isBlank() || href.endsWith("/video/") || href.contains("/search/")) return null

        val img = selectFirst("img")
        val title = selectFirst("h3")?.text()?.trim()?.ifBlank { null }
            ?: a.attr("title").trim().ifBlank { null }
            ?: img?.attr("alt")?.trim()?.ifBlank { null }
            ?: return null

        val poster = img?.let { it.attr("data-src").ifBlank { it.attr("src") } }
            ?.takeIf { it.isNotBlank() && !it.startsWith("data:") }

        return newMovieSearchResponse(title, fixUrl(href), TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    // ── Detail ─────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val html = fetch(url) ?: return null
        val doc = Jsoup.parse(html)

        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.ifBlank { null }
            ?: doc.selectFirst("h1")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("title")?.text()?.substringBefore('|')?.trim()
            ?: return null

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()?.ifBlank { null }
        val tags = doc.select("a[href*=/categories/], a[href*=/models/], a[href*=/tags/]")
            .map { it.text().trim() }.filter { it.isNotBlank() && it.length < 30 }.distinct().take(10)

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

        // 1. the player config: video_url / video_alt_url / video_alt_url2, each with its label
        val fromConfig = LinkedHashMap<String, String>()
        listOf(
            Triple("video_alt_url2", "video_alt_url2_text", "2160p"),
            Triple("video_alt_url", "video_alt_url_text", "720p"),
            Triple("video_url", "video_url_text", "480p")
        ).forEach { (urlKey, textKey, fallback) ->
            val url = flashValue(html, urlKey) ?: return@forEach
            if (!url.startsWith("http")) return@forEach
            val label = flashValue(html, textKey) ?: fallback
            if (!fromConfig.containsKey(url)) fromConfig[url] = label
        }

        // 2. any other file url on the page, never a hover preview
        Regex("""https?://[^\s"'<>\\]+/get_file/[^\s"'<>\\]*?\.mp4/?[^\s"'<>\\]*""")
            .findAll(html)
            .map { it.value }
            .filter { !it.contains("_preview_", true) }
            .forEach { url -> if (!fromConfig.containsKey(url)) fromConfig[url] = qualityFromName(url) }

        if (fromConfig.isEmpty()) return false

        val referer = data
        fromConfig.forEach { (url, label) ->
            val clean = url.replace("&amp;", "&")
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name · $label",
                    url = clean,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = qualityOf(label)
                    this.headers = mapOf(
                        "Referer" to referer,
                        "Origin" to mainUrl,
                        "User-Agent" to userAgent()
                    )
                }
            )
        }
        return true
    }

    /** Reads `key: 'value'` (or "value") out of the page's player config. */
    private fun flashValue(html: String, key: String): String? =
        Regex("""$key\s*:\s*['"]([^'"]+)['"]""")
            .find(html)?.groupValues?.get(1)?.replace("\\/", "/")?.trim()?.ifBlank { null }

    private fun qualityFromName(url: String): String = when {
        url.contains("2160", true) || url.contains("4k", true) -> "2160p"
        url.contains("1440", true) -> "1440p"
        url.contains("1080", true) -> "1080p"
        url.contains("720", true) -> "720p"
        url.contains("480", true) -> "480p"
        url.contains("360", true) -> "360p"
        else -> "auto"
    }

    private fun qualityOf(label: String): Int = when {
        label.contains("2160") || label.contains("4k", true) -> Qualities.P2160.value
        label.contains("1440") -> Qualities.P1440.value
        label.contains("1080") -> Qualities.P1080.value
        label.contains("720") -> Qualities.P720.value
        label.contains("480") -> Qualities.P480.value
        label.contains("360") -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }

    private companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 16; SM-S911B Build/BP4A.251205.006) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.47 Mobile Safari/537.36"
    }
}
