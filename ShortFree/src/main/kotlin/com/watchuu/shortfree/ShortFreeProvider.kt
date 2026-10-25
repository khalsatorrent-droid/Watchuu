package com.watchuu.shortfree

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * ShortFree (Dashverse) - short dramas on the Dashtoon reels backend (same as DashReels):
 *  - catalog : GET  /reels/api/v2/shows/explore            -> shows (cursor paginated)
 *  - search  : GET  /reels/api/v1/shows/search?query=...   -> shows
 *  - show    : POST /reels/api/v1/shows/  {"showId": id}    -> Show (with its reels)
 *  - episodes: GET  /reels/api/v1/reels/show/{showId}       -> [Reel]
 *  - Reel = { reelId/id, name, sequence, season, thumbnailUrl, videoUrl, subtitleUrl,
 *             lockData:{locked, price}, videoLinksMap }
 *
 * Only what a free guest can watch is played; episodes the api marks locked (VIP/coins) are
 * listed with "(Locked)" but not played.
 */
class ShortFreeProvider : MainAPI() {
    override var mainUrl = "https://shortfree.com"
    override var name = "ShortFree"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.AsianDrama)

    private val api = ShortFreeAuth.API
    private val mapper = ObjectMapper()
    private val json = "application/json".toMediaType()

    // ---------------------------------------------------------------- requests

    private suspend fun call(path: String, body: String? = null): JsonNode? {
        repeat(2) { attempt ->
            val token = ShortFreeAuth.token()
                ?: throw ErrorLoadingException("ShortFree: could not sign in as guest (${ShortFreeAuth.lastError})")
            val h = ShortFreeAuth.apiHeaders(token)
            var url = if (path.startsWith("http")) path else "$api$path"
            var r = request(url, h, body)
            var hops = 0
            while (hops < 4) {
                val loc = r.headers["location"]?.trim()?.ifBlank { null } ?: break
                url = if (loc.startsWith("http")) loc else api + (if (loc.startsWith("/")) loc else "/$loc")
                r = request(url, h, body)
                hops++
            }
            if (r.code == 401 || r.code == 403) {
                if (attempt == 0) { ShortFreeAuth.reset(); return@repeat }
                throw ErrorLoadingException("ShortFree: server refused the guest account (HTTP ${r.code}) ${r.text.take(150)}")
            }
            if (r.code !in 200..299) {
                throw ErrorLoadingException("ShortFree: HTTP ${r.code} for ${url.removePrefix(api)} ${r.text.take(200)}")
            }
            val n = mapper.readTree(r.text) ?: return null
            val d = n.get("data")
            return if (d != null && (d.isArray || d.isObject) && n.size() <= 4) d else n
        }
        return null
    }

    private suspend fun request(url: String, h: Map<String, String>, body: String?) =
        if (body == null) app.get(url, headers = h, timeout = 30L, allowRedirects = false)
        else app.post(url, headers = h, requestBody = body.toRequestBody(json), timeout = 30L, allowRedirects = false)

    // ---------------------------------------------------------------- shows

    private fun isShow(n: JsonNode) = n.isObject && n.has("id") && n.has("name") &&
        (n.has("thumbnailUrl") || n.has("portraitThumbnail") || n.has("reels"))

    /** Shows carry several image fields; the vertical grid uses portraitThumbnail. Pick the first real one. */
    private fun imageOf(n: JsonNode?): String? {
        if (n == null) return null
        for (k in listOf("portraitThumbnail", "thumbnailUrl", "verticalThumbnailUrl", "coverImageUrl",
            "coverUrl", "posterUrl", "imageUrl", "bannerThumbnail")) {
            val v = n.get(k)?.asText()?.trim()?.ifBlank { null } ?: continue
            if (v.startsWith("http")) return v
            if (v.startsWith("/")) return "https://content.dashtoon.ai$v"
        }
        return null
    }

    private fun shows(n: JsonNode?): List<JsonNode> {
        val out = LinkedHashMap<String, JsonNode>()
        fun walk(x: JsonNode, depth: Int) {
            if (depth > 6) return
            if (isShow(x)) { out.putIfAbsent(x.get("id").asText(), x); return }
            if (x.isArray) x.forEach { walk(it, depth + 1) }
            else if (x.isObject) x.fields().forEach { if (it.value.isContainerNode) walk(it.value, depth + 1) }
        }
        if (n != null) walk(n, 0)
        return out.values.toList()
    }

    private val known = java.util.concurrent.ConcurrentHashMap<String, JsonNode>()

    private fun JsonNode.toSearch(): SearchResponse {
        val id = get("id").asText()
        known[id] = this
        val title = get("name").asText()
        return newTvSeriesSearchResponse(title, "$mainUrl/reel/$id", TvType.AsianDrama) {
            this.posterUrl = imageOf(this@toSearch)
        }
    }

    private fun showIdOf(url: String) = url.trimEnd('/').substringAfterLast("/").substringBefore("?")

    // ---------------------------------------------------------------- home

    override val mainPage = mainPageOf("explore" to "Explore")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // explore is cursor-paginated; page 1 is enough for a browse row, later pages use the cursor
        val cursor = if (page > 1) lastCursor else null
        val path = "/reels/api/v2/shows/explore?pageSize=20" + (cursor?.let { "&cursor=$it" } ?: "")
        val resp = call(path)
        lastCursor = resp?.get("cursor")?.asText()?.ifBlank { null }
            ?: resp?.get("nextCursor")?.asText()?.ifBlank { null }
        val items = shows(resp).map { it.toSearch() }
        return newHomePageResponse(request.name, items, items.isNotEmpty() && lastCursor != null)
    }

    @Volatile private var lastCursor: String? = null

    // ---------------------------------------------------------------- search

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val q = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        // the app calls it with searchTerm; other names/pageNumber kept as fallbacks
        val tries = listOf(
            "/reels/api/v1/shows/search?searchTerm=$q&pageSize=30&pageNumber=1",
            "/reels/api/v1/shows/search?searchTerm=$q&pageSize=30",
            "/reels/api/v1/shows/search?query=$q&pageSize=30",
            "/reels/api/v1/shows/search?q=$q&pageSize=30",
        )
        for (path in tries) {
            try {
                val items = shows(call(path)).map { it.toSearch() }
                if (items.isNotEmpty()) return items
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        return emptyList()
    }

    // ---------------------------------------------------------------- load

    private suspend fun showWithReels(showId: String): Pair<JsonNode?, List<JsonNode>> {
        var showErr: Exception? = null
        val show = try {
            call("/reels/api/v1/shows/", """{"showId":${mapper.writeValueAsString(showId)}}""")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            showErr = e; null
        }
        var reels = show?.get("reels")?.filter { it.isObject } ?: emptyList()
        if (reels.isEmpty()) {
            val r = try {
                call("/reels/api/v1/reels/show/$showId")
            } catch (e: ErrorLoadingException) {
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

    /** A reel is playable when the server actually handed us a video address for it. */
    private fun videoUrlsOf(r: JsonNode): LinkedHashMap<String, String> {
        val urls = LinkedHashMap<String, String>()
        r.get("videoUrl")?.asText()?.takeIf { it.startsWith("http") }?.let { urls[it] = name }
        r.get("videoLinksMap")?.fields()?.forEach { (k, v) ->
            val u = if (v.isTextual) v.asText() else (v.get("url") ?: v.get("videoUrl"))?.asText()
            if (u != null && u.startsWith("http")) urls.putIfAbsent(u, "$name $k")
        }
        return urls
    }

    override suspend fun load(url: String): LoadResponse? {
        val showId = showIdOf(url)
        var err: String? = null
        val (fetched, reels) = try {
            showWithReels(showId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            err = e.message; null to emptyList()
        }
        val show = fetched ?: known[showId]
        if (show == null && err != null) throw ErrorLoadingException(err)
        val title = show?.get("name")?.asText() ?: "ShortFree $showId"
        val poster = imageOf(show)
        val tags = listOf("genres", "tropes", "tags").flatMap { k -> show?.get(k)?.map { it.asText() } ?: emptyList() }
            .filter { it.isNotBlank() }.distinct()

        val episodes = reels.mapIndexed { i, r ->
            val seq = r.get("sequence")?.asInt() ?: (i + 1)
            // only flag episodes the server gives no stream for; free-for-all shows have urls on every one
            val noVideo = videoUrlsOf(r).isEmpty()
            val epId = (r.get("reelId") ?: r.get("id"))?.asText() ?: seq.toString()
            newEpisode("$showId|$epId|$seq") {
                this.name = (r.get("name")?.asText()?.ifBlank { null } ?: "Episode $seq") + if (noVideo) " (Locked)" else ""
                this.episode = seq
                this.season = r.get("season")?.asInt()?.takeIf { it > 0 } ?: 1
                this.posterUrl = imageOf(r) ?: poster
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
        val parts = data.substringAfterLast("/").split("|")
        if (parts.size < 3) return false
        val (showId, epId, seq) = parts
        val (_, reels) = showWithReels(showId)
        val reel = reels.firstOrNull { (it.get("reelId") ?: it.get("id"))?.asText() == epId }
            ?: reels.firstOrNull { it.get("sequence")?.asText() == seq }
            ?: throw ErrorLoadingException("ShortFree: episode not found")

        // play whatever stream the server returned for this episode; if it returns none
        // (genuinely gated), it simply can't play - nothing is forged here
        val urls = videoUrlsOf(reel)
        ShortFreeSettings.lastReport = "Episode $seq: ${urls.size} stream(s)\n" + urls.keys.joinToString("\n") { it.take(120) }
        if (urls.isEmpty()) throw ErrorLoadingException("ShortFree: the server returned no video for this episode")

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
                    this.headers = mapOf("User-Agent" to ShortFreeAuth.userAgent)
                }
            )
        }
        return true
    }
}
