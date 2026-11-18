package com.watchuu.arcod

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLDecoder
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Arcod - lossless music from player.arcod.xyz.
 *
 * Card urls:
 *   /track/<id>?t=<title>&a=<artist>&art=<cover>&d=<seconds>
 *   /album/<slug>/<title>/<artist>/<cover>
 */
class ArcodProvider : MainAPI() {
    override var mainUrl = ArcodApi.SITE
    override var name = "Arcod"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Music, TvType.Others)

    private val PAGE = 25

    // ------------------------------------------------------------------ helpers

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun dec(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private fun JSONObject.str(vararg keys: String): String? {
        for (k in keys) {
            val v = optString(k, "")
            if (v.isNotBlank() && v != "null") return v
        }
        return null
    }

    private fun JSONArray?.objs(): List<JSONObject> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optJSONObject(it) }
    }

    private fun cover(o: JSONObject): String? =
        o.optJSONObject("image")?.optString("large")?.ifBlank { null }
            ?: o.optJSONObject("image")?.optString("small")?.ifBlank { null }

    private fun trackUrl(o: JSONObject): String? {
        val id = o.opt("id")?.toString()?.takeIf { it.isNotBlank() } ?: return null
        val title = o.str("title") ?: return null
        val artist = o.optJSONObject("performer")?.optString("name").orEmpty()
        val art = cover(o) ?: o.optJSONObject("album")?.let { cover(it) }.orEmpty()
        val dur = o.optInt("duration", 0)
        return "$mainUrl/track/$id?t=${enc(title)}&a=${enc(artist)}&art=${enc(art)}&d=$dur"
    }

    private fun trackCard(o: JSONObject): SearchResponse? {
        val url = trackUrl(o) ?: return null
        val title = o.str("title") ?: return null
        val artist = o.optJSONObject("performer")?.optString("name").orEmpty()
        return newMovieSearchResponse(if (artist.isNotBlank()) "$title - $artist" else title, url, TvType.Music) {
            this.posterUrl = cover(o) ?: o.optJSONObject("album")?.let { cover(it) }
        }
    }

    private fun albumCard(o: JSONObject): SearchResponse? {
        val slug = o.str("id") ?: return null
        val title = o.str("title") ?: return null
        val artist = o.optJSONObject("artist")?.optString("name").orEmpty()
        val art = cover(o).orEmpty()
        return newTvSeriesSearchResponse(
            if (artist.isNotBlank()) "$title - $artist" else title,
            "$mainUrl/album/$slug/${enc(title)}/${enc(artist)}/${enc(art)}",
            TvType.Music,
        ) { this.posterUrl = cover(o) }
    }

    // ------------------------------------------------------------------ home

    override val mainPage = mainPageOf(
        "q:top hits" to "Top hits",
        "q:arijit singh" to "Arijit Singh",
        "q:punjabi" to "Punjabi",
        "q:bollywood" to "Bollywood",
        "q:romantic" to "Romantic",
        "q:lofi" to "Lo-fi",
        "q:english hits" to "English",
        "q:k-pop" to "K-Pop",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val query = request.data.removePrefix("q:")
        val items = runCatching { searchPage(query, page) }.getOrElse { emptyList<SearchResponse>() to false }
        return newHomePageResponse(HomePageList(request.name, items.first), items.second)
    }

    // ------------------------------------------------------------------ search

    /** one search call; also used by the home rows */
    private suspend fun searchPage(query: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        if (query.isBlank()) return emptyList<SearchResponse>() to false
        val offset = (page - 1) * PAGE
        val root = ArcodApi.get(
            "/api/player/search",
            mapOf("q" to query.trim(), "limit" to PAGE.toString(), "offset" to offset.toString())
        ) ?: return emptyList<SearchResponse>() to false

        val data = root.optJSONObject("data") ?: return emptyList<SearchResponse>() to false
        val out = ArrayList<SearchResponse>()

        // "most popular" mixes albums and tracks, each wrapped in {type, content}
        val popular = data.optJSONObject("most_popular")
        popular?.optJSONArray("items").objs().forEach { entry ->
            val content = entry.optJSONObject("content") ?: entry
            when (entry.optString("type").ifBlank { content.optString("type") }) {
                "albums" -> albumCard(content)?.let { out.add(it) }
                "tracks" -> trackCard(content)?.let { out.add(it) }
            }
        }
        // the dedicated album group, when the api sends one
        data.optJSONObject("albums")?.optJSONArray("items").objs().forEach { albumCard(it)?.let { c -> out.add(c) } }
        data.optJSONObject("tracks")?.optJSONArray("items").objs().forEach { trackCard(it)?.let { c -> out.add(c) } }

        val total = popular?.optInt("total", 0) ?: 0
        val hasNext = total > 0 && offset + PAGE < total
        return out.distinctBy { it.url } to hasNext
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val (items, hasNext) = searchPage(query, page)
        return newSearchResponseList(items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> = searchPage(query, 1).first

    // ------------------------------------------------------------------ load

    override suspend fun load(url: String): LoadResponse? {
        Regex("/album/([^/]+)").find(url)?.groupValues?.get(1)?.let { return loadAlbum(it, url) }
        Regex("/track/(\\d+)").find(url)?.groupValues?.get(1)?.let { return loadTrack(it, url) }
        return null
    }

    private suspend fun loadTrack(id: String, url: String): LoadResponse? {
        val title = param(url, "t") ?: "Track"
        val artist = param(url, "a").orEmpty()
        val art = param(url, "art")?.takeIf { it.startsWith("http") }
        val dur = param(url, "d")?.toIntOrNull() ?: 0
        return newMovieLoadResponse(
            if (artist.isNotBlank()) "$title - $artist" else title,
            url,
            TvType.Music,
            url,
        ) {
            this.posterUrl = art
            this.plot = artist.ifBlank { null }
            this.duration = dur / 60
        }
    }

    private suspend fun loadAlbum(slug: String, url: String): LoadResponse? {
        val root = ArcodApi.get("/api/player/get-album", mapOf("album_id" to slug)) ?: return null
        val album = root.optJSONObject("data") ?: return null

        val title = album.str("title") ?: "Album"
        val artist = album.optJSONObject("artist")?.optString("name").orEmpty()
        val art = cover(album)
        val tracks = album.optJSONObject("tracks")?.optJSONArray("items").objs()

        var n = 0
        val eps = tracks.mapNotNull { t ->
            val u = trackUrl(t) ?: return@mapNotNull null
            newEpisode(u) {
                this.name = t.str("title") ?: "Track ${n + 1}"
                this.season = 1
                this.episode = ++n
                this.posterUrl = art
                this.runTime = t.optInt("duration", 0) / 60
            }
        }
        if (eps.isEmpty()) return null
        return newTvSeriesLoadResponse(
            if (artist.isNotBlank()) "$title - $artist" else title,
            "$mainUrl/album/$slug",
            TvType.Music,
            eps,
        ) {
            this.posterUrl = art
            this.plot = listOfNotNull(
                artist.ifBlank { null },
                album.str("copyright"),
                album.str("maximum_technical_specifications"),
                "${eps.size} tracks",
            ).joinToString(" · ")
            this.year = album.str("release_date_original")?.take(4)?.toIntOrNull()
        }
    }

    private fun param(url: String, key: String): String? =
        Regex("[?&]$key=([^&]*)").find(url)?.groupValues?.get(1)?.let { dec(it) }

    // ------------------------------------------------------------------ playback

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = Regex("/track/(\\d+)").find(data)?.groupValues?.get(1)
            ?: Regex("(\\d{5,})").findAll(data).lastOrNull()?.groupValues?.get(1)
            ?: return false

        // the api answers with a signed url on the same host; nothing else is needed to play it
        val (url, label) = ArcodApi.streamUrl(id) ?: return false
        callback.invoke(
            newExtractorLink(name, label, url, ExtractorLinkType.VIDEO) {
                this.referer = "${ArcodApi.SITE}/"
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }
}
