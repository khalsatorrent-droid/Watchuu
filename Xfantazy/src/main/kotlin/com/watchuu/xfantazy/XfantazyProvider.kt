package com.watchuu.xfantazy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import kotlin.random.Random

/**
 * XFantazy — xfantazy.com (Next.js App Router + Cloudflare)
 *
 * Everything below is taken from the captured network log (CS3 Extension Builder, 968 requests).
 *
 * WHY THE OLD VERSION SHOWED NOTHING
 *  - The document pages (/ , /top , /trending , /video/... , /category/... , /tag/...) are behind
 *    Cloudflare and answer plain OkHttp with `503 + bot challenge` (see log entries 52, 54, 58, 59,
 *    61, 91, 114, 131, 144, 147, ...). So the HTML the provider was parsing was a challenge page,
 *    not the listing → zero cards.
 *  - Even when the HTML does arrive, the App-Router payload is inlined in `self.__next_f.push(...)`
 *    with escaped quotes (`\"thumb\":{...}`), so a regex looking for `"thumb":{` never matches.
 *
 * WHAT WORKS (all 200 in the log, no challenge, plain unescaped JSON):
 *  - Listing / search / detail data: the RSC endpoint, i.e. the page URL plus `?_rsc=<random>`
 *    and the request header `RSC: 1`.
 *        /top                      → "The Newest Porn Videos ..."  (log 41, 47, 135, 139, 148, 156)
 *        /trending                 → "Trending Porn Videos"        (log 38, 45, 125, 172, 178)
 *        /search/{query}?page=N    → generic listing              (log 183, 208, 214)
 *        /video/{hru}?_rsc=...     → `videoInfo` object            (log 221)
 *    Card shape:
 *        {"thumb":{"id":"<mongoId>","title":"...","titleES":"...","duration":"20:21",
 *                  "userFileId":"...","viewCount":486,"hru":"<slug>","trending":21},"index":0}
 *  - Poster: https://s.xfantazy.com/thumbnail/{userFileId}/w320h180/0
 *  - Pagination: `?page=N` on every listing and on search (verified up to page 4).
 *  - Streams: GET https://api.xfantazy.com/pub/video-sources/{mongoId}
 *        → {"sources":[{"type":"video/mp4","label":"360p","src":"https://..."},
 *                      {"type":"video/mp4","label":"original","src":"blocked","blocked":true}]}
 *    Needs `Origin` + `Referer` = https://xfantazy.com . `/pub/client-token` is called first,
 *    exactly like the site does, so the k2sAccessToken cookie is in place.
 *    The mp4 URL is IP-bound (`ip_access_policy=first`, `client_ip=...`) so it must be played
 *    from the same connection — that is the normal CloudStream flow.
 *
 * NOTE ON THE AGE GATE: there is no age-confirmation cookie or POST anywhere in the capture
 * (cookies seen: _ga, _ga_*, cf_clearance, zone-cap-4852, k2sAccessToken). What blocks a plain
 * client is the Cloudflare challenge, not a consent click. This provider therefore never loads a
 * document page unless it has to, and falls back to CloudflareKiller when it does — no clicking.
 */
class XfantazyProvider : MainAPI() {
    override var mainUrl = "https://xfantazy.com"
    override var name = "XFantazy"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    // Home sections are fetched one by one with a small gap; the origin 503s when hit in parallel.
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 300L

    private val apiUrl = "https://api.xfantazy.com"
    private val thumbBase = "https://s.xfantazy.com/thumbnail"

    private val mobileUA =
        "Mozilla/5.0 (Linux; Android 16; SM-S911B Build/BP4A.251205.006) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/156.0.8078.47 Mobile Safari/537.36"

    private val cfKiller by lazy { CloudflareKiller() }

    // "path|query" — the part after the pipe is a fixed query (sort), `page` is appended per request.
    override val mainPage = mainPageOf(
        "top" to "New",
        "trending" to "Trending",
        "top|sort=mostViewed" to "Most Viewed"
    )

    // ── Home ───────────────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = fetchListing(request.data, page)
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private suspend fun fetchListing(section: String, page: Int): List<SearchResponse> {
        val path = section.substringBefore('|')
        val sort = section.substringAfter('|', "")
        val query = buildList {
            if (sort.isNotBlank()) add(sort)
            if (page > 1) add("page=$page")
        }.joinToString("&")

        val payload = fetchPayload(path, query)
        val cards = parseCards(payload)
        // Very first page of "New" falling back to the home page listing, in case /top is empty.
        if (cards.isEmpty() && path == "top" && page == 1) {
            return parseCards(fetchPayload("", ""))
        }
        return cards
    }

    // ── Search (paginated) ─────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> = searchPage(query, 1)

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val items = searchPage(query, page)
        return newSearchResponseList(items, hasNext = items.isNotEmpty())
    }

    private suspend fun searchPage(query: String, page: Int): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        if (q.isBlank()) return emptyList()
        val path = "search/$q"
        val p = if (page > 1) "page=$page" else ""
        return parseCards(fetchPayload(path, p))
    }

    // ── Detail ─────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val path = url.removePrefix(mainUrl).trimStart('/').substringBefore('?')
        if (path.isBlank()) return null

        // Prefer the RSC payload; fall back to the rendered document only if it is not there.
        var payload = runCatching { request(rscUrl(path, ""), isRsc = true) }.getOrNull().orEmpty()
        if (!payload.contains("\"videoInfo\"")) {
            val html = runCatching { request("$mainUrl/$path", isRsc = false) }.getOrNull().orEmpty()
            payload = unescapeFlight(html)
        }

        val scope = payload.substring(maxOf(payload.indexOf("\"videoInfo\""), 0))

        val mongoId = idRegex.find(scope)?.groupValues?.get(1)
        val title = titleRegex.find(scope)?.groupValues?.get(1)?.let(::jsonUnescape)
            ?: url.substringAfterLast("/").replace("-", " ").trim()
        val description = descriptionRegex.find(scope)?.groupValues?.get(1)?.let(::jsonUnescape)
        val userFileId = userFileIdRegex.find(scope)?.groupValues?.get(1)
        val poster = userFileId?.let { thumb(it) } ?: ogImage(payload)

        val tags = buildList {
            val tagsArray = Regex(""""tags"\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
                .find(scope)?.groupValues?.get(1)
            if (tagsArray != null) {
                Regex(""""name"\s*:\s*"((?:[^"\\]|\\.)*)"""")
                    .findAll(tagsArray).map { jsonUnescape(it.groupValues[1]).trim() }
                    .filter { it.isNotBlank() }.forEach { add(it) }
            }
            val catArray = Regex(""""categories"\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
                .find(scope)?.groupValues?.get(1)
            if (catArray != null) {
                Regex(""""name"\s*:\s*"((?:[^"\\]|\\.)*)"""")
                    .findAll(catArray).map { jsonUnescape(it.groupValues[1]).trim() }
                    .filter { it.isNotBlank() }.forEach { add(it) }
            }
        }.distinct()

        return newMovieLoadResponse(title, url, TvType.NSFW, mongoId ?: url) {
            this.posterUrl = poster
            this.plot = description
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
        val videoId = if (data.matches(Regex("[0-9a-f]{24}"))) {
            data
        } else {
            idRegex.find(data)?.groupValues?.get(1) ?: return false
        }

        val headers = mapOf(
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/",
            "Accept" to "*/*",
            "User-Agent" to mobileUA
        )

        // Same order as the site: client-token first (sets k2sAccessToken), then the sources.
        runCatching { app.get("$apiUrl/pub/client-token", headers = headers) }

        val json = runCatching {
            app.get("$apiUrl/pub/video-sources/$videoId", headers = headers).text
        }.getOrNull().orEmpty()

        val sourcesArray = Regex(""""sources"\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
            .find(json)?.groupValues?.get(1) ?: return false

        var found = false
        Regex("""\{[^{}]*}""").findAll(sourcesArray).forEach { obj ->
            val o = obj.value
            if (blockedRegex.containsMatchIn(o)) return@forEach
            val src = srcRegex.find(o)?.groupValues?.get(1) ?: return@forEach
            if (!src.startsWith("http")) return@forEach // "blocked" / empty
            val label = labelRegex.find(o)?.groupValues?.get(1) ?: "360p"

            callback(
                newExtractorLink(
                    source = name,
                    name = "$name ($label)",
                    url = src,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = getQualityFromName(label)
                    this.headers = mapOf(
                        "Referer" to "$mainUrl/",
                        "User-Agent" to mobileUA
                    )
                }
            )
            found = true
        }
        return found
    }

    // ── Transport ──────────────────────────────────────────────────────────────

    /** RSC fetch first (clean JSON), document fetch as a fallback. */
    private suspend fun fetchPayload(path: String, query: String): String {
        val rsc = runCatching { request(rscUrl(path, query), isRsc = true) }.getOrNull()
        if (rsc != null && rsc.contains("\"userFileId\"")) return rsc

        val plain = buildString {
            append(if (path.isBlank()) mainUrl else "$mainUrl/$path")
            if (query.isNotBlank()) append("?$query")
        }
        val html = runCatching { request(plain, isRsc = false) }.getOrNull().orEmpty()
        return unescapeFlight(html)
    }

    private fun rscUrl(path: String, query: String): String {
        val base = if (path.isBlank()) "$mainUrl/" else "$mainUrl/$path"
        val q = buildList {
            if (query.isNotBlank()) add(query)
            add("_rsc=${rnd()}")
        }.joinToString("&")
        return "$base?$q"
    }

    private suspend fun request(url: String, isRsc: Boolean): String {
        val headers = mutableMapOf(
            "User-Agent" to mobileUA,
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.9",
            "Referer" to "$mainUrl/"
        )
        if (isRsc) headers["RSC"] = "1"

        val first = app.get(url, headers = headers, allowRedirects = true)
        if (first.code == 403 || first.code == 503 || isChallenge(first.text)) {
            return app.get(
                url,
                headers = headers,
                allowRedirects = true,
                interceptor = cfKiller
            ).text
        }
        return first.text
    }

    /** Cloudflare interstitial / bot challenge, not real content. */
    private fun isChallenge(body: String): Boolean =
        body.length < 4000 && (
            body.contains("Just a moment", ignoreCase = true) ||
                body.contains("challenge-platform") ||
                body.contains("cf-browser-verification") ||
                body.contains("cf_chl_opt") ||
                body.contains("Attention Required", ignoreCase = true)
            )

    private fun rnd(n: Int = 16): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return (1..n).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }

    /** App-Router inlines the payload inside `self.__next_f.push([1,"..."])` with escaped quotes. */
    private fun unescapeFlight(html: String): String =
        html.replace("\\\"", "\"").replace("\\n", " ")

    // ── Parsing ────────────────────────────────────────────────────────────────

    private val thumbBlockRegex = Regex(""""thumb"\s*:\s*\{([^{}]*)}""")
    private val idRegex = Regex(""""id"\s*:\s*"([0-9a-f]{24})"""")
    private val titleRegex = Regex(""""title"\s*:\s*"((?:[^"\\]|\\.)*)"""")
    private val descriptionRegex = Regex(""""description"\s*:\s*"((?:[^"\\]|\\.)*)"""")
    private val userFileIdRegex = Regex(""""userFileId"\s*:\s*"([^"]+)"""")
    private val hruRegex = Regex(""""hru"\s*:\s*"([^"]+)"""")
    private val srcRegex = Regex(""""src"\s*:\s*"([^"]+)"""")
    private val labelRegex = Regex(""""label"\s*:\s*"([^"]+)"""")
    private val blockedRegex = Regex(""""blocked"\s*:\s*true""")

    private fun parseCards(payload: String): List<SearchResponse> =
        thumbBlockRegex.findAll(payload).mapNotNull { match ->
            val block = match.groupValues[1]
            val id = idRegex.find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            val rawTitle = titleRegex.find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            val title = jsonUnescape(rawTitle).trim()
            if (title.isBlank()) return@mapNotNull null

            val userFileId = userFileIdRegex.find(block)?.groupValues?.get(1)
            val hru = hruRegex.find(block)?.groupValues?.get(1)
            val videoUrl = if (!hru.isNullOrBlank()) fixUrl("/video/$hru") else fixUrl("/video/$id")

            newMovieSearchResponse(title, videoUrl, TvType.NSFW) {
                this.posterUrl = userFileId?.let { thumb(it) }
            }
        }.distinctBy { it.url }.toList()

    private fun thumb(userFileId: String) = "$thumbBase/$userFileId/w320h180/0"

    private fun ogImage(payload: String): String? =
        Regex("""<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']""")
            .find(payload)?.groupValues?.get(1)

    private fun jsonUnescape(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n', 'r', 't' -> { sb.append(' '); i += 2 }
                    'u' -> {
                        val hex = s.substring(i + 2, minOf(i + 6, s.length))
                        val code = hex.toIntOrNull(16)
                        sb.append(if (code != null) code.toChar() else ' ')
                        i += 6
                    }
                    else -> { sb.append(n); i += 2 }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
