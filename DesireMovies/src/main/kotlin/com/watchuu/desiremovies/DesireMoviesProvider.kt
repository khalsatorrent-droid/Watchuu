package com.watchuu.desiremovies

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * 1DesireMovies (1desiremovies.lat) — built from the capture of 2026-11-05 15:18.
 *
 * A Bollywood/Hollywood download index, and the chain to a playable link is three hops:
 *
 *   1. the detail page prints each version with its quality, then a link to a link-protector:
 *        <p><strong>HQ 1080p [9.9 GB]</strong></p>
 *        <p><a href="https://gyanigurus.online/view/6aeb2732af37b">GD & DOWNLOAD</a></p>
 *        <p><strong>720p [1.2 GB]</strong></p>
 *        <p><a href="https://gyanigurus.online/view/6aeb270c94fc4">GD & DOWNLOAD</a></p>
 *      so the quality is the text just before the link, which is how the two are paired here.
 *
 *   2. that gyanigurus page lists the hosts it protects, four per quality:
 *        https://hubcloud.ist/drive/<id>          hubcloud
 *        https://hubdrive.bar/file/<id>           hubdrive
 *        https://gdflix.vip/file/<id>             gdflix
 *        https://new3.multicloudlinks.com/view/<id>
 *
 *   3. those are handed to Cloudstream's own extractors — hubcloud, hubdrive and gdflix all have
 *      one, and they are the ones that know how to turn a file page into a stream.
 *
 * The quality label from hop 1 is carried onto whatever hop 3 returns, so a 2160p entry is
 * listed as 2160p rather than as an anonymous link.
 */
class DesireMoviesProvider : MainAPI() {
    override var mainUrl = "https://1desiremovies.lat"
    override var name = "1DesireMovies"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "" to "Latest",
        "bollywood-movies-desiremovie" to "Bollywood Movies",
        "south-movieshindi" to "South Movies (Hindi)",
        "hollywood-movies-download" to "Hollywood Movies",
        "web-series" to "Web Series",
        "tv-show" to "TV Shows",
        "4k-movies" to "4K Movies",
        "gujarati-movies" to "Gujarati Movies",
        "punjabi-movies" to "Punjabi Movies",
        "bhojpuri-movies" to "Bhojpuri Movies",
        "korean-movie-hindi" to "Korean Movie (Hindi)",
        "old-is-gold-movies" to "Old Is Gold"
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
        if (plain != null && plain.contains("<html", true) && plain.contains("mh-loop-thumb", true)) {
            return plain
        }
        if (plain != null && plain.contains("<html", true) && !plain.contains("Just a moment", true) &&
            !plain.contains("challenge-platform", true)
        ) {
            return plain
        }
        return runCatching {
            app.get(url, headers = headers, interceptor = CloudflareKiller(), timeout = 40L).text
        }.getOrNull()
    }

    private fun pageUrl(path: String, page: Int): String {
        val base = "$mainUrl/" + path.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        return if (page > 1) "${base}page/$page/" else base
    }

    // ── Listings ───────────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val html = fetch(pageUrl(request.data, page))
            ?: return newHomePageResponse(request.name, emptyList(), false)
        val items = cards(Jsoup.parse(html))
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        if (q.isBlank()) return emptyList()

        val first = fetch("$mainUrl/?s=$q")?.let { Jsoup.parse(it) }?.let { cards(it) }.orEmpty()
        if (first.isEmpty()) return first
        val second = fetch("$mainUrl/page/2/?s=$q")?.let { Jsoup.parse(it) }?.let { cards(it) }.orEmpty()
        return (first + second).distinctBy { it.url }
    }

    private fun cards(doc: org.jsoup.nodes.Document): List<SearchResponse> =
        doc.select("figure.mh-loop-thumb, article.mh-loop-item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a[href]") ?: return null
        val href = a.attr("href").trim()
        if (href.isBlank() || href.contains("?s=") || href.contains("/page/")) return null

        val img = selectFirst("img[src], img[data-src]")
        val title = img?.attr("alt")?.trim()?.ifBlank { null }
            ?: selectFirst("h2, h3, .entry-title")?.text()?.trim()?.ifBlank { null }
            ?: a.attr("title").trim().ifBlank { null }
            ?: return null

        val poster = img?.let { it.attr("data-src").ifBlank { it.attr("src") } }
            ?.takeIf { it.isNotBlank() }
            ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    // ── Detail ─────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val html = fetch(url) ?: return null
        val doc = Jsoup.parse(html)

        val title = doc.selectFirst("h1")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Movie"
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
        val plot = doc.select(".entry-content p").map { it.text().trim() }
            .filter { it.length > 40 }.take(3).joinToString("\n\n").ifBlank { null }
        val tags = doc.select("a[href*=/category/], [class*=categor] a").map { it.text().trim() }
            .filter { it.isNotBlank() && it.length < 30 }.distinct().take(12)

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
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

        // hop 1: quality label followed by the link-protector url
        val protected = protectedLinks(html)
        if (protected.isEmpty()) return false

        var found = false

        protected.forEach { (quality, protectUrl) ->
            // hop 2: the protector page lists the hosts it holds
            val protectHtml = fetch(protectUrl, "$mainUrl/") ?: return@forEach
            val hosts = Regex("<a[^>]*class=\"hover_a link\"[^>]*href=\"([^\"]+)\"")
                .findAll(protectHtml)
                .map { it.groupValues[1].replace("&amp;", "&").trim() }
                .filter { it.startsWith("http") }
                .distinct()
                .toList()

            hosts.forEach { hostUrl ->
                // hop 3: hubcloud / hubdrive / gdflix are all Cloudstream extractors
                val got = mutableListOf<ExtractorLink>()
                runCatching {
                    loadExtractor(hostUrl, "$mainUrl/", subtitleCallback) { got.add(it) }
                }
                if (got.isNotEmpty()) {
                    got.forEach { link ->
                        callback.invoke(
                            newExtractorLink(
                                source = link.source ?: name,
                                name = "${link.name} · $quality",
                                url = link.url,
                                type = link.type
                            ) {
                                this.referer = link.referer.ifBlank { "$mainUrl/" }
                                this.quality = qualityOf(quality, link.quality)
                                this.headers = link.headers
                            }
                        )
                    }
                    found = true
                }
            }
        }

        return found
    }

    /**
     * Pairs each quality with the protector link that follows it. The page prints the version as
     * text ("HQ 1080p [9.9 GB]") and the link right after, so walking the html once and carrying
     * the last quality seen is enough - and it is what the markup actually encodes.
     */
    private fun protectedLinks(html: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var quality = ""
        Regex(
            "\\b(2160p|4k|1440p|1080p|720p|480p|360p)\\b|href=\"(https://gyanigurus\\.online/view/[^\"]+)\"",
            RegexOption.IGNORE_CASE
        )
            .findAll(html)
            .forEach { m ->
                if (m.groupValues[1].isNotBlank()) {
                    quality = m.groupValues[1].lowercase()
                } else if (m.groupValues[2].isNotBlank()) {
                    out.add((quality.ifBlank { "auto" }) to m.groupValues[2])
                }
            }
        return out.distinctBy { it.second }
    }

    /** Turns the page's label into a Cloudstream quality, keeping the extractor's if it had one. */
    private fun qualityOf(label: String, fallback: Int): Int = when (label.lowercase()) {
        "2160p", "4k" -> Qualities.P2160.value
        "1440p" -> Qualities.P1440.value
        "1080p" -> Qualities.P1080.value
        "720p" -> Qualities.P720.value
        "480p" -> Qualities.P480.value
        "360p" -> Qualities.P360.value
        else -> fallback
    }

    private companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 16; SM-S911B Build/BP4A.251205.006) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.47 Mobile Safari/537.36"
    }
}
