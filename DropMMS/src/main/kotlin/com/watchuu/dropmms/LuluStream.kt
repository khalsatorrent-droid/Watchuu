package com.watchuu.dropmms

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI

/**
 * LuluStream family (lulust.com, luluvido.com, luluvid.com, lulustream.com, luluvdo.com …).
 * The /e/{id} page runs a p,a,c,k,e,d-packed jwplayer setup:
 *   jwplayer("vplayer").setup({sources:[{file:"https://…/master.m3u8?t=…"}], tracks:[…]})
 * luluvid.com/{id} is a landing page that iframes luluvido.com/e/{id}.
 *
 * [extractGeneric] applies the same packed-jwplayer read to any other hoster that the
 * built-in extractors don't know (most of the StreamWish / FileMoon / VidHide clones).
 */
object LuluStream {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

    fun handles(url: String): Boolean =
        runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("").contains("lulu")

    suspend fun extract(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val uri = URI(url)
        val id = uri.path.split('/').filter { it.isNotBlank() && it != "e" && it != "d" && it != "v" }
            .lastOrNull()?.substringBefore('.') ?: return
        val origin = "${uri.scheme}://${uri.host}"
        var embed = "$origin/e/$id"
        var html = app.get(embed, referer = "$origin/", headers = mapOf("User-Agent" to UA)).text

        // landing page → follow its player iframe
        if (!html.contains("jwplayer") && !html.contains("eval(function(p,a,c,k")) {
            Regex("""<iframe[^>]+src=["']([^"']+/e/[^"']+)""").find(html)?.groupValues?.get(1)?.let {
                embed = if (it.startsWith("//")) "https:$it" else it
                html = app.get(embed, referer = "$origin/", headers = mapOf("User-Agent" to UA)).text
            }
        }
        emitFromPlayer(html, embed, "LuluStream", subtitleCallback, callback)
    }

    suspend fun extractGeneric(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val html = app.get(url, referer = referer, headers = mapOf("User-Agent" to UA)).text
        val host = runCatching { URI(url).host.removePrefix("www.") }.getOrDefault("Stream")
        emitFromPlayer(html, url, host.substringBefore('.').replaceFirstChar { it.uppercase() },
            subtitleCallback, callback)
    }

    private suspend fun emitFromPlayer(
        html: String,
        pageUrl: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val script = if (html.contains("eval(function(p,a,c,k")) {
            runCatching { getAndUnpack(html) }.getOrNull().orEmpty() + "\n" + html
        } else html

        val origin = URI(pageUrl).let { "${it.scheme}://${it.host}" }

        val file = Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*["']([^"']+)""").find(script)?.groupValues?.get(1)
            ?: Regex("""file\s*:\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)""").find(script)?.groupValues?.get(1)
            ?: Regex("""["'](https?://[^"']+\.m3u8[^"']*)""").find(script)?.groupValues?.get(1)
            ?: return

        // subtitles
        Regex("""\{\s*file\s*:\s*["']([^"']+\.(?:vtt|srt))["']\s*,\s*label\s*:\s*["']([^"']+)""")
            .findAll(script).forEach { m ->
                val sub = m.groupValues[1]
                if (!sub.contains("empty.srt")) {
                    subtitleCallback(newSubtitleFile(m.groupValues[2], if (sub.startsWith("/")) origin + sub else sub))
                }
            }

        val headers = mapOf("User-Agent" to UA, "Origin" to origin, "Referer" to "$origin/")
        if (file.contains(".m3u8")) {
            val (best, height) = bestVariant(file, headers)
            callback(
                newExtractorLink(label, label, best, ExtractorLinkType.M3U8) {
                    referer = "$origin/"
                    quality = height ?: Qualities.Unknown.value
                    this.headers = headers
                }
            )
        } else {
            callback(
                newExtractorLink(label, label, file, ExtractorLinkType.VIDEO) {
                    referer = "$origin/"
                    quality = Regex("""(\d{3,4})p""").find(file)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Qualities.Unknown.value
                    this.headers = headers
                }
            )
        }
    }

    /** Collapses a master playlist to its highest variant, labelled with that variant's real height. */
    private suspend fun bestVariant(master: String, headers: Map<String, String>): Pair<String, Int?> {
        val text = runCatching { app.get(master, headers = headers).text }.getOrNull() ?: return master to null
        if (!text.contains("#EXT-X-STREAM-INF")) return master to null
        val lines = text.lines()
        var bestUrl: String? = null
        var bestH = -1
        var bestBw = -1L
        for (i in lines.indices) {
            val l = lines[i]
            if (!l.startsWith("#EXT-X-STREAM-INF")) continue
            val uri = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: continue
            val h = Regex("""RESOLUTION=\d+x(\d+)""").find(l)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            val bw = Regex("""BANDWIDTH=(\d+)""").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
            if (h > bestH || (h == bestH && bw > bestBw)) {
                bestH = h; bestBw = bw; bestUrl = uri.trim()
            }
        }
        val chosen = bestUrl ?: return master to null
        val abs = when {
            chosen.startsWith("http") -> chosen
            chosen.startsWith("/") -> URI(master).let { "${it.scheme}://${it.host}$chosen" }
            else -> master.substringBefore('?').substringBeforeLast('/') + "/" + chosen
        }
        return abs to bestH.takeIf { it > 0 }
    }
}
