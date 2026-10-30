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
 * Site structure (confirmed from live log 2026-10-30):
 *
 *  Listing pages:
 *    /videos/latest, /videos/amateur, /videos/anal, /videos/bbc,
 *    /videos/creampie, /videos/milf, /videos/asian, /videos/threesome
 *    Pagination: ?page_id=N  (canonical shows page_id=1 on page 1)
 *
 *  Card HTML (inside listing pages):
 *    <div class="col-md-3 col-sm-6 col-xs-6 keep-padding" data-id="…">
 *      <div class="video-list">
 *        <div class="video-list-image">
 *          <a href="https://…/watch/…">          ← link is on the <a> wrapping the thumb
 *            <img src="…">
 *          </a>
 *        </div>
 *        <div class="pt_vid_fixed_height">
 *          <div class="video-list-title">
 *            <a href="https://…/watch/…"><h4>Title</h4></a>   ← link also on the title <a>
 *          </div>
 *        </div>
 *      </div>
 *    </div>
 *
 *  FIX 1 — toSearchResult() was looking for h4.parent() == <a>, but the actual structure
 *           is: <a href="…"><h4>Title</h4></a>  so parent of h4 IS the <a>. That part was
 *           correct. BUT the card-level link extraction fell back to selectFirst("a[href]")
 *           which reliably picks up the THUMB link — these both point to the same URL, so
 *           it's fine. However, the card selector `div.keep-padding` also matches the
 *           RECOMMENDATION rows returned by the AJAX sidebar (class "col-md-3 col-sm-6
 *           col-xs-6 keep-padding") which link to *external* partner sites like
 *           hornyhill.st — not 8kporner watch pages. We must filter to only keep hrefs
 *           that belong to 8kporner.com itself.
 *
 *  Search result cards (div.pt_srch_vids):
 *    Same card HTML. The outer container is div.pt_srch_vids; the cards inside it are
 *    also div.keep-padding (no nesting issue in practice).
 *
 *  Detail page (/watch/…):
 *    title = h1
 *    poster = og:image
 *    plot   = meta[name=description]
 *    tags   = [class*=categor] a
 *    episode list = div.ra-thumb > a  (confirmed ×40 in log)
 *
 *  Streams:
 *    - Log confirmed only 1 stream found per page; it was a direct mp4 URL.
 *    - Site uses MediaElement.js player, NOT JWPlayer in rendered pages.
 *      JWPlayer entitlements.jwplayer.com was a CDN speculation-prefetch, not real usage.
 *    - Stream links may appear inside the page source as a `src` attribute on a <source>
 *      or <video> tag, OR as a JS string like `file:"https://…mp4"`.
 *    - Iframes to embed hosts (69search.com, etc.) are also present.
 *
 *  FIX 2 — The JWPlayer regex is wrong: the pattern `"file"` with surrounding structure
 *           doesn't match MediaElement.js source tags. Add a <source> / <video> tag
 *           regex to catch inline player markup.
 *
 *  FIX 3 — Pagination: the log shows page 1 canonical is `?page_id=1`, so starting at
 *           page=1 (not page=2) is correct when page > 1. But our code already does
 *           `if (page > 1)`, meaning page 1 hits the bare URL and page 2 hits `?page_id=2`.
 *           The site's canonical says page_id=1 on the first page — the bare URL redirects
 *           to page_id=1 internally. This is correct.
 *
 *  FIX 4 — hasNext detection: The log shows pages do have a next link, but the selector
 *           `a.next` etc. may not be present on 8kporner. Use items.size >= 20 heuristic
 *           only (already in the code). This is fine.
 *
 *  Cloudflare note from log:
 *    - OkHttp direct requests consistently got 503 "bot challenge" on detail pages.
 *    - The WebView hidden browser successfully loads pages (200 OK).
 *    - The extension's Browser.kt handles this correctly; no change needed there.
 */
class EightKPornerProvider : MainAPI() {
    override var mainUrl = EightKPornerSettings.siteUrl
    override var name = "8KPorner"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)

    /** Every request goes through the Cloudflare bypass. */
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
        val base = "$mainUrl/${request.data.trim('/')}"
        // Site uses ?page_id=N; page 1 = bare URL (canonical redirects to page_id=1)
        val url = if (page > 1) "$base?page_id=$page" else base

        val doc = Jsoup.parse(fetch(url))
        val items = doc.listingCards().mapNotNull { it.toSearchResult() }.distinctBy { it.url }

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

        // Confirmed in log: div.ra-thumb > a ×40 — multiple quality/source links
        val episodes = doc.select("div.ra-thumb > a, .ra-thumb a[href]")
            .map { it.attr("href") }
            .filter { it.startsWith("http") }
            .distinct()

        return if (episodes.size > 1) {
            val eps = episodes.mapIndexed { i, epUrl ->
                val label = doc.select("div.ra-thumb > a, .ra-thumb a[href]").getOrNull(i)
                    ?.text()?.trim()?.ifBlank { null } ?: "Source ${i + 1}"
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

        // 1. <video src="…"> or <source src="…"> / <source src='…'> tags
        //    These appear when MediaElement.js is used (confirmed in log).
        val sourceTagRe = Regex(
            """<(?:video|source)[^>]+src=["']([^"']+\.(?:mp4|m3u8|webm)[^"']*)["']""",
            RegexOption.IGNORE_CASE
        )
        sourceTagRe.findAll(html).forEach { m ->
            val link = m.groupValues[1].replace("\\/", "/")
            if (link.isNotBlank() && !isAdLink(link)) {
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
        }

        // 2. Direct mp4/m3u8/webm URLs anywhere in the page source (JS strings, data attrs…)
        //    Confirmed: log shows stream was an mp4 URL inside the page.
        val directRe = Regex(
            """https?://[^\s"'<>\\\u0000-\u001f]+\.(?:mp4|m3u8|webm)(?:[?#][^\s"'<>\\]*)?""",
            RegexOption.IGNORE_CASE
        )
        directRe.findAll(html).map { it.value.replace("\\/", "/") }
            .filter { !isAdLink(it) }
            .distinct()
            .forEach { link ->
                // Skip if already emitted via source tag extraction
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

        // 3. JWPlayer / generic JSON-ish "file":"url" patterns in JS
        //    Kept as fallback even though log didn't show it, in case some pages use it.
        val jwRe = Regex(
            """"file"\s*:\s*"(https?://[^"]+\.(?:mp4|m3u8|webm)[^"]*)"""",
            RegexOption.IGNORE_CASE
        )
        jwRe.findAll(html).forEach { m ->
            val link = m.groupValues[1].replace("\\/", "/")
            if (link.isNotBlank() && !isAdLink(link)) {
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

        // 4. Iframes → hand to Cloudstream's built-in extractors
        //    Log confirmed: 69search.com iframes present on detail pages.
        Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { m ->
                val src = m.groupValues[1].replace("&amp;", "&")
                if (src.startsWith("http") && !src.contains("8kporner") &&
                    !src.contains("challenges.cloudflare") && !isAdLink(src)
                ) {
                    if (loadExtractor(src, "$mainUrl/", subtitleCallback, callback)) found = true
                }
            }

        EightKPornerSettings.lastLinkReport =
            if (found) "✓ Links found for: $data" else "✗ No links found for: $data"

        return found
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Returns true for known ad/tracker domains we should never treat as video streams. */
    private fun isAdLink(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("magsrv") ||
            lower.contains("imasdk") ||
            lower.contains("ads") ||
            lower.contains("pemsrv") ||
            lower.contains("doubleclick") ||
            lower.contains("googlesyndication") ||
            lower.contains("banner")
    }

    /**
     * Listing cards on category / home / search pages.
     *
     * The actual card structure (confirmed from log):
     *   <div class="col-md-3 col-sm-6 col-xs-6 keep-padding" data-id="…">
     *     <div class="video-list">
     *       <div class="video-list-image"><a href="https://8kporner.com/watch/…"><img></a></div>
     *       <div class="pt_vid_fixed_height">
     *         <div class="video-list-title"><a href="https://8kporner.com/watch/…"><h4>…</h4></a></div>
     *       </div>
     *     </div>
     *   </div>
     *
     * IMPORTANT: The AJAX hot-video sidebar also uses div.keep-padding but with
     * links to external sites (hornyhill.st etc.). We filter these out in toSearchResult()
     * by requiring the href domain to be 8kporner.com or a relative /watch/ path.
     */
    private fun Document.listingCards(): List<Element> =
        select("div.keep-padding").ifEmpty { select("div[class*=thumb], article") }

    /** Search result cards: inside div.pt_srch_vids */
    private fun Document.searchCards(): List<Element> =
        select("div.pt_srch_vids div.keep-padding")
            .ifEmpty { select("div.pt_srch_vids > div, div.pt_srch_vids article") }
            .ifEmpty { listingCards() }

    private fun Element.toSearchResult(): SearchResponse? {
        // Title is in h4 (confirmed by log)
        val titleEl = selectFirst("h4") ?: selectFirst("h3, h2, .title, [class*=title]")
        val title = titleEl?.text()?.trim()?.ifBlank { null } ?: return null

        // The link: h4 is inside <a href="…"><h4>…</h4></a>
        // So titleEl.parent() == the <a> — this works in Jsoup.
        val href = (titleEl.parent()?.takeIf { it.tagName() == "a" }?.attr("href"))
            ?: selectFirst("a[href]")?.attr("href")
            ?: return null

        if (!href.startsWith("http") && !href.startsWith("/")) return null

        val absHref = if (href.startsWith("http")) href else "$mainUrl$href"

        // FIX: Filter out external partner site links.
        // Only 8kporner.com watch pages or relative /watch/ paths are valid.
        if (!absHref.contains("8kporner.com") && !href.startsWith("/watch/")) return null

        // Poster: first img in the card
        val poster = selectFirst("img[src]")?.attr("src")?.let { src ->
            if (src.startsWith("http")) src else "$mainUrl$src"
        }

        return newMovieSearchResponse(title, absHref, TvType.NSFW) {
            this.posterUrl = poster
        }
    }
}
