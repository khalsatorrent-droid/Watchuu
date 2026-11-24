package com.watchuu.musicbox.providers

import com.watchuu.musicbox.core.Creds
import com.watchuu.musicbox.core.Diag
import com.watchuu.musicbox.core.Hit
import com.watchuu.musicbox.core.Http
import com.watchuu.musicbox.core.Links
import com.watchuu.musicbox.core.MusicProvider
import com.watchuu.musicbox.core.Stream
import com.watchuu.musicbox.core.Track
import com.watchuu.musicbox.core.jsonOrNull
import com.watchuu.musicbox.core.optStringOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * YouTube Music, ported from Mornye-Extension sources/ytmusic-spotiflac/index.js
 *  - search   POST music.youtube.com/youtubei/v1/search?alt=json with the WEB_REMIX client
 *             (clientVersion 1.20240801.01.00), parsed from musicResponsiveListItemRenderer
 *  - player   POST www.youtube.com/youtubei/v1/player?key=…&prettyPrint=false over the
 *             InnerTube client ladder (visionos, tv_samsung, web_embedded, mweb, android, ios),
 *             contentCheckOk/racyCheckOk set, visitorData forwarded
 *  - formats  chooseYouTubeFormat(): itag preference, audio streams first, bitrate, then itag 18
 */
object YtMusic : MusicProvider {
    override val id = "ytmusic"
    override val label = "YouTube Music"
    override val canCatalogue = true
    override val canStream = true

    private const val SEARCH_URL = "https://music.youtube.com/youtubei/v1/search?alt=json"
    private const val WEB_REMIX_VERSION = "1.20240801.01.00"
    private const val INNERTUBE_KEY = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w"
    private const val ANDROID_VERSION = "21.02.35"
    private const val ANDROID_UA = "com.google.android.youtube/21.02.35 (Linux; U; Android 11) gzip"
    private const val IOS_KEY = "AIzaSyB-63vPrdThhKuerbB2N_l7Kwwcxj6yUAc"

    // YOUTUBE_OPUS_ITAGS / YOUTUBE_M4A_ITAGS, then the low bitrate and video fallbacks.
    private val OPUS_ITAGS = listOf(251, 250, 249)
    private val M4A_ITAGS = listOf(140, 139)

    private fun preferredFormat(): String = (Creds.get("ytmusic.preferredFormat", "opus") ?: "opus").lowercase()

    private fun formatPreference(): List<Int> {
        val m4a = preferredFormat() == "m4a"
        return (if (m4a) M4A_ITAGS else OPUS_ITAGS) + (if (m4a) OPUS_ITAGS else M4A_ITAGS) + listOf(600, 599, 18)
    }

    override fun configured(): Boolean = true
    override fun statusDetail(): String = "ready (no authorization needed)"

    // ------------------------------------------------------------------ search

    private class Client(
        val name: String,
        val headerName: String,
        val client: JSONObject,
        val ua: String,
        val key: String,
    )

    private fun clientLadder(): List<Client> = listOf(
        Client(
            "visionos", "101",
            JSONObject()
                .put("clientName", "VISIONOS").put("clientVersion", "1.02")
                .put("deviceMake", "Apple").put("deviceModel", "RealityDevice17,1")
                .put("hl", "en").put("gl", "US").put("timeZone", "UTC").put("utcOffsetMinutes", 0)
                .put("osName", "visionOS").put("osVersion", "26.5.23O471"),
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15",
            INNERTUBE_KEY
        ),
        Client(
            "tv_samsung", "7",
            JSONObject()
                .put("clientName", "TVHTML5").put("clientVersion", "5.20260707")
                .put("hl", "en").put("gl", "US").put("timeZone", "UTC").put("utcOffsetMinutes", 0)
                .put("osName", "Tizen").put("osVersion", "2.4.0")
                .put("deviceMake", "Samsung").put("deviceModel", "UKS9800"),
            "Mozilla/5.0 (SMART-TV; Linux; Tizen 2.4.0) AppleWebKit/538.1 (KHTML, like Gecko) Version/2.4.0 TV Safari/538.1",
            INNERTUBE_KEY
        ),
        Client(
            "web_embedded", "56",
            JSONObject()
                .put("clientName", "WEB_EMBEDDED_PLAYER").put("clientVersion", "2.20260708.00.00")
                .put("hl", "en").put("gl", "US"),
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.5 Safari/605.1.15,gzip(gfe)",
            INNERTUBE_KEY
        ),
        Client(
            "mweb", "2",
            JSONObject()
                .put("clientName", "MWEB").put("clientVersion", "2.20260115.01.00")
                .put("hl", "en").put("gl", "US").put("timeZone", "UTC").put("utcOffsetMinutes", 0)
                .put(
                    "userAgent",
                    "Mozilla/5.0 (iPad; CPU OS 16_7_10 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1,gzip(gfe)"
                ),
            "Mozilla/5.0 (iPad; CPU OS 16_7_10 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1,gzip(gfe)",
            INNERTUBE_KEY
        ),
        Client(
            "android", "3",
            JSONObject()
                .put("clientName", "ANDROID").put("clientVersion", ANDROID_VERSION)
                .put("androidSdkVersion", 30).put("hl", "en").put("gl", "US")
                .put("timeZone", "UTC").put("utcOffsetMinutes", 0)
                .put("osName", "Android").put("osVersion", "11").put("platform", "MOBILE"),
            ANDROID_UA,
            INNERTUBE_KEY
        ),
        Client(
            "ios", "5",
            JSONObject()
                .put("clientName", "IOS").put("clientVersion", "21.02.3")
                .put("deviceMake", "Apple").put("deviceModel", "iPhone16,2")
                .put("hl", "en").put("gl", "US").put("timeZone", "UTC").put("utcOffsetMinutes", 0)
                .put("osName", "iOS").put("osVersion", "18.3.2").put("platform", "MOBILE"),
            "com.google.ios.youtube/21.02.3 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            IOS_KEY
        ),
    )

    // ------------------------------------------------------------------ json helpers

    private fun walk(obj: Any?, visit: (JSONObject) -> Unit) {
        when (obj) {
            is JSONObject -> {
                visit(obj)
                for (key in obj.keys()) walk(obj.opt(key), visit)
            }
            is JSONArray -> for (i in 0 until obj.length()) walk(obj.opt(i), visit)
        }
    }

    private fun runsText(textObj: JSONObject?): String {
        if (textObj == null) return ""
        textObj.optStringOrNull("simpleText")?.let { return it }
        val runs = textObj.optJSONArray("runs") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until runs.length()) {
            sb.append(runs.optJSONObject(i)?.optStringOrNull("text") ?: "")
        }
        return sb.toString()
    }

    private fun videoIdOf(item: JSONObject): String? {
        item.optJSONObject("playlistItemData")?.optStringOrNull("videoId")?.let { return it }
        var found: String? = null
        walk(item) { node ->
            if (found == null) {
                val endpoint = node.optJSONObject("watchEndpoint")
                if (endpoint != null) found = endpoint.optStringOrNull("videoId")
            }
        }
        return found
    }

    // ------------------------------------------------------------------ catalogue

    override suspend fun search(query: String, limit: Int): List<Hit> {
        val context = JSONObject().put(
            "client",
            JSONObject().put("clientName", "WEB_REMIX").put("clientVersion", WEB_REMIX_VERSION)
        )
        val body = JSONObject().put("context", context).put("query", query).toString()
        val res = Http.postJson(
            SEARCH_URL, body,
            mapOf(
                "User-Agent" to "Mozilla/5.0 (compatible)",
                "x-youtube-client-name" to "WEB_REMIX",
                "x-youtube-client-version" to WEB_REMIX_VERSION,
                "Accept-Language" to "en-US,en;q=0.9",
            )
        )
        if (!res.ok) {
            Diag.log("ytmusic", "search HTTP ${res.code}")
            return emptyList()
        }
        val payload = jsonOrNull(res.body) ?: return emptyList()

        val out = ArrayList<Hit>()
        val seen = HashSet<String>()
        walk(payload) { node ->
            if (out.size >= limit) return@walk
            val candidate = node.optJSONObject("musicResponsiveListItemRenderer")
                ?: node.optJSONObject("musicTwoRowItemRenderer")
                ?: return@walk
            val videoId = videoIdOf(candidate) ?: return@walk
            if (!seen.add(videoId)) return@walk

            val flex = candidate.optJSONArray("flexColumns")
            val title = runsText(flex?.optJSONObject(0)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text"))
                .ifBlank { runsText(candidate.optJSONObject("title")) }
                .ifBlank { candidate.optJSONObject("title")?.optStringOrNull("simpleText") ?: "" }
            if (title.isBlank()) return@walk

            // Second column carries "Artist • Album • 3:24"; keep the artist runs only.
            val artistParts = ArrayList<String>()
            val secondText = flex?.optJSONObject(1)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")
            val secondRuns = secondText?.optJSONArray("runs")
            var durationMs: Long? = null
            if (secondRuns != null) {
                for (i in 0 until secondRuns.length()) {
                    val text = secondRuns.optJSONObject(i)?.optStringOrNull("text")?.trim() ?: continue
                    if (text.isEmpty() || text == "•" || text == "," || text == "&") continue
                    if (Regex("^\\d{1,2}:\\d{2}$").matches(text)) {
                        val parts = text.split(":")
                        durationMs = (parts[0].toLongOrNull() ?: 0L) * 60000 + (parts[1].toLongOrNull() ?: 0L) * 1000
                        continue
                    }
                    val lower = text.lowercase()
                    if (lower == "single" || lower == "album" || lower == "ep" || lower == "song") continue
                    artistParts.add(text)
                }
            }
            val thumb = candidate.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                ?.let { arr -> if (arr.length() > 0) arr.optJSONObject(arr.length() - 1)?.optStringOrNull("url") else null }

            val track = Track(
                provider = id,
                id = videoId,
                title = title,
                artists = artistParts.take(1),
                cover = thumb,
                durationMs = durationMs,
                webUrl = "https://music.youtube.com/watch?v=$videoId",
            )
            out.add(Hit(Hit.Kind.TRACK, track.title, track.artistLine, track.cover, Links.track(id, videoId), track = track))
        }
        return out
    }

    override suspend fun track(id: String): Track? {
        // Search by id gives nothing; the player response carries the title, so a light
        // placeholder keeps playback working when a link is opened directly.
        return Track(
            provider = this.id,
            id = id,
            title = id,
            webUrl = "https://music.youtube.com/watch?v=$id",
        )
    }

    // ------------------------------------------------------------------ streams

    private fun outputExtension(format: JSONObject): String {
        val mime = (format.optStringOrNull("mimeType") ?: "").lowercase()
        val codec = Regex("codecs=\"([^\"]+)\"").find(mime)?.groupValues?.get(1)?.lowercase() ?: ""
        return when {
            mime.contains("audio/webm") || codec.contains("opus") -> "opus"
            mime.contains("audio/mp4") || codec.contains("mp4a") -> "m4a"
            mime.contains("video/mp4") -> "mp4"
            mime.contains("video/webm") -> "webm"
            else -> "opus"
        }
    }

    private fun usable(format: JSONObject): Boolean {
        val url = format.optStringOrNull("url")
        if (url.isNullOrBlank()) return false // signatureCipher would need a JS player
        val mime = (format.optStringOrNull("mimeType") ?: "").lowercase()
        val itag = format.optStringOrNull("itag")?.toIntOrNull() ?: 0
        return mime.startsWith("audio/") || (itag == 18 && mime.startsWith("video/mp4"))
    }

    /** scoreYouTubeFormat() / chooseYouTubeFormat(). */
    private fun chooseFormat(formats: List<JSONObject>): JSONObject? {
        val preference = formatPreference()
        val preferredExt = if (preferredFormat() == "m4a") "m4a" else "opus"
        fun score(format: JSONObject): Int {
            if (!usable(format)) return -1
            val itag = format.optStringOrNull("itag")?.toIntOrNull() ?: 0
            val prefIndex = preference.indexOf(itag)
            var value = if (prefIndex >= 0) 1000 - prefIndex * 50 else 0
            val mime = (format.optStringOrNull("mimeType") ?: "").lowercase()
            if (mime.startsWith("audio/")) value += 100
            if (outputExtension(format) == preferredExt) value += 25
            val bitrate = format.optStringOrNull("averageBitrate")?.toIntOrNull()
                ?: format.optStringOrNull("bitrate")?.toIntOrNull() ?: 0
            value += minOf(bitrate / 1000, 300)
            return value
        }
        for (wanted in preference) {
            val match = formats.firstOrNull { (it.optStringOrNull("itag")?.toIntOrNull() ?: 0) == wanted && usable(it) }
            if (match != null) return match
        }
        return formats.maxByOrNull { score(it) }?.takeIf { score(it) > 0 }
    }

    override suspend fun streams(track: Track, quality: String): List<Stream> {
        val visitorData = Creds.get("ytmusic.visitorData")
        for (client in clientLadder()) {
            val context = JSONObject().put("client", client.client)
            if (!visitorData.isNullOrBlank()) client.client.put("visitorData", visitorData)
            val body = JSONObject()
                .put("context", context)
                .put("videoId", track.id)
                .put("contentCheckOk", true)
                .put("racyCheckOk", true)
                .toString()
            val url = "https://www.youtube.com/youtubei/v1/player?key=${client.key}&prettyPrint=false"
            val res = Http.postJson(
                url, body,
                mapOf(
                    "User-Agent" to client.ua,
                    "Origin" to "https://www.youtube.com",
                    "X-YouTube-Client-Name" to client.headerName,
                    "X-YouTube-Client-Version" to client.client.optString("clientVersion", ""),
                )
            )
            if (!res.ok) {
                Diag.log("ytmusic", "${client.name}: HTTP ${res.code}")
                continue
            }
            val payload = jsonOrNull(res.body) ?: continue
            val status = payload.optJSONObject("playabilityStatus")?.optStringOrNull("status") ?: "?"
            if (status != "OK") {
                val reason = payload.optJSONObject("playabilityStatus")?.optStringOrNull("reason") ?: status
                Diag.log("ytmusic", "${client.name}: $reason")
                continue
            }
            // Remember the visitor id so later requests keep the same session.
            payload.optJSONObject("responseContext")?.optStringOrNull("visitorData")?.let {
                Creds.set("ytmusic.visitorData", it)
            }
            val streaming = payload.optJSONObject("streamingData") ?: continue
            val formats = ArrayList<JSONObject>()
            streaming.optJSONArray("formats")?.let { arr ->
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { formats.add(it) }
            }
            streaming.optJSONArray("adaptiveFormats")?.let { arr ->
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { formats.add(it) }
            }
            val chosen = chooseFormat(formats) ?: continue
            val streamUrl = chosen.optStringOrNull("url") ?: continue
            val ext = outputExtension(chosen)
            val mime = when (ext) {
                "m4a" -> "audio/mp4"
                "opus" -> "audio/webm"
                "mp4" -> "audio/mp4"
                else -> null
            }
            val bitrate = (chosen.optStringOrNull("averageBitrate")?.toIntOrNull()
                ?: chosen.optStringOrNull("bitrate")?.toIntOrNull() ?: 0) / 1000
            Diag.log("ytmusic", "resolved via ${client.name} (itag ${chosen.optStringOrNull("itag")})")
            return listOf(
                Stream(
                    url = streamUrl,
                    quality = "${ext.uppercase()}${if (bitrate > 0) " · ${bitrate}kbps" else ""}",
                    mime = mime,
                    headers = mapOf("User-Agent" to client.ua),
                    provider = id,
                )
            )
        }
        return emptyList()
    }
}
