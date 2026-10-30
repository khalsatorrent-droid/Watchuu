package com.watchuu.eightkporner

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * 8kporner.com — 8K/4K/HD adult video site behind Cloudflare.
 *
 * Site structure (from log analysis):
 *  - Home sections:  /videos/latest, /videos/amateur, /videos/anal, /videos/bbc,
 *                    /videos/creampie, /videos/milf, /videos/asian, /videos/threesome
 *  - Listing cards:  div.keep-padding  (title: h4, poster: img[src])
 *  - Pagination:     ?page_id=N  (e.g. /videos/latest?page_id=2)
 *  - Search:         /search?keyword=<q>  (result container: div.pt_srch_vids)
 *  - Search paging:  /search?keyword=<q>&page_id=N
 *  - Detail page:    /watch/<slug>.html
 *                    title=h1, poster=og:image, plot=meta[description],
 *                    tags=[class*=categor] a
 *  - Episodes/parts: div.ra-thumb > a  (multiple quality/source versions)
 *  - Streams:        direct mp4 links inside the page, or iframes to embed hosts
 */
class EightKPornerProvider : MainAPI() {
    override var mainUrl = EightKPornerSettings.siteUrl
    override var name = "8KPorner"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)

    /** Every request goes through the Cloudflare bypass (saved cookie → hidden browser → solver). */
    private suspend fun fetch(url: String): String = EightKPornerBrowser.get(url, "$mainUrl/")

    // ── Home page sections ──────────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "videos/latest"    to "Latest",
        "videos/amateur"   to "Amateur",
        "videos/anal"      to "Anal",
        "videos/bbc"       to "BBC",
        "videos/creampie"  to "Creampie",
        "videos/milf"      to "MILF",
        "videos/asian"     to "Asian",
        "videos/threesome" to "Threesome",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Pagination: /videos/latest?page_id=2, /videos/latest?page_id=3, …
        val base = "$mainUrl/${request.data.trim('/')}"
        val url = if (page > 1) "$base?page_id=$page" else base

        val doc = Jsoup.parse(fetch(url))
        val items = doc.listingCards().mapNotNull { it.toSearchResult() }.distinctBy { it.url }

        // next page exists if we got results and a pagination link (or just if results are full)
        val hasNext = items.isNotEmpty() &&
            (doc.selectFirst("a.next, .pagination a[href*=page_id], li.next a") != null
                || items.size >= 20)

        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    // ── Search ──────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val doc = Jsoup.parse(fetch("$mainUrl/search?keyword=$q"))
        return doc.searchCards().mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    // ── Detail / load ───────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        val doc = Jsoup.parse(fetch(url))

        val title = (doc.selectFirst("h1")?.text()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: "Video").trim()

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")

        val plot = doc.selectFirst("meta[name=description]")?.attr("content")
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")

        val tags = doc.select("[class*=categor] a, [class*=tag] a")
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()

        // Check for multiple parts/sources (div.ra-thumb > a)
        val episodes = doc.select("div.ra-thumb > a, .ra-thumb a[href]")
            .map { it.attr("href") }.filter { it.startsWith("http") }.distinct()

        return if (episodes.size > 1) {
            // Present as a TvSeries with each episode being a quality/source version
            val eps = episodes.mapIndexed { i, epUrl ->
                val label = doc.select("div.ra-thumb > a, .ra-thumb a[href]").getOrNull(i)?.text()?.trim()
                    ?.ifBlank { null } ?: "Source ${i + 1}"
                newEpisode(epUrl) {
                    this.name = label
                    this.season = 1
                    this.episode = i + 1
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.NSFW, eps) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.NSFW, url) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        }
    }

    // ── Stream extraction ───────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = fetch(data)
        var found = false

        // 1. Direct mp4 / m3u8 / webm links in page source
        val directRe = Regex(
            """https?://[^\s"'<>\\]+\.(?:mp4|m3u8|webm)(?:[?#][^\s"'<>\\]*)?""",
            RegexOption.IGNORE_CASE
        )
        directRe.findAll(html).map { it.value.replace("\\/", "/") }
            .filter { !it.contains("ads") && !it.contains("magsrv") && !it.contains("imasdk") }
            .distinct()
            .forEach { link ->
                val isM3u8 = link.contains(".m3u8", true)
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = link,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
            }

        // 2. JWPlayer / MediaElement setup object
        val jwRe = Regex(
            """(?:"file"|'file'|sources\s*:)\s*[\[\{]?\s*["\{]?\s*(?:"file"\s*:\s*)?"(https?://[^"]+\.(?:mp4|m3u8|webm)[^"]*)"""",
            RegexOption.IGNORE_CASE
        )
        jwRe.findAll(html).forEach { m ->
            val link = m.groupValues[1].replace("\\/", "/")
            if (link.isNotBlank() && !found) {
                val isM3u8 = link.contains(".m3u8", true)
                callback.invoke(
                    newExtractorLink(name, name, link, if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
            }
        }

        // 3. Iframes → hand to Cloudstream's built-in extractors
        Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { m ->
                val src = m.groupValues[1].replace("&amp;", "&")
                if (src.startsWith("http") && !src.contains("8kporner") &&
                    !src.contains("challenges.cloudflare") && !src.contains("magsrv")
                ) {
                    if (loadExtractor(src, "$mainUrl/", subtitleCallback, callback)) found = true
                }
            }

        EightKPornerSettings.lastLinkReport =
            if (found) "✓ Links found for: $data" else "✗ No links found for: $data"

        return found
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Listing cards on category / home pages: div.keep-padding */
    private fun Document.listingCards(): List<Element> =
        select("div.keep-padding").ifEmpty { select("div[class*=thumb], article") }

    /** Search result cards: div.pt_srch_vids children */
    private fun Document.searchCards(): List<Element> =
        select("div.pt_srch_vids div.keep-padding")
            .ifEmpty { select("div.pt_srch_vids > div, div.pt_srch_vids article") }
            .ifEmpty { listingCards() }

    private fun Element.toSearchResult(): SearchResponse? {
        // Card title is in h4 inside the card
        val titleEl = selectFirst("h4") ?: selectFirst("h3, h2, .title, [class*=title]")
        val title = titleEl?.text()?.trim()?.ifBlank { null } ?: return null

        // Link — either the h4 is inside an <a>, or there's a direct thumb link
        val href = (titleEl.parent()?.takeIf { it.tagName() == "a" }?.attr("href"))
            ?: selectFirst("a[href]")?.attr("href")
            ?: return null
        if (!href.startsWith("http") && !href.startsWith("/")) return null

        val absHref = if (href.startsWith("http")) href else "$mainUrl$href"

        // Poster: first img in the card
        val poster = selectFirst("img[src]")?.attr("src")?.let { src ->
            if (src.startsWith("http")) src else "$mainUrl$src"
        }

        return newMovieSearchResponse(title, absHref, TvType.NSFW) {
            this.posterUrl = poster
        }
    }
}
