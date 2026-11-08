package com.watchuu.cinephile

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * Cinephile (cinephile.live) — built from the capture of 2026-11-08 09:11.
 *
 * The site is a client-rendered app over its own json api, so everything of value comes from
 * https://api.cinephile.live/api/cinephile and nothing has to be scraped:
 *
 *   listing   ?action=home
 *   search    ?action=search&q=<q>&type=movie|tv
 *   detail    ?action=detail&slug=<slug>&subjectId=<id>
 *   seasons   ?action=season_info&id=<subjectId>
 *             -> {seasons:[{se, maxEp, resolutions:[{resolution, epNum}]}]}
 *   files     ?action=resources&id=<subjectId>&se=<se>&ep=<ep>
 *             -> {data:[{resourceLink, resolution:360|480|1080, codecName, se, ep, extCaptions[]}]}
 *
 * A resourceLink is not the file: it is another api call (?action=video&url=<encoded>&exp=&token=)
 * which answers with the signed cdn url that actually plays, of the form
 * https://bcdnxw.hakunaymatama.com/…mp4?sign=… . So each resource is followed to its final url
 * before being offered, and the resolution the api reports is what the link is labelled with -
 * that is where 720p and 1080p come from rather than from guessing at a filename.
 *
 * Site pages look like /watch/<slug>?sid=<subjectId>, and an episode adds &se=<n>&ep=<m>, so the
 * urls the extension produces are the site's own.
 *
 * subjectType 1 is a film and 2 is a series; a series is turned into real episodes from
 * season_info, named S01E01 style, each carrying only its own file list.
 */
class CinephileProvider : MainAPI() {
    override var mainUrl = "https://cinephile.live"
    override var name = "Cinephile"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val api = "https://api.cinephile.live/api/cinephile"
    private val cf = CloudflareKiller()

    override val mainPage = mainPageOf(
        "" to "Latest",
        "movies" to "Movies",
        "series" to "Series",
        "anime" to "Anime"
    )

    private fun userAgent(): String = UA

    private suspend fun apiJson(query: String): JSONObject? = runCatching {
        JSONObject(
            app.get(
                "$api?$query",
                headers = mapOf(
                    "User-Agent" to userAgent(),
                    "Accept" to "application/json, text/plain, */*",
                    "Origin" to mainUrl,
                    "Referer" to "$mainUrl/"
                ),
                interceptor = cf,
                timeout = 30L
            ).text
        )
    }.getOrNull()

    // ── Listings ───────────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val section = request.data.trim('/')
        val filter = when (section) {
            "movies" -> 1
            "series" -> 2
            "anime" -> 3
            else -> 0
        }

        val items = fromHome(filter)
        if (items.isNotEmpty()) {
            return newHomePageResponse(request.name, items, hasNext = false)
        }

        // the api did not answer: read the site's own listing page, whose posters are the
        // background-image div the app renders (div.absolute.inset-0)
        val scraped = scrapeListing(section)
        return newHomePageResponse(request.name, scraped, hasNext = scraped.isNotEmpty())
    }

    /**
     * The site's own hook does exactly this, in this order: fetch ?action=home, and if that yields
     * no sections, fetch ?action=tabcontent&tabId=0. Both answer {ok, data:{sections:[…]}}, where
     * each section holds its own array of subjects - so the payload is walked rather than assumed,
     * and a section-less answer falls through to the next source instead of showing an empty row.
     */
    private suspend fun fromHome(subjectType: Int): List<SearchResponse> {
        val sources = listOf("action=home", "action=tabcontent&tabId=0")

        for (query in sources) {
            val json = apiJson(query) ?: continue
            val found = ArrayList<Pair<SearchResponse, Int>>()
            collectItems(json.opt("data") ?: json, found)

            val picked = found
                .filter { subjectType == 0 || it.second == subjectType }
                .map { it.first }
                .distinctBy { it.url }

            if (picked.isNotEmpty()) return picked
        }
        return emptyList()
    }

    private suspend fun scrapeListing(section: String): List<SearchResponse> {
        val paths = if (section.isBlank()) listOf("", "movies", "series") else listOf(section)
        val out = ArrayList<SearchResponse>()
        for (path in paths) {
            val html = runCatching {
                app.get(
                    "$mainUrl/$path",
                    headers = mapOf("User-Agent" to userAgent(), "Referer" to "$mainUrl/"),
                    interceptor = cf,
                    timeout = 25L
                ).text
            }.getOrNull() ?: continue
            out.addAll(scrapeCards(html))
        }
        return out.distinctBy { it.url }
    }

    /**
     * Walks whatever the api returns and collects every subject it can recognise, at any depth.
     * Fields are read defensively: the listing uses title/cover, a nested subject object appears
     * in some payloads, and name is used elsewhere - none of that should decide whether a row
     * renders.
     */
    private fun collectItems(node: Any?, into: MutableList<Pair<SearchResponse, Int>>, depth: Int = 0) {
        if (depth > 8 || node == null) return

        val array = node as? JSONArray
        if (array != null) {
            for (i in 0 until array.length()) {
                collectItems(array.opt(i), into, depth + 1)
            }
            return
        }

        val obj = node as? JSONObject ?: return
        val subject = obj.optJSONObject("subject") ?: obj
        val slug = subject.optString("slug").ifBlank { obj.optString("slug") }
        val title = subject.optString("title")
            .ifBlank { subject.optString("name") }
            .ifBlank { obj.optString("title") }
            .ifBlank { obj.optString("name") }

        if (slug.isNotBlank() && title.isNotBlank()) {
            val poster = subject.optString("cover")
                .ifBlank { subject.optString("poster_url") }
                .ifBlank { subject.optString("poster") }
                .ifBlank { subject.optString("image") }
                .ifBlank { obj.optString("cover") }
                .ifBlank { null }
            val sid = subject.optString("subjectId").ifBlank { obj.optString("subjectId") }
            val type = subject.optInt("subjectType", obj.optInt("subjectType", 1))
            val url = if (sid.isNotBlank()) "$mainUrl/watch/$slug?sid=$sid" else "$mainUrl/watch/$slug"

            val response: SearchResponse = when (type) {
                2 -> newTvSeriesSearchResponse(title, url, TvType.TvSeries) { this.posterUrl = poster }
                3 -> newTvSeriesSearchResponse(title, url, TvType.Anime) { this.posterUrl = poster }
                else -> newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = poster }
            }
            into.add(response to type)
        }

        val keys = obj.keys()
        while (keys.hasNext()) {
            val child = obj.opt(keys.next())
            if (child is JSONArray || child is JSONObject) collectItems(child, into, depth + 1)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        if (q.isBlank()) return emptyList()

        val out = ArrayList<SearchResponse>()
        listOf("movie", "tv").forEach { type ->
            val json = apiJson("action=search&q=$q&type=$type") ?: return@forEach
            val arr = json.optJSONArray("data") ?: return@forEach
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.toSearchResult()?.let { out.add(it) }
            }
        }
        return out.distinctBy { it.url }
    }

    private fun JSONObject.toSearchResult(): SearchResponse? {
        val slug = optString("slug").ifBlank { return null }
        val title = optString("title").ifBlank { slug.replace('-', ' ') }
        val poster = optString("cover").ifBlank { optString("poster_url").ifBlank { null } }
        val sid = optString("subjectId").ifBlank { null }
        val url = if (sid != null) "$mainUrl/watch/$slug?sid=$sid" else "$mainUrl/watch/$slug"

        return when (optInt("subjectType", 1)) {
            2 -> newTvSeriesSearchResponse(title, url, TvType.TvSeries) { this.posterUrl = poster }
            3 -> newTvSeriesSearchResponse(title, url, TvType.Anime) { this.posterUrl = poster }
            else -> newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = poster }
        }
    }

    /** fallback for a listing page: the app paints posters as div.absolute.inset-0 backgrounds */
    private fun scrapeCards(html: String): List<SearchResponse> {
        val doc = Jsoup.parse(html)
        val out = ArrayList<SearchResponse>()
        doc.select("a[href*=/watch/]").forEach { a ->
            val href = a.attr("href").trim()
            if (href.isBlank()) return@forEach
            val card = a.parent() ?: a
            val style = card.selectFirst("div.absolute.inset-0, div[style*=background-image]")
                ?.attr("style").orEmpty()
            val poster = Regex("""url\(['"]?([^'")]+)['"]?\)""")
                .find(style)?.groupValues?.get(1)
                ?: card.selectFirst("img")?.attr("src")?.ifBlank { null }
            val title = card.selectFirst("h3, h2, .title")?.text()?.trim()
                ?: a.attr("aria-label").trim().ifBlank { null }
                ?: return@forEach
            out.add(newMovieSearchResponse(title, fixUrl(href), TvType.Movie) { this.posterUrl = poster })
        }
        return out.distinctBy { it.url }
    }

    // ── Detail ─────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.substringAfter("/watch/").substringBefore('?').trim('/')
        if (slug.isBlank()) return null
        val sid = Regex("""[?&]sid=(\d+)""").find(url)?.groupValues?.get(1)

        val detail = apiJson(
            "action=detail&slug=${URLEncoder.encode(slug, "UTF-8")}" + (sid?.let { "&subjectId=$it" } ?: "")
        )?.optJSONObject("data")

        val subjectId = detail?.optString("subjectId")?.ifBlank { null } ?: sid
        val title = detail?.optString("title")?.ifBlank { null } ?: slug.replace('-', ' ')
        val poster = detail?.optString("poster_url")?.ifBlank { null }
        val plot = detail?.optString("description")?.ifBlank { null }
        val type = detail?.optInt("subjectType", 1) ?: 1
        val tags = ArrayList<String>()
        detail?.optJSONArray("genres")?.let { arr ->
            for (i in 0 until arr.length()) arr.optString(i).ifBlank { null }?.let { tags.add(it) }
        }
        detail?.optString("country")?.ifBlank { null }?.let { tags.add(it) }
        detail?.optString("imdb")?.ifBlank { null }?.let { tags.add("IMDb $it") }

        if (subjectId == null) {
            return newMovieLoadResponse(title, url, TvType.Movie, "$slug|$slug|0|0") {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        }

        if (type == 2 || type == 3) {
            val seasons = apiJson("action=season_info&id=$subjectId")?.optJSONObject("data")
                ?.optJSONArray("seasons")

            val episodes = ArrayList<Episode>()
            if (seasons != null) {
                for (i in 0 until seasons.length()) {
                    val season = seasons.optJSONObject(i) ?: continue
                    val se = season.optInt("se", i + 1)
                    val maxEp = season.optInt("maxEp", 0)
                    for (ep in 1..maxEp.coerceAtMost(500)) {
                        episodes.add(
                            newEpisode("$slug|$subjectId|$se|$ep") {
                                this.name = "S%02dE%02d".format(se, ep)
                                this.season = se
                                this.episode = ep
                                this.posterUrl = poster
                            }
                        )
                    }
                }
            }

            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.tags = tags
                }
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, "$slug|$subjectId|0|0") {
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
        val parts = data.split('|')
        val slug = parts.getOrNull(0).orEmpty()
        val subjectId = parts.getOrNull(1).orEmpty()
        val se = parts.getOrNull(2)?.toIntOrNull() ?: 1
        val ep = parts.getOrNull(3)?.toIntOrNull() ?: 0
        if (subjectId.isBlank()) return false

        val pageUrl = "$mainUrl/watch/$slug?sid=$subjectId"
        val json = apiJson("action=resources&id=$subjectId&se=$se&ep=$ep") ?: return false
        val arr = json.optJSONArray("data") ?: return false

        var found = false
        for (i in 0 until arr.length()) {
            val res = arr.optJSONObject(i) ?: continue
            val link = res.optString("resourceLink").ifBlank { continue }

            // captions first: they belong to this resource whether or not the file resolves
            res.optJSONArray("extCaptions")?.let { caps ->
                for (c in 0 until caps.length()) {
                    val cap = caps.optJSONObject(c) ?: continue
                    val capUrl = cap.optString("url").ifBlank { cap.optString("src") }.ifBlank { null } ?: continue
                    subtitleCallback.invoke(SubtitleFile(cap.optString("lang", "English"), capUrl))
                }
            }

            val playable = resolve(link, pageUrl) ?: continue
            val resolution = res.optInt("resolution", 0)
            val codec = res.optString("codecName").ifBlank { null }

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = buildString {
                        append(name)
                        if (resolution > 0) append(" · ${resolution}p")
                        if (codec != null) append(" · $codec")
                    },
                    url = playable,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = pageUrl
                    this.quality = if (resolution > 0) resolution else Qualities.Unknown.value
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

    /**
     * A resourceLink is an api call that hands back the signed cdn url. It may answer as a
     * redirect, as {url: …}, or as {data:{url: …}}; all three are handled, and an html answer is
     * searched for an mp4 as a last resort.
     */
    private suspend fun resolve(resourceLink: String, referer: String): String? {
        val res = runCatching {
            app.get(
                resourceLink,
                headers = mapOf(
                    "User-Agent" to userAgent(),
                    "Accept" to "*/*",
                    "Origin" to mainUrl,
                    "Referer" to referer
                ),
                allowRedirects = true,
                timeout = 25L
            )
        }.getOrNull() ?: return null

        val finalUrl = res.url
        if (finalUrl != resourceLink && finalUrl.contains(".mp4", true)) return finalUrl

        val text = runCatching { res.text }.getOrNull().orEmpty()
        if (text.isBlank()) return null

        runCatching {
            val json = JSONObject(text)
            json.optString("url").ifBlank { null }
                ?: json.optJSONObject("data")?.optString("url")?.ifBlank { null }
        }.getOrNull()?.let { if (it.startsWith("http")) return it }

        return Regex("""https?://[^\s"'<>\\]+\.mp4[^\s"'<>\\]*""").find(text)?.value
    }

    private companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 16; SM-S911B Build/BP4A.251205.006) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.47 Mobile Safari/537.36"
    }
}
