package com.watchuu.fullvideosporn

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import org.json.JSONArray
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * FullVideosPorn (fullvideosporn.com) — built from the capture of 2026-11-08 16:31.
 *
 * Browsing is ordinary html:
 *
 *   listing   article.item > a[href=/en/video/<id>/<slug>/], img.thumb, title from h2 / a[title]
 *   paging    query: /en/videos.php?p=<n>        search  /en/videos.php?q=<query>&p=<n>
 *   detail    /en/video/<id>/<slug>/   title h1, poster og:image
 *
 * The file is not in the html, and it is deliberately awkward. The player is started with three
 * arguments, and the second one is a string:
 *
 *   window.initPlayer({…params…}, 'W3siZm9ybWF0IjoiX2xxLm1wNСIs…', null);
 *
 * That string is base64 of a json array, but some of its characters have been swapped for Cyrillic
 * lookalikes (А В С Е М …), so a plain decode fails. Un-swap them and it decodes to:
 *
 *   [{"format":"_lq.mp4","timelines_count":"38","timelines_interval":"30",
 *     "video_url":"L2dldF9maWxlLzEv…,ZD0xMTE5JmJyPTEzNyZ0aT0xNzk0MTM1NjUx","is_default":1}]
 *
 * and video_url is itself two base64 parts joined by a comma - the path, then the query:
 *
 *   /get_file/1/<hash>/752000/752877/752877_lq.mp4/     and     d=1119&br=137&ti=1794135651
 *
 * which together make https://fullvideosporn.com/get_file/1/<hash>/…/752877_lq.mp4/?d=…&br=…&ti=…
 *
 * Each entry is one quality - the page names them _lq / _hq / _vhq (LQ / HQ / VHQ in its own
 * qualityLabels map), and every entry present is offered, so a video that has HQ or VHQ shows those
 * rather than only the default.
 */
class FullvideospornProvider : MainAPI() {
    override var mainUrl = "https://fullvideosporn.com"
    override var name = "FullVideosPorn"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val cf = CloudflareKiller()

    override val mainPage = mainPageOf(
        "" to "Latest",
        "videos" to "Videos",
        "videos?s=pd" to "Top"
    )

    private fun userAgent(): String = UA

    private suspend fun fetch(url: String, referer: String = "$mainUrl/en/"): String? = runCatching {
        app.get(
            url,
            headers = mapOf(
                "User-Agent" to userAgent(),
                "Accept" to "text/html,application/xhtml+xml,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.9",
                "Referer" to referer
            ),
            interceptor = cf,
            timeout = 30L
        ).text
    }.getOrNull()?.takeIf { it.contains("<html", true) }

    // ── Listings ───────────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data.trim('/')
        val base = when {
            data.isEmpty() -> "$mainUrl/en/"
            data.contains('?') -> "$mainUrl/en/${data.substringBefore('?')}?${data.substringAfter('?')}"
            else -> "$mainUrl/en/$data.php"
        }
        val url = when {
            page <= 1 -> base
            base.contains('?') -> "$base&p=$page"
            else -> "$base?p=$page"
        }

        val html = fetch(url) ?: return newHomePageResponse(request.name, emptyList(), false)
        val items = cards(Jsoup.parse(html))
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        if (q.isBlank()) return emptyList()

        val first = fetch("$mainUrl/en/videos.php?q=$q&p=1")
            ?.let { Jsoup.parse(it) }?.let { cards(it) }.orEmpty()
        if (first.isEmpty()) return first
        val second = fetch("$mainUrl/en/videos.php?q=$q&p=2")
            ?.let { Jsoup.parse(it) }?.let { cards(it) }.orEmpty()
        return (first + second).distinctBy { it.url }
    }

    private fun cards(doc: org.jsoup.nodes.Document): List<SearchResponse> =
        doc.select("article.item, article")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a[href*=/video/]") ?: return null
        val href = a.attr("href").trim()
        if (href.isBlank()) return null

        val img = selectFirst("img")
        val title = selectFirst("h2")?.text()?.trim()?.ifBlank { null }
            ?: a.attr("title").trim().ifBlank { null }
            ?: img?.attr("alt")?.trim()?.ifBlank { null }
            ?: return null

        val poster = img?.let { it.attr("data-original").ifBlank { it.attr("src") } }
            ?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?.let { if (it.startsWith("http")) it else if (it.startsWith("//")) "https:$it" else fixUrl(it) }

        return newMovieSearchResponse(title, fixUrl(href), TvType.NSFW) {
            this.posterUrl = poster
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
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()?.ifBlank { null }

        val tags = doc.select("a[href*=/tag/], a[href*=/pornstar/], a[href*=/category/]")
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
        val files = playerFiles(html)
        if (files.isEmpty()) return false

        var found = false
        files.forEach { (fileUrl, label) ->
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name · $label",
                    url = fileUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = data
                    this.quality = qualityOf(label)
                    this.headers = mapOf(
                        "Referer" to data,
                        "Origin" to mainUrl,
                        "User-Agent" to userAgent(),
                        "Accept" to "*/*"
                    )
                }
            )
            found = true
        }
        return found
    }

    /**
     * Reads the second argument of window.initPlayer, un-obfuscates it, and turns each entry into a
     * playable url. Returns url to label pairs, one per quality the page offers.
     */
    private fun playerFiles(html: String): List<Pair<String, String>> {
        val blob = Regex("""\},\s*'([^']{20,})'\s*,\s*null\s*\)""").find(html)?.groupValues?.get(1)
            ?: return emptyList()

        val json = decodeBase64(blob) ?: return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()

        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            val raw = entry.optString("video_url").ifBlank { continue }

            val parts = raw.split(',')
            val path = decodeBase64(parts.getOrNull(0).orEmpty()) ?: continue
            val query = parts.getOrNull(1)?.let { decodeBase64(it) }
            if (!path.startsWith("/")) continue

            val url = buildString {
                append(mainUrl)
                append(path)
                if (!query.isNullOrBlank()) append("?").append(query)
            }

            val format = entry.optString("format")
            val label = when {
                format.contains("_vhq", true) -> "VHQ"
                format.contains("_hq", true) -> "HQ"
                format.contains("_lq", true) -> "LQ"
                else -> format.ifBlank { "auto" }
            }
            out.add(url to label)
        }
        return out.distinctBy { it.first }
    }

    /**
     * The blob's alphabet has been swapped for Cyrillic lookalikes, so the characters have to be
     * put back before it will decode at all.
     */
    private val homoglyphs = mapOf(
        'А' to 'A', 'В' to 'B', 'С' to 'C', 'Е' to 'E', 'Н' to 'H', 'К' to 'K',
        'М' to 'M', 'О' to 'O', 'Р' to 'P', 'Т' to 'T', 'Х' to 'X', 'І' to 'I',
        'ѕ' to 's', 'ԛ' to 'q', 'а' to 'a', 'е' to 'e', 'о' to 'o', 'р' to 'p',
        'с' to 'c', 'у' to 'y', 'х' to 'x', 'ј' to 'j'
    )

    private fun decodeBase64(input: String): String? {
        if (input.isBlank()) return null
        val normalised = input.map { homoglyphs[it] ?: it }.joinToString("")
        val padded = when (normalised.length % 4) {
            0 -> normalised
            2 -> "$normalised=="
            3 -> "$normalised="
            else -> return null
        }
        return runCatching {
            String(android.util.Base64.decode(padded, android.util.Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun qualityOf(label: String): Int = when (label.uppercase()) {
        "VHQ" -> Qualities.P1080.value
        "HQ" -> Qualities.P720.value
        "LQ" -> Qualities.P480.value
        else -> Qualities.Unknown.value
    }

    private companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 16; SM-S911B Build/BP4A.251205.006) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.47 Mobile Safari/537.36"
    }
}
