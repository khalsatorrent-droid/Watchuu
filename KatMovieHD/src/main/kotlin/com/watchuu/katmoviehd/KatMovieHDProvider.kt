package com.watchuu.katmoviehd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * KatMovieHD (new2.katmoviehd.top), reached through links.kmhd.me — from the capture of
 * 2026-11-05 19:09.
 *
 * The site is a SvelteKit app, so it does not serve html for its data: it serves devalue
 * payloads at /__data.json, where values are indices into one flat array. That is why the
 * builder could not find cards - there are none in the html.
 *
 *   listing   /__data.json?page=N
 *   search    /__data.json?q=<query>
 *   detail    /<id>/__data.json          -> post_content, the full html of the post
 *
 * The shape, from the capture:
 *   {"type":"chunk","id":1,"data":[{"success":1,"data":2}, true,
 *     {"page":3,"perPage":4,"totalItems":5,"totalPages":5,"items":6}, 2, 12, -1, [7,20,…],
 *     {"created":8,"post_title":9,"slug":10,"thumbnail_image":11,"categories":12,"id":18}, …]}
 * so a field is read by taking its index and looking that up in the same array.
 *
 * Links come out of post_content. Modern posts point at the site's own redirector,
 *
 *   https://gd.kmhd.me/file/<id>   ->  302  ->  https://gdflix.dev/file/<id>
 *
 * which is followed here so the extractor sees the host it knows; gdflix, hubcloud and hubdrive
 * all have Cloudstream extractors, and anything already pointing at one of them is passed
 * straight through. Older posts carry dead hosts (openload, uptobox, bdupload) and simply yield
 * nothing, which is correct - those files are gone.
 */
class KatMovieHDProvider : MainAPI() {
    override var mainUrl = "https://new2.katmoviehd.top"
    override var name = "KatMovieHD"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "" to "Latest"
    )

    private fun userAgent(): String = UA

    private suspend fun fetch(url: String, referer: String = "$mainUrl/"): String? =
        runCatching {
            app.get(
                url,
                headers = mapOf(
                    "User-Agent" to userAgent(),
                    "Accept" to "application/json, text/html;q=0.9, */*;q=0.8",
                    "Referer" to referer
                ),
                timeout = 25L
            ).text
        }.getOrNull()

    private suspend fun dataJson(pathAndQuery: String): String? =
        fetch("$mainUrl$pathAndQuery" + (if (pathAndQuery.contains("?")) "&" else "?") + "x-sveltekit-trailing-slash=1")

    // ── Devalue ────────────────────────────────────────────────────────────────

    /** The flat index arrays from a SvelteKit payload, one per line of the body. */
    private fun chunks(body: String): List<JSONArray> =
        body.lineSequence()
            .mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }
            .mapNotNull { it.optJSONArray("data") }
            .toList()

    private fun JSONArray.str(index: Int): String? =
        if (index < 0 || index >= length()) null else optString(index, null)

    // ── Listings ───────────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val query = if (page > 1) "/__data.json?page=$page" else "/__data.json"
        val body = dataJson(query) ?: return newHomePageResponse(request.name, emptyList(), false)
        val (items, hasNext) = parseListing(body)
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        if (q.isBlank()) return emptyList()
        val body = dataJson("/__data.json?q=$q") ?: return emptyList()
        return parseListing(body).first
    }

    private fun parseListing(body: String): Pair<List<SearchResponse>, Boolean> {
        val arr = chunks(body).firstOrNull { it.optJSONObject(0)?.has("success") == true }
            ?: return emptyList<SearchResponse>() to false

        val root = arr.optJSONObject(0) ?: return emptyList<SearchResponse>() to false
        val dataObj = arr.optJSONObject(root.optInt("data", -1)) ?: return emptyList<SearchResponse>() to false
        val itemsIdx = dataObj.optInt("items", -1)
        val itemsArr = if (itemsIdx in 0 until arr.length()) arr.optJSONArray(itemsIdx) else null
            ?: return emptyList<SearchResponse>() to false

        val page = dataObj.optInt("page", 1)
        val totalPages = dataObj.optInt("totalPages", 1)

        val out = ArrayList<SearchResponse>()
        for (i in 0 until itemsArr.length()) {
            val idx = itemsArr.optInt(i, -1)
            val o = if (idx in 0 until arr.length()) arr.optJSONObject(idx) else null ?: continue

            val title = arr.str(o.optInt("post_title", -1))
                ?.replace("&amp;", "&")?.replace("&#8217;", "'")?.trim()
                ?: continue
            val slug = arr.str(o.optInt("slug", -1)) ?: continue
            val thumb = arr.str(o.optInt("thumbnail_image", -1))

            out.add(
                newMovieSearchResponse(title, "$mainUrl/$slug", TvType.Movie) {
                    this.posterUrl = thumb
                }
            )
        }
        return out to (page < totalPages)
    }

    /**
     * Series posts group their links per episode:
     *
     *   Episode 1 -
     *     1080p // 720p // 480p
     *     720p 10bit // 1080p 10bit
     *     2160p HDR/DV // 2160p SDR
     *   Episode 2 -
     *     ...
     *
     * so the post is cut at each "Episode N -" marker and each piece keeps only its own links.
     * A post with no such markers is a single film and comes back as one section under 0.
     */
    private fun episodeSections(html: String): List<Pair<Int, String>> {
        val markers = Regex("""Episode\s*(\d{1,3})\s*[-–—]""", RegexOption.IGNORE_CASE).findAll(html).toList()
        if (markers.isEmpty()) return listOf(0 to html)

        val out = ArrayList<Pair<Int, String>>()
        markers.forEachIndexed { i, m ->
            val number = m.groupValues[1].toIntOrNull() ?: (i + 1)
            val start = m.range.last + 1
            val end = markers.getOrNull(i + 1)?.range?.first ?: html.length
            if (start < end) out.add(number to html.substring(start, end))
        }
        return out.ifEmpty { listOf(0 to html) }
    }

    /** Anchors in a section, with the link text as the label ("2160p HDR/DV", "720p 10bit"). */
    private fun labelledAnchors(section: String): List<Pair<String, String>> =
        Regex("""<a[^>]+href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(section)
            .map { m ->
                val url = m.groupValues[1].replace("&amp;", "&").trim()
                val label = m.groupValues[2].replace(Regex("<[^>]+>"), " ")
                    .replace("&amp;", "&").replace(Regex("\\s+"), " ").trim()
                url to label
            }
            .filter { it.first.startsWith("http") }
            .distinctBy { it.first }
            .toList()

    // ── Detail ─────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.trimEnd('/').substringAfterLast('/')
        if (slug.isBlank()) return null

        val body = dataJson("/$slug/__data.json") ?: return null
        val arr = chunks(body).firstOrNull() ?: return null

        // the detail chunk is [{"success":1,"data":2}, true, {"post_content":3}, "<html>"]
        var content: String? = null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.has("post_content")) {
                content = arr.str(o.optInt("post_content", -1))
                break
            }
        }

        val html = content.orEmpty()
        val title = Regex("""<h[1-3][^>]*>(.*?)</h[1-3]>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)
            ?.replace(Regex("<[^>]+>"), "")?.trim()?.ifBlank { null }
            ?: slug.replace('-', ' ').replaceFirstChar { it.uppercase() }
        val poster = Regex("""<img[^>]+src="([^"]+)"""")
            .find(html)?.groupValues?.get(1)?.ifBlank { null }
        val plot = Regex("""Movie Plot:?</b>(.{20,400}?)</p>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)
            ?.replace(Regex("<[^>]+>"), "")?.trim()?.ifBlank { null }

        // a post with "Episode N -" sections is a series; each episode carries its own links
        val sections = episodeSections(html)
        if (sections.size > 1 || sections.first().first != 0) {
            val episodes = sections.map { (number, section) ->
                newEpisode("$slug|$number") {
                    this.name = "Episode $number"
                    this.season = 1
                    this.episode = number
                    this.posterUrl = poster
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
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
        // data is "<slug>" for a film, or "<slug>|<episode>" for one episode of a series
        val slug = data.substringBefore('|').trimEnd('/').substringAfterLast('/')
        val wantedEpisode = data.substringAfter('|', "").toIntOrNull()

        val body = dataJson("/$slug/__data.json") ?: return false
        val arr = chunks(body).firstOrNull() ?: return false

        val html = arr.let { a ->
            var c: String? = null
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                if (o.has("post_content")) { c = a.str(o.optInt("post_content", -1)); break }
            }
            c.orEmpty()
        }
        if (html.isBlank()) return false

        // take the section for the episode asked for; the link text is the label
        val sections = episodeSections(html)
        val section = if (wantedEpisode != null) {
            sections.firstOrNull { it.first == wantedEpisode }?.second
                ?: sections.firstOrNull()?.second.orEmpty()
        } else {
            sections.joinToString("\n") { it.second }
        }
        val candidates = LinkedHashMap<String, String>() // url -> label
        labelledAnchors(section).forEach { (u, label) -> if (!candidates.containsKey(u)) candidates[u] = label }

        var found = false
        candidates.forEach { (rawUrl, label) ->
            // the site's own redirectors resolve to the host an extractor knows
            val url = if (rawUrl.contains("kmhd.me")) {
                runCatching { app.get(rawUrl, allowRedirects = true).url }.getOrNull() ?: rawUrl
            } else rawUrl

            val got = mutableListOf<ExtractorLink>()
            runCatching {
                loadExtractor(url, "$mainUrl/", subtitleCallback) { got.add(it) }
            }
            if (got.isNotEmpty()) {
                got.forEach { link ->
                    callback.invoke(
                        newExtractorLink(
                            source = link.source ?: name,
                            name = if (label.isBlank()) link.name else "$label · ${link.name}",
                            url = link.url,
                            type = link.type
                        ) {
                            this.referer = link.referer.ifBlank { "$mainUrl/" }
                            this.quality = qualityOf(label, link.quality)
                            this.headers = link.headers
                        }
                    )
                }
                found = true
            }
        }

        return found
    }

    private fun qualityOf(label: String, fallback: Int): Int = when (label.lowercase()) {
        "2160p", "4k" -> Qualities.P2160.value
        "1440p" -> Qualities.P1440.value
        "1080p" -> Qualities.P1080.value
        "720p" -> Qualities.P720.value
        "480p" -> Qualities.P480.value
        else -> fallback
    }

    private companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 16; SM-S911B Build/BP4A.251205.006) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.47 Mobile Safari/537.36"
    }
}
