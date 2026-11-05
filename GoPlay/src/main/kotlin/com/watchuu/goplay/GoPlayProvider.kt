package com.watchuu.goplay

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * GoPlay (goplay.su) — built from the capture of 2026-11-05 19:05.
 *
 * Korean drama and film streaming. Browsing is plain html:
 *
 *   listing   div#indexepisodes > div#indexepisodeimage > a[href] + img[src]
 *             the title sits in div#indexepisodetitle, next to it
 *   paging    query: /?page=N
 *   search    /?search=<query>
 *   series    /<id>/<Name>/ — the title in div#infotitle, episodes in #episodesodd > a[href]
 *             each episode is its own url: /<id>/<Name>/Episode-1/
 *
 * Playing is the interesting part. The player asks for
 *
 *   https://goplay.su/stream/stream_dash_v3.mpd?d=<long encrypted token>
 *
 * and that token is produced by the site's javascript when the player starts - it is not in the
 * page, and it is different for every episode. So the episode page is loaded in a WebView and the
 * mpd request is intercepted as it happens (WebViewResolver), which is the same technique CineLite
 * uses here.
 *
 * The mpd itself is only served to a request carrying the site's session cookies and the episode
 * page as referer, so both are attached to the link.
 *
 * Note on the manifest: it declares ContentProtection (cenc default_KID + a PlayReady PSSH), but
 * no licence request appears anywhere in the capture while the video played, so the segments are
 * served clear and the declaration is vestigial. If a title ever does come through encrypted, it
 * will not play - that would need a licence, which an extension cannot provide.
 */
class GoPlayProvider : MainAPI() {
    override var mainUrl = "https://goplay.su"
    override var name = "GoPlay"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "" to "Latest"
    )

    private fun userAgent(): String = UA

    /**
     * The site answers 404 to a first, cookieless request - the capture's very first hit on "/"
     * is a 404 with a 1 KB body, while the same url a moment later returns 167 KB of cards. It
     * sets d/locl/GL_UI4 on that first exchange, so a second try with the jar now populated is
     * what actually works. Retrying here is the difference between an empty home page and a full
     * one.
     */
    private suspend fun fetch(url: String, referer: String = "$mainUrl/"): String? {
        repeat(2) {
            val body = runCatching {
                app.get(
                    url,
                    headers = mapOf(
                        "User-Agent" to userAgent(),
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                        "Accept-Language" to "en-US,en;q=0.9",
                        "Referer" to referer
                    ),
                    timeout = 25L
                ).text
            }.getOrNull()
            if (body != null && body.contains("<html", true) && looksLikePage(body)) return body
        }
        return null
    }

    /** A real page carries one of these; a 404 shell or an interstitial carries none. */
    private fun looksLikePage(body: String): Boolean =
        body.contains("indexepisodes", true) || body.contains("indexsearchepisodes", true) ||
            body.contains("episodesodd", true) || body.contains("infotitle", true)

    // ── Listings ───────────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // the capture pages through /browse?page=N&type=1; "/" is the same listing without the query
        val urls = if (page > 1) {
            listOf("$mainUrl/browse?page=$page&type=1")
        } else {
            listOf("$mainUrl/", "$mainUrl/browse?type=1")
        }
        for (url in urls) {
            val html = fetch(url) ?: continue
            val items = cards(Jsoup.parse(html))
            if (items.isNotEmpty()) {
                return newHomePageResponse(request.name, items, hasNext = true)
            }
        }
        return newHomePageResponse(request.name, emptyList(), false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        if (q.isBlank()) return emptyList()

        val first = fetch("$mainUrl/?search=$q")?.let { Jsoup.parse(it) }?.let { cards(it) }.orEmpty()
        if (first.isEmpty()) return first
        val second = fetch("$mainUrl/?search=$q&page=2")?.let { Jsoup.parse(it) }?.let { cards(it) }.orEmpty()
        return (first + second).distinctBy { it.url }
    }

    private fun cards(doc: org.jsoup.nodes.Document): List<SearchResponse> =
        doc.select("div#indexepisodes, div.indexsearchepisodes")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a[href]") ?: return null
        val href = a.attr("href").trim()
        if (href.isBlank()) return null

        val title = selectFirst("div#indexepisodetitle")?.text()?.trim()?.ifBlank { null }
            ?: selectFirst("img[alt]")?.attr("alt")?.trim()?.ifBlank { null }
            ?: a.attr("title").trim().ifBlank { null }
            ?: return null

        val poster = selectFirst("img[src], img[data-src]")
            ?.let { it.attr("data-src").ifBlank { it.attr("src") } }
            ?.takeIf { it.isNotBlank() }
            ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }

        val episodes = selectFirst("div#indexepisodecount")?.text()
            ?.filter { it.isDigit() }?.toIntOrNull() ?: 0

        return if (episodes > 1) {
            newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    // ── Detail ─────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val html = fetch(url) ?: return null
        val doc = Jsoup.parse(html)

        // #infotitle holds "Series Name (native)<br><font id=episodetitle>Episode 1</font>"
        val infoTitle = doc.selectFirst("div#infotitle")
        val episodeLabel = infoTitle?.selectFirst("font#episodetitle")?.text()?.trim()
        val seriesName = infoTitle?.clone()?.let { el ->
            el.select("font#episodetitle").remove()
            el.text().trim()
        }?.ifBlank { null }

        val title = seriesName
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "GoPlay"

        val episodeAnchors = doc.select("#episodesodd > a[href], #episodesodd a[href]")
        val poster = episodeAnchors.firstOrNull()?.selectFirst("img[src]")?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }

        // an episode page itself (opened directly) is a single movie entry
        if (episodeAnchors.isEmpty()) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
            }
        }

        val episodes = ArrayList<Episode>()
        episodeAnchors.forEachIndexed { index, a ->
            val href = a.attr("href").trim()
            if (href.isBlank()) return@forEachIndexed
            val label = a.selectFirst("div#episodesnumber")?.text()?.trim()
            val number = label?.filter { it.isDigit() }?.toIntOrNull() ?: (index + 1)
            episodes.add(
                newEpisode(fixUrl(href)) {
                    this.name = label?.ifBlank { null } ?: "Episode $number"
                    this.season = 1
                    this.episode = number
                    this.posterUrl = a.selectFirst("img[src]")?.attr("src")
                }
            )
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = episodeLabel?.let { "Currently listed: $it" }
        }
    }

    // ── Streams ────────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // the mpd url is built by the site's js, so let the WebView make the request and take it
        val resolver = WebViewResolver(
            interceptUrl = Regex("""(?i)/stream/.*\.(mpd|m3u8)"""),
            additionalUrls = listOf(
                Regex("""(?i)\.mpd"""),
                Regex("""(?i)\.m3u8""")
            ),
            userAgent = userAgent(),
            useOkhttp = false,
            timeout = 25_000L
        )

        val streamUrl = runCatching {
            app.get(data, referer = "$mainUrl/", interceptor = resolver).url
        }.getOrNull()

        if (streamUrl.isNullOrBlank() || !Regex("""(?i)\.(mpd|m3u8)""").containsMatchIn(streamUrl)) {
            return false
        }

        // the manifest is only served with the site's own cookies and the episode as referer
        val cookie = runCatching {
            android.webkit.CookieManager.getInstance().getCookie("$mainUrl/")
        }.getOrNull().orEmpty()

        val headers = LinkedHashMap<String, String>()
        headers["Referer"] = data
        headers["User-Agent"] = userAgent()
        if (cookie.isNotBlank()) headers["Cookie"] = cookie

        val isDash = streamUrl.contains(".mpd", true)
        callback.invoke(
            newExtractorLink(
                source = name,
                name = if (isDash) "$name (DASH)" else "$name (HLS)",
                url = streamUrl,
                type = if (isDash) ExtractorLinkType.DASH else ExtractorLinkType.M3U8
            ) {
                this.referer = data
                this.quality = Qualities.Unknown.value
                this.headers = headers
            }
        )
        return true
    }

    private companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 16; SM-S911B Build/BP4A.251205.006) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.47 Mobile Safari/537.36"
    }
}
