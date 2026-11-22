package com.watchuu.clowniptvplus

import android.content.SharedPreferences
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

/**
 * Clown IPTV playlist with a country / region filter.
 *
 * Design:
 *   - [mainPage]    → ALL rows always listed (bridge discovers every row)
 *   - [getMainPage] → returns channels only for rows that match the selected countries;
 *                     returns empty for non-matching rows — so the bridge catalog only
 *                     contains channels from the selected countries
 *   - [search]      → filtered by selected countries
 *
 * "All countries" (empty selection) = every row returns its full channel list.
 */
class ClownIPTVPlusProvider(private val prefs: SharedPreferences?) : MainAPI() {
    override var mainUrl = MAIN_URL
    override var name = "Clown IPTV+"
    override var lang = "en"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    data class Channel(
        val name: String,
        val url: String,
        val logo: String,
        val section: String,
        val adult: Boolean,
    )

    @Volatile private var channels: List<Channel> = emptyList()
    @Volatile private var rows: Map<String, List<Channel>> = emptyMap()
    @Volatile private var loadedAt = 0L

    // ---- settings

    /**
     * Returns the user-selected countries as a set, or empty set when "All countries" is selected.
     * Empty set = no filter (all countries visible).
     */
    fun selectedCountries(): Set<String> = runCatching {
        val raw = prefs?.getString(KEY_COUNTRIES, "") ?: ""
        raw.split(',').map { it.trim() }.filter { it.isNotEmpty() && it != ALL }.toSet()
    }.getOrDefault(emptySet())

    private fun showAdult(): Boolean = runCatching { prefs?.getBoolean(KEY_ADULT, false) == true }.getOrDefault(false)

    // ---- home page

    /**
     * Always exposes every row so the bridge discovers them all.
     * The country filter is enforced inside [getMainPage]: non-matching rows return empty,
     * so the bridge only indexes channels for the selected countries.
     */
    override val mainPage: List<MainPageData>
        get() = mainPageOf(*CountryData.ROW_ORDER.map { it to it }.toTypedArray())

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data
        val countries = selectedCountries()   // empty = all countries
        val adult = showAdult()

        // Adult row: skip when NSFW is off
        if (data == ADULT_ROW && !adult) {
            return newHomePageResponse(HomePageList(request.name, emptyList(), isHorizontalImages = true), hasNext = false)
        }

        // Country filter: return empty for rows outside the selection.
        // This applies to both the CloudStream home UI and the bridge catalog — only the
        // selected countries' channels will appear in "All TV channels".
        val allowed = countries.isEmpty() || data in countries
        val list = if (allowed) catalog()[data].orEmpty() else emptyList()

        return newHomePageResponse(
            HomePageList(request.name, list.map { it.toSearchResult() }, isHorizontalImages = true),
            hasNext = false
        )
    }

    // ---- search

    override suspend fun search(query: String): List<SearchResponse> {
        catalog()
        val countries = selectedCountries()
        val adult = showAdult()
        return channels.filter { ch ->
            (adult || !ch.adult) &&
                (countries.isEmpty() || rowOf(ch) in countries) &&
                (ch.name.contains(query, true) || ch.section.contains(query, true))
        }.map { it.toSearchResult() }
    }

    // ---- playback

    override suspend fun load(url: String): LoadResponse {
        val (_, title) = split(url)
        return newLiveStreamLoadResponse(title.ifEmpty { name }, url, url) {}
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val (url, title) = split(data)
        if (url.isEmpty()) return false
        callback(newExtractorLink(name, title.ifEmpty { name }, url, ExtractorLinkType.M3U8))
        return true
    }

    // ---- catalogue

    private suspend fun catalog(): Map<String, List<Channel>> {
        val fresh = rows
        if (fresh.isNotEmpty() && System.currentTimeMillis() - loadedAt < CACHE_TTL_MS) return fresh
        return runCatching {
            val parsed = parse(app.get("$MAIN_URL$PLAYLIST_PATH", headers = mapOf("User-Agent" to USER_AGENT), timeout = 60L).text)
            if (parsed.isNotEmpty()) {
                channels = parsed
                rows = parsed.groupBy { rowOf(it) }
                loadedAt = System.currentTimeMillis()
            }
            rows
        }.getOrDefault(rows)
    }

    private fun Channel.toSearchResult(): SearchResponse =
        newLiveSearchResponse(name, "$url$SEP$name", TvType.Live) {
            this.posterUrl = logo.ifEmpty { null }
        }

    private fun split(data: String): Pair<String, String> {
        val i = data.indexOf(SEP)
        return if (i < 0) data to "" else data.substring(0, i) to data.substring(i + 1)
    }

    companion object {
        const val PREFS_NAME = "ClownIPTVPlus"
        /** Multi-select countries, stored as comma-joined string; empty / "All countries" = no filter. */
        const val KEY_COUNTRIES = "countries"
        const val KEY_ADULT = "show_adult"
        const val ALL = "All countries"
        const val MAIN_URL = "https://sxcce.dpdns.org"
        const val PLAYLIST_PATH = "/playlist.m3u"

        private const val ADULT_ROW = "Adult (18+)"
        private const val SEP = '\u0001'
        private const val CACHE_TTL_MS = 30L * 60L * 1000L
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        private val LOGO = Regex("tvg-logo=\"([^\"]*)\"")
        private val ADULT = Regex("\\b(XXX|ADULT|18\\+)|\\+18\\b", RegexOption.IGNORE_CASE)
        private val ADULT_NAME = Regex("\\bXXX\\b|\\b18\\+|\\+18\\b", RegexOption.IGNORE_CASE)
        private const val SEP_A = "✦●✦"
        private const val SEP_B = "◆◆◆"

        /** Parses an M3U playlist into channels, tracking the section separators. */
        fun parse(body: String): List<Channel> {
            val out = ArrayList<Channel>(1024)
            var section = ""
            var pending: String? = null
            for (raw in body.split(Regex("\r\n|\r|\n"))) {
                val line = raw.trim()
                if (line.startsWith("#EXTINF")) { pending = line; continue }
                if (line.isEmpty() || line.startsWith("#")) continue
                val extinf = pending ?: continue
                pending = null

                val name = extinf.substringAfterLast(',').trim()
                val logo = LOGO.find(extinf)?.groupValues?.get(1).orEmpty()
                if (name.contains(SEP_A) || name.contains(SEP_B)) {
                    section = name.replace(SEP_A, "").replace(SEP_B, "").trim(' ', '|')
                    continue
                }
                if (name.isNotEmpty()) {
                    val display = name.substringBefore('|').trim()
                    val adult = ADULT.containsMatchIn(display) || ADULT.containsMatchIn(section) || ADULT_NAME.containsMatchIn(name)
                    out.add(Channel(name, line, logo, section, adult))
                }
            }
            return out
        }

        /** Maps a channel to its region row. */
        fun rowOf(ch: Channel): String {
            if (ch.adult) return ADULT_ROW
            if (!ch.name.contains('|')) return "Other"
            val prefix = ch.name.substringBefore('|').trim().uppercase()
            if (prefix.startsWith("24/7")) return "24/7"
            return CountryData.PREFIX_ROWS[prefix]
                ?: CountryData.PREFIX_ROWS[prefix.substringBefore('-').trim()]
                ?: "Other"
        }
    }
}
