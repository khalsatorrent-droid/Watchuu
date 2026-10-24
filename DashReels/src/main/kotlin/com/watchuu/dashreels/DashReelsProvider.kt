package com.watchuu.dashreels

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * DashReels (Dashtoon) - short dramas, built from the app's own code (Flutter, decompiled):
 *
 *  - home     : GET  /reels/api/v1/layout/HOME_PAGE_V2?invalidateCache=false&resetShowCache=false
 *               -> { layoutSubSections:[{title, widgetType, entityType, entityIds}], shows:[Show], bannerContent }
 *  - explore  : GET  /reels/api/v2/shows/explore
 *  - show     : POST /reels/api/v1/shows/  {"showId": id}  -> Show (with its reels)
 *  - episodes : GET  /reels/api/v1/reels/show/{showId}     -> [Reel]
 *  - Show  = { id, name, description, thumbnailUrl, rating, genres, tropes, tags, reels, lockStatus, ... }
 *  - Reel  = { id, reelId, name, sequence, season, thumbnailUrl, videoUrl, subtitleUrl,
 *              lockData:{locked, price}, videoLinksMap, ... }
 *
 * Only what a free guest account can watch is played: episodes the api marks as locked
 * (VIP / coins) are listed but not played.
 */
class DashReelsProvider : MainAPI() {
    override var mainUrl = "https://dashreels.com"
    override var name = "DashReels"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.AsianDrama)

    private val api = DashReelsAuth.API
    private val mapper = ObjectMapper()
    private val json = "application/json".toMediaType()

    // ---------------------------------------------------------------- requests

    /**
     * Same as the app's HttpClient.get/post: when the reply carries a "location" header (any status,
     * the load balancer answers this way), the request is sent again - same method, headers and
     * body - to baseUrl + location. Redirects are not followed automatically.
     */
    private suspend fun call(path: String, body: String? = null): JsonNode? {
        repeat(2) { attempt ->
            val token = DashReelsAuth.token()
                ?: throw ErrorLoadingException("DashReels: could not sign in as guest (${DashReelsAuth.lastError})")
            val h = DashReelsAuth.apiHeaders(token)
            var url = "$api$path"
            var r = request(url, h, body)
            var hops = 0
            while (hops < 4) {
                val loc = r.headers["location"]?.trim()?.ifBlank { null } ?: break
                url = if (loc.startsWith("http")) loc else api + (if (loc.startsWith("/")) loc else "/$loc")
                r = request(url, h, body)
                hops++
            }
            if (r.code == 401 || r.code == 403) {
                // token no longer accepted: start over with a new guest
                if (attempt == 0) { DashReelsAuth.reset(); return@repeat }
                throw ErrorLoadingException("DashReels: server refused the guest account (HTTP ${r.code}) ${r.text.take(150)}")
            }
            if (r.code !in 200..299) {
                throw ErrorLoadingException("DashReels: HTTP ${r.code} for ${url.removePrefix(api)} ${r.text.take(200)}")
            }
            val n = mapper.readTree(r.text) ?: return null
            // some services wrap the payload: {data: ...}
            val d = n.get("data")
            return if (d != null && (d.isArray || d.isObject) && n.size() <= 4) d else n
        }
        return null
    }

    private suspend fun request(url: String, h: Map<String, String>, body: String?) =
        if (body == null) app.get(url, headers = h, timeout = 30L, allowRedirects = false)
        else app.post(url, headers = h, requestBody = body.toRequestBody(json), timeout = 30L, allowRedirects = false)

    // ---------------------------------------------------------------- shows

    private fun isShow(n: JsonNode) = n.isObject && n.has("id") && n.has("name") && (n.has("thumbnailUrl") || n.has("reels"))

    /** Every show object anywhere in a response. */
    private fun shows(n: JsonNode?): List<JsonNode> {
        val out = LinkedHashMap<String, JsonNode>()
        fun walk(x: JsonNode, depth: Int) {
            if (depth > 6) return
            if (isShow(x)) {
                out.putIfAbsent(x.get("id").asText(), x); return
            }
            if (x.isArray) x.forEach { walk(it, depth + 1) }
            else if (x.isObject) x.fields().forEach { if (it.value.isContainerNode) walk(it.value, depth + 1) }
        }
        if (n != null) walk(n, 0)
        return out.values.toList()
    }

    private fun showLocked(s: JsonNode): Boolean {
        val ls = s.get("lockStatus") ?: return false
        return when {
            ls.isBoolean -> ls.asBoolean()
            ls.isTextual -> ls.asText().contains("LOCK", true) && !ls.asText().contains("UNLOCK", true)
            ls.isObject -> ls.get("locked")?.asBoolean() == true
            else -> false
        }
    }

    // show info seen in home / search lists, by id
    private val known = java.util.concurrent.ConcurrentHashMap<String, JsonNode>()

    private fun JsonNode.toSearch(): SearchResponse {
        val id = get("id").asText()
        known[id] = this
        val title = get("name").asText()
        val thumb = get("thumbnailUrl")?.asText()?.takeIf { it.startsWith("http") }
        return newTvSeriesSearchResponse(title, "$mainUrl/reel/$id", TvType.AsianDrama) {
            this.posterUrl = thumb
        }
    }

    private fun showIdOf(url: String) = url.trimEnd('/').substringAfterLast("/").substringBefore("?")

    // ---------------------------------------------------------------- home

    override val mainPage = mainPageOf("home" to "DashReels")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return newHomePageResponse(emptyList(), false)
        val layout = call("/reels/api/v1/layout/HOME_PAGE_V2?invalidateCache=false&resetShowCache=false")
        val byId = shows(layout).associateBy { it.get("id").asText() }
        val rows = ArrayList<HomePageList>()

        layout?.get("layoutSubSections")?.forEach { sec ->
            val ids = sec.get("entityIds")?.mapNotNull { it.asText() } ?: emptyList()
            val items = ids.mapNotNull { byId[it] }.map { it.toSearch() }
            if (items.isNotEmpty()) rows.add(HomePageList(sec.get("title")?.asText()?.ifBlank { null } ?: "Shows", items))
        }
        try {
            val explore = shows(call("/reels/api/v2/shows/explore")).map { it.toSearch() }
            if (explore.isNotEmpty()) rows.add(HomePageList("Explore", explore))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        if (rows.isEmpty() && byId.isNotEmpty()) rows.add(HomePageList("Shows", byId.values.map { it.toSearch() }))
        return newHomePageResponse(rows, false)
    }

    // ---------------------------------------------------------------- search

    // the app has no search endpoint: shows from the home layout and explore are filtered here
    @Volatile private var catalog: List<JsonNode> = emptyList()
    @Volatile private var catalogAt = 0L

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        if (catalog.isEmpty() || System.currentTimeMillis() - catalogAt > 30 * 60_000L) {
            val all = LinkedHashMap<String, JsonNode>()
            listOf(
                "/reels/api/v1/layout/HOME_PAGE_V2?invalidateCache=false&resetShowCache=false",
                "/reels/api/v2/shows/explore"
            ).forEach { p ->
                try {
                    shows(call(p)).forEach { all.putIfAbsent(it.get("id").asText(), it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
            catalog = all.values.toList()
            catalogAt = System.currentTimeMillis()
        }
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        return catalog.filter { s ->
            val hay = buildString {
                append(s.get("name")?.asText()); append(' ')
                append(s.get("description")?.asText()); append(' ')
                listOf("genres", "tropes", "tags").forEach { k -> s.get(k)?.forEach { append(it.asText()); append(' ') } }
            }.lowercase()
            words.all { hay.contains(it) }
        }.map { it.toSearch() }
    }

    // ---------------------------------------------------------------- load

    private suspend fun showWithReels(showId: String): Pair<JsonNode?, List<JsonNode>> {
        var showErr: Exception? = null
        val show = try {
            call("/reels/api/v1/shows/", """{"showId":${mapper.writeValueAsString(showId)}}""")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            showErr = e
            null
        }
        var reels = show?.get("reels")?.filter { it.isObject } ?: emptyList()
        if (reels.isEmpty()) {
            val r = try {
                call("/reels/api/v1/reels/show/$showId")
            } catch (e: ErrorLoadingException) {
                // report both failures
                throw ErrorLoadingException(listOfNotNull(showErr?.message, e.message).joinToString(" | "))
            }
            reels = when {
                r == null -> emptyList()
                r.isArray -> r.filter { it.isObject }
                else -> (r.get("reels") ?: r.get("items") ?: r.get("content"))?.filter { it.isObject } ?: emptyList()
            }
        }
        return show to reels.sortedWith(compareBy({ it.get("season")?.asInt() ?: 1 }, { it.get("sequence")?.asInt() ?: 0 }))
    }

    private fun reelLocked(r: JsonNode) = r.get("lockData")?.get("locked")?.asBoolean() == true

    override suspend fun load(url: String): LoadResponse? {
        val showId = showIdOf(url)
        var err: String? = null
        val (fetched, reels) = try {
            showWithReels(showId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            err = e.message
            null to emptyList()
        }
        val show = fetched ?: known[showId]
        if (show == null && err != null) throw ErrorLoadingException(err)
        val title = show?.get("name")?.asText() ?: "DashReels $showId"
        val poster = show?.get("thumbnailUrl")?.asText()?.takeIf { it.startsWith("http") }
        val tags = listOf("genres", "tropes", "tags").flatMap { k -> show?.get(k)?.map { it.asText() } ?: emptyList() }
            .filter { it.isNotBlank() }.distinct()

        val episodes = reels.mapIndexed { i, r ->
            val seq = r.get("sequence")?.asInt() ?: (i + 1)
            val locked = reelLocked(r)
            val epId = (r.get("reelId") ?: r.get("id"))?.asText() ?: seq.toString()
            newEpisode("$showId|$epId|$seq") {
                this.name = (r.get("name")?.asText()?.ifBlank { null } ?: "Episode $seq") + if (locked) " (Locked)" else ""
                this.episode = seq
                this.season = r.get("season")?.asInt()?.takeIf { it > 0 } ?: 1
                this.posterUrl = r.get("thumbnailUrl")?.asText()?.takeIf { it.startsWith("http") }
            }
        }
        return newTvSeriesLoadResponse(title, "$mainUrl/reel/$showId", TvType.AsianDrama, episodes) {
            this.posterUrl = poster
            this.plot = (show?.get("description")?.asText() ?: "") + (if (err != null) "\n\n[episodes could not be loaded: $err]" else "")
            this.tags = tags
            show?.get("rating")?.asText()?.let { r -> this.score = Score.from10(r) }
        }
    }

    // ---------------------------------------------------------------- links

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Cloudstream may prefix mainUrl to the episode data
        val parts = data.substringAfterLast("/").split("|")
        if (parts.size < 3) return false
        val (showId, epId, seq) = parts
        // fresh episode list: video addresses are signed and expire
        val (_, reels) = showWithReels(showId)
        val reel = reels.firstOrNull { (it.get("reelId") ?: it.get("id"))?.asText() == epId }
            ?: reels.firstOrNull { it.get("sequence")?.asText() == seq }
            ?: throw ErrorLoadingException("DashReels: episode not found")

        if (reelLocked(reel)) {
            throw ErrorLoadingException("DashReels: this episode is locked in the app (VIP / coins) - only free episodes play here")
        }

        val urls = LinkedHashMap<String, String>() // url -> label
        reel.get("videoUrl")?.asText()?.takeIf { it.startsWith("http") }?.let { urls[it] = name }
        reel.get("videoLinksMap")?.fields()?.forEach { (k, v) ->
            val u = if (v.isTextual) v.asText() else (v.get("url") ?: v.get("videoUrl"))?.asText()
            if (u != null && u.startsWith("http")) urls.putIfAbsent(u, "$name $k")
        }
        if (urls.isEmpty()) throw ErrorLoadingException("DashReels: no video address for this episode")

        reel.get("subtitleUrl")?.asText()?.takeIf { it.startsWith("http") }?.let {
            subtitleCallback.invoke(newSubtitleFile("English", it))
        }
        reel.get("subtitles")?.let { s ->
            if (s.isArray) s.forEach { x ->
                val u = (x.get("url") ?: x.get("subtitleUrl"))?.asText() ?: return@forEach
                if (u.startsWith("http")) subtitleCallback.invoke(newSubtitleFile(x.get("language")?.asText() ?: x.get("label")?.asText() ?: "Subtitle", u))
            }
        }

        urls.forEach { (u, label) ->
            val l = u.lowercase()
            val type = if (l.contains(".m3u8")) ExtractorLinkType.M3U8
            else if (l.substringBefore("?").endsWith(".mpd")) ExtractorLinkType.DASH
            else ExtractorLinkType.VIDEO
            val q = Regex("""(?i)(2160|1440|1080|720|540|480|360)p?""").find(label)?.groupValues?.get(1)?.toIntOrNull()
            callback.invoke(
                newExtractorLink(name, label, u, type) {
                    this.quality = q ?: Qualities.Unknown.value
                    this.headers = mapOf("User-Agent" to "Reels/1.2.55")
                }
            )
        }
        return true
    }
}
