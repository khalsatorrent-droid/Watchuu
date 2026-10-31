package com.watchuu.eightkporner

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * 8kporner.com — rebuilt from the capture of 2026-10-30 22:50.
 *
 * The important find in that capture is the playback source. The detail page carries a schema.org
 * VideoObject block:
 *
 *   <script type="application/ld+json">
 *   { "@type": "VideoObject",
 *     "name": "...", "description": "...", "thumbnailUrl": "https://8kporner.com/uploads/images/…",
 *     "uploadDate": "2026-10-24T16:00:00+00:00", "duration": "PT13M10S",
 *     "contentUrl": "https://vd196.okcdn.ru/?expires=…&sig=…",
 *     "embedUrl": "https://8kporner.com/embed/34114531" }
 *   </script>
 *
 * So `contentUrl` is the actual video (an OK.ru CDN link) and no player has to be reverse
 * engineered — which is what the earlier version got wrong: it only ever found the ad banner
 * 69search.com/…/banner.mp4 that sits on the page, because it never looked in the JSON-LD.
 *
 * Everything here goes through EightKPornerBrowser: the site answers a plain client with a
 * Cloudflare challenge and only renders for a browser.
 */
class EightKPornerProvider : MainAPI() {
    override var mainUrl = "https://8kporner.com"
    override var name = "8Kporner"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    // Sections the site itself lists on its home page in the capture.
    override val mainPage = mainPageOf(
        "amateur" to "Amateur",
        "anal" to "Anal",
        "bbc" to "BBC",
        "creampie" to "Creampie",
        "milf" to "MILF",
        "asian" to "Asian",
        "threesome" to "Threesome",
        "incest" to "Incest"
    )

    private suspend fun fetch(url: String): String? =
        EightKPornerBrowser.html(url, "$mainUrl/")

    // ── Listings ───────────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Path pagination, e.g. /videos/anal?page_id=2 (seen in the earlier capture).
        val base = "$mainUrl/videos/${request.data}"
        val url = if (page > 1) "$base?page_id=$page" else base

        val doc = fetch(url)?.let { Jsoup.parse(it) } ?: return newHomePageResponse(request.name, emptyList(), false)
        val items = cards(doc)
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        val doc = fetch("$mainUrl/search?keyword=$encoded")?.let { Jsoup.parse(it) } ?: return emptyList()
        val items = cards(doc)
        if (items.isNotEmpty()) return items
        // Older layout: results sit in their own container.
        return doc.select("div.pt_srch_vids div.keep-padding, div.pt_srch_vids > div")
            .mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    private fun cards(doc: org.jsoup.nodes.Document): List<SearchResponse> {
        val primary = doc.select("div.keep-padding").mapNotNull { it.toSearchResult() }
        if (primary.isNotEmpty()) return primary.distinctBy { it.url }

        // Second chance: build straight from the watch links, whatever the container class is.
        return doc.select("a[href*=/watch/]").mapNotNull { a ->
            val href = a.attr("href").trim().ifBlank { null } ?: return@mapNotNull null
            val title = a.attr("title").trim().ifBlank { null }
                ?: a.selectFirst("h4, h3, .title")?.text()?.trim()?.ifBlank { null }
                ?: a.selectFirst("img[alt]")?.attr("alt")?.trim()?.ifBlank { null }
                ?: return@mapNotNull null
            val poster = a.selectFirst("img[src], img[data-src]")
                ?.let { it.attr("data-src").ifBlank { it.attr("src") } }
                ?.takeIf { it.isNotBlank() }
                ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
            newMovieSearchResponse(title, fixUrl(href), TvType.NSFW) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleEl = selectFirst("h4") ?: selectFirst("h3, .title, [class*=title]") ?: return null
        val title = titleEl.text().trim().ifBlank { null } ?: return null

        val href = (titleEl.parent()?.takeIf { it.tagName() == "a" }?.attr("href"))
            ?: selectFirst("a[href]")?.attr("href")
            ?: return null
        if (!href.contains("/watch/") && !href.startsWith("http")) return null
        if (!href.contains("/watch/")) return null

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

        // The schema.org VideoObject block is the reliable source for every field.
        val ld = doc.selectFirst("script[type=application/ld+json]")?.data().orEmpty()

        val title = jsonValue(ld, "name")
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Video"
        val plot = jsonValue(ld, "description")
            ?: doc.selectFirst("meta[name=description]")?.attr("content")
        val poster = jsonValue(ld, "thumbnailUrl")
            ?: doc.selectFirst("meta[name=thumbnail]")?.attr("content")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        val tags = doc.select("a[href*=/videos/]").map { it.text().trim() }
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

        // contentUrl is the video; embedUrl is the player page (a second chance if needed).
        val direct = jsonValue(html, "contentUrl")?.takeIf { it.startsWith("http") }
        val embed = jsonValue(html, "embedUrl")?.takeIf { it.startsWith("http") }

        var found = false
        direct?.let { url ->
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = url,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf("Referer" to "$mainUrl/")
                }
            )
            found = true
        }

        if (!found && embed != null) {
            val playerHtml = fetch(embed).orEmpty()
            val url = jsonValue(playerHtml, "contentUrl")
                ?: Regex("""https?://[^\s"'<>\\]+\.(?:mp4|m3u8)[^\s"'<>\\]*""")
                    .find(playerHtml)?.value
            if (url != null && url.startsWith("http")) {
                callback.invoke(
                    newExtractorLink(name, name, url, ExtractorLinkType.VIDEO) {
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

    /** Pull a string field out of the page's JSON-LD without a JSON parser. */
    private fun jsonValue(html: String, field: String): String? =
        Regex("\"$field\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
            .find(html)?.groupValues?.get(1)
            ?.replace("\\/", "/")
            ?.replace("\\\"", "\"")
            ?.trim()
            ?.ifBlank { null }
}
