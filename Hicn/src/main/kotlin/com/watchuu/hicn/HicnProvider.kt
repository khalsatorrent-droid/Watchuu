package com.watchuu.hicn

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * HiCN — lossless music from flac.music.hi.cn (NetEase Cloud Music mirror).
 *
 * API (POST, application/x-www-form-urlencoded):
 *
 *   Search:   /ajax.php?act=search
 *     body:   platform=wyy&keyword=…&page=1&size=20
 *     result: { code:0, data:{ list:[{ id, name, artist, album_name, pic_url,
 *                duration, minfo:[{bitrate,size,format}], sign, time }] } }
 *
 *   Get URL:  /ajax.php?act=getUrl
 *     body:   platform=wyy&songid=…&format=flac&bitrate=…&time=…&sign=…
 *     result: { code:0, data:{ url:"https://m7xx.music.126.net/…" } }
 *
 * Card URL: /song/<id>?t=…&a=…&p=…&d=…&fmt=…&br=…&time=…&sign=…
 */
class HicnProvider : MainAPI() {

    override var mainUrl = "https://flac.music.hi.cn"
    override var name = "HiCN"
    override var lang = "zh"
    override val hasMainPage = false
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Music, TvType.Others)

    private val PLATFORM = "wyy"
    private val FORM_TYPE = "application/x-www-form-urlencoded;charset=UTF-8".toMediaType()

    // ------------------------------------------------------------------ helpers

    private fun enc(s: String) =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun dec(s: String) =
        runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private fun parseQuery(query: String): Map<String, String> =
        query.split("&").associate { kv ->
            val idx = kv.indexOf('=')
            if (idx < 0) kv to "" else kv.substring(0, idx) to dec(kv.substring(idx + 1))
        }

    private suspend fun post(act: String, body: String): JSONObject? {
        val resp = runCatching {
            app.post(
                "$mainUrl/ajax.php?act=$act",
                headers = mapOf(
                    "Origin" to mainUrl,
                    "Accept" to "*/*"
                ),
                requestBody = body.toRequestBody(FORM_TYPE)
            )
        }.getOrNull() ?: return null
        return runCatching { JSONObject(resp.text) }.getOrNull()
    }

    /** Picks the best minfo entry: prefer FLAC, then highest MP3 bitrate. */
    private data class BestFormat(val format: String, val bitrate: String)

    private fun bestFormat(minfo: JSONArray): BestFormat? {
        var best: BestFormat? = null
        var bestScore = -1
        for (i in 0 until minfo.length()) {
            val o = minfo.optJSONObject(i) ?: continue
            val fmt = o.optString("format", "")
            val br = o.optString("bitrate", "0").toIntOrNull() ?: 0
            val score = if (fmt.equals("flac", ignoreCase = true)) 1_000_000 + br else br
            if (score > bestScore) {
                bestScore = score
                best = BestFormat(fmt, o.optString("bitrate", ""))
            }
        }
        return best
    }

    private fun cardUrl(
        id: Long, title: String, artist: String,
        pic: String, duration: Int,
        fmt: String, bitrate: String, time: Long, sign: String
    ): String = "/song/$id?t=${enc(title)}&a=${enc(artist)}&p=${enc(pic)}" +
        "&d=$duration&fmt=${enc(fmt)}&br=${enc(bitrate)}&time=$time&sign=${enc(sign)}"

    // ------------------------------------------------------------------ search

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        for (page in 1..2) {
            val body = "platform=$PLATFORM&keyword=${enc(query)}&page=$page&size=20"
            val json = post("search", body) ?: break
            if (json.optInt("code", -1) != 0) break
            val list = json.optJSONObject("data")?.optJSONArray("list") ?: break
            if (list.length() == 0) break

            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                val id = o.optLong("id", 0L)
                if (id == 0L) continue
                val title = o.optString("name", "")
                val artist = o.optString("artist", "")
                val albumName = o.optString("album_name", "")
                val pic = o.optString("pic_url", "")
                val duration = o.optInt("duration", 0)
                val sign = o.optString("sign", "")
                val time = o.optLong("time", 0L)
                val minfo = o.optJSONArray("minfo") ?: continue
                val best = bestFormat(minfo) ?: continue

                val displayTitle = buildString {
                    append(title)
                    if (albumName.isNotBlank() && albumName != title) append(" · $albumName")
                }

                results += MovieSearchResponse(
                    name = displayTitle,
                    url = cardUrl(id, title, artist, pic, duration, best.format, best.bitrate, time, sign),
                    apiName = this.name,
                    type = TvType.Music,
                    posterUrl = pic.ifBlank { null },
                    year = null
                )
            }
        }
        return results
    }

    // ------------------------------------------------------------------ load

    override suspend fun load(url: String): LoadResponse {
        val uri = java.net.URI(url)
        val p = parseQuery(uri.query.orEmpty())
        val title = p["t"] ?: ""
        val artist = p["a"] ?: ""
        val pic = p["p"] ?: ""
        val duration = p["d"]?.toIntOrNull() ?: 0
        val fmt = p["fmt"] ?: ""
        val bitrate = p["br"] ?: ""

        val qualityLabel = if (fmt.equals("flac", ignoreCase = true))
            "FLAC ${bitrate}kbps" else "MP3 ${bitrate}kbps"
        val mins = duration / 60
        val secs = duration % 60

        return MovieLoadResponse(
            name = title,
            url = url,
            apiName = this.name,
            type = TvType.Music,
            dataUrl = url,
            posterUrl = pic.ifBlank { null },
            year = null,
            plot = "Artist: $artist\nFormat: $qualityLabel\nDuration: $mins:${"%02d".format(secs)}",
            rating = null
        )
    }

    // ------------------------------------------------------------------ loadLinks

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val uri = java.net.URI(data)
        val p = parseQuery(uri.query.orEmpty())
        val songId = uri.path.substringAfterLast("/")
        val fmt = p["fmt"] ?: ""
        val bitrate = p["br"] ?: ""
        val time = p["time"] ?: ""
        val sign = p["sign"] ?: ""
        val artist = p["a"] ?: ""
        val title = p["t"] ?: ""

        val body = "platform=$PLATFORM&songid=$songId&format=$fmt&bitrate=$bitrate&time=$time&sign=$sign"
        val json = post("getUrl", body) ?: return false
        if (json.optInt("code", -1) != 0) return false
        val streamUrl = json.optJSONObject("data")?.optString("url", "").orEmpty()
        if (streamUrl.isBlank()) return false

        val label = if (fmt.equals("flac", ignoreCase = true))
            "FLAC ${bitrate}kbps" else "MP3 ${bitrate}kbps"

        callback(
            newExtractorLink(
                source = this.name,
                name = "$artist — $title ($label)",
                url = streamUrl,
                type = ExtractorLinkType.AUDIO
            ) {
                this.quality = Qualities.Unknown.value
                this.referer = mainUrl
            }
        )
        return true
    }
}
