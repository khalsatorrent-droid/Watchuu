package com.watchuu.vedu

import com.lagradost.cloudstream3.app
import java.net.URL
import kotlinx.coroutines.CancellationException

/**
 * Verified stream quality. A link is only ever marked with a resolution the stream itself declares:
 *  - HLS: the master playlist is fetched; an adaptive master is collapsed to its single
 *    highest-resolution variant and marked with that variant's RESOLUTION. When audio is a separate
 *    rendition (#EXT-X-MEDIA TYPE=AUDIO) the variants are video-only, so the master is kept and
 *    marked with its top RESOLUTION.
 *  - DASH: the MPD is fetched and marked with its tallest video Representation.
 *  - MP4: only an explicit resolution token from the source's own quality field (never the URL,
 *    a server name or a label).
 * Anything that can't be confirmed stays Unknown: a wrong mark is worse than none.
 */
object VeduQuality {

    class Checked(val url: String, val kind: String, val quality: Int?)

    private class Variant(val url: String, val width: Int?, val height: Int?, val bandwidth: Long?)

    private val widthSteps = listOf(3800 to 2160, 2500 to 1440, 1900 to 1080, 1260 to 720, 840 to 480, 630 to 360, 420 to 240)
    private val heightSteps = listOf(4320, 2160, 1440, 1080, 720, 480, 360, 240, 144)

    /** Standard quality for a frame size. Widescreen-aware (1920x800 -> 1080) and portrait-aware (720x1280 -> 720). */
    fun standardQuality(width: Int?, height: Int?): Int? {
        var w = width?.takeIf { it > 0 }
        var h = height?.takeIf { it > 0 }
        if (w != null && h != null && h > w) { val t = w; w = h; h = t } // portrait: the short side is the "p"
        val byHeight = h?.let { v -> heightSteps.firstOrNull { it <= v * 1.05 } ?: 144 }
        val byWidth = w?.let { v -> widthSteps.firstOrNull { v >= it.first }?.second ?: 144 }
        return listOfNotNull(byHeight, byWidth).maxOrNull()
    }

    /** A resolution only from a real, delimited marker: "1920x1080", "1080p", "720", "4K". */
    fun strictQuality(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val t = text.lowercase()
        Regex("""(?<![0-9])(\d{3,4})x(\d{3,4})(?![0-9])""").find(t)?.let {
            return standardQuality(it.groupValues[1].toIntOrNull(), it.groupValues[2].toIntOrNull())
        }
        Regex("""(?<![a-z0-9])(4320p?|2160p?|1440p?|1080p?|720p?|576p?|540p?|480p?|360p?|240p?|144p?|8k|4k|2k|uhd|qhd|fhd)(?![a-z0-9])""")
            .find(t)?.let {
                return when (val tok = it.groupValues[1]) {
                    "8k" -> 4320
                    "4k", "uhd" -> 2160
                    "2k", "qhd" -> 1440
                    "fhd" -> 1080
                    else -> tok.removeSuffix("p").toIntOrNull()
                }
            }
        return null
    }

    private fun attr(line: String, name: String): String? =
        Regex("""(?:^|[,:])$name=("[^"]*"|[^,]*)""").find(line)?.groupValues?.get(1)?.trim('"')

    private fun resolve(base: String, ref: String): String = try { URL(URL(base), ref.trim()).toString() } catch (_: Exception) { ref.trim() }

    private fun parseMaster(text: String, base: String): List<Variant> {
        val out = ArrayList<Variant>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                var j = i + 1
                while (j < lines.size && (lines[j].isBlank() || lines[j].trim().startsWith("#"))) j++
                if (j < lines.size) {
                    val res = attr(line, "RESOLUTION")?.split("x")
                    out.add(Variant(resolve(base, lines[j]), res?.getOrNull(0)?.toIntOrNull(), res?.getOrNull(1)?.toIntOrNull(),
                        attr(line, "BANDWIDTH")?.toLongOrNull()))
                }
                i = j
            }
            i++
        }
        return out
    }

    private fun dashQuality(mpd: String): Int? {
        var best: Int? = null
        for (m in Regex("""<Representation\b[^>]*>""").findAll(mpd)) {
            val tag = m.value
            if (Regex("""mimeType="audio""").containsMatchIn(tag)) continue
            val h = Regex("""\bheight="(\d+)"""").find(tag)?.groupValues?.get(1)?.toIntOrNull()
            val w = Regex("""\bwidth="(\d+)"""").find(tag)?.groupValues?.get(1)?.toIntOrNull()
            if (h == null && w == null) continue
            val q = standardQuality(w, h) ?: continue
            if (best == null || q > best) best = q
        }
        if (best == null) {
            // height/width can sit on the AdaptationSet instead
            for (m in Regex("""<AdaptationSet\b[^>]*>""").findAll(mpd)) {
                val tag = m.value
                if (!Regex("""(?:mimeType|contentType)="video""").containsMatchIn(tag)) continue
                val h = Regex("""\b(?:maxHeight|height)="(\d+)"""").find(tag)?.groupValues?.get(1)?.toIntOrNull()
                val w = Regex("""\b(?:maxWidth|width)="(\d+)"""").find(tag)?.groupValues?.get(1)?.toIntOrNull()
                val q = standardQuality(w, h) ?: continue
                if (best == null || q > best) best = q
            }
        }
        return best
    }

    /**
     * Returns the url to play (the best variant for an adaptive HLS master) and its verified quality.
     * [kind] is "hls", "dash" or "mp4"; [advertised] is the source's own quality field (used for MP4 only).
     */
    suspend fun check(url: String, kind: String, headers: Map<String, String>, advertised: String): Checked {
        if (kind == "mp4") return Checked(url, kind, strictQuality(advertised))
        return try {
            val r = app.get(url, headers = headers, timeout = 10L)
            val text = r.text
            val base = r.url.ifBlank { url }
            when {
                r.code !in 200..299 -> Checked(url, kind, null)
                kind == "dash" || text.trimStart().startsWith("<?xml") || text.contains("<MPD") ->
                    Checked(url, "dash", dashQuality(text))
                !text.trimStart().startsWith("#EXTM3U") -> Checked(url, kind, null) // not a playlist we can read
                else -> {
                    val variants = parseMaster(text, base)
                    if (variants.isEmpty()) {
                        // a media playlist declares no resolution: leave it Unknown
                        Checked(url, "hls", null)
                    } else {
                        val separateAudio = Regex("""#EXT-X-MEDIA:[^\n]*TYPE=AUDIO[^\n]*URI=""").containsMatchIn(text)
                        val best = variants.maxWithOrNull(compareBy<Variant>({ standardQuality(it.width, it.height) ?: 0 }, { it.bandwidth ?: 0L }))!!
                        val q = standardQuality(best.width, best.height)
                        if (separateAudio) Checked(url, "hls", q) else Checked(best.url, "hls", q)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Checked(url, kind, null)
        }
    }
}
