package com.watchuu.latestpornvideo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * latestpornvideo.com — rebuilt from the capture of 2026-10-30 22:47.
 *
 * Structure, all confirmed in that capture:
 *   listing cards  article.loop-video.thumb-block, <a href="/1200033/" title="…">, img src webp
 *   paging         path: /page/2/ , and for search /page/2/?s=…
 *   detail         /{id}/  (numeric post id), two iframes
 *   iframe         https://luluvdo.com/e/<code>  or  https://luluvido.com/e/<code>
 *   embed page     a <script> with the classic packed player config:
 *                    eval(function(p,a,c,k,e,d){…}('…',62,N,'word|table'.split('|'),0,{}))
 *                  which unpacks to  sources:[{file:"https://…/master.m3u8?t=…"}]
 *                  (hanerix-style pages instead carry links = {"hls2":"…","hls3":"…"})
 *   streams        HLS on cdn*.cdn-tnmr.org, absolute urls with a token, Referer = the embed host
 *
 * So playback needs the packer unpacked, not a regex for a literal .m3u8 — the url simply is not
 * in the page until it is unpacked.
 */
class LatestPornVideoProvider : MainAPI() {
    override var mainUrl = "https://latestpornvideo.com"
    override var name = "LatestPornVideo"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "" to "Latest",
        "category/porn-movie/" to "Porn Movie",
        "category/jav/" to "JAV",
        "category/webcam/" to "Webcam"
    )

    private suspend fun fetch(url: String, referer: String = "$mainUrl/"): String? =
        LatestPornVideoBrowser.html(url, referer)

    // ── Listings ───────────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = mainUrl.trimEnd('/') + "/" +
            request.data.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        val url = if (page > 1) "${base}page/$page/" else base

        val doc = fetch(url)?.let { Jsoup.parse(it) }
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
        // Only numeric post urls; this drops tags, categories, dates and pagination links.
        if (!Regex("""/\d{4,}/?$""").containsMatchIn(href)) return null

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
            ?: doc.selectFirst("article img[src]")?.attr("src")
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")?.ifBlank { null }
            ?: doc.selectFirst("meta[name=description]")?.attr("content")
        val tags = doc.select("a[rel=tag], a[href*=/tag/]").map { it.text().trim() }
            .filter { it.isNotBlank() }.distinct().take(12)

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

        val fromDom = doc.select("iframe[src]")
            .map { it.attr("src").replace("&amp;", "&").trim() }
            .filter { it.startsWith("http") && !it.contains("latestpornvideo") }

        // Players are sometimes injected from JS, so the embed urls are also taken from the raw
        // source (where the slashes may be escaped).
        val fromSource = Regex(
            """(?:https?:)?(?:(?:\\?/){2})([a-z0-9.\-]*(?:luluvdo|luluvido|hanerix|hgcloud|lulustream)[a-z0-9.\-]*)(?:\\?/)(?:e|embed|video)(?:\\?/)([A-Za-z0-9]{6,})""",
            RegexOption.IGNORE_CASE
        ).findAll(html).map { m -> "https://${m.groupValues[1]}/e/${m.groupValues[2]}" }

        var found = false
        for (embed in (fromDom + fromSource).distinct()) {
            if (isAdHost(embed)) continue

            val playlists = resolveEmbed(embed).ifEmpty {
                mirrorEmbed(embed)?.let { resolveEmbed(it) }.orEmpty()
            }
            if (playlists.isEmpty()) continue

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
                        this.headers = mapOf("Referer" to referer)
                    }
                )
            }
            found = true
        }

        if (!found) {
            val direct = Regex("""https?://[^\s"'<>\\]+\.(?:m3u8|mp4)[^\s"'<>\\]*""")
                .findAll(html).map { it.value }
                .filter { !isAdHost(it) }
                .distinct()
            for (url in direct) {
                callback.invoke(
                    newExtractorLink(
                        name,
                        name,
                        url,
                        if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf("Referer" to "$mainUrl/")
                    }
                )
                found = true
            }
        }
        return found
    }

    /** luluvdo.com/e/<code> ↔ luluvido.com/e/<code> — the same code on the other host. */
    private fun mirrorEmbed(embedUrl: String): String? {
        val code = Regex("/e/([A-Za-z0-9]+)").find(embedUrl)?.groupValues?.get(1) ?: return null
        return when {
            embedUrl.contains("luluvdo.com") -> "https://luluvido.com/e/$code"
            embedUrl.contains("luluvido.com") -> "https://luluvdo.com/e/$code"
            else -> null
        }
    }

    private fun originOf(url: String): String =
        Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.get(1)?.plus("/") ?: "$mainUrl/"

    private fun isAdHost(url: String): Boolean {
        val u = url.lowercase()
        return listOf(
            "a-ads.com", "nucankaktop", "spleniidizzy", "doubleclick", "googlesyndication",
            "onclickads", "popads", "exoclick", "juicyads", "google-analytics", "cloudflareinsights",
            "69search.com", "magsrv.com"
        ).any { u.contains(it) }
    }

    /** Unpack the player config, then read the playlist urls out of it. */
    private suspend fun resolveEmbed(embedUrl: String): List<String> {
        val html = fetch(embedUrl, "$mainUrl/") ?: return emptyList()
        val js = unpackJs(html)
        val out = LinkedHashSet<String>()

        // luluvido / luluvdo: sources:[{file:"https://…/master.m3u8?t=…"}]
        Regex("""sources\s*:\s*\[[^]]*?file\s*:\s*["'](https?://[^"']+)["']""", RegexOption.DOT_MATCHES_ALL)
            .find(js)?.groupValues?.get(1)?.let { out.add(it) }

        // hanerix style: links = {"hls2":"…m3u8", "hls3":"…"} — hls2 is the real playlist.
        listOf("hls2", "hls4", "hls3").forEach { key ->
            val value = Regex(""""$key"\s*:\s*"([^"]+)"""").find(js)?.groupValues?.get(1) ?: return@forEach
            if (value.contains(".m3u8") || key == "hls2") out.add(value)
        }

        Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""").findAll(js).forEach { out.add(it.value) }

        return out.filter { it.startsWith("http") }.distinct()
    }

    /**
     * Dean Edwards packer: eval(function(p,a,c,k,e,d){…}('payload',62,N,'word|table'.split('|'),0,{}))
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
}
