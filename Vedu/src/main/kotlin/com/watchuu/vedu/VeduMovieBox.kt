package com.watchuu.vedu

import android.util.Base64
import com.lagradost.cloudstream3.app
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.TimeZone
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * MovieBox (aoneroom) client, ported from assets/resolvers/moviebox.js (v2.5.34) and
 * moviebox_shorttv.js (v1.0.1): the "v1 request identity" (com.community.oneroom 4.0.03),
 * HMAC-MD5 x-tr-signature, bottom-tab bootstrap for the bearer token, play-info v2 across the
 * api hosts with play-info v1 as fallback, CloudFront-cookie DASH manifests, and shorts/mini-list
 * for ShortTV episodes. Search uses the app's own /subject-api/search/v2.
 */
object VeduMovieBox {
    private const val BASE = "https://api6.aoneroom.com"
    private val PLAY_HOSTS = listOf(BASE, "https://api4sg.aoneroom.com", "https://api5.aoneroom.com", "https://api4.aoneroom.com", "https://api3.aoneroom.com")
    private const val SECRET = "76iRl07s0xSN9jqmEWAt79EBJZulIQIsV64FZr2O"
    private const val UA = "okhttp/4.12.0"
    private const val JSON_TYPE = "application/json"
    private const val CERT = "03d4b741632d136bc23e2de4c6adcdb7712f1980b619c183221801730bbd0410"
    private const val AUTH_HOST = "sacdn.hakunaymatata.com"
    private const val PLACEHOLDER_HOST = "macdn.aoneroom.com"

    private val secretBytes: ByteArray by lazy { Base64.decode(SECRET, Base64.DEFAULT) }
    private val deviceId = UUID.randomUUID().toString()
    private val gaid = UUID.randomUUID().toString()
    @Volatile private var token: String = ""
    @Volatile private var tokenAt: Long = 0L

    // ------------------------------------------------------------------ signing

    private fun md5Hex(b: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun clientBuild(): String {
        val nonce = "${System.currentTimeMillis() * 1000 + (0..999).random()}${(0..999).random()}"
        return "$nonce.${md5Hex((CERT + "oneroom" + nonce).toByteArray())}-"
    }

    private fun clientInfo(build: String): String = JSONObject()
        .put("package_name", "com.community.oneroom")
        .put("version_name", "4.0.03.0930.03")
        .put("version_code", 50020132)
        .put("X-Client-Build", build)
        .put("os", "android")
        .put("os_version", "14")
        .put("install_ch", "ps")
        .put("device_id", deviceId)
        .put("install_store", "ps")
        .put("gaid", gaid)
        .put("brand", "")
        .put("model", "Pixel 8")
        .put("system_language", "en")
        .put("net", "NETWORK_WIFI")
        .put("region", "US")
        .put("timezone", try { TimeZone.getDefault().id } catch (_: Exception) { "UTC" })
        .put("sp_code", "")
        .toString()

    private fun clientToken(): String {
        val ts = System.currentTimeMillis().toString()
        return "$ts,${md5Hex(ts.reversed().toByteArray())}"
    }

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8").replace("+", "%20")

    private fun signature(method: String, path: String, query: List<Pair<String, String>>, accept: String,
                          contentType: String, body: ByteArray?, ts: Long): String {
        val decoded = LinkedHashMap<String, String>()
        for ((k, v) in query) {
            try {
                val name = URLDecoder.decode(k, "UTF-8")
                if (name.isNotEmpty()) decoded[name] = URLDecoder.decode(v, "UTF-8")
            } catch (_: Exception) {
            }
        }
        val canonicalQuery = decoded.entries.sortedBy { it.key }.joinToString("&") { "${it.key}=${it.value}" }
        val signedPath = if (canonicalQuery.isNotEmpty()) "$path?$canonicalQuery" else path
        val canonical = listOf(
            method, accept, contentType, body?.size?.toString() ?: "", ts.toString(),
            body?.let { md5Hex(it) } ?: "", signedPath
        ).joinToString("\n")
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(secretBytes, "HmacMD5"))
        return Base64.encodeToString(mac.doFinal(canonical.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private class Resp(val code: Int, val text: String, val headers: okhttp3.Headers)

    private suspend fun request(
        base: String, path: String, query: List<Pair<String, String>>, accept: String?,
        contentType: String = "", body: String? = null, auth: String = token, timeout: Long = 20L,
    ): Resp {
        val ts = System.currentTimeMillis()
        val bodyBytes = body?.toByteArray(Charsets.UTF_8)
        val method = if (body != null) "POST" else "GET"
        val sig = signature(method, path, query, accept ?: "", contentType, bodyBytes, ts)
        val build = clientBuild()
        val headers = linkedMapOf(
            "User-Agent" to UA,
            "X-Play-Mode" to "2",
            "X-Family-Mode" to "0",
            "X-Idle-Data" to "1",
            "X-Client-Info" to clientInfo(build),
            "X-Client-Status" to "0",
            "x-tr-signature" to "$ts|2|$sig",
            "X-Client-Build" to build,
        )
        if (accept != null) headers["Accept"] = accept
        if (contentType.isNotEmpty()) headers["Content-Type"] = contentType
        if (auth.isNotEmpty()) headers["Authorization"] = "Bearer $auth" else headers["X-Client-Token"] = clientToken()
        val qs = query.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        val url = "$base$path" + if (qs.isNotEmpty()) "?$qs" else ""
        val r = if (body != null) {
            app.post(url, headers = headers, requestBody = body.toRequestBody(contentType.ifEmpty { JSON_TYPE }.toMediaType()), timeout = timeout)
        } else {
            app.get(url, headers = headers, timeout = timeout)
        }
        return Resp(r.code, r.text, r.headers)
    }

    private suspend fun ensureToken(force: Boolean = false): String {
        if (!force && token.isNotEmpty() && System.currentTimeMillis() - tokenAt < 3_600_000L) return token
        val r = request(BASE, "/wefeed-mobile-bff/subject-api/bottom-tab", listOf("host" to "moviebox.ph"), JSON_TYPE, auth = "")
        val user = r.headers["X-User"] ?: r.headers["x-user"] ?: return ""
        val t = try { JSONObject(user).optString("token") } catch (_: Exception) { "" }
        if (t.isNotEmpty()) {
            token = t
            tokenAt = System.currentTimeMillis()
        }
        return t
    }

    private suspend fun authed(block: suspend () -> Resp): Resp? {
        try { ensureToken() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        var r = block()
        if (r.code == 401) {
            try { ensureToken(true) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            r = block()
        }
        return r
    }

    private fun data(r: Resp?): JSONObject? {
        if (r == null || r.code !in 200..299) return null
        val o = try { JSONObject(r.text) } catch (_: Exception) { return null }
        if (o.optInt("code", -1) != 0) return null
        return o.optJSONObject("data")
    }

    private fun JSONArray?.objs(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    // ------------------------------------------------------------------ search

    data class Subject(
        val id: String,
        val title: String,
        val type: Int,          // 1 movie, 2 series, 7 short tv
        val year: String,
        val cover: String?,
        val description: String?,
    )

    private fun subjectOf(o: JSONObject): Subject? {
        val id = o.optString("subjectId").takeIf { it.isNotBlank() && it != "null" } ?: return null
        val cover = o.optJSONObject("cover")?.optString("url")?.takeIf { it.startsWith("http") }
        return Subject(id, o.optString("title").trim(), o.optInt("subjectType"), o.optString("releaseDate").take(4), cover, o.optString("description"))
    }

    suspend fun search(keyword: String, page: Int = 1): List<Subject> {
        val body = JSONObject().put("page", page).put("perPage", 24).put("keyword", keyword).toString()
        val r = authed { request(BASE, "/wefeed-mobile-bff/subject-api/search/v2", emptyList(), JSON_TYPE, "application/json; charset=utf-8", body) }
        val d = data(r) ?: return emptyList()
        val out = ArrayList<Subject>()
        for (key in listOf("items", "subjectList", "list", "results")) {
            for (o in d.optJSONArray(key).objs()) {
                subjectOf(o)?.let { out.add(it) }
                // search/v2 groups results; each group can hold its own subject list
                for (inner in listOf("subjects", "subjectList", "items")) o.optJSONArray(inner).objs().mapNotNullTo(out) { subjectOf(it) }
            }
        }
        return out.distinctBy { it.id }
    }

    suspend fun subjectDetail(id: String): JSONObject? {
        val r = authed { request(BASE, "/wefeed-mobile-bff/subject-api/get", listOf("subjectId" to id), JSON_TYPE) }
        return data(r)
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("""\[[^]]*]|\([^)]*\)"""), " ")
        .replace(Regex("[^a-z0-9]+"), " ").trim()

    /** Subjects matching a TMDB title: the main one plus language/dub variants ("Title [Hindi]"). */
    suspend fun match(title: String, year: String, isMovie: Boolean): List<Subject> {
        if (title.isBlank()) return emptyList()
        val wantType = if (isMovie) 1 else 2
        val target = norm(title)
        val hits = search(title).filter { it.type == wantType && norm(it.title) == target }
        val y = year.toIntOrNull()
        val dated = hits.filter { s -> y == null || s.year.toIntOrNull()?.let { kotlin.math.abs(it - y) <= 1 } != false }
        return dated.take(5)
    }

    // ------------------------------------------------------------------ playback

    data class Stream(val url: String, val type: String, val quality: String, val headers: Map<String, String>, val streamId: String)

    private fun cookieValue(cookie: String, name: String): String =
        cookie.split(";").map { it.trim() }.firstOrNull { it.startsWith("$name=") }?.substringAfter("=")?.trim() ?: ""

    private fun authorizedManifest(cookie: String, id: String, se: Int, ep: Int): String? = try {
        val policy = cookieValue(cookie, "CloudFront-Policy").replace('-', '+').replace('_', '=').replace('~', '/')
        val json = JSONObject(String(Base64.decode(policy, Base64.DEFAULT), Charsets.UTF_8))
        val statements = json.optJSONArray("Statement")
        val resource = statements?.optJSONObject(0)?.optString("Resource").orEmpty()
        if (statements?.length() != 1 || !resource.endsWith("/*") || resource.count { it == '*' } != 1) null else {
            val dir = resource.dropLast(1)
            val m = Regex("""^https://$AUTH_HOST/dash/([A-Za-z0-9_]+)/$""").find(dir)
            if (m == null || !m.groupValues[1].startsWith("${id}_${se}_${ep}_")) null else "${dir}index_web.mpd"
        }
    } catch (_: Exception) {
        null
    }

    private fun quality(v: String): String = v.split(",").map { it.trim() }.filter { it.matches(Regex("\\d{2,4}")) }
        .distinct().take(6).joinToString(" / ") { "${it}p" }

    private fun streamOf(s: JSONObject, id: String, se: Int, ep: Int): Stream? {
        val url = s.optString("url").trim()
        val cookie = s.optString("signCookie").trim()
        val host = url.substringAfter("://", "").substringBefore("/").lowercase()
        val sid = s.optString("id")
        if (host == PLACEHOLDER_HOST) {
            if (cookie.isEmpty()) return null
            val manifest = authorizedManifest(cookie, id, se, ep) ?: return null
            return Stream(manifest, "dash", quality(s.optString("resolutions")), mapOf("Cookie" to cookie), sid)
        }
        if (!url.startsWith("https://")) return null
        val path = url.substringBefore("?").lowercase()
        val type = when (s.optString("format").uppercase()) {
            "DASH", "MPD" -> "dash"
            "HLS", "M3U8" -> "hls"
            "MP4" -> "mp4"
            else -> when {
                path.endsWith(".mpd") -> "dash"
                path.endsWith(".m3u8") -> "hls"
                path.endsWith(".mp4") -> "mp4"
                else -> return null
            }
        }
        return Stream(url, type, quality(s.optString("resolutions")), if (cookie.isNotEmpty()) mapOf("Cookie" to cookie) else emptyMap(), sid)
    }

    private fun streamsFrom(d: JSONObject?, id: String, se: Int, ep: Int): List<Stream> =
        d?.optJSONArray("streams").objs().mapIndexedNotNull { _, s -> streamOf(s, id, se, ep) }.take(8)

    suspend fun play(subjectId: String, se: Int, ep: Int): List<Stream> {
        val q = listOf("subjectId" to subjectId, "se" to se.toString(), "ep" to ep.toString(), "isVip" to "true")
        // play-info v2: the app races the api hosts and keeps the best answer
        for (host in PLAY_HOSTS) {
            try {
                val r = authed { request(host, "/wefeed-mobile-bff/subject-api/play-info/v2", q, JSON_TYPE, JSON_TYPE, timeout = 8L) }
                val streams = streamsFrom(data(r), subjectId, se, ep)
                if (streams.isNotEmpty()) return streams
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        return try {
            val r = authed {
                request(BASE, "/wefeed-mobile-bff/subject-api/play-info",
                    listOf("subjectId" to subjectId, "se" to se.toString(), "ep" to ep.toString()), null)
            }
            streamsFrom(data(r), subjectId, se, ep)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun captions(subjectId: String, streamId: String): List<VeduSources.Sub> {
        if (streamId.isBlank()) return emptyList()
        return try {
            val r = authed {
                request(BASE, "/wefeed-mobile-bff/subject-api/get-stream-captions",
                    listOf("subjectId" to subjectId, "streamId" to streamId), JSON_TYPE)
            }
            data(r)?.optJSONArray("extCaptions").objs().mapNotNull { c ->
                val u = c.optString("url").takeIf { it.startsWith("http") } ?: return@mapNotNull null
                VeduSources.Sub(u, c.optString("lanName").ifBlank { c.optString("lan").ifBlank { "Unknown" } })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ------------------------------------------------------------------ ShortTV

    data class ShortEpisode(val ep: Int, val title: String?, val cover: String?, val streams: List<Stream>)

    /** shorts/mini-list for a range of episodes (pagerMode=1, host=moviebox.ph). */
    suspend fun shortEpisodes(subjectId: String, start: Int, end: Int): Pair<JSONObject?, List<ShortEpisode>> {
        val r = authed {
            request(BASE, "/wefeed-mobile-bff/shorts/mini-list", listOf(
                "subjectId" to subjectId, "startPosition" to start.toString(), "endPosition" to end.toString(),
                "pagerMode" to "1", "host" to "moviebox.ph",
            ), JSON_TYPE)
        }
        val d = data(r) ?: return null to emptyList()
        val eps = d.optJSONArray("items").objs().mapNotNull { item ->
            val ep = item.optInt("ep").takeIf { it > 0 } ?: return@mapNotNull null
            val media = item.optJSONObject("video") ?: JSONObject()
            val addresses = media.optJSONArray("addressList").objs().ifEmpty { listOfNotNull(media.optJSONObject("videoAddress")) }
            val streams = addresses.mapNotNull { a ->
                val url = a.optString("url").trim().takeIf { it.startsWith("http") } ?: return@mapNotNull null
                val low = url.lowercase()
                val type = if (low.contains(".m3u8")) "hls" else if (low.contains(".mpd")) "dash" else "mp4"
                val h = a.optInt("height")
                Stream(url, type, if (h > 0) "${h}p" else a.optString("resolution").ifBlank { "Auto" }, emptyMap(), "")
            }
            ShortEpisode(ep, item.optString("title").takeIf { it.isNotBlank() }, item.optJSONObject("cover")?.optString("url"), streams)
        }
        return d.optJSONObject("info") to eps
    }

    /** Trending short dramas for the home page. */
    suspend fun trendingShorts(page: Int): List<Subject> = try {
        val r = authed {
            request(BASE, "/wefeed-mobile-bff/shorts/most-trending",
                listOf("page" to page.toString(), "perPage" to "24", "host" to "moviebox.ph"), JSON_TYPE)
        }
        val d = data(r)
        val list = ArrayList<Subject>()
        for (key in listOf("items", "subjectList", "list")) {
            d?.optJSONArray(key).objs().forEach { o ->
                (subjectOf(o) ?: o.optJSONObject("subject")?.let { subjectOf(it) })?.let { list.add(it) }
            }
        }
        list.distinctBy { it.id }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }
}
