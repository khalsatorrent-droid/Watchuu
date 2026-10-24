package com.watchuu.nartodrama

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup

/**
 * Narto Drama (narto-drama.com) - short dramas from 50+ apps (DramaBox, ReelShort, ShortMax, ...),
 * plus movies, series and anime. Laravel site behind Cloudflare.
 *
 * Built from a captured network log (2026-10-24):
 *  - first visit sets a cookie `nd_ck` from JS and reloads ("Checking your browser...")
 *  - lists / search  : HTML pages with a JSON-LD ItemList (url, name, image) of /detail/watch/{slug}
 *  - provider rows   : GET /home/providers/sections?provider={key}&lang=en-US&target_lang=en-US (json)
 *  - detail page     : /detail/watch/{slug}  (JSON-LD TVSeries, a.episode-item links)
 *  - episode count   : GET edge.narto-drama.com/e/rs/detail/watch/{slug}/check-new-episodes
 *  - "more like this": GET /detail/watch/{slug}/extras  (json with html cards)
 *  - episode page    : /detail/watch/{slug}/{n}  - the video address is created by the page's
 *                      player, so it is taken from a hidden WebView (NartoBrowser.sniff)
 *
 * Requests: plain (with the nd_ck cookie and the WebView's cookies) -> CloudflareKiller -> a
 * hidden WebView that loads the page like a browser.
 */
class NartoDramaProvider : MainAPI() {
    override var mainUrl = NartoSettings.siteUrl
    override var name = "NartoDrama"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.TvSeries, TvType.Movie, TvType.Anime)

    private val edgeUrl get() = mainUrl.replace("://", "://edge.")
    private val langQ = "lang=en-US"

    private val mapper = ObjectMapper()

    // ---------------------------------------------------------------- requests

    /**
     * Body of a page / json, past the site's cookie gate and Cloudflare (see NartoBrowser.get:
     * saved cookie -> hidden browser or Cloudstream bypass -> solver window, per the settings).
     * Throws a readable error when Cloudflare is not passed.
     */
    private suspend fun fetch(url: String, json: Boolean = false): Pair<String, String> {
        val body = NartoBrowser.get(url, "$mainUrl/")
        return body to url
    }

    // ---------------------------------------------------------------- list helpers

    private class Item(val url: String, val name: String, val image: String?)

    private fun fixUrl2(u: String?): String? {
        if (u.isNullOrBlank()) return null
        return when {
            u.startsWith("//") -> "https:$u"
            u.startsWith("/") -> mainUrl + u
            else -> u
        }
    }

    /** /detail/watch/{slug} without query, episode number or junk. */
    private fun detailBase(u: String): String? {
        val m = Regex("""/detail/watch/([^/?#]+)""").find(u) ?: return null
        return "$mainUrl/detail/watch/${m.groupValues[1]}"
    }

    private fun cleanTitle(t: String) = t.replace(Regex("""\s*-\s*Free Streaming\s*$""", RegexOption.IGNORE_CASE), "").trim()

    /** Items of every JSON-LD ItemList in a page (lists, search results). */
    private fun ldItems(html: String): List<Item> {
        val doc = Jsoup.parse(html)
        val out = LinkedHashMap<String, Item>()
        fun walk(n: JsonNode, depth: Int) {
            if (depth > 8) return
            if (n.isArray) n.forEach { walk(it, depth + 1) }
            else if (n.isObject) {
                val url = n.get("url")?.asText()
                val nm = n.get("name")?.asText()
                if (n.get("@type")?.asText() == "ListItem" && url != null && nm != null && url.contains("/detail/watch/")) {
                    detailBase(url)?.let { b -> out.putIfAbsent(b, Item(b, cleanTitle(nm), fixUrl2(n.get("image")?.asText()))) }
                }
                n.fields().forEach { if (it.value.isContainerNode) walk(it.value, depth + 1) }
            }
        }
        doc.select("script[type=application/ld+json]").forEach { s ->
            try {
                mapper.readTree(s.data())?.let { walk(it, 0) }
            } catch (_: Exception) {
            }
        }
        // fallback: plain cards / links
        if (out.isEmpty()) {
            doc.select("a[href*=/detail/watch/]").forEach { a ->
                val b = detailBase(a.attr("abs:href").ifBlank { a.attr("href") }) ?: return@forEach
                if (Regex("""/detail/watch/[^/]+/\d+""").containsMatchIn(a.attr("href"))) return@forEach
                val img = a.selectFirst("img")
                val nm = a.attr("title").ifBlank { img?.attr("alt") ?: "" }.ifBlank { a.selectFirst("h3,h2,h4")?.text() ?: "" }
                if (nm.isNotBlank()) out.putIfAbsent(b, Item(b, cleanTitle(nm), fixUrl2(img?.attr("data-src")?.ifBlank { null } ?: img?.attr("src"))))
            }
        }
        return out.values.toList()
    }

    private fun Item.toSearch(): SearchResponse =
        newTvSeriesSearchResponse(name, url, TvType.AsianDrama) { this.posterUrl = image }

    // ---------------------------------------------------------------- home page

    override val mainPage = mainPageOf(
        "provider:dramabox" to "DramaBox",
        "provider:reelshort" to "ReelShort",
        "provider:shortmax" to "ShortMax",
        "provider:goodshort" to "GoodShort",
        "provider:netshort" to "NetShort",
        "provider:dramawave" to "DramaWave",
        "provider:flickreels" to "FlickReels",
        "provider:anyreel" to "AnyReel",
        "provider:kalostv" to "KalosTV",
        "provider:moboreels" to "MoboReels",
        "provider:melolo" to "Melolo",
        "provider:dramashorts" to "DramaShorts",
        "$mainUrl/featured?$langQ" to "Featured",
        "$mainUrl/?$langQ&type=movie-series" to "Movies & Series",
        "$mainUrl/?$langQ&type=anime" to "Anime",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            if (request.data.startsWith("provider:")) {
                val key = request.data.removePrefix("provider:")
                // the sections api has no paging in the log: one page per provider
                val items = if (page > 1) emptyList() else providerItems(key, page)
                newHomePageResponse(request.name, items, false)
            } else {
                val url = if (page > 1) request.data + "&page=$page" else request.data
                val items = ldItems(fetch(url).first).map { it.toSearch() }
                newHomePageResponse(request.name, items, items.size >= 10)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ErrorLoadingException) {
            throw e
        } catch (_: Exception) {
            newHomePageResponse(request.name, emptyList())
        }
    }

    private suspend fun providerItems(key: String, page: Int): List<SearchResponse> {
        val url = "$mainUrl/home/providers/sections?provider=$key&$langQ&target_lang=en-US" +
            (if (page > 1) "&page=$page" else "")
        val body = fetch(url, json = true).first
        val root = try { mapper.readTree(body) } catch (_: Exception) { null } ?: return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<SearchResponse>()
        root.get("sections")?.forEach { sec ->
            sec.get("items")?.forEach { item ->
                val title = item.get("title")?.asText() ?: return@forEach
                val watch = item.get("watch_url")?.asText()?.replace("&amp;", "&") ?: return@forEach
                val id = item.get("book_id")?.asText() ?: watch
                if (!seen.add(id)) return@forEach
                val poster = fixUrl2(item.get("poster_url")?.asText())
                out.add(newTvSeriesSearchResponse(title, watch, TvType.AsianDrama) { this.posterUrl = poster })
            }
        }
        return out
    }

    // ---------------------------------------------------------------- search

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        return try {
            coroutineScope {
                (1..2).map { p ->
                    async {
                        val url = "$mainUrl/search?$langQ&q=$q" + (if (p > 1) "&page=$p" else "")
                        try {
                            ldItems(fetch(url).first)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: ErrorLoadingException) {
                            if (p == 1) throw e else emptyList()
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                }.awaitAll().flatten().distinctBy { it.url }.map { it.toSearch() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ErrorLoadingException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ---------------------------------------------------------------- load

    override suspend fun load(url: String): LoadResponse? {
        // provider rows link to /search/import?... which redirects to the drama's page
        val (html, finalUrl) = fetch(url)
        val doc = Jsoup.parse(html, finalUrl)
        val base = detailBase(finalUrl)
            ?: doc.selectFirst("link[rel=canonical]")?.attr("href")?.let { detailBase(it) }
            ?: doc.selectFirst("meta[property=og:url]")?.attr("content")?.let { detailBase(it) }
            ?: return null
        val slug = base.substringAfterLast("/")

        // JSON-LD TVSeries / Movie of this page
        var ldName: String? = null
        var ldDesc: String? = null
        var ldImage: String? = null
        var isMovie = false
        doc.select("script[type=application/ld+json]").forEach { s ->
            try {
                val n = mapper.readTree(s.data()) ?: return@forEach
                val t = n.get("@type")?.asText()
                if (t == "TVSeries" || t == "Movie" || t == "TVEpisode") {
                    ldName = ldName ?: n.get("name")?.asText() ?: n.get("partOfSeries")?.get("name")?.asText()
                    ldDesc = ldDesc ?: n.get("description")?.asText()
                    ldImage = ldImage ?: n.get("image")?.let { i -> if (i.isTextual) i.asText() else i.get("url")?.asText() }
                    if (t == "Movie") isMovie = true
                }
            } catch (_: Exception) {
            }
        }
        val title = cleanTitle(
            ldName ?: doc.selectFirst("h1")?.text()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content") ?: slug
        ).replace(Regex("""\s+Episode\s+\d+$""", RegexOption.IGNORE_CASE), "")
        val poster = fixUrl2(ldImage ?: doc.selectFirst("meta[property=og:image]")?.attr("content"))
        // the description ends with the site's slogan and starts with "<title>. "
        val plot = (ldDesc ?: doc.selectFirst("meta[name=description]")?.attr("content"))
            ?.substringBefore(" Narto Drama - ")
            ?.removePrefix("$title. ")
            ?.trim()
        val tags = doc.select("a[href*=/genre/]").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()

        // episodes: the page's own list, else the count from the edge api
        val eps = sortedMapOf<Int, String>()
        doc.select("a.episode-item, a[href*=/detail/watch/$slug/]").forEach { a ->
            val href = a.attr("href")
            val n = Regex("""/detail/watch/[^/]+/(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                ?: a.text().trim().toIntOrNull() ?: return@forEach
            eps.putIfAbsent(n, "$base/$n?$langQ")
        }
        if (eps.isEmpty()) {
            try {
                val r = NartoBrowser.get("$edgeUrl/e/rs/detail/watch/$slug/check-new-episodes?_t=${System.currentTimeMillis()}", base)
                val c = mapper.readTree(r)?.get("current_count")?.asInt() ?: 0
                for (i in 1..c) eps[i] = "$base/$i?$langQ"
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }

        val recs = try {
            fetch("$base/extras?$langQ", json = true).first.let { body ->
                val h = mapper.readTree(body)?.get("html")?.asText() ?: return@let null
                Jsoup.parse(h, mainUrl).select("a.watch-history-card, a[href*=/detail/watch/]").mapNotNull { a ->
                    val b = detailBase(a.attr("href")) ?: return@mapNotNull null
                    val nm = a.attr("title").ifBlank { a.selectFirst("h3")?.text() ?: "" }
                    if (nm.isBlank()) null
                    else newTvSeriesSearchResponse(cleanTitle(nm), b, TvType.AsianDrama) {
                        this.posterUrl = fixUrl2(a.selectFirst("img")?.attr("src"))
                    }
                }.distinctBy { it.url }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

        if (isMovie || eps.size <= 1) {
            val data = eps.values.firstOrNull() ?: "$base?$langQ"
            if (isMovie || eps.isEmpty()) {
                return newMovieLoadResponse(title, base, TvType.Movie, data) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.tags = tags
                    this.recommendations = recs
                }
            }
        }

        val episodes = eps.map { (n, u) ->
            newEpisode(u) {
                this.name = "Episode $n"
                this.episode = n
                this.season = 1
            }
        }
        return newTvSeriesLoadResponse(title, base, TvType.AsianDrama, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.recommendations = recs
        }
    }

    // ---------------------------------------------------------------- links

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Cloudstream may hand the data back with mainUrl prefixed twice or as a path
        val url = when {
            data.startsWith("http") -> data.substring(data.lastIndexOf("https://").coerceAtLeast(0))
            data.startsWith("/") -> mainUrl + data
            else -> "$mainUrl/$data"
        }
        val sn = NartoBrowser.sniff(url)
        // what <video> plays first, then urls in the player's own api answers, then requests seen
        val fromApi = sn.apiBodies.flatMap { b ->
            Regex("""https?:\\?/\\?/[^\s"'<>]+?\.(?:m3u8|mp4|mpd)[^\s"'<>]*""").findAll(b).map { it.value.replace("\\/", "/") }.toList()
        }
        val all = (sn.video + fromApi + sn.media).filter { it.startsWith("http") }.distinct()
        NartoSettings.lastLinkReport = "Episode $url\nvideo=${sn.video.size} api=${fromApi.size} requests=${sn.media.size}\n" +
            all.take(5).joinToString("\n") { it.take(140) }
        if (all.isEmpty()) {
            NartoSettings.toast("NartoDrama: no video found - the episode may be locked, or open settings > Solve Cloudflare")
            return false
        }

        // a master playlist first, then other playlists, then plain files
        val ordered = all.sortedBy { u ->
            val l = u.lowercase()
            when {
                sn.video.contains(u) && !l.startsWith("blob:") -> -1
                l.contains(".m3u8") && (l.contains("master") || l.contains("index")) -> 0
                l.contains(".m3u8") -> 1
                l.contains(".mpd") -> 2
                else -> 3
            }
        }.distinctBy { it.substringBefore("?") }.take(3).map { NartoBrowser.Hit(it) }
        val ua = NartoBrowser.userAgent()

        ordered.forEachIndexed { i, h ->
            val l = h.url.lowercase()
            val type = when {
                l.contains(".m3u8") -> ExtractorLinkType.M3U8
                l.substringBefore("?").endsWith(".mpd") -> ExtractorLinkType.DASH
                else -> ExtractorLinkType.VIDEO
            }
            val q = Regex("""(?i)(2160|1440|1080|720|540|480|360)p?""").find(h.url)?.groupValues?.get(1)?.toIntOrNull()
            callback.invoke(
                newExtractorLink(name, if (i == 0) name else "$name ${i + 1}", h.url, type) {
                    this.referer = "$mainUrl/"
                    this.quality = q ?: Qualities.Unknown.value
                    this.headers = mapOf("Referer" to "$mainUrl/", "Origin" to mainUrl, "User-Agent" to ua)
                }
            )
        }
        return true
    }
}
