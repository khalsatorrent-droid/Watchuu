package com.watchuu.xfantazy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import kotlin.random.Random

/**
 * xfantazy.com — written from the captures of 2026-10-29 21:43 and 2026-10-30 18:35.
 *
 * The site is Next.js behind Cloudflare. Two things make it awkward and both are handled here:
 *
 * 1. Document pages (/ , /top , /trending , /video/… ) answer a plain client with a Cloudflare
 *    challenge, and even when they do arrive the card data is inlined in `self.__next_f.push(…)`
 *    with escaped quotes. The App-Router data endpoints avoid both problems: the page URL plus
 *    `?_rsc=<random>` and the header `RSC: 1` return clean, unescaped JSON.
 *
 * 2. Cards are component props:
 *      {"thumb":{"id":"<mongoId>","title":"…","titleES":"…","duration":"20:21",
 *                "userFileId":"…","viewCount":486,"hru":"<slug>","trending":21},"index":0}
 *    Poster: https://s.xfantazy.com/thumbnail/{userFileId}/w320h180/0
 *
 * Endpoints (all confirmed in the captures):
 *    /top                          newest videos          ?page=N for more
 *    /trending                     trending                ?page=N
 *    /top?sort=mostViewed          most viewed
 *    /search/{query}               search                  ?page=N
 *    /video/{hru}?_rsc=…           detail, carries the `videoInfo` object
 *    https://api.xfantazy.com/pub/video-sources/{mongoId}
 *          → {"sources":[{"type":"video/mp4","label":"360p","src":"https://…"},
 *                        {"type":"video/mp4","label":"original","src":"blocked","blocked":true}]}
 *    Streams need `Referer: https://xfantazy.com/`; the free one is 360p, "original" is blocked.
 *
 * Deliberately plain: only the long-standing MainAPI members are used (no paginated
 * search(query, page) overload, no sequentialMainPage), so the provider loads on older
 * Cloudstream builds too.
 */
class XfantazyProvider : MainAPI() {
    override var mainUrl = "https://xfantazy.com"
    override var name = "XFantazy"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val apiUrl = "https://api.xfantazy.com"
    private val thumbBase = "https://s.xfantazy.com/thumbnail"

    private val ua =
        "Mozilla/5.0 (Linux; Android 16; SM-S911B Build/BP4A.251205.006) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/156.0.8078.47 Mobile Safari/537.36"

    private val cf by lazy { CloudflareKiller() }

    // "path|query" — everything after the pipe is a fixed query, `page` is appended per request.
    override val mainPage = mainPageOf(
        "top" to "New",
        "trending" to "Trending",
        "top|sort=mostViewed" to "Most Viewed"
    )

    // ── Home ───────────────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.substringBefore('|')
        val sort = request.data.substringAfter('|', "")
        val query = buildList {
            if (sort.isNotBlank()) add(sort)
            if (page > 1) add("page=$page")
        }.joinToString("&")

        val items = cards(payload(path, query))
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // ── Search ─────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        if (q.isBlank()) return emptyList()

        val first = cards(payload("search/$q", ""))
        if (first.isEmpty()) return first
        // One more page merged in, so a search returns a usable list without the newer
        // paginated-search API.
        val second = cards(payload("search/$q", "page=2"))
        return (first + second).distinctBy { it.url }
    }

    // ── Detail ─────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val path = url.removePrefix(mainUrl).trimStart('/').substringBefore('?')
        if (path.isBlank()) return null

        var body = get(rscUrl(path, ""), rsc = true).orEmpty()
        if (!body.contains("\"videoInfo\"")) {
            body = get("$mainUrl/$path").orEmpty().replace("\\\"", "\"")
        }
        val scope = body.substring(maxOf(body.indexOf("\"videoInfo\""), 0))

        val id = firstMatch(scope, """"id"\s*:\s*"([0-9a-f]{24})"""")
        val title = firstMatch(scope, """"title"\s*:\s*"((?:[^"\\]|\\.)*)"""")
            ?.let(::unescape)
            ?: url.substringAfterLast('/').replace('-', ' ').trim().ifBlank { "Video" }
        val description = firstMatch(scope, """"description"\s*:\s*"((?:[^"\\]|\\.)*)"""")
            ?.let(::unescape)
        val userFileId = firstMatch(scope, """"userFileId"\s*:\s*"([^"]+)"""")

        val tags = (namesIn(scope, "tags") + namesIn(scope, "categories")).distinct()

        return newMovieLoadResponse(title, url, TvType.NSFW, id ?: url) {
            this.posterUrl = userFileId?.let { thumb(it) }
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
        val id = if (data.matches(Regex("[0-9a-f]{24}"))) data
        else firstMatch(data, """/video/([0-9a-f]{24})""") ?: return false

        // Same order the site uses: client-token first (it sets the k2sAccessToken cookie).
        val apiHeaders = mapOf(
            "User-Agent" to ua,
            "Accept" to "*/*",
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/"
        )
        runCatching { app.get("$apiUrl/pub/client-token", headers = apiHeaders) }

        val json = runCatching {
            app.get("$apiUrl/pub/video-sources/$id", headers = apiHeaders).text
        }.getOrNull() ?: return false

        val sources = Regex(""""sources"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
            .find(json)?.groupValues?.get(1) ?: return false

        var found = false
        Regex("""\{[^{}]*\}""").findAll(sources).forEach { obj ->
            val o = obj.value
            if (blockedRx.containsMatchIn(o)) return@forEach
            val src = firstMatch(o, """"src"\s*:\s*"([^"]+)"""") ?: return@forEach
            if (!src.startsWith("http")) return@forEach
            val label = firstMatch(o, """"label"\s*:\s*"([^"]+)"""") ?: "360p"

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name ($label)",
                    url = src,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = getQualityFromName(label)
                    this.headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to ua)
                }
            )
            found = true
        }
        return found
    }

    // ── Transport ──────────────────────────────────────────────────────────────

    private suspend fun get(url: String, rsc: Boolean = false): String? {
        val headers = mutableMapOf(
            "User-Agent" to ua,
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.9",
            "Referer" to "$mainUrl/"
        )
        if (rsc) headers["RSC"] = "1"

        val plain = runCatching { app.get(url, headers = headers).text }.getOrNull()
        if (plain != null && !isChallenge(plain)) return plain

        val solved = runCatching {
            app.get(url, headers = headers, interceptor = cf).text
        }.getOrNull()
        if (solved != null && !isChallenge(solved)) return solved
        return null
    }

    private fun isChallenge(body: String): Boolean {
        if (body.length > 4000) return false
        return body.contains("Just a moment", ignoreCase = true) ||
            body.contains("challenge-platform") ||
            body.contains("cf_chl_opt") ||
            body.contains("Attention Required", ignoreCase = true)
    }

    private fun rscUrl(path: String, query: String): String {
        val base = if (path.isBlank()) "$mainUrl/" else "$mainUrl/$path"
        val q = buildList {
            if (query.isNotBlank()) add(query)
            add("_rsc=${randomToken()}")
        }.joinToString("&")
        return "$base?$q"
    }

    private fun randomToken(): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return (1..16).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }

    /** RSC payload when it works, otherwise the page with the escaped flight data unescaped. */
    private suspend fun payload(path: String, query: String): String {
        get(rscUrl(path, query), rsc = true)?.let { if (it.contains("userFileId")) return it }

        val plainUrl = if (path.isBlank()) "$mainUrl/" else "$mainUrl/$path"
        val withQuery = if (query.isBlank()) plainUrl else "$plainUrl?$query"
        return get(withQuery)?.replace("\\\"", "\"") ?: ""
    }

    // ── Cards ──────────────────────────────────────────────────────────────────

    private fun cards(payload: String): List<SearchResponse> {
        if (payload.isBlank()) return emptyList()

        return Regex(""""thumb"\s*:\s*\{([^{}]*)\}""").findAll(payload).mapNotNull { match ->
            val block = match.groupValues[1]
            val id = firstMatch(block, """"id"\s*:\s*"([0-9a-f]{24})"""") ?: return@mapNotNull null
            val rawTitle = firstMatch(block, """"title"\s*:\s*"((?:[^"\\]|\\.)*)"""")
                ?: return@mapNotNull null
            val title = unescape(rawTitle).trim()
            if (title.isBlank()) return@mapNotNull null

            val userFileId = firstMatch(block, """"userFileId"\s*:\s*"([^"]+)"""")
            val hru = firstMatch(block, """"hru"\s*:\s*"([^"]+)"""")
            val url = if (!hru.isNullOrBlank()) fixUrl("/video/$hru") else fixUrl("/video/$id")

            newMovieSearchResponse(title, url, TvType.NSFW) {
                this.posterUrl = userFileId?.let { thumb(it) }
            }
        }.distinctBy { it.url }.toList()
    }

    private fun thumb(userFileId: String) = "$thumbBase/$userFileId/w320h180/0"

    /** `"tags":[{…},{"name":"…"}]` → the names inside that array. */
    private fun namesIn(scope: String, field: String): List<String> {
        val array = Regex("\"$field\"\\s*:\\s*\\[(.*?)\\]", RegexOption.DOT_MATCHES_ALL)
            .find(scope)?.groupValues?.get(1) ?: return emptyList()
        return Regex(""""name"\s*:\s*"((?:[^"\\]|\\.)*)"""")
            .findAll(array).map { unescape(it.groupValues[1]).trim() }
            .filter { it.isNotBlank() }.toList()
    }

    private val blockedRx = Regex(""""blocked"\s*:\s*true""")

    private fun firstMatch(text: String, regex: String): String? =
        Regex(regex).find(text)?.groupValues?.get(1)

    private fun unescape(value: String): String {
        if (!value.contains('\\')) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (val n = value[i + 1]) {
                    'n', 'r', 't' -> { out.append(' '); i += 2 }
                    'u' -> {
                        val hex = value.substring(i + 2, minOf(i + 6, value.length))
                        val code = hex.toIntOrNull(16)
                        if (code != null) out.append(code.toChar())
                        i += 6
                    }
                    else -> { out.append(n); i += 2 }
                }
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}
