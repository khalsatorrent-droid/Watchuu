package com.watchuu.freeiptv

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Free IPTV: live channels from an Xtream-codes server. The login is a 24 hour account that
 * [FreeIptvAccounts] creates and renews on its own, so channel cards carry only the stream id, never
 * the (short lived) credentials; the account is looked up again every time a stream is opened.
 */
class FreeIptvProvider(private val accounts: FreeIptvAccounts) : MainAPI() {
    override var mainUrl = FreeIptvAccounts.SITE
    override var name = "Free IPTV"
    override var lang = "en"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live, TvType.Others)

    override val mainPage = mainPageOf("live" to "Live TV")

    data class Category(
        @JsonProperty("category_id") val id: String? = null,
        @JsonProperty("category_name") val name: String? = null,
    )

    data class LiveStream(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("stream_id") val streamId: Long? = null,
        @JsonProperty("stream_icon") val icon: String? = null,
        @JsonProperty("category_id") val categoryId: String? = null,
    )

    @Volatile private var categories: List<Category> = emptyList()
    @Volatile private var rows: List<Pair<String, List<LiveStream>>> = emptyList()
    @Volatile private var loadedAt = 0L

    // ---- Xtream calls

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "Accept" to "*/*",
    )

    /**
     * A list action. A JSON array is the answer; a JSON object is the server refusing the login, in
     * which case the account is renewed once and the call repeated. Anything else is reported.
     */
    private suspend fun xtreamList(action: String, extra: String = ""): String? {
        for (attempt in 0..1) {
            val acc = accounts.current(force = attempt == 1) ?: run {
                accounts.report("no account: could not create one")
                return null
            }
            val url = "${acc.server}/player_api.php?username=${acc.user}&password=${acc.pass}&action=$action$extra"
            val text = try {
                app.get(url, headers = headers, timeout = 60L).text.trim().trimStart('\uFEFF')
            } catch (e: Throwable) {
                accounts.report("$action failed: ${e.javaClass.simpleName} ${e.message}")
                return null
            }
            if (text.startsWith("[")) return text
            val refused = text.contains("\"auth\":0") || text.contains("\"user_info\"") || text.contains("Disabled", true) || text.contains("Expired", true)
            accounts.report("$action gave ${text.take(80).ifEmpty { "an empty answer" }}")
            if (!refused) return null
        }
        return null
    }

    private suspend fun loadCategories(): List<Category> {
        if (categories.isNotEmpty()) return categories
        val list = tryParseJson<List<Category>>(xtreamList("get_live_categories").orEmpty()).orEmpty()
            .filter { !it.id.isNullOrBlank() && !it.name.isNullOrBlank() }
        if (list.isNotEmpty()) categories = list
        return list
    }

    private suspend fun fetchStreams(extra: String): List<LiveStream> =
        tryParseJson<List<LiveStream>>(xtreamList("get_live_streams", extra).orEmpty()).orEmpty()

    /**
     * Every row (category name to its channels). One small call per category, eight at a time,
     * rather than one 30k-channel answer that is slow and can fail to parse; if the server lists no
     * categories the whole list is fetched and grouped by the id on each channel.
     */
    private suspend fun catalogue(): List<Pair<String, List<LiveStream>>> {
        if (rows.isNotEmpty() && System.currentTimeMillis() - loadedAt < CACHE_TTL_MS) return rows
        val cats = loadCategories()
        val built: List<Pair<String, List<LiveStream>>> = if (cats.isNotEmpty()) {
            val gate = Semaphore(8)
            coroutineScope {
                cats.map { c -> async { gate.withPermit { c.name!!.trim() to fetchStreams("&category_id=${c.id}") } } }.awaitAll()
            }
        } else {
            fetchStreams("").groupBy { it.categoryId.orEmpty() }.map { (id, list) -> "Category $id" to list }
        }.filter { it.second.isNotEmpty() }
        if (built.isNotEmpty()) {
            rows = built
            loadedAt = System.currentTimeMillis()
            accounts.report(null)
        } else if (accounts.lastError == null) {
            accounts.report("the server returned no channels")
        }
        return rows
    }

    // ---- cards

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    private fun LiveStream.toCard(): SearchResponse? {
        val id = streamId ?: return null
        val title = name?.trim().orEmpty().ifEmpty { "Channel $id" }
        return newLiveSearchResponse(title, "$mainUrl/ch/$id?n=${enc(title)}&i=${enc(icon.orEmpty())}", TvType.Live) {
            this.posterUrl = icon?.takeIf { it.startsWith("http") }
        }
    }

    // ---- home page: every category as a row, all channels, in one response (the app only ever
    // asked for the first page when rows were paged)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList<HomePageList>(), hasNext = false)
        val lists = catalogue().mapNotNull { (name, streams) ->
            val cards = streams.mapNotNull { it.toCard() }
            if (cards.isEmpty()) null else HomePageList(name, cards, isHorizontalImages = true)
        }
        return newHomePageResponse(lists, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> =
        catalogue().asSequence().flatMap { it.second.asSequence() }
            .filter { it.name?.contains(query, ignoreCase = true) == true }
            .distinctBy { it.streamId }
            .take(400)
            .mapNotNull { it.toCard() }
            .toList()

    // ---- playback

    override suspend fun load(url: String): LoadResponse {
        val q = parseQuery(url)
        val title = q["n"].orEmpty().ifEmpty { name }
        val icon = q["i"].orEmpty().takeIf { it.startsWith("http") }
        return newLiveStreamLoadResponse(title, url, url) {
            this.posterUrl = icon
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = data.substringBefore('?').substringAfterLast('/').toLongOrNull() ?: return false
        val title = parseQuery(data)["n"].orEmpty().ifEmpty { name }
        val acc = accounts.current() ?: return false
        val base = "${acc.server}/live/${acc.user}/${acc.pass}/$id"

        callback(newExtractorLink(name, "$title (HLS)", "$base.m3u8", ExtractorLinkType.M3U8) {
            this.quality = Qualities.Unknown.value
        })
        callback(newExtractorLink(name, "$title (TS)", "$base.ts", ExtractorLinkType.VIDEO) {
            this.quality = Qualities.Unknown.value
        })
        return true
    }

    private fun parseQuery(url: String): Map<String, String> =
        url.substringAfter('?', "").split('&').filter { it.contains('=') }.associate {
            it.substringBefore('=') to runCatching { java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }.getOrDefault("")
        }

    companion object {
        private const val ROWS_PER_PAGE = 5
        private const val CACHE_TTL_MS = 30L * 60L * 1000L
    }
}
