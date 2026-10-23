package com.watchuu.monochrome

/**
 * Monochrome (monochrome.st) - music extension for Cloudstream.
 *
 * The site moved its catalog + playback to its own service, tracks.monochrome.st
 * (the old Tidal proxy at tidal-proxy.monochrome.tf / hot.monochrome.tf no longer answers):
 *
 *  - search    : GET /search?q=          -> {tracks, releases, artists, topResults}
 *                GET /search/releases?q=  -> {releases:[{releaseId,title,artistNames,artwork}]}
 *  - album     : GET /releases/{id}      -> {title, artists, artwork, tracks:[{trackId,title,duration,...}]}
 *  - artist    : GET /artists/{id}       -> {name, avatar, banner, topTracks, albums, releases, ...}
 *  - stream    : GET /track/{trackId}    -> the FLAC file itself (range requests supported)
 *
 * Home rows are built from release searches; "Editor's Picks" are the site's picks
 * (editors-picks.json) matched to tracks.monochrome.st releases.
 *
 * URLs used inside Cloudstream:
 *   https://monochrome.st/album/{releaseId}   (series, tracks = episodes)
 *   https://monochrome.st/artist/{artistId}   (series, top tracks = episodes, albums as recommendations)
 *   https://monochrome.st/track/{trackId}     (single)
 */

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

class MonochromeProvider : MainAPI() {
    override var mainUrl = "https://monochrome.st"
    override var name = "Monochrome"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Music, TvType.Others)

    private val api = "https://tracks.monochrome.st"

    private val ua =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val headers = mapOf(
        "Origin" to mainUrl,
        "Referer" to "$mainUrl/",
        "User-Agent" to ua,
        "Accept" to "application/json",
    )

    // ---------------------------------------------------------------- networking

    private suspend fun getJson(url: String): JSONObject {
        var r = app.get(url, headers = headers, timeout = 30L)
        if (r.code >= 500 || r.code == 429) { // the server sometimes answers 502 - try again once
            kotlinx.coroutines.delay(700L)
            r = app.get(url, headers = headers, timeout = 30L)
        }
        if (r.code !in 200..299) throw ErrorLoadingException("Monochrome: HTTP ${r.code} for ${url.substringAfter("://").take(60)}")
        return try {
            JSONObject(r.text)
        } catch (_: Throwable) {
            throw ErrorLoadingException("Monochrome: bad answer from server")
        }
    }

    private fun q(s: String) = URLEncoder.encode(s.trim(), "UTF-8")

    // ---------------------------------------------------------------- json helpers

    private fun JSONObject.str(vararg keys: String): String? {
        for (k in keys) {
            val v = optString(k, "")
            if (v.isNotBlank() && v != "null") return v
        }
        return null
    }

    private fun JSONArray?.objects(): List<JSONObject> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optJSONObject(it) }
    }

    /** "Pritam, Mohit Chauhan" from either artistNames[] or artists[{name}] */
    private fun artistsOf(o: JSONObject): String {
        o.optJSONArray("artistNames")?.let { a ->
            val names = (0 until a.length()).mapNotNull { a.optString(it).ifBlank { null } }
            if (names.isNotEmpty()) return names.take(3).joinToString(", ")
        }
        val names = o.optJSONArray("artists").objects().mapNotNull { it.str("name", "displayName") }
        return names.take(3).joinToString(", ")
    }

    private fun artwork(o: JSONObject, releaseId: String?): String? =
        o.str("artwork", "cover", "avatar") ?: releaseId?.let { "$api/proxy/mi/$it-1A01.jpg" }

    private fun yearOf(o: JSONObject): Int? = o.str("releaseDate")?.take(4)?.toIntOrNull()

    // ---------------------------------------------------------------- cards

    private fun releaseCard(o: JSONObject): SearchResponse? {
        val id = o.str("releaseId", "id") ?: return null
        val title = o.str("title") ?: return null
        val artist = artistsOf(o)
        return newTvSeriesSearchResponse(if (artist.isNotBlank()) "$title - $artist" else title, "$mainUrl/album/$id", TvType.Music) {
            this.posterUrl = artwork(o, id)
            this.year = yearOf(o)
        }
    }

    private fun trackCard(o: JSONObject): SearchResponse? {
        if (o.has("playable") && !o.optBoolean("playable", true)) return null
        val id = o.str("trackId", "id") ?: return null
        val title = o.str("title") ?: return null
        val artist = artistsOf(o)
        val rel = o.str("releaseId")?.let { "?album=$it" } ?: ""
        return newMovieSearchResponse(if (artist.isNotBlank()) "$title - $artist" else title, "$mainUrl/track/$id$rel", TvType.Music) {
            this.posterUrl = artwork(o, o.str("releaseId"))
        }
    }

    private fun artistCard(o: JSONObject): SearchResponse? {
        val id = o.str("artistId", "id") ?: return null
        val name = o.str("displayName", "name") ?: return null
        return newTvSeriesSearchResponse(name, "$mainUrl/artist/$id", TvType.Music) {
            this.posterUrl = o.str("avatar")
        }
    }

    // ---------------------------------------------------------------- home page

    override val mainPage = mainPageOf(
        "picks" to "Editor's Picks",
        "q:hits 2025" to "Hits",
        "q:bollywood" to "Bollywood",
        "q:arijit singh" to "Arijit Singh",
        "q:punjabi" to "Punjabi",
        "q:romantic" to "Romantic",
        "q:lofi" to "Lo-fi & Chill",
        "q:hip hop" to "Hip Hop",
        "q:pop" to "Pop",
        "q:phonk" to "Phonk",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
        val items = if (request.data == "picks") editorsPicks()
        else releaseSearch(request.data.removePrefix("q:"), 30)
        return newHomePageResponse(HomePageList(request.name, items), false)
    }

    private suspend fun releaseSearch(query: String, limit: Int): List<SearchResponse> =
        getJson("$api/search/releases?q=${q(query)}&limit=$limit")
            .optJSONArray("releases").objects().mapNotNull { releaseCard(it) }

    /** The site's editor picks are Tidal albums - find the same album on tracks.monochrome.st. */
    private suspend fun editorsPicks(): List<SearchResponse> {
        val picks = try {
            JSONArray(app.get("$mainUrl/editors-picks.json", headers = headers, timeout = 30L).text).objects()
        } catch (_: Throwable) {
            emptyList()
        }
        val found = picks.take(24).amap { p ->
            try {
                val title = p.str("title") ?: return@amap null
                val artist = p.optJSONObject("artist")?.str("name") ?: ""
                val hits = getJson("$api/search/releases?q=${q("$artist $title")}&limit=5")
                    .optJSONArray("releases").objects()
                val norm = { s: String -> s.lowercase().replace(Regex("[^a-z0-9]"), "") }
                val best = hits.firstOrNull { norm(it.str("title") ?: "") == norm(title) }
                    ?: hits.firstOrNull { norm(it.str("title") ?: "").contains(norm(title).take(8)) }
                best?.let { releaseCard(it) }
            } catch (_: Throwable) {
                null
            }
        }.filterNotNull()
        // fall back to something useful if the picks can't be matched
        return found.ifEmpty { releaseSearch("hits", 30) }
    }

    // ---------------------------------------------------------------- search

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val root = getJson("$api/search?q=${q(query)}")
        val out = ArrayList<SearchResponse>()
        root.optJSONArray("artists").objects().take(4).mapNotNullTo(out) { artistCard(it) }
        root.optJSONArray("tracks").objects().mapNotNullTo(out) { trackCard(it) }
        root.optJSONArray("releases").objects().mapNotNullTo(out) { releaseCard(it) }
        // more tracks than the combined search gives
        try {
            getJson("$api/search/tracks?q=${q(query)}&limit=30").optJSONArray("tracks").objects()
                .mapNotNull { trackCard(it) }.forEach { t -> if (out.none { it.url == t.url }) out.add(t) }
        } catch (_: Throwable) {
        }
        return out.distinctBy { it.url }
    }

    // ---------------------------------------------------------------- load

    private fun idFrom(url: String, kind: String): String? =
        Regex("/$kind/(\\d+)").find(url)?.groupValues?.get(1)

    override suspend fun load(url: String): LoadResponse? {
        // links saved by older versions: track|id|releaseId|title|artist
        if (url.startsWith("track|")) {
            val p = url.split("|")
            val rel = p.getOrNull(2)?.takeIf { it.isNotBlank() && it != p.getOrNull(1) }?.let { "?album=$it" } ?: ""
            return load("$mainUrl/track/${p.getOrNull(1) ?: return null}$rel")
        }
        idFrom(url, "album")?.let { return loadAlbum(it) }
        idFrom(url, "artist")?.let { return loadArtist(it) }
        idFrom(url, "track")?.let { return loadTrack(it, Regex("[?&]album=(\\d+)").find(url)?.groupValues?.get(1)) }
        throw ErrorLoadingException("Monochrome: unknown link")
    }

    private fun trackEpisode(t: JSONObject, n: Int, fallbackPoster: String?): Episode? {
        if (t.has("playable") && !t.optBoolean("playable", true)) return null
        val id = t.str("trackId", "id") ?: return null
        val title = t.str("title") ?: "Track $n"
        val artist = artistsOf(t)
        return newEpisode(id) {
            this.name = title
            this.episode = t.optInt("trackNumber", n).takeIf { it > 0 } ?: n
            this.season = t.optInt("discNumber", 1).takeIf { it > 0 } ?: 1
            this.posterUrl = t.str("artwork") ?: fallbackPoster
            this.description = artist.ifBlank { null }
            this.runTime = (t.optLong("duration", 0L) / 1000L).toInt().takeIf { it > 0 }
        }
    }

    private suspend fun loadAlbum(id: String): LoadResponse {
        val r = getJson("$api/releases/$id")
        val title = r.str("title") ?: "Album"
        val artist = artistsOf(r)
        val cover = artwork(r, id)
        var n = 0
        val eps = r.optJSONArray("tracks").objects().mapNotNull { trackEpisode(it, ++n, cover) }
        return newTvSeriesLoadResponse(if (artist.isNotBlank()) "$title - $artist" else title, "$mainUrl/album/$id", TvType.Music, eps) {
            this.posterUrl = cover
            this.backgroundPosterUrl = cover
            this.year = yearOf(r)
            this.plot = listOfNotNull(
                artist.ifBlank { null },
                r.str("releaseType")?.lowercase()?.replaceFirstChar { it.uppercase() },
                r.str("label"),
                "${eps.size} tracks",
            ).joinToString(" | ")
            this.tags = listOfNotNull(r.str("label"))
        }
    }

    private suspend fun loadArtist(id: String): LoadResponse {
        val a = getJson("$api/artists/$id")
        val name = a.str("displayName", "name") ?: "Artist"
        val avatar = a.str("avatar")
        var n = 0
        val eps = a.optJSONArray("topTracks").objects().mapNotNull { trackEpisode(it, ++n, avatar) }
            .mapIndexed { i, e -> e.apply { episode = i + 1; season = 1 } }
        val albums = (a.optJSONArray("albums").objects() + a.optJSONArray("releases").objects() +
            a.optJSONArray("singles").objects() + a.optJSONArray("compilations").objects())
            .mapNotNull { releaseCard(it) }.distinctBy { it.url }
        return newTvSeriesLoadResponse(name, "$mainUrl/artist/$id", TvType.Music, eps) {
            this.posterUrl = avatar
            this.backgroundPosterUrl = a.str("banner") ?: avatar
            this.plot = a.str("biography", "bio")
                ?: "Top tracks by $name. Albums are listed below."
            this.recommendations = albums
        }
    }

    private suspend fun loadTrack(id: String, releaseId: String?): LoadResponse {
        // there is no single-track endpoint; the track is read from its album
        val album = releaseId?.let { rid -> try { getJson("$api/releases/$rid") } catch (_: Throwable) { null } }
        val t = album?.optJSONArray("tracks").objects().firstOrNull { it.str("trackId", "id") == id }
        val title = t?.str("title") ?: "Track"
        val artist = t?.let { artistsOf(it) } ?: ""
        val cover = t?.let { artwork(it, releaseId) } ?: releaseId?.let { "$api/proxy/mi/$it-1A01.jpg" }
        val others = album?.optJSONArray("tracks").objects()
            .filter { it.str("trackId") != id }
            .mapNotNull { trackCard(it.apply { if (!has("artwork")) put("artwork", cover); if (!has("releaseId")) put("releaseId", releaseId) }) }
        val self = "$mainUrl/track/$id" + (releaseId?.let { "?album=$it" } ?: "")
        return newMovieLoadResponse(if (artist.isNotBlank()) "$title - $artist" else title, self, TvType.Music, id) {
            this.posterUrl = cover
            this.backgroundPosterUrl = cover
            this.plot = listOfNotNull(artist.ifBlank { null }, album?.str("title")?.let { "from $it" }).joinToString(" | ")
            this.duration = t?.optLong("duration", 0L)?.let { (it / 60000L).toInt() }?.takeIf { it > 0 }
            this.recommendations = others
        }
    }

    // ---------------------------------------------------------------- links

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = when {
            data.startsWith("track|") -> data.split("|").getOrNull(1)
            data.startsWith("http") -> idFrom(data, "track")
            else -> data
        }?.takeIf { it.all(Char::isDigit) && it.isNotEmpty() } ?: return false

        // the stream endpoint returns the FLAC file itself
        callback.invoke(
            newExtractorLink(name, "$name FLAC (lossless)", "$api/track/$id", ExtractorLinkType.VIDEO) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.Unknown.value
                this.headers = mapOf("Origin" to mainUrl, "User-Agent" to ua)
            }
        )
        return true
    }
}
