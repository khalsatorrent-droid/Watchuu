package com.watchuu.ssrmovies

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * SSR Movies (new1.ssrmovies.page) — built from the capture of 2026-11-07 22:51.
 *
 * WordPress, so browsing is ordinary html:
 *
 *   listing   article.latestPost.excerpt -> h2 a[href], poster from the card's img
 *   paging    path: /page/<n>            search  /?s=<query>
 *   detail    /<slug>  title h1, poster .poster img, plot .entry-content p
 *
 * Each quality is one anchor on the post, pointing at the site's link protector:
 *
 *   <a href="https://secure.linkszilla.top/view/89OE79zOSr">Download in 1080p (Source) - 4.8GB</a>
 *
 * and the protector page is where the real hosts are listed - in the capture, one of them held
 * hubcloud.pw, new9.gdflix.io, dl.uploadflix.com, dl.direct-cloud.top, streamtape.com, gofile.io,
 * megaup.net, clicknupload.cam, hexload.com, multiup.io and 1fichier.com at once.
 *
 * So the chain is post -> protector -> host, and every host url is handed to Cloudstream's own
 * extractors rather than parsed here: streamtape, gdflix, hubcloud and the rest all have one, and
 * they are the ones that know how to turn a file page into a stream. The quality printed on the
 * post's anchor is carried onto whatever those extractors return, so a 1080p entry stays 1080p.
 *
 * Protector pages are a webpack app and the capture of one was taken after its javascript had run,
 * so a plain fetch is tried first and the rendered DOM is used when that yields no hosts.
 */
class SsrmoviesProvider : MainAPI() {
    override var mainUrl = "https://new1.ssrmovies.page"
    override var name = "SSRMovies"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val cf = CloudflareKiller()

    override val mainPage = mainPageOf(
        "" to "Latest",
        "category/bollywood-movies" to "Bollywood",
        "category/hollywood-movies-hd" to "Hollywood",
        "category/dubbed-movies" to "Dubbed",
        "category/dual-audio-movies" to "Dual Audio",
        "category/4k-movies" to "4K Movies",
        "category/animated-movies" to "Animated",
        "category/pre-release" to "Pre Release",
        "category/punjabi-movies" to "Punjabi",
        "category/gujarati-movies" to "Gujarati",
        "category/marathi-movies" to "Marathi"
    )

    /** hosts that are never a file: the site itself, its protector, and documentation links */
    private val skipHosts = listOf(
        "new1.ssrmovies.page", "ssrmovies.page", "linkszilla.top", "imgurworld.top",
        "google.com", "w3.org", "schema.org", "gmpg.org", "reddit.com", "trustpilot.com",
        "facebook.com", "twitter.com", "telegram.me", "wa.me", "pinterest.com", "quora.com",
        "wikipedia.org", "youtube.com", "youtu.be", "protrafficinspector.com", "clips4sale.com"
    )

    private fun userAgent(): String = UA

    private suspend fun fetch(url: String, referer: String = "$mainUrl/"): String? = runCatching {
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
        val base = request.data.trim('/').let { if (it.isEmpty()) mainUrl else "$mainUrl/$it" }
        val url = if (page > 1) "$base/page/$page" else base

        val html = fetch(url) ?: return newHomePageResponse(request.name, emptyList(), false)
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

    /**
     * The listing theme lazy-loads posters in an unusual way: src is a 1x1 base64 gif and the real
     * image sits in the inline style as a background:
     *
     *   <img src="data:image/gif;base64,R0lGODlhAQABAIAA..." 
     *        style="background:url('https://imgurworld.top/images/2026/10/03/....jpg') no-repeat center center">
     *
     * Reading src alone therefore yields a placeholder, which is why posters came up blank. The
     * background url is tried first, then the usual lazy attributes, then srcset, and only then src
     * - and never a data: placeholder.
     */
    private fun imageUrl(img: Element?): String? {
        if (img == null) return null

        val candidates = ArrayList<String>()
        Regex("""url\(\s*['"]?([^'")]+)['"]?\s*\)""")
            .find(img.attr("style"))?.groupValues?.get(1)?.let { candidates.add(it) }
        candidates.add(img.attr("data-src"))
        candidates.add(img.attr("data-lazy-src"))
        candidates.add(img.attr("data-original"))
        candidates.add(img.attr("data-poster"))
        img.attr("srcset").split(',').firstOrNull()?.trim()?.substringBefore(' ')
            ?.takeIf { it.isNotBlank() }?.let { candidates.add(it) }
        candidates.add(img.attr("src"))

        return candidates.firstOrNull { url ->
            url.isNotBlank() && !url.startsWith("data:") && !url.startsWith("about:") &&
                !url.contains("nobg.png") && !url.contains("blank.gif")
        }?.let { url ->
            when {
                url.startsWith("//") -> "https:$url"
                url.startsWith("http") -> url
                else -> fixUrl(url)
            }
        }
    }

    /**
     * The theme marks its poster with attachment-sociallyviral-featured wp-post-image, so that is
     * asked for by name first. "first img in the article" happens to be the poster on the cards
     * captured so far, but a label, avatar or rating icon ahead of it would silently win instead.
     */
    private val posterSelector =
        "img.attachment-sociallyviral-featured, img.wp-post-image, .featured-thumbnail img, " +
            "img[class*=featured], .post-image img"

    private fun posterIn(scope: Element?): String? {
        if (scope == null) return null
        return imageUrl(scope.selectFirst(posterSelector)) ?: imageUrl(scope.selectFirst("img"))
    }

    private fun cards(doc: org.jsoup.nodes.Document): List<SearchResponse> =
        doc.select("article.latestPost, article.excerpt, article")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("h2 a[href], a[href]") ?: return null
        val href = a.attr("href").trim()
        if (href.isBlank() || href.contains("/category/") || href.contains("/page/") ||
            href.contains("?s=")
        ) return null

        val title = selectFirst("h2")?.text()?.trim()?.ifBlank { null }
            ?: a.attr("title").trim().ifBlank { null }
            ?: selectFirst("img")?.attr("alt")?.trim()?.ifBlank { null }
            ?: return null

        val poster = posterIn(this)

        val isSeries = Regex("""-s\d{2}|season|complete|series|episode""", RegexOption.IGNORE_CASE)
            .containsMatchIn("$href $title")

        return if (isSeries) {
            newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(title, fixUrl(href), TvType.Movie) { this.posterUrl = poster }
        }
    }

    // ── Detail ─────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val html = fetch(url) ?: return null
        val doc = Jsoup.parse(html)

        val title = doc.selectFirst("h1")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.ifBlank { null }
            ?: url.trimEnd('/').substringAfterLast('/').replace('-', ' ')
        val poster = imageUrl(doc.selectFirst(".poster img"))
            ?: posterIn(doc.selectFirst("article, .single_post, body"))
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
        val plot = doc.select(".entry-content p").map { it.text().trim() }
            .filter { it.length > 60 }.take(2).joinToString("\n\n").ifBlank { null }
        val tags = doc.select("[class*=categor] a, a[href*=/category/]").map { it.text().trim() }
            .filter { it.isNotBlank() && it.length < 28 }.distinct().take(10)

        val content = doc.selectFirst(".entry-content")?.html().orEmpty()
        val sections = episodeSections(content)

        if (sections.size > 1) {
            val episodes = sections.map { (number, _) ->
                newEpisode("$url|e$number") {
                    this.name = "Episode $number"
                    this.season = 1
                    this.episode = number
                    this.posterUrl = poster
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
        }
    }

    /**
     * Series posts list their episodes as "Episode 1", "EP 2", "S01E03" and so on, each followed by
     * its own download links. Cutting at those markers is what gives real episode names instead of
     * one entry holding every episode's links.
     */
    private fun episodeSections(html: String): List<Pair<Int, String>> {
        val markers = Regex(
            """(?:Episode|EP)\s*\.?\s*(\d{1,3})\b|S\d{1,2}\s*E(\d{1,3})""",
            RegexOption.IGNORE_CASE
        ).findAll(html).toList()

        if (markers.isEmpty()) return listOf(0 to html)

        val out = ArrayList<Pair<Int, String>>()
        markers.forEachIndexed { i, m ->
            val number = (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull() ?: (i + 1)
            val start = m.range.last + 1
            val end = markers.getOrNull(i + 1)?.range?.first ?: html.length
            if (start < end) out.add(number to html.substring(start, end))
        }
        return out.distinctBy { it.first }.ifEmpty { listOf(0 to html) }
    }

    // ── Streams ────────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = data.substringBefore('|')
        val selector = data.substringAfter('|', "")

        val html = fetch(pageUrl) ?: return false
        val content = Jsoup.parse(html).selectFirst(".entry-content")?.html() ?: html

        val section = if (selector.startsWith("e")) {
            val n = selector.drop(1).toIntOrNull()
            episodeSections(content).firstOrNull { it.first == n }?.second ?: content
        } else {
            content
        }

        // each anchor on the post is one quality, and points at the protector
        val targets = LinkedHashMap<String, String>() // url -> quality label
        labelledAnchors(section).forEach { (url, label) ->
            if (targets.containsKey(url)) return@forEach
            val quality = qualityLabel(label) ?: qualityLabel(url) ?: "auto"
            targets[url] = quality
        }
        if (targets.isEmpty()) return false

        var found = false

        targets.forEach { (target, quality) ->
            // a protector page hides the hosts; a direct host link is used as-is
            val hosts = if (isProtector(target)) {
                hostLinks(target)
            } else {
                listOf(target)
            }

            hosts.forEach { hostUrl ->
                val got = mutableListOf<ExtractorLink>()
                runCatching {
                    loadExtractor(hostUrl, pageUrl, subtitleCallback) { got.add(it) }
                }
                if (got.isNotEmpty()) {
                    got.forEach { link ->
                        callback.invoke(
                            newExtractorLink(
                                source = link.source ?: name,
                                name = if (quality == "auto") link.name else "${link.name} · $quality",
                                url = link.url,
                                type = link.type
                            ) {
                                this.referer = link.referer.ifBlank { pageUrl }
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

    private fun isProtector(url: String): Boolean =
        url.contains("linkszilla", true) || url.contains("/view/", true)

    /** The host urls listed on a protector page, rendered if the plain fetch has none. */
    private suspend fun hostLinks(protectorUrl: String): List<String> {
        val fromHtml = fetch(protectorUrl, "$mainUrl/")?.let { harvestHosts(it) }.orEmpty()
        if (fromHtml.isNotEmpty()) return fromHtml

        // the protector is a webpack app; the capture of it was taken after its js had run
        val rendered = runCatching { SsrmoviesBrowser.renderedHtml(protectorUrl, "$mainUrl/") }.getOrNull()
        return rendered?.let { harvestHosts(it) }.orEmpty()
    }

    private fun harvestHosts(html: String): List<String> {
        val out = LinkedHashSet<String>()
        Regex("""<a[^>]+href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.groupValues[1].replace("&amp;", "&").trim() }
            .filter { it.startsWith("http") }
            .forEach { url ->
                val host = Regex("""^https?://([^/]+)""").find(url)?.groupValues?.get(1).orEmpty()
                if (skipHosts.none { host.contains(it, true) }) out.add(url)
            }
        return out.toList()
    }

    /** href + its text, so "Download in 1080p (Source) - 4.8GB" stays attached to its link */
    private fun labelledAnchors(html: String): List<Pair<String, String>> =
        Regex("""<a[^>]+href=["']([^"']+)["'][^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(html)
            .map { m ->
                val url = m.groupValues[1].replace("&amp;", "&").trim()
                val label = m.groupValues[2].replace(Regex("<[^>]+>"), " ")
                    .replace("&amp;", "&").replace(Regex("\\s+"), " ").trim()
                url to label
            }
            .filter { (url, _) -> url.startsWith("http") }
            .filter { (url, label) ->
                val host = Regex("""^https?://([^/]+)""").find(url)?.groupValues?.get(1).orEmpty()
                skipHosts.none { host.contains(it, true) } ||
                    url.contains("linkszilla", true) ||
                    Regex("""\d{3,4}p|download|watch""", RegexOption.IGNORE_CASE).containsMatchIn(label)
            }
            .toList()

    private fun qualityLabel(text: String): String? {
        val m = Regex("""\b(2160p|1440p|1080p|720p|480p|360p|4k)\b""", RegexOption.IGNORE_CASE)
            .find(text) ?: return null
        return m.groupValues[1].lowercase()
    }

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
