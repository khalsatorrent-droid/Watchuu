package com.watchuu.musicbox

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import org.json.JSONObject

/**
 * MusicBox — a music catalogue with real playback, built from the SpotiFLAC extension registry
 * (github.com/zarzet/SpotiFLAC-Extension).
 *
 * Those extensions were pulled apart to see what they actually call, and the answer decides what
 * is possible here:
 *
 *   deezer    api.deezer.com — public, no key. Search and metadata work for anyone.
 *   qobuz     api.zarz.moe/v2/qbz + appID, and an X-Zarz-Ticket per download
 *   tidal     tidal.com/v1 with a public token; full-length needs an account token
 *   amazon    na.mesk.skill.music.a2z.com with device and auth tokens
 *   ytmusic   music.youtube.com/youtubei — public, and googlevideo serves the audio directly
 *
 * Four of the five gate their audio behind api.zarz.moe (a signed ticket) or an account, which an
 * extension cannot reproduce. So the catalogue comes from Deezer, which needs nothing, and the
 * audio comes from YouTube Music, which also needs nothing - resolved through Cloudstream's own
 * YouTube extractor rather than a hand-rolled signature.
 *
 * A Deezer track carries a 30-second preview url. That is offered too, labelled as such, so a track
 * still plays something if YouTube resolution fails for it.
 */
class MusicBoxProvider : MainAPI() {
    override var mainUrl = "https://api.deezer.com"
    override var name = "MusicBox"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Music)

    private val ytApiKey = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
    private val ytClientVersion = "1.20260101.01.00"

    override val mainPage = mainPageOf(
        "chart/tracks" to "Top Tracks",
        "chart/albums" to "Top Albums",
        "chart/artists" to "Top Artists",
        "editorial/0/charts" to "Charts"
    )

    private fun userAgent(): String = UA

    // ── Deezer ─────────────────────────────────────────────────────────────────

    private suspend fun deezer(path: String): JSONObject? = runCatching {
        JSONObject(
            app.get(
                "$mainUrl/$path",
                headers = mapOf("User-Agent" to userAgent(), "Accept" to "application/json"),
                timeout = 30L
            ).text
        )
    }.getOrNull()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), false)

        val json = deezer(request.data) ?: return newHomePageResponse(request.name, emptyList(), false)
        val data = json.optJSONObject("data") ?: json

        val out = ArrayList<SearchResponse>()
        listOf("tracks", "albums", "artists").forEach { key ->
            data.optJSONArray(key)?.let { arr -> out.addAll(toCards(arr)) }
        }
        if (out.isEmpty()) out.addAll(toCards(data.optJSONArray("data")))
        return newHomePageResponse(request.name, out, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        if (q.isBlank()) return emptyList()

        val out = ArrayList<SearchResponse>()
        listOf("track", "album", "artist").forEach { type ->
            val json = deezer("search/$type?q=$q&limit=20") ?: return@forEach
            json.optJSONArray("data")?.let { arr -> out.addAll(toCards(arr)) }
        }
        return out.distinctBy { it.url }
    }

    private fun toCards(arr: org.json.JSONArray): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").ifBlank { continue }
            val kind = o.optString("type").ifBlank {
                when {
                    o.has("nb_tracks") || o.has("record_type") -> "album"
                    o.has("nb_album") || o.has("radio") -> "artist"
                    else -> "track"
                }
            }
            val title = o.optString("title").ifBlank {
                o.optString("name").ifBlank { continue }
            }
            val artist = o.optJSONObject("artist")?.optString("name").orEmpty()
            val display = if (artist.isNotBlank() && kind == "track") "$artist - $title" else title
            val poster = o.optString("cover_xl").ifBlank {
                o.optString("cover_big").ifBlank {
                    o.optString("cover_medium").ifBlank { o.optString("picture_xl").ifBlank { null } }
                }
            }
            val url = "$mainUrl/${kind}/${id}"

            out.add(
                when (kind) {
                    "album", "artist" -> newTvSeriesSearchResponse(
                        display, url, TvType.Music
                    ) { this.posterUrl = poster }
                    else -> newMovieSearchResponse(display, url, TvType.Music) {
                        this.posterUrl = poster
                    }
                }
            )
        }
        return out
    }

    // ── Detail ─────────────────────────────────────────────────────────────────

    private fun parse(url: String): Pair<String, String>? {
        val m = Regex("""/(track|album|artist)/(\d+)""").find(url) ?: return null
        return m.groupValues[1] to m.groupValues[2]
    }

    override suspend fun load(url: String): LoadResponse? {
        val (kind, id) = parse(url) ?: return null
        val o = deezer("$kind/$id") ?: return null

        if (kind == "track") {
            val title = o.optString("title").ifBlank { "Track $id" }
            val artist = o.optJSONObject("artist")?.optString("name").orEmpty()
            val album = o.optJSONObject("album")?.optString("title").orEmpty()
            val poster = o.optJSONObject("album")?.optString("cover_xl")?.ifBlank { null }
                ?: o.optString("cover_xl").ifBlank { null }
            val plot = listOf(artist, album).filter { it.isNotBlank() }.joinToString(" · ")

            return newMovieLoadResponse(title, url, TvType.Music, "track|$id") {
                this.posterUrl = poster
                this.plot = plot.ifBlank { null }
            }
        }

        if (kind == "artist") {
            val name = o.optString("name").ifBlank { "Artist $id" }
            val poster = o.optString("picture_xl").ifBlank { null }
            val top = deezer("artist/$id/top?limit=50")?.optJSONArray("data")

            val episodes = ArrayList<Episode>()
            if (top != null) {
                for (i in 0 until top.length()) {
                    val t = top.optJSONObject(i) ?: continue
                    val tid = t.optString("id").ifBlank { continue }
                    val tname = t.optString("title").ifBlank { "Track ${i + 1}" }
                    episodes.add(
                        newEpisode("track|$tid") {
                            this.name = tname
                            this.season = 1
                            this.episode = i + 1
                            this.posterUrl = poster
                        }
                    )
                }
            }
            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(name, url, TvType.Music, episodes) {
                    this.posterUrl = poster
                    this.plot = o.optString("nb_fan").ifBlank { null }?.let { "$it fans" }
                }
            }
        }

        // album (or an artist with nothing listed): its tracks become episodes
        val title = o.optString("title").ifBlank { o.optString("name") }.ifBlank { "Album $id" }
        val poster = o.optString("cover_xl").ifBlank { null }
        val artist = o.optJSONObject("artist")?.optString("name").orEmpty()

        val tracks = deezer("album/$id")?.optJSONArray("tracks")

        val episodes = ArrayList<Episode>()
        if (tracks != null) {
            for (i in 0 until tracks.length()) {
                val t = tracks.optJSONObject(i) ?: continue
                val tid = t.optString("id").ifBlank { continue }
                val tname = t.optString("title").ifBlank { "Track ${i + 1}" }
                episodes.add(
                    newEpisode("track|$tid") {
                        this.name = tname
                        this.season = 1
                        this.episode = i + 1
                        this.posterUrl = poster
                    }
                )
            }
        }

        if (episodes.isNotEmpty()) {
            return newTvSeriesLoadResponse(title, url, TvType.Music, episodes) {
                this.posterUrl = poster
                this.plot = artist.ifBlank { null }
            }
        }

        return newMovieLoadResponse(title, url, TvType.Music, "album|$id") {
            this.posterUrl = poster
            this.plot = artist.ifBlank { null }
        }
    }

    // ── Streams ────────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val kind = data.substringBefore('|')
        val id = data.substringAfter('|')
        if (id.isBlank()) return false

        val track = deezer("track/$id") ?: return false
        val title = track.optString("title")
        val artist = track.optJSONObject("artist")?.optString("name").orEmpty()
        var found = false

        // 1. the audio itself, through Cloudstream's YouTube extractor
        val videoId = youtubeSearch("$artist $title".trim())
        if (videoId != null) {
            val got = mutableListOf<ExtractorLink>()
            runCatching {
                loadExtractor("https://www.youtube.com/watch?v=$videoId", "https://music.youtube.com/", subtitleCallback) {
                    got.add(it)
                }
            }
            got.forEach { link ->
                callback.invoke(
                    newExtractorLink(
                        source = link.source ?: name,
                        name = "${link.name.ifBlank { name }} · YouTube",
                        url = link.url,
                        type = link.type
                    ) {
                        this.referer = link.referer.ifBlank { "https://www.youtube.com/" }
                        this.quality = link.quality
                        this.headers = link.headers
                    }
                )
                found = true
            }
        }

        // 2. Deezer's own 30 second preview, so a track still plays something if YouTube fails
        val preview = track.optString("preview").ifBlank { null }
        if (preview != null) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name · Deezer preview (30s)",
                    url = preview,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "https://www.deezer.com/"
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "User-Agent" to userAgent(),
                        "Accept" to "*/*"
                    )
                }
            )
            found = true
        }

        return found
    }

    /**
     * YouTube Music search over InnerTube, with the same public key the SpotiFLAC extension uses.
     * Only the first video id is needed - Cloudstream's extractor does the rest.
     */
    private suspend fun youtubeSearch(query: String): String? {
        if (query.isBlank()) return null
        val body = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", ytClientVersion)
                    put("hl", "en")
                    put("gl", "US")
                })
            })
            put("query", query)
            put("params", "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D")
        }

        val res = runCatching {
            app.post(
                "https://music.youtube.com/youtubei/v1/search?key=$ytApiKey&prettyPrint=false",
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "User-Agent" to userAgent(),
                    "Origin" to "https://music.youtube.com",
                    "Referer" to "https://music.youtube.com/"
                ),
                data = body.toString(),
                timeout = 30L
            ).text
        }.getOrNull() ?: return null

        // any videoId in the answer is good enough; the extractor re-resolves the stream
        return Regex(""""videoId"\s*:\s*"([A-Za-z0-9_-]{11})"""")
            .find(res)?.groupValues?.get(1)
    }

    private companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 16; SM-S911B Build/BP4A.251205.006) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.47 Mobile Safari/537.36"
    }
}
