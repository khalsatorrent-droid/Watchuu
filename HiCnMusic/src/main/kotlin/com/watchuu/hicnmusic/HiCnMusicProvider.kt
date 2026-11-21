package com.watchuu.hicnmusic

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.fasterxml.jackson.annotation.JsonProperty

class HiCnMusicProvider : MainAPI() {
    override var mainUrl = "https://flac.music.hi.cn"
    override var name = "hi.cn music"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Music, TvType.Others)

    companion object {
        // Platforms confirmed in capture: wyy (网易云). kuwo inferred from site JS.
        private val PLATFORMS = listOf("wyy", "kuwo")
        private const val PAGE_SIZE = 20
    }

    // ── Data classes ─────────────────────────────────────────────────────────

    data class MInfo(
        @JsonProperty("bitrate") val bitrate: String = "",
        @JsonProperty("size")    val size: String    = "",
        @JsonProperty("format")  val format: String  = ""
    )

    data class SongItem(
        @JsonProperty("id")          val id: Long        = 0,
        @JsonProperty("name")        val name: String    = "",
        @JsonProperty("artist")      val artist: String  = "",
        @JsonProperty("album_name")  val albumName: String = "",
        @JsonProperty("pic_url")     val picUrl: String  = "",
        @JsonProperty("duration")    val duration: Int   = 0,
        @JsonProperty("minfo")       val minfo: List<MInfo> = emptyList(),
        @JsonProperty("time")        val time: Long      = 0,
        @JsonProperty("sign")        val sign: String    = ""
    )

    data class SearchData(
        @JsonProperty("list")  val list: List<SongItem> = emptyList(),
        @JsonProperty("total") val total: Int = 0
    )

    data class SearchResp(
        @JsonProperty("code") val code: Int = -1,
        @JsonProperty("data") val data: SearchData? = null
    )

    data class UrlData(
        @JsonProperty("url")     val url: String = "",
        @JsonProperty("format")  val format: String = "",
        @JsonProperty("bitrate") val bitrate: String = ""
    )

    data class UrlResp(
        @JsonProperty("code") val code: Int = -1,
        @JsonProperty("msg")  val msg: String = "",
        @JsonProperty("data") val data: UrlData? = null
    )

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Encode a song's metadata into a fake "url" that load() will parse back.
     * Format: hicn://platform/songid/time/sign/format1:bitrate1,format2:bitrate2/.../name/artist/album/pic
     * We URL-encode the last four fields to avoid delimiter collisions.
     */
    private fun encodeDataUrl(platform: String, song: SongItem): String {
        val formats = song.minfo.joinToString(",") { "${it.format}:${it.bitrate}" }
        val nameSafe    = song.name.replace("|", "%7C")
        val artistSafe  = song.artist.replace("|", "%7C")
        val albumSafe   = song.albumName.replace("|", "%7C")
        val picSafe     = song.picUrl.replace("|", "%7C")
        return "hicn://$platform/${song.id}/${song.time}/${song.sign}/$formats|$nameSafe|$artistSafe|$albumSafe|$picSafe"
    }

    private data class Decoded(
        val platform: String,
        val songId: String,
        val time: String,
        val sign: String,
        val minfo: List<MInfo>,
        val name: String,
        val artist: String,
        val album: String,
        val pic: String
    )

    private fun decodeDataUrl(url: String): Decoded? {
        // hicn://platform/songid/time/sign/formats|name|artist|album|pic
        val noScheme = url.removePrefix("hicn://")
        val parts = noScheme.split("/")
        if (parts.size < 6) return null
        val platform = parts[0]
        val songId   = parts[1]
        val time     = parts[2]
        val sign     = parts[3]
        // parts[4] onwards may contain '/' in pic_url, so rejoin from index 4
        val tail     = parts.drop(4).joinToString("/")
        val pipeIdx  = tail.indexOf('|')
        if (pipeIdx < 0) return null
        val formatsPart = tail.substring(0, pipeIdx)
        val rest        = tail.substring(pipeIdx + 1)
        val minfo = formatsPart.split(",").mapNotNull { token ->
            val kv = token.split(":")
            if (kv.size == 2) MInfo(bitrate = kv[1], format = kv[0]) else null
        }
        val metaParts = rest.split("|")
        if (metaParts.size < 4) return null
        return Decoded(
            platform = platform,
            songId   = songId,
            time     = time,
            sign     = sign,
            minfo    = minfo,
            name     = metaParts[0].replace("%7C", "|"),
            artist   = metaParts[1].replace("%7C", "|"),
            album    = metaParts[2].replace("%7C", "|"),
            pic      = metaParts[3].replace("%7C", "|")
        )
    }

    private fun SongItem.toSearchResult(platform: String): SearchResponse {
        val dataUrl = encodeDataUrl(platform, this)
        return newMovieSearchResponse(
            name   = "$name — $artist",
            url    = dataUrl,
            type   = TvType.Music
        ) {
            this.posterUrl = picUrl
        }
    }

    // ── Main page (trending / default keyword search) ─────────────────────────

    override val mainPage = mainPageOf(
        "wyy|trending" to "网易云 Trending",
        "kuwo|trending" to "酷我 Trending"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val (platform, _) = request.data.split("|")
        val keyword = "music"          // default keyword for a "home" row
        val items = searchPlatform(platform, keyword, page)
        return newHomePageResponse(request.name, items)
    }

    // ── Search ────────────────────────────────────────────────────────────────

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        for (platform in PLATFORMS) {
            results += searchPlatform(platform, query, 1)
        }
        return results
    }

    private suspend fun searchPlatform(
        platform: String,
        keyword: String,
        page: Int
    ): List<SearchResponse> {
        val resp = app.post(
            "$mainUrl/ajax.php?act=search",
            data = mapOf(
                "platform" to platform,
                "keyword"  to keyword,
                "page"     to page.toString(),
                "size"     to PAGE_SIZE.toString()
            ),
            headers = mapOf(
                "Origin"  to mainUrl,
                "Referer" to "$mainUrl/"
            )
        ).parsedSafe<SearchResp>() ?: return emptyList()

        if (resp.code != 0) return emptyList()
        return resp.data?.list?.map { it.toSearchResult(platform) } ?: emptyList()
    }

    // ── Load ─────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val d = decodeDataUrl(url) ?: return null
        return newMovieLoadResponse(
            name = "${d.name} — ${d.artist}",
            url  = url,
            type = TvType.Music,
            dataUrl = url
        ) {
            this.posterUrl = d.pic
            this.plot      = "Album: ${d.album}\nArtist: ${d.artist}"
            this.tags      = listOf(d.artist, d.album)
        }
    }

    // ── Load links ────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val d = decodeDataUrl(data) ?: return false

        // Sort formats: flac first (highest bitrate among flac), then mp3 320, mp3 128
        val sorted = d.minfo.sortedWith(
            compareByDescending<MInfo> { if (it.format == "flac") 1 else 0 }
                .thenByDescending { it.bitrate.toIntOrNull() ?: 0 }
        )

        var anySuccess = false
        for (minfo in sorted) {
            val urlResp = fetchUrl(d.platform, d.songId, minfo.format, minfo.bitrate, d.time, d.sign)
                ?: continue
            val quality = when {
                minfo.format == "flac" -> Qualities.Unknown.value
                (minfo.bitrate.toIntOrNull() ?: 0) >= 320 -> Qualities.P1080.value
                else -> Qualities.P720.value
            }
            val label = "${minfo.format.uppercase()} ${minfo.bitrate}kbps"
            callback(
                newExtractorLink(
                    source  = name,
                    name    = label,
                    url     = urlResp,
                    type    = ExtractorLinkType.AUDIO
                ) {
                    this.quality  = quality
                    this.referer  = "$mainUrl/"
                }
            )
            anySuccess = true
        }
        return anySuccess
    }

    /**
     * POST to act=getUrl; retries once on code=-1 (transient failure).
     * Returns the CDN URL string, or null if unavailable.
     */
    private suspend fun fetchUrl(
        platform: String,
        songId: String,
        format: String,
        bitrate: String,
        time: String,
        sign: String,
        retry: Boolean = true
    ): String? {
        val resp = app.post(
            "$mainUrl/ajax.php?act=getUrl",
            data = mapOf(
                "platform" to platform,
                "songid"   to songId,
                "format"   to format,
                "bitrate"  to bitrate,
                "time"     to time,
                "sign"     to sign
            ),
            headers = mapOf(
                "Origin"  to mainUrl,
                "Referer" to "$mainUrl/"
            )
        ).parsedSafe<UrlResp>() ?: return null

        return when {
            resp.code == 0 -> resp.data?.url?.takeIf { it.isNotBlank() }
            resp.code == -1 && retry -> fetchUrl(platform, songId, format, bitrate, time, sign, retry = false)
            else -> null
        }
    }
}
