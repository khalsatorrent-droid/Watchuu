package com.watchuu.musicbox

import com.lagradost.cloudstream3.app
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Amazon Music web catalogue search, used only to turn "title + artist" into an ASIN for the ZARZ
 * `/dl/amazeamazeamaze` resolver. Port of the SpotiFLAC amzn extension's session + showSearch flow.
 */
object AmazonApi {
    private const val BASE = "https://music.amazon.com"
    private const val SKILL = "https://na.mesk.skill.music.a2z.com/api"
    private const val APP_VERSION = "1.0.9678.0"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36"
    private val ASIN = Regex("^B[0-9A-Z]{9}$")

    private val cookies = ConcurrentHashMap<String, String>()
    @Volatile private var inited = false
    @Volatile private var deviceId = ""
    @Volatile private var sessionId = ""
    @Volatile private var appVersion = APP_VERSION
    @Volatile private var language = "en_US"
    @Volatile private var territory = "US"
    @Volatile private var csrfToken = ""
    @Volatile private var csrfTs = ""
    @Volatile private var csrfRnd = ""

    data class Hit(val asin: String, val title: String, val artist: String, val durationMs: Long)

    private fun cookieHeader() = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    private suspend fun initSession(force: Boolean = false) {
        if (inited && !force) return
        if (force) cookies.clear()
        inited = false
        try {
            val res = app.post(
                "$BASE/config.json?skipToken=false&clientApplication=skyfire",
                headers = mapOf("User-Agent" to UA, "Accept" to "application/json"),
                requestBody = "".toRequestBody(null),
            )
            for (sc in res.headers.values("Set-Cookie")) {
                val m = Regex("^([^=]+)=([^;]*)").find(sc) ?: continue
                cookies[m.groupValues[1].trim()] = m.groupValues[2].trim()
            }
            if (res.code in 200..299) {
                val c = JSONObject(res.text)
                deviceId = c.optString("deviceId")
                sessionId = c.optString("sessionId")
                appVersion = c.optString("version").ifBlank { APP_VERSION }
                language = c.optString("displayLanguage").ifBlank { "en_US" }
                territory = c.optString("musicTerritory").ifBlank { "US" }
                c.optJSONObject("csrf")?.let {
                    csrfToken = it.optString("token")
                    csrfTs = it.optString("ts").ifBlank { (System.currentTimeMillis() / 1000).toString() }
                    csrfRnd = it.optString("rnd").ifBlank { (Math.random() * 2_000_000_000).toLong().toString() }
                }
                inited = true
                return
            }
        } catch (_: Exception) {
        }
        // fallback: random session (may be rate limited)
        deviceId = (Math.random() * 99_999_999_999_999_999.0).toLong().toString()
        sessionId = "${(Math.random() * 999).toInt()}-${(Math.random() * 9_999_999).toInt()}-${(Math.random() * 9_999_999).toInt()}"
        csrfToken = ""
        csrfTs = (System.currentTimeMillis() / 1000).toString()
        csrfRnd = (Math.random() * 2_000_000_000).toLong().toString()
        inited = true
    }

    private fun headersJson(pageUrl: String): String {
        val csrf = JSONObject().put("interface", "CSRFInterface.v1_0.CSRFHeaderElement").put("token", csrfToken)
            .put("timestamp", csrfTs).put("rndNonce", csrfRnd).toString()
        val auth = JSONObject().put("interface", "ClientAuthenticationInterface.v1_0.ClientTokenElement").put("accessToken", "").toString()
        return JSONObject()
            .put("x-amzn-authentication", auth)
            .put("x-amzn-device-model", "WEBPLAYER")
            .put("x-amzn-device-width", "1920")
            .put("x-amzn-device-family", "WebPlayer")
            .put("x-amzn-device-id", deviceId)
            .put("x-amzn-user-agent", UA)
            .put("x-amzn-session-id", sessionId)
            .put("x-amzn-device-height", "1080")
            .put("x-amzn-request-id", java.lang.Long.toString((Math.random() * 1e12).toLong(), 36) + "-" + System.currentTimeMillis())
            .put("x-amzn-device-language", language)
            .put("x-amzn-currency-of-preference", "USD")
            .put("x-amzn-os-version", "1.0")
            .put("x-amzn-application-version", appVersion)
            .put("x-amzn-device-time-zone", "UTC")
            .put("x-amzn-timestamp", System.currentTimeMillis().toString())
            .put("x-amzn-csrf", csrf)
            .put("x-amzn-music-domain", "music.amazon.com")
            .put("x-amzn-referer", "")
            .put("x-amzn-affiliate-tags", "")
            .put("x-amzn-ref-marker", "")
            .put("x-amzn-page-url", pageUrl)
            .put("x-amzn-weblab-id-overrides", "")
            .put("x-amzn-video-player-token", "")
            .put("x-amzn-feature-flags", "")
            .put("x-amzn-has-profile-id", "")
            .put("x-amzn-age-band", "")
            .toString()
    }

    private suspend fun showSearch(keyword: String, retry: Boolean = true): JSONObject? {
        initSession()
        val pageUrl = "$BASE/search/${java.net.URLEncoder.encode(keyword, "UTF-8").replace("+", "%20")}"
        val body = JSONObject()
            .put("filter", JSONObject().put("IsLibrary", JSONArray().put("false")).toString())
            .put(
                "keyword",
                JSONObject().put("interface", "Web.TemplatesInterface.v1_0.Touch.SearchTemplateInterface.SearchKeywordClientInformation")
                    .put("keyword", keyword).toString(),
            )
            .put("suggestedKeyword", keyword)
            .put("userHash", JSONObject().put("level", "LIBRARY_MEMBER").toString())
            .put("headers", headersJson(pageUrl))
            .toString()
        val res = app.post(
            "$SKILL/showSearch",
            headers = mapOf("User-Agent" to UA, "Origin" to BASE, "Referer" to pageUrl, "Cookie" to cookieHeader())
                .filterValues { it.isNotEmpty() },
            requestBody = body.toRequestBody("text/plain;charset=UTF-8".toMediaType()),
        )
        val data = if (res.code in 200..299) runCatching { JSONObject(res.text) }.getOrNull() else null
        if (data == null || isServiceError(data)) {
            if (retry) {
                initSession(force = true)
                return showSearch(keyword, false)
            }
            return null
        }
        return data
    }

    private fun isServiceError(data: JSONObject): Boolean {
        val methods = data.optJSONArray("methods") ?: return false
        var dialog = false
        for (i in 0 until methods.length()) {
            val name = methods.optJSONObject(i)?.optJSONObject("template")?.optString("interface").orEmpty()
            if (name.endsWith("SearchTemplate")) return false
            if (name.endsWith("DialogTemplate")) dialog = true
        }
        return dialog
    }

    // ------------------------------------------------------------------ parsing

    private fun text(v: Any?): String = when (v) {
        null, JSONObject.NULL -> ""
        is String -> v
        is Number -> v.toString()
        is JSONObject -> v.optString("text").ifBlank {
            v.opt("defaultValue")?.let { text(it) }?.ifBlank { null }
                ?: v.optJSONObject("observer")?.opt("defaultValue")?.let { text(it) }.orEmpty()
        }
        else -> ""
    }

    private fun duration(v: Any?): Long {
        val t = text(v)
        val p = t.split(":").mapNotNull { it.trim().toLongOrNull() }
        return when (p.size) {
            2 -> p[0] * 60 + p[1]
            3 -> p[0] * 3600 + p[1] * 60 + p[2]
            else -> 0
        } * 1000
    }

    private fun trackAsinFromDeeplink(link: String): String? {
        if (link.isBlank()) return null
        return try {
            val u = URI(BASE).resolve(link)
            val kind = u.path.trim('/').split("/").firstOrNull()?.lowercase()
            val query = (u.rawQuery ?: "").split("&").associate { it.substringBefore("=") to it.substringAfter("=", "") }
            val asin = query["trackAsin"]?.let { java.net.URLDecoder.decode(it, "UTF-8").uppercase() }
            if (kind == "albums" && asin != null && ASIN.matches(asin)) asin else null
        } catch (_: Exception) {
            null
        }
    }

    private fun collect(node: Any?, out: MutableList<Hit>, depth: Int = 0) {
        if (node == null || depth > 25) return
        if (node is JSONArray) {
            for (i in 0 until node.length()) collect(node.opt(i), out, depth + 1)
        } else if (node is JSONObject) {
            val iface = node.optString("interface")
            if (iface.contains("DescriptiveRowItemElement") || iface.contains("SquareHorizontalItemElement")) {
                val link = node.optJSONObject("primaryTextLink")?.optString("deeplink").orEmpty()
                    .ifBlank { node.optJSONObject("primaryLink")?.optString("deeplink").orEmpty() }
                val asin = trackAsinFromDeeplink(link)
                val title = text(node.opt("primaryText"))
                if (asin != null && title.isNotBlank()) {
                    val artist = text(node.opt("secondaryText1")).ifBlank { text(node.opt("secondaryText")) }
                    val dur = duration(node.opt("secondaryText3")).takeIf { it > 0 } ?: duration(node.opt("duration"))
                    out.add(Hit(asin, title, artist, dur))
                }
            }
            val keys = node.keys()
            while (keys.hasNext()) collect(node.opt(keys.next()), out, depth + 1)
        }
    }

    private fun norm(s: String) = s.lowercase().replace("&", " and ").replace(Regex("[^a-z0-9]+"), " ").trim().replace(Regex("\\s+"), " ")

    private fun stripAnnotations(s: String) =
        s.replace(Regex("""[(\[]\s*(?:(?:feat\.?|ft\.?|featuring)\s+[^)\]]+|from\s+["“][^)\]]+["”]\s*)[)\]]""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+"), " ").trim()

    private fun artistMatches(candidate: String, expected: String): Boolean {
        val c = norm(candidate)
        val e = norm(expected)
        if (e.isEmpty()) return true
        if (c.isEmpty()) return false
        return c == e || c.contains(e) || e.contains(c)
    }

    /** Exact-title match on Amazon's catalogue; returns the ASIN or null. */
    suspend fun findAsin(title: String, artist: String, durationMs: Long = 0): String? {
        val stripped = stripAnnotations(title)
        val titleKey = norm(stripped)
        if (titleKey.isEmpty()) return null
        val primary = artist.split(Regex("""\s*[,;]\s*|\s+(?:feat\.?|ft\.?|featuring)\s+""", RegexOption.IGNORE_CASE)).firstOrNull().orEmpty().trim()
        val queries = LinkedHashSet<String>()
        queries.add("$stripped $primary".trim())
        queries.add(title.trim())
        queries.add(stripped)
        for (q in queries) {
            val data = try { showSearch(q) } catch (_: Exception) { null } ?: continue
            val hits = ArrayList<Hit>()
            collect(data, hits)
            for (h in hits) {
                if (norm(stripAnnotations(h.title)) != titleKey) continue
                if (!artistMatches(h.artist, artist)) continue
                if (durationMs > 0 && h.durationMs > 0 && kotlin.math.abs(durationMs - h.durationMs) > 15_000) continue
                return h.asin
            }
        }
        return null
    }
}
