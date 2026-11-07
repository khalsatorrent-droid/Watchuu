package com.watchuu.xvideotube

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
class XVideoTubeProvider : MainAPI() {
    override var mainUrl = "https://x-video.tube"
    override var name = "XVideoTube"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW, TvType.Movie)

    override val mainPage = mainPageOf(
        "" to "Latest",
        "video" to "Videos",
        "albums" to "Albums"
    )

    private fun userAgent(): String = UA

    /**
     * Every page goes through XVideoTubeBrowser, which owns the Cloudflare ladder and the settings
     * screen. The capture's detail fetch carried cf_clearance, so the site does challenge - a
     * plain request from a fresh install gets an interstitial with no player config in it, which
     * is exactly why the link list came back empty.
     */
    private suspend fun fetch(url: String, referer: String = "$mainUrl/"): String? {
        val body = XVideoTubeBrowser.html(url, referer) ?: return null
        return if (looksLikePage(body)) body else null
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

        // 1. the player config, as the capture shows it:
        //    video_url:        .../get_file/11/<hash>/<id>/<id>_single_hd_vertical.mp4/?v-acctoken=...
        //    event_reporting2: .../get_file/11/<hash>/<id>/<id>.mp4/?v-acctoken=...   (the original)
        //    postfix / video_url_fhd describe the variant, so they label it
        val fromConfig = LinkedHashMap<String, String>()

        val variant = flashValue(html, "postfix")
            ?.removePrefix("_")?.removeSuffix(".mp4")?.replace('_', ' ')?.trim()
            ?.ifBlank { null }
        val isFhd = flashValue(html, "video_url_fhd") == "1"

        flashValue(html, "video_url")?.takeIf { it.startsWith("http") }?.let { url ->
            val label = when {
                isFhd -> "FHD"
                variant != null -> variant
                else -> "HD"
            }
            if (!fromConfig.containsKey(url)) fromConfig[url] = label
        }

        flashValue(html, "event_reporting2")?.takeIf { it.startsWith("http") && it.contains("/get_file/") }
            ?.let { url -> if (!fromConfig.containsKey(url)) fromConfig[url] = "original" }

        // 2. any other file url on the page, never a hover preview
        Regex("""https?://[^\s"'<>\\]+/get_file/[^\s"'<>\\]*?\.mp4/?[^\s"'<>\\]*""")
            .findAll(html)
            .map { it.value }
            .filter { !it.contains("_preview", true) && !it.contains("/preview", true) }
            .forEach { url -> if (!fromConfig.containsKey(url)) fromConfig[url] = qualityFromName(url) }

        if (fromConfig.isEmpty()) return false

        val referer = data
        // ask each link for its first byte; prefer what answered, but never end up with nothing
        // just because the check itself was refused
        val verified = ArrayList<Pair<String, String>>()
        val unverified = ArrayList<Pair<String, String>>()
        fromConfig.forEach { (url, label) ->
            val clean = url.replace("&amp;", "&")
            val playable = resolvePlayable(clean, referer)
            if (playable != null) verified.add(playable to label) else unverified.add(clean to label)
        }
        val toEmit = if (verified.isNotEmpty()) verified else unverified
        var emitted = false

        toEmit.forEach { (playable, label) ->
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name · $label",
                    url = playable,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = qualityOf(label)
                    val h = LinkedHashMap<String, String>()
                    h["Referer"] = referer
                    h["Origin"] = mainUrl
                    h["User-Agent"] = userAgent()
                    h["Accept"] = "*/*"
                    cookieHeader()?.let { h["Cookie"] = it }
                    this.headers = h
                }
            )
            emitted = true
        }
        return emitted
    }

    /** Reads `key: 'value'` (or "value") out of the page's player config. */
    private fun flashValue(html: String, key: String): String? =
        Regex("""$key\s*:\s*['"]([^'"]+)['"]""")
            .find(html)?.groupValues?.get(1)?.replace("\\/", "/")?.trim()?.ifBlank { null }

    /**
     * The cookie the file request needs, built exactly as the browser builds it for pages:
     * whatever was pasted in settings, plus anything the app's jar holds for the site.
     *
     * This was missing: the page requests carried the cookie but the emitted stream links did
     * not, so a link could be resolved and then be refused when the player asked for the bytes.
     * kt_acctoken is the part that matters - it is what makes ?v-acctoken= on the file url valid.
     */
    /**
     * Asks for the first byte of a link, following redirects, and returns the url that actually
     * serves the file - or null if the site refuses it.
     *
     * This matters because a KVS file url is not the file. It 302s to remote_control.php with a
     * per-request token, which 302s again to the real object, and any of those hops can refuse a
     * request that lacks the referer or the session cookie. Handing the player the final url, and
     * only when it answered, means a link in the list is one that has been shown to work rather
     * than one that merely looked right.
     */
    private suspend fun resolvePlayable(url: String, referer: String): String? {
        val headers = LinkedHashMap<String, String>()
        headers["Referer"] = referer
        headers["Origin"] = mainUrl
        headers["User-Agent"] = userAgent()
        headers["Accept"] = "*/*"
        headers["Range"] = "bytes=0-0"
        cookieHeader()?.let { headers["Cookie"] = it }

        val res = runCatching {
            app.get(url, headers = headers, allowRedirects = true, timeout = 25L)
        }.getOrNull() ?: return null

        if (res.code !in 200..299) return null

        val type = res.headers["content-type"].orEmpty().lowercase()
        val finalUrl = res.url
        val looksMedia = type.startsWith("video/") || type.startsWith("audio/") ||
            type.contains("octet-stream") || finalUrl.contains(".mp4", true)
        return if (looksMedia) finalUrl else null
    }

    private fun cookieHeader(): String? {
        val manual = XVideoTubeSettings.cookie
        val jar = runCatching {
            android.webkit.CookieManager.getInstance().getCookie("$mainUrl/")
        }.getOrNull().orEmpty()
        return listOf(manual, jar).filter { it.isNotBlank() }
            .joinToString("; ").ifBlank { null }
    }

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
