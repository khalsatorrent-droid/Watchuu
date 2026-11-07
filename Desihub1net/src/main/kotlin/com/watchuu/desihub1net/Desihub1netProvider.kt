package com.watchuu.desihub1net

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * desihub1.net — Indian adult content (Next.js). Built from the capture of 2026-11-07 12:24.
 *
 * Unlike the other desihub domains this one publishes a real json api, so almost nothing has to be
 * scraped:
 *
 *   listing    GET /api/v1/public/videos?page=<n>&per_page=20
 *              -> {data:[{id, slug, title, thumbnail, duration_seconds, views, categories[]}],
 *                  meta:{current_page, last_page}}
 *   detail     GET /api/v1/public/videos/<slug>
 *              -> {data:{…, video_url, description, tags[], performers[], countries[]}}
 *   stream     video_url, e.g.
 *              https://cdn.desihub1.com/uploads/2026/11/06/<uuid>-480p.mp4   (Referer: desihub1.net)
 *
 * Home rows are the site's own categories, whose pages are html (/category/<slug>, paged by path),
 * with the api tried first in case it filters by category - the captured /categories/<slug>
 * endpoint only returns the category itself, not its videos, so the html is the reliable route.
 *
 * Search was not captured. It is attempted against the api with ?q= and, failing that, against
 * the site's /search page, whose cards are article.w-full.min-w-0.
 *
 * The file name carries its quality (-480p.mp4), so the 720p and 1080p names are probed and only
 * offered when they actually answer. Nothing is guessed into the list.
 */
class Desihub1netProvider : MainAPI() {
    override var mainUrl = "https://desihub1.net"
    override var name = "Desihub1net"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val api = "$mainUrl/api/v1/public"
    private val cf = CloudflareKiller()

    override val mainPage = mainPageOf(
        "" to "Latest",
        "category/desi-mms" to "Desi MMS",
        "category/models" to "Models",
        "category/punjabi-sex-videos" to "Punjabi",
        "category/bengali-sex-videos" to "Bengali",
        "category/tamil-sex-videos" to "Tamil",
        "category/nepali-sex-videos" to "Nepali",
        "category/kannada-sex-videos" to "Kannada"
    )

    private fun userAgent(): String = UA

    private suspend fun getJson(url: String): JSONObject? = runCatching {
        JSONObject(
            app.get(
                url,
                headers = mapOf(
                    "User-Agent" to userAgent(),
                    "Accept" to "application/json",
                    "Referer" to "$mainUrl/"
                ),
                interceptor = cf,
                timeout = 25L
            ).text
        )
    }.getOrNull()

    private suspend fun getHtml(url: String): String? = runCatching {
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
    }.getOrNull()?.takeIf { it.contains("<html", true) }

    // ── Listings ───────────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data.trim('/')

        val items = if (data.isEmpty()) {
            fromApi(page) ?: emptyList()
        } else {
            val slug = data.substringAfter("category/")
            fromApiCategory(slug, page)
                ?: fromCategoryHtml(data, page)
                ?: emptyList()
        }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private suspend fun fromApi(page: Int): List<SearchResponse>? {
        val json = getJson("$api/videos?page=$page&per_page=20") ?: return null
        val arr = json.optJSONArray("data") ?: return null
        val out = ArrayList<SearchResponse>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { out.add(it.toSearchResult() ?: return@let) }
        }
        return out.ifEmpty { null }
    }

    /** the api may or may not filter by category; when it does not, the caller falls back to html */
    private suspend fun fromApiCategory(slug: String, page: Int): List<SearchResponse>? {
        val json = getJson("$api/videos?category=$slug&page=$page&per_page=20") ?: return null
        val arr = json.optJSONArray("data") ?: return null
        if (arr.length() == 0) return null
        val out = ArrayList<SearchResponse>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.toSearchResult()?.let { out.add(it) }
        }
        return out.ifEmpty { null }
    }

    private suspend fun fromCategoryHtml(path: String, page: Int): List<SearchResponse>? {
        val base = "$mainUrl/${path.trim('/')}"
        val url = if (page > 1) "$base/page/$page" else base
        val html = getHtml(url) ?: return null
        return cards(Jsoup.parse(html)).ifEmpty { null }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val enc = URLEncoder.encode(q, "UTF-8")

        val out = ArrayList<SearchResponse>()
        for (page in 1..2) {
            // the api first, in case it supports q
            getJson("$api/videos?q=$enc&page=$page&per_page=20")?.optJSONArray("data")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.toSearchResult()?.let { out.add(it) }
                }
            }
            if (out.isEmpty()) {
                // otherwise the site's own search page
                getHtml("$mainUrl/search?q=$enc" + if (page > 1) "&page=$page" else "")
                    ?.let { cards(Jsoup.parse(it)).forEach { c -> out.add(c) } }
            }
        }
        return out.distinctBy { it.url }
    }

    private fun JSONObject.toSearchResult(): SearchResponse? {
        val slug = optString("slug").ifBlank { return null }
        val title = optString("title").ifBlank { slug.replace('-', ' ') }
        val poster = optString("thumbnail").ifBlank { null }
        return newMovieSearchResponse(title, "$mainUrl/$slug", TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    private fun cards(doc: org.jsoup.nodes.Document): List<SearchResponse> =
        doc.select("article.w-full, article")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a[href]") ?: return null
        val href = a.attr("href").trim()
        if (href.isBlank() || href.startsWith("/category/") || href.startsWith("/page/")) return null

        val img = selectFirst("img")
        val title = selectFirst("h3, h2")?.text()?.trim()?.ifBlank { null }
            ?: img?.attr("alt")?.trim()?.ifBlank { null }
            ?: return null
        val poster = img?.let { it.attr("src").ifBlank { it.attr("data-src") } }
            ?.takeIf { it.isNotBlank() && !it.startsWith("data:") }

        return newMovieSearchResponse(title, fixUrl(href), TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    // ── Detail ─────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.trimEnd('/').substringAfterLast('/')
        if (slug.isBlank()) return null

        val data = getJson("$api/videos/$slug")?.optJSONObject("data")
        if (data != null) {
            val title = data.optString("title").ifBlank { slug.replace('-', ' ') }
            val poster = data.optString("thumbnail").ifBlank { null }
            val plot = data.optString("description")
                .replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
                .ifBlank { null }

            val tags = ArrayList<String>()
            data.optJSONArray("tags")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.optString("name")?.ifBlank { null }?.let { tags.add(it) }
                }
            }
            data.optJSONArray("categories")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.optString("name")?.ifBlank { null }?.let { tags.add(it) }
                }
            }

            return newMovieLoadResponse(title, url, TvType.NSFW, slug) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags.distinct().take(12)
            }
        }

        // api unavailable: fall back to the page's own metadata
        val doc = getHtml(url)?.let { Jsoup.parse(it) } ?: return null
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.ifBlank { null }
            ?: doc.selectFirst("h1")?.text()?.trim()?.ifBlank { null }
            ?: slug.replace('-', ' ')
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()?.ifBlank { null }

        return newMovieLoadResponse(title, url, TvType.NSFW, slug) {
            this.posterUrl = poster
            this.plot = plot
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

        val detail = getJson("$api/videos/$slug")?.optJSONObject("data")
        var fileUrl = detail?.optString("video_url")?.ifBlank { null }

        // if the api did not answer, the page still carries the file
        if (fileUrl == null) {
            val html = getHtml("$mainUrl/$slug") ?: return false
            fileUrl = Regex("""https?://cdn\.desihub1\.com/[^\s"'<>\\]+\.mp4""")
                .find(html)?.value
        }
        if (fileUrl.isNullOrBlank()) return false

        val pageUrl = "$mainUrl/$slug"
        val candidates = LinkedHashMap<String, String>()

        // the file name carries its quality: <uuid>-480p.mp4. Probe the neighbours and keep only
        // the ones that answer, so nothing guessed ends up in the list.
        val match = Regex("""^(.*-)(\d{3,4})p\.mp4$""").find(fileUrl)
        if (match != null) {
            val stem = match.groupValues[1]
            val original = match.groupValues[2].toIntOrNull() ?: 480
            listOf(2160, 1440, 1080, 720, original, 480, 360)
                .distinct()
                .sortedDescending()
                .forEach { q ->
                    val candidate = "$stem${q}p.mp4"
                    if (candidate == fileUrl || probe(candidate, pageUrl)) {
                        candidates[candidate] = "${q}p"
                    }
                }
        }
        if (candidates.isEmpty()) candidates[fileUrl] = "auto"

        var found = false
        candidates.forEach { (link, label) ->
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name · $label",
                    url = link,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = pageUrl
                    this.quality = qualityOf(label)
                    this.headers = mapOf(
                        "Referer" to "$mainUrl/",
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

    /** Asks for the first byte of a candidate; true when the cdn actually serves it. */
    private suspend fun probe(url: String, referer: String): Boolean {
        val res = runCatching {
            app.get(
                url,
                headers = mapOf(
                    "Referer" to "$mainUrl/",
                    "Origin" to mainUrl,
                    "User-Agent" to userAgent(),
                    "Accept" to "*/*",
                    "Range" to "bytes=0-0"
                ),
                timeout = 20L
            )
        }.getOrNull() ?: return false
        if (res.code !in 200..299) return false
        val type = res.headers["content-type"].orEmpty().lowercase()
        return type.startsWith("video/") || type.contains("octet-stream") || type.contains("mp4")
    }

    private fun qualityOf(label: String): Int = when {
        label.startsWith("2160") -> Qualities.P2160.value
        label.startsWith("1440") -> Qualities.P1440.value
        label.startsWith("1080") -> Qualities.P1080.value
        label.startsWith("720") -> Qualities.P720.value
        label.startsWith("480") -> Qualities.P480.value
        label.startsWith("360") -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }

    private companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 16; SM-S911B Build/BP4A.251205.006) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.47 Mobile Safari/537.36"
    }
}
