package com.watchuu.nonu

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
 * Nonu - Apple's catalogue through music.ak1ra.de5.net.
 *
 * Card urls:
 *   /song/<id>?t=<title>&a=<artist>&al=<album>&d=<ms>&art=<artwork>
 *   /album/<id>/<title>/<artist>/<artwork>
 *   /artist/<id>/<name>
 */
class NonuProvider : MainAPI() {
    override var mainUrl = NonuApi.SITE
    override var name = "Nonu"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Music, TvType.Others)

    private val cc get() = NonuApi.storefront

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

    private fun attrs(o: JSONObject): JSONObject = o.optJSONObject("attributes") ?: o

    /** artwork urls come as a template with {w}x{h}; ask for a usable size */
    private fun art(o: JSONObject, size: Int = 600): String? {
        val raw = attrs(o).optJSONObject("artwork")?.optString("url").orEmpty()
        if (raw.isBlank()) return null
        return raw.replace("{w}", size.toString()).replace("{h}", size.toString())
            .replace("{f}", "jpg").replace("{c}", "bb")
    }

    private fun songUrl(o: JSONObject): String? {
        val a = attrs(o)
        val id = o.str("id") ?: return null
        val title = a.str("name") ?: return null
        val artist = a.str("artistName").orEmpty()
        val album = a.str("albumName").orEmpty()
        val dur = a.optLong("durationInMillis", 0L)
        val cover = art(o).orEmpty()
        return "$mainUrl/song/$id?t=${enc(title)}&a=${enc(artist)}&al=${enc(album)}&d=$dur&art=${enc(cover)}"
    }

    private fun songCard(o: JSONObject): SearchResponse? {
        val url = songUrl(o) ?: return null
        val a = attrs(o)
        val title = a.str("name") ?: return null
        val artist = a.str("artistName").orEmpty()
        return newMovieSearchResponse(if (artist.isNotBlank()) "$title - $artist" else title, url, TvType.Music) {
            this.posterUrl = art(o)
        }
    }

    private fun albumCard(o: JSONObject): SearchResponse? {
        val id = o.str("id") ?: return null
        val a = attrs(o)
        val title = a.str("name") ?: return null
        val artist = a.str("artistName").orEmpty()
        val cover = art(o).orEmpty()
        return newTvSeriesSearchResponse(
            if (artist.isNotBlank()) "$title - $artist" else title,
            "$mainUrl/album/$id/${enc(title)}/${enc(artist)}/${enc(cover)}",
            TvType.Music,
        ) { this.posterUrl = cover }
    }

    private fun artistCard(o: JSONObject): SearchResponse? {
        val id = o.str("id") ?: return null
        val nm = attrs(o).str("name") ?: return null
        return newTvSeriesSearchResponse(nm, "$mainUrl/artist/$id/${enc(nm)}", TvType.Music) {
            this.posterUrl = art(o)
        }
    }

    // ------------------------------------------------------------------ home

    override val mainPage = mainPageOf(
        "chart:songs" to "Top songs",
        "chart:albums" to "Top albums",
        "q:new music" to "New music",
        "q:arijit singh" to "Arijit Singh",
        "q:punjabi hits" to "Punjabi",
        "q:lofi" to "Lo-fi",
        "q:romantic" to "Romantic",
        "q:english hits" to "English",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), false)

        val items = runCatching {
            if (request.data.startsWith("chart:")) {
                val type = request.data.removePrefix("chart:")
                val root = NonuApi.json("/amp/v1/catalog/$cc/charts?types=$type&limit=25&l=en-GB&platform=web")
                val data = root?.optJSONObject("results")?.optJSONArray(type)?.objs().orEmpty()
                    .ifEmpty { root?.optJSONArray("data").objs().orEmpty() }
                val resources = ArrayList<JSONObject>()
                data.forEach { entry ->
                    val inner = entry.optJSONArray("data").objs()
                    if (inner.isNotEmpty()) resources.addAll(inner) else resources.add(entry)
                }
                if (type == "albums") resources.mapNotNull { albumCard(it) } else resources.mapNotNull { songCard(it) }
            } else {
                search(request.data.removePrefix("q:")).take(30)
            }
        }.getOrElse { e ->
            NonuApi.log("WARN", "row '${request.name}' failed: ${e.message}")
            emptyList()
        }
        NonuApi.log("step", "row '${request.name}': ${items.size} items")
        return newHomePageResponse(HomePageList(request.name, items), false)
    }

    // ------------------------------------------------------------------ search

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val root = NonuApi.json(
            "/amp/v1/catalog/$cc/search?term=${enc(query.trim())}&types=songs,albums,artists&limit=25&l=en-GB&platform=web"
        ) ?: return emptyList()

        val results = root.optJSONObject("results") ?: root
        val out = ArrayList<SearchResponse>()
        results.optJSONObject("artists")?.optJSONArray("data").objs().take(3).mapNotNullTo(out) { artistCard(it) }
        results.optJSONObject("albums")?.optJSONArray("data").objs().take(6).mapNotNullTo(out) { albumCard(it) }
        results.optJSONObject("songs")?.optJSONArray("data").objs().mapNotNullTo(out) { songCard(it) }
        NonuApi.log("step", "search \"$query\": ${out.size} cards")
        return out.distinctBy { it.url }
    }

    // ------------------------------------------------------------------ load

    override suspend fun load(url: String): LoadResponse? {
        Regex("/album/(\\d+)").find(url)?.groupValues?.get(1)?.let { return loadAlbum(it, url) }
        Regex("/artist/(\\d+)").find(url)?.groupValues?.get(1)?.let { return loadArtist(it, url) }
        Regex("/song/(\\d+)").find(url)?.groupValues?.get(1)?.let { return loadSong(it, url) }
        return null
    }

    private suspend fun loadSong(id: String, url: String): LoadResponse? {
        val title = param(url, "t") ?: "Track"
        val artist = param(url, "a").orEmpty()
        val album = param(url, "al").orEmpty()
        val dur = param(url, "d")?.toLongOrNull() ?: 0L
        val cover = param(url, "art")?.takeIf { it.startsWith("http") }
        NonuApi.log("step", "song $id \"$title\" - $artist")
        return newMovieLoadResponse(
            if (artist.isNotBlank()) "$title - $artist" else title,
            url,
            TvType.Music,
            url,
        ) {
            this.posterUrl = cover
            this.plot = listOfNotNull(artist.ifBlank { null }, album.ifBlank { null }).joinToString(" · ")
            this.duration = (dur / 1000).toInt()
        }
    }

    private suspend fun loadAlbum(id: String, url: String): LoadResponse? {
        val root = NonuApi.json(
            "/amp/v1/catalog/$cc/albums/$id?extend=extendedAssetUrls&include=tracks,artists&l=en-GB&platform=web"
        ) ?: return null
        val album = root.optJSONArray("data").objs().firstOrNull() ?: return null
        val a = attrs(album)
        val title = a.str("name") ?: dec(url.substringAfter("/album/$id/").substringBefore("/").ifBlank { "Album" })
        val artist = a.str("artistName").orEmpty()
        val cover = art(album, 900)

        val tracks = album.optJSONObject("relationships")?.optJSONObject("tracks")?.optJSONArray("data").objs()
            .ifEmpty { a.optJSONObject("tracks")?.optJSONArray("data").objs() }

        var n = 0
        val eps = tracks.mapNotNull { t ->
            val u = songUrl(t) ?: return@mapNotNull null
            val ta = attrs(t)
            newEpisode(u) {
                this.name = ta.str("name") ?: "Track ${n + 1}"
                this.season = 1
                this.episode = ++n
                this.posterUrl = art(t) ?: cover
                this.runTime = (ta.optLong("durationInMillis", 0L) / 60000L).toInt().takeIf { it > 0 }
            }
        }
        NonuApi.log("step", "album $id \"$title\": ${eps.size} tracks")
        if (eps.isEmpty()) return null
        return newTvSeriesLoadResponse(
            if (artist.isNotBlank()) "$title - $artist" else title,
            "$mainUrl/album/$id",
            TvType.Music,
            eps,
        ) {
            this.posterUrl = cover
            this.plot = listOfNotNull(artist.ifBlank { null }, "${eps.size} tracks").joinToString(" · ")
        }
    }

    private suspend fun loadArtist(id: String, url: String): LoadResponse? {
        val nm = dec(url.substringAfter("/artist/$id/").ifBlank { "Artist" })
        val root = NonuApi.json(
            "/amp/v1/catalog/$cc/artists/$id?views=top-songs&extend=extendedAssetUrls&l=en-GB&platform=web"
        ) ?: return null
        val artist = root.optJSONArray("data").objs().firstOrNull() ?: return null
        val songs = artist.optJSONObject("views")?.optJSONObject("top-songs")?.optJSONArray("data").objs()

        var n = 0
        val eps = songs.mapNotNull { t ->
            val u = songUrl(t) ?: return@mapNotNull null
            newEpisode(u) {
                this.name = attrs(t).str("name") ?: "Track ${n + 1}"
                this.season = 1
                this.episode = ++n
                this.posterUrl = art(t)
            }
        }
        NonuApi.log("step", "artist $id \"$nm\": ${eps.size} tracks")
        if (eps.isEmpty()) return null
        return newTvSeriesLoadResponse(nm, "$mainUrl/artist/$id", TvType.Music, eps) {
            this.posterUrl = art(artist)
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
        val id = Regex("/song/(\\d+)").find(data)?.groupValues?.get(1)
            ?: Regex("(\\d{5,})").findAll(data).lastOrNull()?.groupValues?.get(1)
            ?: return false

        // Password check
        if (NonuApi.password.isNotBlank()) {
            if (NonuApi.savedPassword != NonuApi.password) {
                NonuApi.log("WARN", "password mismatch for song $id")
                return false
            }
        }

        // the song resource carries the HLS master and a preview
        val root = NonuApi.json(
            "/amp/v1/catalog/$cc/songs/$id?extend=extendedAssetUrls&include=albums,artists&l=en-GB&platform=web"
        )
        val songAttrs = root?.optJSONArray("data").objs().firstOrNull()?.let { attrs(it) }
        val master = songAttrs?.optJSONObject("extendedAssetUrls")?.optString("enhancedHls").orEmpty()
        val preview = songAttrs?.optJSONArray("previews").objs().firstOrNull()?.optString("url").orEmpty()

        NonuApi.log(
            "step",
            "song resource: master=${if (master.isBlank()) "none" else "yes"}, preview=${if (preview.isBlank()) "none" else "yes"}"
        )

        // the master is a plain HLS playlist: variants with CODECS, then their uri on the next line
        // Decrypt the track
        val track = NonuDecrypt.parseMediaPlaylist(master, master, songAttrs?.str("id"))
        NonuDecrypt.decrypt(com.lagradost.cloudstream3.CloudStreamApp.context ?: context, track)

        // Rebuild the playlist with local decrypted URLs
        val variantM3u8 = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:6")
            track.segments.forEach { seg ->
                appendLine("#EXTINF:${seg.duration},")
                appendLine(seg.decryptedUrl)
            }
            append("#EXT-X-ENDLIST")
        }

        val variantUrl = java.io.File((com.lagradost.cloudstream3.CloudStreamApp.context).cacheDir, "${track.adamId}.m3u8").apply {
            writeText(variantM3u8)
        }.absolutePath

        callback.invoke(
            newExtractorLink(name, "$name ${track.segments.sumOf { it.duration.toInt() }.toInt() / 1000}kbps", variantUrl, ExtractorLinkType.M3U8) {
                this.referer = "$mainUrl/song/${id}"
                this.quality = Qualities.Unknown.value
            }
        )
        return true
            val playlist = NonuApi.text(master)
            if (playlist != null) {
                val variants = parseVariants(playlist, master)
                NonuApi.log("step", "master: ${variants.size} variant(s)")
                val best = variants.maxByOrNull { it.rank }
                if (best != null) {
                    NonuApi.log("step", "picked ${best.codecs} ${best.bandwidth / 1000}kbps")
                    callback.invoke(
                        newExtractorLink(name, "$name ${best.label}", best.url, ExtractorLinkType.M3U8) {
                            this.referer = "${NonuApi.SITE}/"
                            this.quality = best.quality
                            this.headers = NonuApi.headers(mapOf("Accept" to "*/*"))
                        }
                    )
                    return true
                }
            }
        }

        if (preview.startsWith("http")) {
            NonuApi.log("step", "emitted preview only")
            callback.invoke(
                newExtractorLink(name, "$name preview (30s)", preview, ExtractorLinkType.VIDEO) {
                    this.referer = "${NonuApi.SITE}/"
                    this.quality = 128
                }
            )
            return true
        }

        NonuApi.log("WARN", "nothing playable for $id")
        return false
    }

    // ------------------------------------------------------------------ HLS parsing

    private data class Variant(
        val url: String,
        val codecs: String,
        val bandwidth: Int,
        val rank: Int,
        val label: String,
        val quality: Int,
    )

    /**
     * Reads EXT-X-STREAM-INF entries. ALAC ranks highest because it is the lossless one; the rest
     * are ordered by bandwidth. The uri follows its tag on the next non-comment line.
     */
    private fun parseVariants(playlist: String, masterUrl: String): List<Variant> {
        val out = ArrayList<Variant>()
        val base = masterUrl.substringBeforeLast("/") + "/"
        var pending: String? = null
        var pendingBw = 0
        var pendingCodecs = ""

        for (raw in playlist.split("\n")) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                pending = line
                pendingBw = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                pendingCodecs = Regex("CODECS=\"([^\"]+)\"").find(line)?.groupValues?.get(1) ?: ""
                continue
            }
            if (line.startsWith("#")) continue
            if (pending != null) {
                val url = if (line.startsWith("http")) line else base + line
                val codecs = pendingCodecs.lowercase()
                val (rank, label, quality) = when {
                    codecs.contains("alac") -> Triple(100, "ALAC (lossless)", Qualities.Unknown.value)
                    codecs.contains("ec-3") || codecs.contains("ac-3") -> Triple(60, "Dolby", 512)
                    pendingBw >= 250_000 -> Triple(50, "AAC 256kbps", 256)
                    pendingBw >= 120_000 -> Triple(40, "AAC 128kbps", 128)
                    else -> Triple(30, "AAC 64kbps", 64)
                }
                out.add(Variant(url, pendingCodecs.ifBlank { "unknown" }, pendingBw, rank, label, quality))
                pending = null
            }
        }
        return out
    }
}
