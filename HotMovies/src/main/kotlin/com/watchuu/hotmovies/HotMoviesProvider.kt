package com.watchuu.hotmovies

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import org.jsoup.nodes.Element

/**
 * Hot Movies (Android app "HOMO 4.0", fun.playme.wobljr) - rebuilt from the APK.
 *
 * The app's code is deliberately undecompilable; its strings were recovered from the dex string
 * pool (bitwise-NOT encoded) and show it is a front-end for porn-xp.eu:
 *  - lists    : {base}{path}?page={n}  (or &page= when the path already has a query), sent with
 *               X-Requested-With: XMLHttpRequest
 *               New Movies = released/, Popular Now = best/7days, studios = tags/{tag}?sort=new
 *  - cards    : div.item.preview > a (href), img.item_img (src | data-src), div.item_title, div.item_dur,
 *               data-preview (hover preview)
 *  - details  : div#desc, div.tags > a, "Released:" / "Uploaded:" text
 *  - streams  : <source> tags  .../360.mp4  .../720.mp4  .../1080.mp4
 */
class HotMoviesProvider : MainAPI() {
    override var mainUrl = "https://porn-xp.eu"
    override var name = "Hot Movies"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val cf = CloudflareKiller()
    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    override val mainPage = mainPageOf(
        "released/" to "New Movies",
        "best/7days" to "Popular Now",
        "tags/teamskeet?sort=new" to "Team Skeet",
        "tags/tiny4k?sort=new" to "Tiny4K",
        "tags/sexmex?sort=new" to "SexMex",
        "tags/private?sort=new" to "Private",
        "tags/x-art?sort=new" to "X-Art",
        "tags/milfs?sort=new" to "MILFs",
    )

    private fun pageUrl(path: String, page: Int): String {
        val base = "$mainUrl/${path.trimStart('/')}"
        if (page <= 1) return base
        return base + (if (base.contains("?")) "&" else "?") + "page=$page"
    }

    private suspend fun get(url: String, ajax: Boolean) = app.get(
        url, referer = "$mainUrl/", interceptor = cf,
        headers = buildMap {
            put("User-Agent", ua)
            if (ajax) put("X-Requested-With", "XMLHttpRequest")
        }
    ).document

    private suspend fun listing(url: String, page: Int): List<SearchResponse> {
        // the app pages through the ajax variant; page 1 also works as a normal page
        val ajax = page > 1
        val items = get(url, ajax).select("div.item.preview").mapNotNull { it.toSearchResult() }
        if (items.isNotEmpty() || !ajax) return items
        return get(url, false).select("div.item.preview").mapNotNull { it.toSearchResult() }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = runCatching { listing(pageUrl(request.data, page), page) }.getOrNull().orEmpty().distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val slug = query.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        val candidates = listOf("search/$slug/", "search/?q=$q", "?s=$q", "tags/$slug?sort=new")
        for (c in candidates) {
            val out = LinkedHashMap<String, SearchResponse>()
            for (p in 1..3) {
                val found = runCatching { listing(pageUrl(c, p), p) }.getOrNull().orEmpty()
                val fresh = found.filter { out[it.url] == null }
                if (fresh.isEmpty()) break
                fresh.forEach { out[it.url] = it }
            }
            if (out.isNotEmpty()) return out.values.toList()
        }
        return emptyList()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a[href]") ?: return null
        val href = fixUrlNull(a.attr("href")) ?: return null
        val img = selectFirst("img.item_img") ?: selectFirst("img")
        val title = selectFirst("div.item_title")?.text()?.trim()?.ifBlank { null }
            ?: img?.attr("alt")?.trim()?.ifBlank { null }
            ?: a.attr("title").trim().ifBlank { null }
            ?: return null
        val poster = listOf("data-src", "src").map { img?.attr(it).orEmpty() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
            ?.let { if (it.startsWith("//")) "https:$it" else fixUrl(it) }
        return newMovieSearchResponse(title, href, TvType.NSFW) { posterUrl = poster }
    }

    override suspend fun load(url: String): LoadResponse {
        val d = get(url, false)
        val title = d.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.ifBlank { null }
            ?: d.selectFirst("h1")?.text()?.trim() ?: d.title()
        val poster = d.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
            ?: d.selectFirst("video[poster]")?.attr("poster")
        val plot = d.selectFirst("div#desc")?.text()?.trim()?.ifBlank { null }
            ?: d.selectFirst("meta[name=description]")?.attr("content")
        val tags = d.select("div.tags > a").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val year = Regex("""(?:Released|Uploaded):\s*.*?(\d{4})""").find(d.text())?.groupValues?.get(1)?.toIntOrNull()
        val recs = d.select("div.item.preview").mapNotNull { it.toSearchResult() }.filter { it.url != url }
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.year = year
            recommendations = recs
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val d = get(data, false)
        val srcs = d.select("video source[src], source[src]").map { it.attr("src") } +
            Regex("""https?:[^"'\s<>]+?/(?:360|480|720|1080|2160)\.mp4[^"'\s<>]*""").findAll(d.html()).map { it.value }.toList()
        var found = false
        srcs.map { s -> if (s.startsWith("//")) "https:$s" else fixUrl(s) }
            .filter { it.contains(".mp4") }
            .distinct()
            .forEach { link ->
                val q = Regex("""/(\d{3,4})\.mp4""").find(link)?.groupValues?.get(1)?.toIntOrNull()
                callback(
                    newExtractorLink(name, "$name ${q?.let { "${it}p" } ?: ""}".trim(), link, ExtractorLinkType.VIDEO) {
                        referer = "$mainUrl/"
                        quality = q ?: Qualities.Unknown.value
                        headers = mapOf("User-Agent" to ua)
                    }
                )
                found = true
            }
        if (!found) {
            d.select("iframe[src]").map { fixUrl(it.attr("src")) }.distinct().forEach {
                if (loadExtractor(it, data, subtitleCallback, callback)) found = true
            }
        }
        return found
    }
}
