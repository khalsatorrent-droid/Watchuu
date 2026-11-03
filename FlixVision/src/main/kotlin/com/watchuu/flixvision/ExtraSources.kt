package com.watchuu.flixvision

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLDecoder
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

/**
 * Current TMDB-keyed stream sources, written against how working players call them
 * (late-Oct 2026). Every source is independent and fails silently; all run in parallel.
 *
 *  - NetMirror : net79.cc/api/variants-tmdb/{movie|tv}/{id}?se&ep -> subjects (per audio language)
 *                -> /api/download-info?subjectId&se&ep -> qualities[{res,url,raw,codec}]  (MP4)
 *  - VixSrc    : vixsrc.to/api/{movie|tv}/{id}?lang=en -> {src} -> page window.masterPlaylist (HLS)
 *  - Vidrock   : vidrock.net/api/{movie|tv}/{AES-CBC(id[_s_e])} -> {server:{url}}
 *  - PrimeSrc  : primesrc.me/api/v1/s -> servers{name,key} -> /api/v1/l?key -> host embed
 *  - Rivestream: secretKey computed per title from the site's own key list
 */
object ExtraSources {
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    suspend fun all(
        tmdbId: Int,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        skip: Set<String> = emptySet(),
    ) {
        val names = listOf("netmirror", "vixsrc", "vidrock", "primesrc", "rivestream").filter { it !in skip }
        coroutineScope {
            names.map { n ->
                async {
                    safe {
                        when (n) {
                            "netmirror" -> netMirror(tmdbId, season, episode, callback)
                            "vixsrc" -> vixSrc(tmdbId, season, episode, callback)
                            "vidrock" -> vidrock(tmdbId, season, episode, callback)
                            "primesrc" -> primeSrc(tmdbId, season, episode, subtitleCallback, callback)
                            "rivestream" -> rivestream(tmdbId, season, episode, callback)
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun safe(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
        }
    }

    private fun h(referer: String, extra: Map<String, String> = emptyMap()) =
        mapOf("User-Agent" to UA, "Referer" to referer) + extra

    private fun qualityOf(res: Int?): Int = when {
        res == null || res <= 0 -> Qualities.Unknown.value
        res >= 2160 -> Qualities.P2160.value
        res >= 1080 -> Qualities.P1080.value
        res >= 720 -> Qualities.P720.value
        res >= 480 -> Qualities.P480.value
        else -> Qualities.P360.value
    }

    // ------------------------------------------------------------------ NetMirror
    suspend fun netMirror(tmdbId: Int, season: Int?, episode: Int?, callback: (ExtractorLink) -> Unit) {
        val base = "https://net79.cc"
        val type = if (season == null) "movie" else "tv"
        val se = season ?: 0
        val ep = episode ?: 0
        val headers = h("$base/")
        val variants = JSONObject(app.get("$base/api/variants-tmdb/$type/$tmdbId?se=$se&ep=$ep", headers = headers).text)
        if (!variants.optBoolean("ok")) return
        val subjects = LinkedHashMap<String, String>()
        variants.optString("defaultSubjectId").takeIf { it.isNotBlank() }?.let { subjects[it] = "Default" }
        val arr = variants.optJSONArray("variants") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("dubSubjectId").trim()
            if (id.isBlank()) continue
            val lang = o.optString("language").trim().ifBlank { "Default" }
            if (subjects[id] == null || subjects[id] == "Default") subjects[id] = lang
        }
        coroutineScope {
            subjects.entries.take(6).map { (subject, lang) ->
                async {
                    safe {
                        val info = JSONObject(app.get("$base/api/download-info?subjectId=$subject&se=$se&ep=$ep", headers = headers).text)
                        if (!info.optBoolean("ok")) return@safe
                        val qs = info.optJSONArray("qualities") ?: return@safe
                        for (i in 0 until qs.length()) {
                            val q = qs.optJSONObject(i) ?: continue
                            val codec = q.optString("codec")
                            if (codec.contains("hevc", true) || codec.contains("h265", true)) continue
                            val res = q.optInt("res")
                            val raw = q.optString("raw")
                            val proxied = q.optString("url").let {
                                when {
                                    it.startsWith("http") -> it
                                    it.startsWith("//") -> "https:$it"
                                    it.startsWith("/") -> base + it
                                    else -> ""
                                }
                            }
                            val label = "NetMirror $lang"
                            if (raw.startsWith("http")) callback(
                                newExtractorLink(label, label, raw, ExtractorLinkType.VIDEO) {
                                    referer = "https://moviebox.ph/"; quality = qualityOf(res)
                                    this.headers = h("https://moviebox.ph/")
                                }
                            )
                            if (proxied.isNotBlank()) callback(
                                newExtractorLink("$label (proxy)", "$label (proxy)", proxied, ExtractorLinkType.VIDEO) {
                                    referer = "$base/"; quality = qualityOf(res); this.headers = headers
                                }
                            )
                        }
                    }
                }
            }.awaitAll()
        }
    }

    // --------------------------------------------------------------------- VixSrc
    suspend fun vixSrc(tmdbId: Int, season: Int?, episode: Int?, callback: (ExtractorLink) -> Unit) {
        val api = if (season == null) "https://vixsrc.to/api/movie/$tmdbId?lang=en"
        else "https://vixsrc.to/api/tv/$tmdbId/$season/$episode?lang=en"
        val res = app.get(api, headers = h("https://vixsrc.to", mapOf("X-Requested-With" to "XMLHttpRequest")))
        val src = JSONObject(res.text).optString("src").trimStart('/').ifBlank { return }
        val embed = "https://vixsrc.to/$src"
        val page = app.get(embed, headers = h("https://vixsrc.to"), cookies = res.cookies)
        val html = page.text
        val playlist = html.substringAfter("window.masterPlaylist", "")
        fun grab(key: String) = Regex("""['"]?$key['"]?\s*:\s*['"]([^'"]*)['"]""").find(playlist)?.groupValues?.get(1)
        val token = grab("token") ?: return
        val expires = grab("expires") ?: return
        val videoId = Regex("""window\.video\s*=\s*\{.*?id:\s*['"]?([^,'"\s}]+)""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)
        val master = grab("url") ?: videoId?.let { "https://vixsrc.to/playlist/$it" } ?: return
        val q = mutableListOf("token=${URLEncoder.encode(token, "UTF-8")}", "expires=${URLEncoder.encode(expires, "UTF-8")}", "lang=en")
        if (html.contains("window.canPlayFHD = true")) q += "h=1"
        val stream = master + (if (master.contains("?")) "&" else "?") + q.joinToString("&")
        val cookie = (res.cookies + page.cookies).entries.joinToString("; ") { "${it.key}=${it.value}" }
        callback(
            newExtractorLink("VixSrc", "VixSrc", stream, ExtractorLinkType.M3U8) {
                referer = "https://vixsrc.to/"; quality = Qualities.P1080.value
                headers = h("https://vixsrc.to/", buildMap {
                    put("Origin", "https://vixsrc.to"); if (cookie.isNotBlank()) put("Cookie", cookie)
                })
            }
        )
    }

    // -------------------------------------------------------------------- Vidrock
    private fun vidrockEnc(value: String): String {
        val key = "x7k9mPqT2rWvY8zA5bC3nF6hJ2lK4mN9".toByteArray()
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key.copyOfRange(0, 16)))
        return Base64.encodeToString(c.doFinal(value.toByteArray()), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    suspend fun vidrock(tmdbId: Int, season: Int?, episode: Int?, callback: (ExtractorLink) -> Unit) {
        val plain = if (season == null) "$tmdbId" else "${tmdbId}_${season}_$episode"
        val api = "https://vidrock.net/api/${if (season == null) "movie" else "tv"}/${vidrockEnc(plain)}"
        val headers = h("https://vidrock.net/", mapOf("Origin" to "https://vidrock.net"))
        val json = JSONObject(app.get(api, headers = h("https://vidrock.net/")).text)
        for (server in json.keys()) {
            safe {
                var url = json.optJSONObject(server)?.optString("url").orEmpty()
                if (!url.startsWith("http")) return@safe
                if (server.contains("Atlas", true) && !url.contains(".m3u8")) {
                    val arr = JSONArray(app.get(url, headers = headers).text)
                    var best: JSONObject? = null
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        if (best == null || o.optInt("resolution") > best.optInt("resolution")) best = o
                    }
                    url = best?.optString("url")?.takeIf { it.startsWith("http") } ?: return@safe
                }
                val hls = url.contains(".m3u8") || !url.substringBefore("?").endsWith(".mp4")
                callback(
                    newExtractorLink("Vidrock $server", "Vidrock $server", url,
                        if (hls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                        referer = "https://vidrock.net/"; quality = Qualities.P1080.value; this.headers = headers
                    }
                )
            }
        }
    }

    // ------------------------------------------------------------------- PrimeSrc
    suspend fun primeSrc(
        tmdbId: Int, season: Int?, episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ) {
        val api = "https://primesrc.me/api/v1"
        val list = if (season == null) "$api/s?tmdb=$tmdbId&type=movie"
        else "$api/s?tmdb=$tmdbId&season=$season&episode=$episode&type=tv"
        val servers = JSONObject(app.get(list, headers = h("https://primesrc.me/")).text).optJSONArray("servers") ?: return
        val linkHeaders = h("https://primesrc.me/", mapOf(
            "Accept" to "application/json, text/plain, */*",
            "Origin" to "https://primesrc.me",
            "X-Requested-With" to "XMLHttpRequest",
        ))
        coroutineScope {
            (0 until servers.length()).mapNotNull { servers.optJSONObject(it)?.optString("key")?.takeIf { k -> k.isNotBlank() } }
                .distinct().map { key ->
                    async {
                        safe {
                            var link = JSONObject(app.get("$api/l?key=$key", headers = linkHeaders).text).optString("link")
                            if (!link.startsWith("http")) return@safe
                            if ((link.contains("mixdrop") || link.contains("mxdrop")) && link.contains("/f/")) link = link.replace("/f/", "/e/")
                            loadExtractor(link, "https://primesrc.me/", subtitleCallback, callback)
                        }
                    }
                }.awaitAll()
        }
    }

    // ----------------------------------------------------------------- Rivestream
    suspend fun rivestream(tmdbId: Int, season: Int?, episode: Int?, callback: (ExtractorLink) -> Unit) {
        val base = "https://rivestream.org"
        val headers = mapOf("User-Agent" to UA)
        val services = runCatching {
            val arr = JSONObject(app.get("$base/api/backendfetch?requestID=VideoProviderServices&secretKey=rive", headers = headers).text)
                .optJSONArray("data")
            (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optString(it)?.takeIf { s -> s.isNotBlank() } }
        }.getOrNull().orEmpty().ifEmpty { listOf("flowcast", "halo", "kage", "volt", "zenith") }

        val secret = runCatching {
            val doc = app.get(base, headers = headers).document
            val appJs = doc.select("script[src]").map { it.attr("src") }.firstOrNull { it.contains("_app") } ?: error("no _app")
            val js = app.get(if (appJs.startsWith("http")) appJs else base + appJs, headers = headers).text
            val keys = Regex("""let\s+c\s*=\s*(\[[^]]*])""").findAll(js).firstOrNull { it.groupValues[1].length > 2 }
                ?.groupValues?.get(1)?.let { a -> Regex("\"([^\"]+)\"").findAll(a).map { it.groupValues[1] }.toList() }
                .orEmpty()
            app.get("https://rivestream.supe2372.workers.dev/?input=$tmdbId&cList=${keys.joinToString(",")}").text.trim()
        }.getOrNull()?.takeIf { it.isNotBlank() && it.length < 200 }
            ?: if (season == null) "LTViC1c1MGQ0YzBl" else "LTM1ViC1OTM1YTk0" // TeaTV's built-in keys

        coroutineScope {
            services.map { svc ->
                async {
                    safe {
                        val url = if (season == null)
                            "$base/api/backendfetch?requestID=movieVideoProvider&id=$tmdbId&service=$svc&secretKey=$secret"
                        else "$base/api/backendfetch?requestID=tvVideoProvider&id=$tmdbId&season=$season&episode=$episode&service=$svc&secretKey=$secret"
                        val sources = JSONObject(app.get(url, headers = headers, timeout = 15).text)
                            .optJSONObject("data")?.optJSONArray("sources") ?: return@safe
                        for (i in 0 until sources.length()) {
                            val s = sources.optJSONObject(i) ?: continue
                            var u = s.optString("url")
                            val label = "Rivestream ${s.optString("source").ifBlank { svc }}"
                            var ref = ""
                            var origin = ""
                            if (u.contains("proxy?url=")) {
                                val dec = URLDecoder.decode(u, "UTF-8")
                                u = URLDecoder.decode(dec.substringAfter("proxy?url=").substringBefore("&headers="), "UTF-8")
                                runCatching {
                                    val hj = JSONObject(URLDecoder.decode(dec.substringAfter("&headers=", "{}"), "UTF-8"))
                                    ref = hj.optString("Referer"); origin = hj.optString("Origin")
                                }
                            }
                            if (!u.startsWith("http")) continue
                            val hls = u.contains(".m3u8") || !u.substringBefore("?").endsWith(".mp4")
                            callback(
                                newExtractorLink(label, label, u, if (hls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                                    referer = ref
                                    quality = Regex("""(\d{3,4})""").find(s.optString("quality"))?.groupValues?.get(1)?.toIntOrNull()
                                        ?.let { qualityOf(it) } ?: Qualities.P1080.value
                                    headers = buildMap {
                                        put("User-Agent", UA)
                                        if (ref.isNotBlank()) put("Referer", ref)
                                        if (origin.isNotBlank()) put("Origin", origin)
                                    }
                                }
                            )
                        }
                    }
                }
            }.awaitAll()
        }
    }
}
