package com.watchuu.mmshunt

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * MMSHunt (mmshunt.net) — built from the capture of 2026-11-09 14:15.
 *
 * An Invision Community forum, so the layout is its own:
 *
 *   listing   li.cForumRow -> a[href=/topic/<id>-<slug>/], thumbnail in .tthumb_wrap
 *   paging    path: /forum/<id>-<slug>/page/<n>
 *   search    /search/?&q=<query>&search_and_or=or
 *   detail    /topic/<id>-<slug>/  title from h1, poster og:image
 *
 * The video is never embedded. Each topic's first post is a list of links to file hosts, which is
 * what the capture shows:
 *
 *   https://d.torupload.com/…            https://krakenfiles.com/view/…/file.html
 *   https://vids.st/v/4007               https://luluvdo.com/…      (four of them)
 *   https://vidara.to/v/…                …
 *
 * so every external link in the post is handed to Cloudstream's own extractors rather than parsed
 * here - luluvdo, streamtape, vids.st and the rest each have one, and that is the only thing that
 * can turn those pages into a stream. Links to hosts with no extractor are dropped rather than
 * offered as dead entries, and anything that is already a direct mp4/m3u8 is taken as-is.
 */
class MmshuntProvider : MainAPI() {
    override var mainUrl = "https://mmshunt.net"
    override var name = "MMSHunt"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val cf = CloudflareKiller()

    companion object {
        /** set by the plugin so posters can be cached on disk */
        @Volatile
        var appContext: android.content.Context? = null
    }

    override val mainPage = mainPageOf(
        "forum/11-desi-latest-videos-hd-sd" to "Desi Latest Videos",
        "forum/12-desi-old-videos-hdsd" to "Desi Old Videos",
        "forum/15-desi-latest-semi-nude-masked-no-face-videospics" to "Semi Nude Masked",
        "forum/16-overseas-desi-videosphotos" to "Overseas Desi",
        "forum/17-desi-models-webcam-girls-lust-webmovies" to "Models & Webcam",
        "forum/18-live-streams-tango-videos" to "Live Streams & Tango"
    )

    private fun userAgent(): String = UA

    /** cookies the site has handed to this session; the image requests need them too */
    private val sessionCookies = java.util.concurrent.ConcurrentHashMap<String, String>()

    private suspend fun fetch(url: String, referer: String = "$mainUrl/"): String? = runCatching {
        val res = app.get(
            url,
            headers = mapOf(
                "User-Agent" to userAgent(),
                "Accept" to "text/html,application/xhtml+xml,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.9",
                "Referer" to referer
            ),
            interceptor = cf,
            timeout = 30L
        )
        res.cookies.forEach { (k, v) -> if (k.isNotBlank()) sessionCookies[k] = v }
        res.text
    }.getOrNull()?.takeIf { it.contains("<html", true) }

    // ── Listings ───────────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = "$mainUrl/${request.data.trim('/')}"
        val url = if (page > 1) "$base/page/$page" else base

        val html = fetch(url) ?: return newHomePageResponse(request.name, emptyList(), false)
        val items = cards(Jsoup.parse(html))
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        if (q.isBlank()) return emptyList()

        // Invision's search wants a csrf key in the url it generates, but answers ?q= on its own
        val first = fetch("$mainUrl/search/?&q=$q&search_and_or=or")
            ?.let { Jsoup.parse(it) }?.let { cards(it) }.orEmpty()
        if (first.isEmpty()) return first
        val second = fetch("$mainUrl/search/?&q=$q&search_and_or=or&page=2")
            ?.let { Jsoup.parse(it) }?.let { cards(it) }.orEmpty()
        return (first + second).distinctBy { it.url }
    }

    private suspend fun cards(doc: org.jsoup.nodes.Document): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()

        // plain loops rather than forEach: toSearchResult is suspend now, since it downloads the
        // poster to a local file, and a non-suspend lambda cannot call it
        for (row in doc.select("li.cForumRow, li.ipsDataItem, li.ipsStreamItem, .ipsDataItem")) {
            row.toSearchResult()?.let { out.add(it) }
        }

        // a search page may list bare topic links instead of rows
        if (out.isEmpty()) {
            for (a in doc.select("a[href*=/topic/]")) {
                a.toSearchResult()?.let { out.add(it) }
            }
        }
        return out.distinctBy { it.url }
    }

    /**
     * The tthumb plugin paints its thumbnail from a data attribute, not from an img:
     *
     *   <div class="tthumb_standard"
     *        data-background-src="https://mmshunt.net/uploads/monthly_2026_11/topic_45792.thumb.jpg...">
     *     <img ...>    <- injected later by the plugin, the 157x350 one
     *
     * The inline style holds only spacer.png, so reading style or src alone gives a placeholder.
     * data-background-src is asked for first, then a real img, then the style url, and the
     * ?do=tthumbGetImage endpoint only as a last resort.
     */
    private fun posterOf(card: Element): String? {
        card.selectFirst("[data-background-src]")
            ?.attr("data-background-src")?.trim()?.ifBlank { null }
            ?.let { return absolute(it) }

        card.selectFirst("img[data-src], img[src]")?.let { img ->
            listOf(img.attr("data-src"), img.attr("src"))
                .firstOrNull { it.isNotBlank() && !it.contains("spacer", true) && !it.startsWith("data:") }
                ?.let { return absolute(it) }
        }

        card.selectFirst("[style*=background-image]")?.attr("style")?.let { style ->
            Regex("""url\(\s*['"]?([^'")]+)['"]?\s*\)""").find(style)?.groupValues?.get(1)
                ?.takeIf { !it.contains("spacer", true) && !it.startsWith("data:") }
                ?.let { return absolute(it) }
        }

        card.selectFirst("[data-ipsHover-target]")?.attr("data-ipsHover-target")
            ?.trim()?.ifBlank { null }
            ?.let { return absolute(it) }

        return null
    }

    private fun absolute(url: String): String = when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("http") -> url
        else -> fixUrl(url)
    }

    /**
     * Posters are fetched here and handed to the app as a local file.
     *
     * The artwork sits behind the same Cloudflare as the pages, and whether the app's own image
     * loader applies posterHeaders is not something an extension can rely on - so instead of
     * pointing at a url that may be refused, the image is downloaded with the session's headers,
     * cached by its file name, and offered as file:///…. That removes the image request from the
     * app entirely: whatever the loader does, the bytes are already local.
     */
    private suspend fun posterFor(url: String?): String? = withContext(Dispatchers.IO) {
        if (url.isNullOrBlank()) return null
        if (!url.startsWith("http")) return url

        val ctx = appContext ?: return url
        val name = url.substringAfterLast('/').substringBefore('?')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { url.hashCode().toString() }
        val dir = File(ctx.cacheDir, "mmshunt_posters").apply { mkdirs() }
        val file = File(dir, name)
        if (file.exists() && file.length() > 1024) return "file://${file.absolutePath}"

        return runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = true
            posterHeaders().forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (conn.responseCode !in 200..299) return@runCatching url
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            if (bytes.size < 512) return@runCatching url
            file.writeBytes(bytes)
            "file://${file.absolutePath}"
        }.getOrDefault(url)
    }

    private fun posterHeaders(): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["User-Agent"] = userAgent()
        h["Referer"] = "$mainUrl/"
        h["Accept"] = "image/avif,image/webp,image/apng,image/*,*/*;q=0.8"
        h["Accept-Language"] = "en-US,en;q=0.9"

        // the images sit behind the same Cloudflare as the pages, so they need the session the
        // pages earned - cf_clearance and the ips4_* cookies. Those live in the http jar, not in
        // the WebView's CookieManager, so both are read and merged.
        val harvested = sessionCookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val jar = runCatching { android.webkit.CookieManager.getInstance().getCookie(mainUrl) }
            .getOrNull().orEmpty()
        val cookie = listOf(harvested, jar).filter { it.isNotBlank() }.joinToString("; ")
        if (cookie.isNotBlank()) h["Cookie"] = cookie
        return h
    }

    private suspend fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a[href*=/topic/]") ?: return null
        val href = a.attr("href").trim()
        if (href.isBlank()) return null

        val title = a.attr("alt").trim().ifBlank { null }
            ?: a.attr("title").trim().ifBlank { null }
            ?: selectFirst("h4, h3, .ipsDataItem_title")?.text()?.trim()?.ifBlank { null }
            ?: a.text().trim().ifBlank { null }
            ?: return null

        val poster = posterFor(posterOf(this))

        return newMovieSearchResponse(title, fixUrl(href), TvType.NSFW) {
            this.posterUrl = poster
            this.posterHeaders = posterHeaders()
        }
    }

    // ── Detail ─────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val html = fetch(url) ?: return null
        val doc = Jsoup.parse(html)

        val title = doc.selectFirst("h1")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.ifBlank { null }
            ?: url.trimEnd('/').substringAfterLast('/').replace('-', ' ')
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
            ?.let { posterFor(it) }
            ?: posterFor(posterOf(doc))
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()?.ifBlank { null }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = plot
            this.posterHeaders = posterHeaders()
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

        // the first post is the one carrying the links; later posts are chatter
        val post = doc.selectFirst("[data-role=commentContent]")
            ?: doc.selectFirst(".cPost_contentWrap")
            ?: doc

        val hosts = LinkedHashMap<String, String>() // url -> label
        post.select("a[href]").forEach { a ->
            val href = a.attr("href").replace("&amp;", "&").trim()
            if (!href.startsWith("http")) return@forEach
            if (href.contains("mmshunt.net", true)) return@forEach
            if (hosts.containsKey(href)) return@forEach
            hosts[href] = hostLabel(href)
        }

        // and any file the post points at directly
        Regex("""https?://[^\s"'<>\\]+\.(?:mp4|m3u8)[^\s"'<>\\]*""")
            .findAll(post.html())
            .map { it.value.replace("&amp;", "&") }
            .forEach { url -> if (!hosts.containsKey(url)) hosts[url] = "direct" }

        if (hosts.isEmpty()) return false

        var found = false
        hosts.forEach { (link, label) ->
            // a direct file needs no extractor
            if (Regex("""\.(mp4|m3u8)(\?|$)""", RegexOption.IGNORE_CASE).containsMatchIn(link)) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name · $label",
                        url = link,
                        type = if (link.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = data
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf(
                            "Referer" to data,
                            "User-Agent" to userAgent(),
                            "Accept" to "*/*"
                        )
                    }
                )
                found = true
                return@forEach
            }

            // everything else goes through Cloudstream's extractors
            val got = mutableListOf<ExtractorLink>()
            runCatching {
                loadExtractor(link, data, subtitleCallback) { got.add(it) }
            }
            got.forEach { extracted ->
                callback.invoke(
                    newExtractorLink(
                        source = extracted.source ?: name,
                        name = if (extracted.name.isBlank()) label else extracted.name,
                        url = extracted.url,
                        type = extracted.type
                    ) {
                        this.referer = extracted.referer.ifBlank { data }
                        this.quality = extracted.quality
                        this.headers = extracted.headers
                    }
                )
                found = true
            }
        }
        return found
    }

    private fun hostLabel(url: String): String =
        Regex("""^https?://([^/]+)""").find(url)?.groupValues?.get(1)
            ?.removePrefix("www.") ?: "link"

    private companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 16; SM-S911B Build/BP4A.251205.006) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.47 Mobile Safari/537.36"
    }
}
