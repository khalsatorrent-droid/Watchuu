package com.watchuu.desihub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import org.jsoup.nodes.Element

/**
 * desihub.skin — Indian adult content (Next.js). Rewritten from the capture of 2026-11-06 22:11.
 *
 * The site moved since the desihub.sh version this module replaced: new domain, new cdn, and a
 * json api for search. What the capture shows:
 *
 *   listing   article.group.flex > a[href=/video/<slug>] > img, title from the link's aria-label
 *             sections: / (latest), /latest-videos, /feature-video
 *   paging    path: /page/<n>, /latest-videos/page/<n>, /feature-video/page/<n>
 *   search    https://edge.desihub.skin/api/user/search?q=<q>&page=<n>
 *             -> {success, query, page, videos:{items:[{title, slug, thumbnail, views, …}]}}
 *   detail    /video/<slug>
 *   stream    https://cdn.desixprime.com/videos/<slug>/video.mp4   (Referer: the video page)
 *
 * The stream url is deterministic from the slug - all three videos in the capture follow it - so
 * it is built directly rather than scraped, and any cdn.desixprime.com url actually present on the
 * page is added on top in case a title ever diverges from the pattern.
 */
class DesiHubProvider : MainAPI() {
    override var mainUrl = "https://desihub.skin"
    override var name = "Desihub"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val api = "https://edge.desihub.skin/api/user"
    private val cdn = "https://cdn.desixprime.com/videos"
    private val cf = CloudflareKiller()

    override val mainPage = mainPageOf(
        "" to "Latest",
        "latest-videos" to "Latest Videos",
        "feature-video" to "Featured"
    )

    private fun userAgent(): String = UA

    private fun pageUrl(path: String, page: Int): String {
        val base = path.trim('/')
        return when {
            page <= 1 && base.isEmpty() -> "$mainUrl/"
            page <= 1 -> "$mainUrl/$base"
            base.isEmpty() -> "$mainUrl/page/$page"
            else -> "$mainUrl/$base/page/$page"
        }
    }

    private suspend fun fetch(url: String): String? =
        runCatching {
            app.get(
                url,
                headers = mapOf(
                    "User-Agent" to userAgent(),
                    "Accept" to "text/html,application/xhtml+xml,*/*;q=0.8",
                    "Referer" to "$mainUrl/"
                ),
                interceptor = cf,
                timeout = 25L
            ).text
        }.getOrNull()

    // ── Listings ───────────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val html = fetch(pageUrl(request.data, page))
            ?: return newHomePageResponse(request.name, emptyList(), false)
        val items = cards(org.jsoup.Jsoup.parse(html))
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        if (q.isBlank()) return emptyList()

        // the api answers clean json: {success, query, page, videos:{items:[{title, slug, thumbnail}]}}
        val out = ArrayList<SearchResponse>()
        for (page in 1..2) {
            val body = runCatching {
                app.get("$api/search?q=$q&page=$page", headers = mapOf("User-Agent" to userAgent()), timeout = 25L).text
            }.getOrNull() ?: continue

            val items = runCatching {
                org.json.JSONObject(body).optJSONObject("videos")?.optJSONArray("items")
            }.getOrNull() ?: continue

            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                val slug = o.optString("slug").ifBlank { continue }
                val title = o.optString("title").replace("&amp;", "&").ifBlank { slug.replace('-', ' ') }
                val thumb = o.optString("thumbnail").ifBlank { null }

                out.add(
                    newMovieSearchResponse(title, "$mainUrl/video/$slug", TvType.NSFW) {
                        this.posterUrl = thumb
                    }
                )
            }
        }
        return out.distinctBy { it.url }
    }

    private fun cards(doc: org.jsoup.nodes.Document): List<SearchResponse> =
        doc.select("article.group, article")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a[href*=/video/]") ?: return null
        val href = a.attr("href").trim()
        if (href.isBlank()) return null

        val img = selectFirst("img")
        val title = a.attr("aria-label").removePrefix("Watch ").trim().ifBlank { null }
            ?: selectFirst("h2, h3")?.text()?.trim()?.ifBlank { null }
            ?: img?.attr("alt")?.trim()?.ifBlank { null }
            ?: return null

        val poster = img?.let { it.attr("src").ifBlank { it.attr("data-src") } }
            ?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }

        return newMovieSearchResponse(title, fixUrl(href), TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    // ── Detail ─────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val html = fetch(url) ?: return null
        val doc = org.jsoup.Jsoup.parse(html)

        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.ifBlank { null }
            ?: doc.selectFirst("h1")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("title")?.text()?.substringBefore('|')?.trim()
            ?: url.substringAfterLast('/').replace('-', ' ')

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()?.ifBlank { null }
        val tags = doc.select("a[href*=/category/], a[href*=/tag/]")
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
        val slug = data.trimEnd('/').substringAfterLast('/')
        if (slug.isBlank()) return false

        val links = LinkedHashSet<String>()

        // deterministic from the slug, and what every video in the capture used
        links.add("$cdn/$slug/video.mp4")

        // plus anything the page itself points at, in case a title ever differs
        runCatching { fetch(data) }.getOrNull()?.let { html ->
            Regex("""https?://cdn\.desixprime\.com/videos/[^\s"'<>\\]+\.mp4""")
                .findAll(html).map { it.value }.forEach { links.add(it) }
        }

        var found = false
        links.forEach { link ->
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = link,
                    type = ExtractorLinkType.VIDEO
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
        }
        return found
    }

    private companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 16; SM-S911B Build/BP4A.251205.006) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.47 Mobile Safari/537.36"
    }
}
