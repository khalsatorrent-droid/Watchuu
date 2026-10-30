package com.watchuu.allinonereborn

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.util.UUID

// ──────────────────────────────────────────────
//  Data models – JioTV (jjtvxweb)
// ──────────────────────────────────────────────
data class JioChannel(
    val name: String = "",
    val id: String = "",
    val category: String = "",
    val url: String = "",
    val logo: String = "",
    // single key/keyId (flat)
    val keyId: String? = null,
    val key: String? = null,
    // array of keys
    val keys: List<JioKey>? = null
)

data class JioKey(
    val keyId: String = "",
    val key: String = ""
)

// ──────────────────────────────────────────────
//  Data models – FanCode live matches
// ──────────────────────────────────────────────
data class FancodeResponse(
    val matches: List<FancodeMatch> = emptyList()
)

data class FancodeMatch(
    val match_id: String = "",
    val title: String = "",
    val match_name: String = "",
    val event_name: String = "",
    val event_category: String = "",
    val image: String = "",
    val status: String = "",
    val startTime: String = "",
    val streams: List<FancodeStream>? = null
)

data class FancodeStream(
    val language: String = "",
    val playlist_url: String = ""
)

// ──────────────────────────────────────────────
//  Unified load-data payload (serialised as JSON in the URL field)
// ──────────────────────────────────────────────
data class LoadData(
    val url: String,
    val title: String,
    val poster: String,
    val category: String,
    val source: String,          // "jiotv" | "fancode"
    val keyId: String = "",
    val key: String = "",
    val referer: String = "",
    val userAgent: String = ""
)

// ──────────────────────────────────────────────
//  Provider A – JioTV (channels from jjtvxweb.pages.dev)
// ──────────────────────────────────────────────
class JioTvProvider : MainAPI() {
    override var lang           = "hi"
    override var mainUrl        = "https://jjtvxweb.pages.dev"
    override var name           = "JioTV+ (AllinoneReborn)"
    override val hasMainPage    = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    private val jsonUrl = "$mainUrl/jstr4web.json"

    private val defaultUA =
        "Mozilla/5.0 (Linux; Android 16; SM-S911B Build/BP4A.251205.006) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.47 Mobile Safari/537.36"

    // Fetch and parse the JSON once
    private suspend fun fetchChannels(): List<JioChannel> {
        return try {
            val text = app.get(
                jsonUrl,
                headers = mapOf(
                    "User-Agent" to defaultUA,
                    "Referer"    to "$mainUrl/"
                )
            ).text
            parseJson<List<JioChannel>>(text)
        } catch (e: Exception) {
            emptyList()
        }
    }

    // Resolve best keyId/key from a channel (handles both flat & array form)
    private fun resolveKey(ch: JioChannel): Pair<String, String> {
        if (!ch.keyId.isNullOrBlank() && ch.keyId != "null" &&
            !ch.key.isNullOrBlank()   && ch.key   != "null") {
            return ch.keyId to ch.key
        }
        val firstKey = ch.keys?.firstOrNull()
        if (firstKey != null) return firstKey.keyId to firstKey.key
        return "" to ""
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val channels = fetchChannels()
        val grouped  = channels.groupBy { it.category.ifBlank { "Other" } }

        val sections = grouped.map { (cat, list) ->
            val items = list.map { ch ->
                val (kid, k) = resolveKey(ch)
                val ld = LoadData(
                    url      = ch.url,
                    title    = ch.name,
                    poster   = ch.logo,
                    category = cat,
                    source   = "jiotv",
                    keyId    = kid,
                    key      = k,
                    referer  = "$mainUrl/",
                    userAgent = defaultUA
                )
                newLiveSearchResponse(ch.name, ld.toJson(), TvType.Live) {
                    posterUrl = ch.logo
                    this.lang = cat
                }
            }
            HomePageList(cat, items, isHorizontalImages = true)
        }
        return newHomePageResponse(sections)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return fetchChannels()
            .filter { it.name.contains(query, ignoreCase = true) }
            .map { ch ->
                val (kid, k) = resolveKey(ch)
                val ld = LoadData(
                    url      = ch.url,
                    title    = ch.name,
                    poster   = ch.logo,
                    category = ch.category,
                    source   = "jiotv",
                    keyId    = kid,
                    key      = k,
                    referer  = "$mainUrl/",
                    userAgent = defaultUA
                )
                newLiveSearchResponse(ch.name, ld.toJson(), TvType.Live) {
                    posterUrl = ch.logo
                }
            }
    }

    override suspend fun load(url: String): LoadResponse {
        val ld = parseJson<LoadData>(url)
        return newLiveStreamLoadResponse(ld.title, ld.url, url) {
            posterUrl = ld.poster
            plot      = ld.category
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ld = parseJson<LoadData>(data)
        return invokeStream(ld, name, callback)
    }
}

// ──────────────────────────────────────────────
//  Provider B – FanCode live sports events
// ──────────────────────────────────────────────
class FancodeProvider : MainAPI() {
    override var lang           = "hi"
    override var mainUrl        = "https://allinonereborn2.online"
    override var name           = "FanCode Sports (AllinoneReborn)"
    override val hasMainPage    = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    private val jsonUrl   = "$mainUrl/fctest/json/fancode_latest.json"
    private val refererUrl = "$mainUrl/fcw/"

    private val defaultUA =
        "Mozilla/5.0 (Linux; Android 16; SM-S911B Build/BP4A.251205.006) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.47 Mobile Safari/537.36"

    private suspend fun fetchMatches(): FancodeResponse {
        return try {
            val text = app.get(
                jsonUrl,
                headers = mapOf(
                    "Accept"     to "application/json",
                    "User-Agent" to defaultUA,
                    "Referer"    to refererUrl
                )
            ).text
            parseJson(text)
        } catch (e: Exception) {
            FancodeResponse()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val resp   = fetchMatches()
        val live   = mutableListOf<SearchResponse>()
        val upcoming = mutableListOf<SearchResponse>()

        for (match in resp.matches) {
            val hasStream = !match.streams.isNullOrEmpty()
            if (!hasStream && match.status != "LIVE") {
                // upcoming with no stream yet – still show
            }
            val firstStream = match.streams?.firstOrNull()
            val ld = LoadData(
                url      = firstStream?.playlist_url ?: "",
                title    = match.match_name,
                poster   = match.image,
                category = match.event_category,
                source   = "fancode",
                referer  = refererUrl,
                userAgent = defaultUA
            )
            val card = newLiveSearchResponse(match.match_name, ld.toJson(), TvType.Live) {
                posterUrl = match.image
                this.lang = match.event_category
            }
            when (match.status) {
                "LIVE"   -> live.add(card)
                else     -> upcoming.add(card)
            }
        }

        val sections = mutableListOf<HomePageList>()
        if (live.isNotEmpty())
            sections.add(HomePageList("🔴 LIVE NOW", live, isHorizontalImages = true))
        if (upcoming.isNotEmpty())
            sections.add(HomePageList("⏳ Upcoming", upcoming, isHorizontalImages = true))

        return newHomePageResponse(sections)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return fetchMatches().matches
            .filter {
                it.match_name.contains(query, ignoreCase = true) ||
                it.event_name.contains(query, ignoreCase = true)
            }
            .map { match ->
                val firstStream = match.streams?.firstOrNull()
                val ld = LoadData(
                    url      = firstStream?.playlist_url ?: "",
                    title    = match.match_name,
                    poster   = match.image,
                    category = match.event_category,
                    source   = "fancode",
                    referer  = refererUrl,
                    userAgent = defaultUA
                )
                newLiveSearchResponse(match.match_name, ld.toJson(), TvType.Live) {
                    posterUrl = match.image
                }
            }
    }

    override suspend fun load(url: String): LoadResponse {
        val ld = parseJson<LoadData>(url)
        return newLiveStreamLoadResponse(ld.title, ld.url, url) {
            posterUrl = ld.poster
            plot      = "${ld.category} • FanCode"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ld = parseJson<LoadData>(data)
        return invokeStream(ld, name, callback)
    }
}

// ──────────────────────────────────────────────
//  Shared stream invocation logic
// ──────────────────────────────────────────────
fun invokeStream(
    ld: LoadData,
    sourceName: String,
    callback: (ExtractorLink) -> Unit
): Boolean {
    if (ld.url.isBlank()) return false

    val isDash = ld.url.contains(".mpd", ignoreCase = true)
    val hasDrm = ld.keyId.isNotBlank() && ld.key.isNotBlank()

    if (isDash && hasDrm) {
        // ClearKey DASH stream
        callback.invoke(
            newDrmExtractorLink(
                source  = sourceName,
                name    = ld.title,
                url     = ld.url,
                type    = INFER_TYPE,
                uuid    = UUID.randomUUID()
            ) {
                quality  = Qualities.Unknown.value
                kid      = ld.keyId.trim()
                key      = ld.key.trim()
                referer  = ld.referer
                headers  = mapOf("User-Agent" to ld.userAgent)
            }
        )
    } else if (ld.url.contains(".m3u8", ignoreCase = true) ||
               ld.url.contains("m3u8")                     ||
               ld.url.contains("hls")) {
        // HLS / M3U8 stream
        callback.invoke(
            newExtractorLink(
                source  = sourceName,
                name    = ld.title,
                url     = ld.url,
                type    = ExtractorLinkType.M3U8
            ) {
                quality = Qualities.Unknown.value
                referer = ld.referer
                headers = mapOf("User-Agent" to ld.userAgent)
            }
        )
    } else {
        // Generic / inferred type (plain DASH without keys, etc.)
        callback.invoke(
            newExtractorLink(
                source  = sourceName,
                name    = ld.title,
                url     = ld.url,
                type    = INFER_TYPE
            ) {
                quality = Qualities.Unknown.value
                referer = ld.referer
                headers = mapOf("User-Agent" to ld.userAgent)
            }
        )
    }
    return true
}
