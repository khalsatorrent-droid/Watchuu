package com.watchuu.allinonereborn

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

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
//  Data models – Cookie JSON responses
// ──────────────────────────────────────────────
data class CookieEntry(
    val cookie: String? = null,
    val last_updated: String? = null
)

data class StarCookieResult(
    val channel_id: String? = null,
    val final_url: String? = null,
    val error_details: StarCookieErrorDetails? = null
)

data class StarCookieErrorDetails(
    val final_url: String? = null
)

data class StarCookieResponse(
    val successful_results: List<StarCookieResult>? = null,
    val failed_results: List<StarCookieResult>? = null
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
    val channelId: String = "",  // JioTV channel id (for star-cookie lookup)
    val keyId: String = "",
    val key: String = "",
    val keysJson: String = "",   // JSON array of JioKey for multi-key channels
    val referer: String = "",
    val userAgent: String = ""
)

// ──────────────────────────────────────────────
//  Cookie helpers
// ──────────────────────────────────────────────

/** Strip the stale __hdnea__ from a URL and append the fresh one */
fun replaceHdnea(url: String, freshCookie: String): String {
    val base = url.substringBefore("?")
    return "$base?$freshCookie"
}

/** Is this a jiotvpllive stream (needs channel-specific star cookie)? */
fun isJioPlLiveUrl(url: String): Boolean {
    return try {
        url.contains("jiotvpllive.cdn.jio.com", ignoreCase = true)
    } catch (e: Exception) { false }
}

/** Fetch the shared primary cookie for jiotvmblive streams */
suspend fun fetchPrimaryCookie(): String {
    return try {
        val text = app.get(
            "https://allinonereborn2.online/jstrweb2/cookies.json",
            headers = mapOf("Accept" to "*/*"),
            cacheTime = 0
        ).text
        val entries = parseJson<List<CookieEntry>>(text)
        entries.firstOrNull { !it.cookie.isNullOrBlank() }?.cookie ?: ""
    } catch (e: Exception) { "" }
}

/** Fetch the channel-specific star cookie for jiotvpllive streams */
suspend fun fetchStarCookie(channelId: String): String {
    return try {
        val text = app.get(
            "https://allinonereborn2.online/jtv-fetch/jstarcookie/cookie.json",
            headers = mapOf("Accept" to "*/*"),
            cacheTime = 0
        ).text
        val resp = parseJson<StarCookieResponse>(text)
        val allResults = mutableListOf<StarCookieResult>()
        resp.successful_results?.let { allResults.addAll(it) }
        resp.failed_results?.let { allResults.addAll(it) }

        for (item in allResults) {
            if (item.channel_id == channelId) {
                val finalUrl = item.error_details?.final_url ?: item.final_url ?: continue
                val match = Regex("[?&]__hdnea__=([^&]+)").find(finalUrl)
                    ?: Regex("__hdnea__=([^&]+)").find(finalUrl)
                if (match != null) return "__hdnea__=${match.groupValues[1]}"
            }
        }
        ""
    } catch (e: Exception) { "" }
}

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
    private fun resolveKey(ch: JioChannel): Triple<String, String, String> {
        // Check flat single key first
        if (!ch.keyId.isNullOrBlank() && ch.keyId != "null" &&
            !ch.key.isNullOrBlank()   && ch.key   != "null") {
            return Triple(ch.keyId, ch.key, "")
        }
        // Multi-key array
        val keys = ch.keys?.filter { it.keyId.isNotBlank() && it.key.isNotBlank() }
        if (!keys.isNullOrEmpty()) {
            return Triple(keys[0].keyId, keys[0].key, keys.toJson())
        }
        return Triple("", "", "")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val channels = fetchChannels()
        val grouped  = channels.groupBy { it.category.ifBlank { "Other" } }

        val sections = grouped.map { (cat, list) ->
            val items = list.map { ch ->
                val (kid, k, keysJson) = resolveKey(ch)
                val ld = LoadData(
                    url       = ch.url,
                    title     = ch.name,
                    poster    = ch.logo,
                    category  = cat,
                    source    = "jiotv",
                    channelId = ch.id,
                    keyId     = kid,
                    key       = k,
                    keysJson  = keysJson,
                    referer   = "$mainUrl/",
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
                val (kid, k, keysJson) = resolveKey(ch)
                val ld = LoadData(
                    url       = ch.url,
                    title     = ch.name,
                    poster    = ch.logo,
                    category  = ch.category,
                    source    = "jiotv",
                    channelId = ch.id,
                    keyId     = kid,
                    key       = k,
                    keysJson  = keysJson,
                    referer   = "$mainUrl/",
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
        return invokeJioTvStream(ld, name, callback)
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
            // Use first stream URL; all streams are stored in LoadData for loadLinks
            val firstStream = match.streams?.firstOrNull()
            val streamsJson = match.streams?.toJson() ?: "[]"
            val ld = LoadData(
                url       = firstStream?.playlist_url ?: "",
                title     = match.match_name,
                poster    = match.image,
                category  = match.event_category,
                source    = "fancode",
                keysJson  = streamsJson,   // reuse keysJson field to carry all streams
                referer   = refererUrl,
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
                val streamsJson = match.streams?.toJson() ?: "[]"
                val ld = LoadData(
                    url       = firstStream?.playlist_url ?: "",
                    title     = match.match_name,
                    poster    = match.image,
                    category  = match.event_category,
                    source    = "fancode",
                    keysJson  = streamsJson,
                    referer   = refererUrl,
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
        return invokeFancodeStream(ld, name, callback)
    }
}

// ──────────────────────────────────────────────
//  JioTV stream invocation – fetches fresh cookie and injects it
// ──────────────────────────────────────────────
suspend fun invokeJioTvStream(
    ld: LoadData,
    sourceName: String,
    callback: (ExtractorLink) -> Unit
): Boolean {
    if (ld.url.isBlank()) return false

    val streamUrl = ld.url
    val isPlLive  = isJioPlLiveUrl(streamUrl)

    // Fetch fresh cookie
    val freshCookie = if (isPlLive) {
        fetchStarCookie(ld.channelId).ifBlank { fetchPrimaryCookie() }
    } else {
        fetchPrimaryCookie()
    }

    // Build the two stream variants:
    //   1) Worldwide  – uses fresh __hdnea__ cookie (works outside India on correct subscription)
    //   2) India      – same URL but with the JioTV mobile Referer (works on Indian ISPs/JioTV)
    // Both use the same DASH URL with fresh cookie; only headers differ.
    // The "India only" variant uses the mobile JioTV referer which allows Indian ISP access.

    val baseUrl = streamUrl.substringBefore("?")
    val urlWithCookie = if (freshCookie.isNotBlank()) "$baseUrl?$freshCookie" else streamUrl

    // Build DRM clearKey config
    val drmKeys: Map<String, String> = buildMap {
        // Multi-key array (keysJson carries serialised List<JioKey>)
        if (ld.keysJson.isNotBlank()) {
            try {
                val keys = parseJson<List<JioKey>>(ld.keysJson)
                keys.forEach { if (it.keyId.isNotBlank() && it.key.isNotBlank()) put(it.keyId, it.key) }
            } catch (_: Exception) {}
        }
        // Single flat key fallback
        if (isEmpty() && ld.keyId.isNotBlank() && ld.key.isNotBlank()) {
            put(ld.keyId, ld.key)
        }
    }

    val isDash = urlWithCookie.contains(".mpd", ignoreCase = true)
    val hasDrm = drmKeys.isNotEmpty()

    // Worldwide stream (fresh cookie, JioTV web referer)
    val worldwideHeaders = mapOf(
        "User-Agent" to "plaYtv/7.1.5",
        "Referer"    to "https://www.jiotv.com/"
    ).let { base ->
        if (hasDrm) base + mapOf(
            "clearkey-kid" to (drmKeys.keys.first()),
            "clearkey-key" to (drmKeys.values.first())
        ) else base
    }

    // India stream (fresh cookie, mobile app referer that works on Indian networks)
    val indiaHeaders = mapOf(
        "User-Agent" to "plaYtv/7.1.5",
        "Referer"    to "https://jiotv.com/",
        "X-Forwarded-For" to "49.32.0.1"   // hint: Indian IP range (Jio)
    ).let { base ->
        if (hasDrm) base + mapOf(
            "clearkey-kid" to (drmKeys.keys.first()),
            "clearkey-key" to (drmKeys.values.first())
        ) else base
    }

    val linkType = if (isDash) ExtractorLinkType.DASH else ExtractorLinkType.M3U8

    // Worldwide link
    callback.invoke(
        newExtractorLink(sourceName, "🌐 ${ld.title} [Worldwide]", urlWithCookie, linkType) {
            quality = Qualities.Unknown.value
            referer = "https://www.jiotv.com/"
            headers = worldwideHeaders
        }
    )

    // India link (same URL – Indian ISPs/JioTV subscribers can access it directly)
    callback.invoke(
        newExtractorLink(sourceName, "🇮🇳 ${ld.title} [India]", urlWithCookie, linkType) {
            quality = Qualities.Unknown.value
            referer = "https://jiotv.com/"
            headers = indiaHeaders
        }
    )

    return true
}

// ──────────────────────────────────────────────
//  FanCode stream invocation – lists ALL language streams
// ──────────────────────────────────────────────
suspend fun invokeFancodeStream(
    ld: LoadData,
    sourceName: String,
    callback: (ExtractorLink) -> Unit
): Boolean {
    // keysJson carries the full FancodeStream list (all language streams)
    val streams: List<FancodeStream> = if (ld.keysJson.isNotBlank()) {
        try { parseJson(ld.keysJson) } catch (_: Exception) { emptyList() }
    } else emptyList()

    // Fall back to the single URL in ld.url if no streams array
    val effectiveStreams = if (streams.isNotEmpty()) streams
    else if (ld.url.isNotBlank()) listOf(FancodeStream("Worldwide", ld.url))
    else return false

    var added = false
    for (stream in effectiveStreams) {
        if (stream.playlist_url.isBlank()) continue
        val label = if (stream.language.isNotBlank()) stream.language else "Stream"

        callback.invoke(
            newExtractorLink(
                sourceName,
                "📺 ${ld.title} [$label]",
                stream.playlist_url,
                ExtractorLinkType.M3U8
            ) {
                quality = Qualities.Unknown.value
                referer = ld.referer
                headers = mapOf(
                    "User-Agent" to ld.userAgent,
                    "Referer"    to ld.referer
                )
            }
        )
        added = true
    }
    return added
}
