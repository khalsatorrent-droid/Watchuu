package com.watchuu.dropmms

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * DropMMS — an Invision Community forum. Every topic is one video post: the opening post (OP)
 * carries the poster image and the hoster links (LuluStream, StreamTape, DoodStream, …),
 * resolved with Cloudstream's built-in extractors.
 *
 *  - Home rows are forum sections, paged as /forum/{id}-{slug}/page/{n}/
 *  - Topic cards come from the "tthumb" gallery plugin: poster in [data-background-src]
 *  - Search is /search/?q=…&type=forums_topic&page={n}; several pages are merged
 *    (the paged search overload breaks installs on older Cloudstream builds)
 *  - Streams: every external hoster link / iframe in the OP only (replies are ignored)
 */
class DropMMSProvider : MainAPI() {
    override var mainUrl = "https://dropmms.com"
    override var name = "DropMMS"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val cf by lazy { CloudflareKiller() }

    override val mainPage = mainPageOf(
        "forum/2-desi-new-videoz-hd-sd/" to "Desi New Videos",
        "forum/49-watch-online-videoss/" to "Watch Online",
        "forum/31-desi-old-videoz-hd-sd/" to "Desi Old Videos",
        "forum/20-onlyfans-exclusive-leaks-%F0%9F%92%8E/" to "OnlyFans Exclusive Leaks",
        "forum/58-social-media-famous-viral-paid-collections-%F0%9F%92%8E/" to "Social Media Viral",
        "forum/5-overseas-desi-videoss-pics/" to "Overseas Desi",
        "forum/6-desi-models-webcam-girls-lust-web-moviess-here/" to "Models, Webcam & Lust Web Movies",
        "forum/4-desi-new-semi-nude-masked-no-face-videos-pics/" to "Semi-Nude / Masked",
        "forum/21-live-streams-tango-instagram-onlyfans/" to "Live Streams / Tango / Instagram",
        "forum/34-onlyfans-videos-pics/" to "OnlyFans",
        "forum/35-patreon-videos-pics/" to "Patreon",
        "forum/9-world-porn-videos/" to "World Porn",
        "forum/14-pornstar-videoss-pics/" to "Pornstars",
        "forum/64-faphouse-exclusive-videos/" to "Faphouse Exclusive",
        "forum/40-hollywood-hot-scenes/" to "Hollywood Hot Scenes",
        "forum/23-arab-porn-video-section/" to "Arab",
        "forum/28-lesbian-porn-videos-section/" to "Lesbian",
        "forum/29-gay-porn-videos/" to "Gay",
        "forum/54-trans-porn-videos-section/" to "Trans",
        "forum/63-hentai-porn-videos/" to "Hentai",
        "forum/62-anime-porn-videos/" to "Anime",
    )

    // ─── HTTP ───────────────────────────────────────────────────────────────

    private suspend fun getDoc(url: String, referer: String? = null): Document {
        val res = app.get(url, referer = referer ?: "$mainUrl/")
        val blocked = res.code == 403 || res.code == 503 ||
            res.text.contains("challenge-platform") && res.text.contains("cf-chl")
        return if (blocked) app.get(url, referer = referer ?: "$mainUrl/", interceptor = cf).document
        else res.document
    }

    // ─── Home ───────────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = "$mainUrl/${request.data}"
        val url = if (page <= 1) base else "${base}page/$page/"
        val doc = getDoc(url)
        val items = parseTopics(doc)
        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = true),
            hasNext = items.isNotEmpty() && hasNextPage(doc, page),
        )
    }

    private fun hasNextPage(doc: Document, page: Int): Boolean {
        val next = doc.selectFirst("li.ipsPagination_next") ?: return false
        if (next.hasClass("ipsPagination_inactive")) return false
        val total = doc.selectFirst("ul.ipsPagination[data-pages]")?.attr("data-pages")?.toIntOrNull()
        return total == null || page < total
    }

    // ─── Search ─────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val first = searchPage(q, 1)
        if (first.first.isEmpty() || first.second <= 1) return first.first
        val last = minOf(first.second, SEARCH_PAGES)
        val rest = coroutineScope {
            (2..last).map { p -> async { runCatching { searchPage(q, p).first }.getOrDefault(emptyList()) } }
                .awaitAll()
        }
        return (first.first + rest.flatten()).distinctBy { it.url }
    }

    /** Returns the topics on one results page and the total number of pages. */
    private suspend fun searchPage(query: String, page: Int): Pair<List<SearchResponse>, Int> {
        val enc = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/search/?q=$enc&type=forums_topic&search_and_or=or&sortby=relevancy" +
            if (page > 1) "&page=$page" else ""
        val doc = getDoc(url)
        val total = doc.selectFirst("ul.ipsPagination[data-pages]")?.attr("data-pages")?.toIntOrNull() ?: 1
        return parseTopics(doc) to total
    }

    // ─── Card parsing (gallery grid, list rows and search stream items) ────

    private val topicRe = Regex("""(https?://[^/]+/topic/\d+[^/?#]*/?)""")

    private fun topicUrl(href: String): String? =
        topicRe.find(href)?.groupValues?.get(1)?.let { if (it.endsWith("/")) it else "$it/" }

    private fun parseTopics(doc: Document): List<SearchResponse> {
        val cards = doc.select(
            "div.tthumb_grid_item, li.ipsDataItem, li.ipsStreamItem, article.ipsStreamItem"
        )
        return cards.mapNotNull { it.toCard() }.distinctBy { it.url }
    }

    private fun Element.toCard(): SearchResponse? {
        val link = selectFirst(
            "div.tthumb_gal_title a[href*=/topic/], .ipsDataItem_title a[href*=/topic/], " +
                ".ipsStreamItem_title a[href*=/topic/], h4 a[href*=/topic/], h2 a[href*=/topic/]"
        ) ?: return null
        val url = topicUrl(link.absUrl("href")) ?: return null
        val title = link.attr("title").ifBlank { link.text() }.trim()
            .removePrefix("Go to").trim()
        if (title.isBlank()) return null
        return newMovieSearchResponse(title, url, TvType.NSFW) {
            posterUrl = cardPoster(this@toCard)
        }
    }

    private fun cardPoster(el: Element): String? {
        el.selectFirst("[data-background-src]")?.attr("data-background-src")
            ?.takeIf { it.isNotBlank() }?.let { return fixUrl(it) }
        // search snippets: first content image that isn't an avatar / emoji
        return el.select("img").firstOrNull { img ->
            img.closest(".ipsUserPhoto") == null && !img.hasClass("ipsEmoji")
        }?.let { img ->
            img.attr("data-src").ifBlank { img.attr("src") }
                .takeIf { it.isNotBlank() && !it.startsWith("data:") }?.let { fixUrl(it) }
        }
    }

    // ─── Topic page ─────────────────────────────────────────────────────────

    /** The opening post's content block. */
    private fun opContent(doc: Document): Element? =
        doc.selectFirst("article.cPost div[data-role=commentContent]")
            ?: doc.selectFirst("div[data-role=commentContent]")
            ?: doc.selectFirst("div.cPost_contentWrap")

    override suspend fun load(url: String): LoadResponse {
        val doc = getDoc(url)
        val title = doc.selectFirst("h1.ipsType_pageTitle span.ipsType_break, h1.ipsType_pageTitle")
            ?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.title().substringBefore(" - ")
        val op = opContent(doc)

        val opImage = op?.select("img")?.firstOrNull { img ->
            !img.hasClass("ipsEmoji") && img.closest(".ipsUserPhoto") == null
        }?.let { img ->
            // lightboxed images link to the full size file
            img.parent()?.takeIf { it.tagName() == "a" && it.attr("href").matches(IMG_RE) }?.absUrl("href")
                ?: img.attr("data-src").ifBlank { img.attr("src") }
        }?.takeIf { it.isNotBlank() && !it.startsWith("data:") }?.let { fixUrl(it) }

        val poster = opImage
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }

        val plot = op?.let { body ->
            body.clone().apply { select("a, script, style, iframe").remove() }.text()
                .replace(Regex("\\s+"), " ").trim().take(500)
        }?.ifBlank { null }

        val tags = doc.select("ul.ipsTags a[href*=/tags/] span, a.ipsTag span, ul.ipsTags a[href*=/tags/]")
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()

        val recs = doc.select("div.tthumb_grid_item").mapNotNull { it.toCard() }
            .filter { it.url != url }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            posterUrl = poster
            this.plot = plot
            this.tags = tags
            recommendations = recs
        }
    }

    // ─── Links ──────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val doc = getDoc(data)
        val op = opContent(doc) ?: return false

        val links = linkedSetOf<String>()
        op.select("iframe[src], iframe[data-embed-src], iframe[data-src]").forEach { f ->
            links += f.attr("data-embed-src").ifBlank { f.attr("data-src") }.ifBlank { f.attr("src") }
        }
        op.select("a[href]").forEach { links += it.absUrl("href") }
        op.select("video[src], video source[src]").forEach { links += it.absUrl("src") }
        // plain-text URLs that the editor didn't auto-link
        Regex("""https?://[^\s"'<>]+""").findAll(op.text()).forEach { links += it.value }

        // the same link often appears twice (auto-linked text + %-encoded href): dedupe on the decoded form
        val targets = links.map { it.trim().trimEnd('.', ',') }
            .filter { it.startsWith("http") && isStreamCandidate(it) }
            .map { normalizeMirror(it) }
            .distinctBy { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it).trimEnd('/') }

        var found = false
        val cb: (ExtractorLink) -> Unit = { found = true; callback(it) }
        coroutineScope {
            targets.map { link ->
                async {
                    runCatching {
                        // hoster pages can end in ".mp4" too (streamtape /v/{id}/name.mp4), so the
                        // extractors get first go and only unmatched media URLs are played directly
                        val handled = loadExtractor(link, data, subtitleCallback, cb)
                        val path = link.substringBefore('?').lowercase()
                        if (!handled && (path.endsWith(".mp4") || path.endsWith(".m3u8"))) {
                            cb(newExtractorLink(name, "$name Direct", link,
                                if (path.endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                                referer = data
                            })
                        }
                    }
                }
            }.awaitAll()
        }
        return found
    }

    /**
     * Posts use LuluStream mirrors (lulust.com, luluvid.com, luluvido.com, /e/ or /d/ paths).
     * They all serve the same file code, so point them at luluvido.com/{code}, which the plugin
     * registers as a mirror domain of Cloudstream's built-in LuluStream extractor.
     */
    private fun normalizeMirror(url: String): String {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return url
        val host = uri.host?.lowercase() ?: return url
        if (!host.contains("lulu")) return url
        val id = uri.path.split('/').filter { it.isNotBlank() && it != "e" && it != "d" }.lastOrNull()
            ?: return url
        return "https://luluvido.com/$id"
    }

    private fun isStreamCandidate(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return false
        if (host.endsWith("dropmms.com") || host.contains("dropmms.")) return false
        if (BLOCKED_HOSTS.any { host.contains(it) }) return false
        if (url.substringBefore('?').matches(IMG_RE)) return false
        return true
    }

    companion object {
        private const val SEARCH_PAGES = 4
        private val IMG_RE = Regex("""(?i).+\.(jpe?g|png|gif|webp|bmp)$""")

        /** Ad, image and social hosts that show up in posts but never carry a video. */
        private val BLOCKED_HOSTS = listOf(
            "handskeeping", "rstrix", "imagetwist", "imgbox", "pixhost", "postimg", "ibb.co",
            "imgur", "telegram", "t.me", "twitter", "x.com", "instagram", "facebook", "youtube",
            "google", "rapidgator", "nitroflare", "katfile", "mega.nz", "keep2share", "k2s.cc",
            "fboom", "tezfiles", "turbobit", "uploadgig", "ddownload", "fikper",
        )
    }
}
