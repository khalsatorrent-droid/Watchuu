package com.watchuu.freeiptv

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

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
    @Volatile private var allStreams: List<LiveStream> = emptyList()
    @Volatile private var allLoadedAt = 0L

    // ---- Xtream calls

    /** A list action; an expired or refused account answers with an object, so renew once and retry. */
    private suspend fun xtreamList(action: String, extra: String = ""): String? {
        for (attempt in 0..1) {
            val acc = accounts.current(force = attempt == 1) ?: return null
            val url = "${acc.server}/player_api.php?username=${acc.user}&password=${acc.pass}&action=$action$extra"
            val text = runCatching { app.get(url, timeout = 120L).text }.getOrNull()
            if (text != null && text.trimStart().startsWith("[")) return text
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

    private suspend fun everything(): List<LiveStream> {
        if (allStreams.isNotEmpty() && System.currentTimeMillis() - allLoadedAt < CACHE_TTL_MS) return allStreams
        val list = tryParseJson<List<LiveStream>>(xtreamList("get_live_streams").orEmpty()).orEmpty()
        if (list.isNotEmpty()) { allStreams = list; allLoadedAt = System.currentTimeMillis() }
        return allStreams
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

    // ---- home page: every category as a row, all channels, in one response. The whole list is
    // fetched with a single call and grouped here, instead of one call per category and a few rows
    // per page (the app only ever asked for the first page).

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList<HomePageList>(), hasNext = false)
        val cats = loadCategories()
        val byCat = everything().groupBy { it.categoryId.orEmpty() }
        val known = cats.mapNotNull { it.id }.toSet()
        val rows = ArrayList<HomePageList>()
        for (c in cats) {
            val cards = byCat[c.id].orEmpty().mapNotNull { it.toCard() }
            if (cards.isNotEmpty()) rows.add(HomePageList(c.name!!.trim(), cards, isHorizontalImages = true))
        }
        // channels whose category the server did not list
        val rest = byCat.filterKeys { it !in known }.values.flatten().mapNotNull { it.toCard() }
        if (rest.isNotEmpty()) rows.add(HomePageList("Other", rest, isHorizontalImages = true))
        return newHomePageResponse(rows, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> =
        everything().asSequence()
            .filter { it.name?.contains(query, ignoreCase = true) == true }
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
