package com.watchuu.vedu

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.watchuu.vedu.VeduSources.Result
import com.watchuu.vedu.VeduSources.Src
import com.watchuu.vedu.VeduSources.Sub
import java.io.ByteArrayOutputStream
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ports of the Vedu resolvers that key titles by their own site ids (the app gets those ids from its
 * backend, so each one here first finds the title by searching the site):
 *  - 9anime.js     (Anikoto -> MegaPlay, AES-CBC "enc" + HMAC token)
 *  - animepahe.js  (animepahe.ch -> Blogger / OK.ru / MegaPlay / Vkspeed / Vidmoly)
 *  - dramika.js    (dramika.com -> KissKH player -> MixDrop / Streamtape / Vidmoly)
 *  - netmirror.js  (net*.cc mobile site: entry config, support-gate verification, playlist.php)
 *  - reelshort.js  (reelshort.com book api, AES play_info + zlib)
 */
object VeduSites {
    private const val UA = VeduSources.UA
    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    // ------------------------------------------------------------------ helpers

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    fun norm(s: String) = s.lowercase().replace("&amp;", "&").replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun decodeEntities(v: String): String = Regex("&(#x[0-9a-fA-F]+|#\\d+|amp|quot|apos|lt|gt|#039);").replace(v) { m ->
        val n = m.groupValues[1]
        when {
            n.equals("amp", true) -> "&"
            n.equals("quot", true) -> "\""
            n.equals("apos", true) || n == "#039" -> "'"
            n.equals("lt", true) -> "<"
            n.equals("gt", true) -> ">"
            n.startsWith("#x", true) -> n.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
            else -> n.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
        }
    }

    private fun attrs(tag: String): Map<String, String> {
        val out = HashMap<String, String>()
        Regex("""([\w-]+)\s*=\s*(["'])([\s\S]*?)\2""").findAll(tag).forEach {
            out[it.groupValues[1].lowercase()] = decodeEntities(it.groupValues[3])
        }
        return out
    }

    private fun resolve(base: String, ref: String): String? = try { URL(URL(base), ref.trim()).toString() } catch (_: Exception) { null }

    private fun b64(s: String): ByteArray {
        val n = s.trim().replace('-', '+').replace('_', '/').replace(Regex("[^A-Za-z0-9+/]"), "")
        return Base64.decode(n.padEnd(4 * ((n.length + 3) / 4), '='), Base64.DEFAULT)
    }

    private fun b64UrlNoPad(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)

    private fun aesCbc(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(data)
    }

    private fun hmacSha256(key: ByteArray, msg: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(msg)
    }

    private fun isChallenge(body: String) = Regex("(?i)<title>\\s*Just a moment|Checking your browser|cf-chl-widget").containsMatchIn(body)

    private fun JSONArray?.objs(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private inline fun <T> safe(block: () -> T): T? = try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

    // =================================================================== MegaPlay

    private const val MEGAPLAY = "https://megaplay.buzz"
    private val MEGAPLAY_KEY = ByteArray(32).also { k -> "i?LMTAx0Q6,:}50U".toByteArray().copyInto(k) }
    private val MEGAPLAY_IV = "W0;27ToaUpl_P%'c".toByteArray()
    private const val MEGAPLAY_TOKEN_SECRET = "MpCdnT0k3n!9f2K#xQ7vL5mR8wN1pY4s"

    private fun megaplayDataId(html: String): String? =
        (Regex("""(?i)id=["']megaplay-player["'][^>]*data-id=["']?(\d+)""").find(html)
            ?: Regex("""(?i)data-id=["']?(\d+)[^>]*id=["']megaplay-player["']""").find(html)
            ?: Regex("""data-id=["']?(\d+)""").find(html))?.groupValues?.get(1)

    private fun signMegaplay(url: String): String {
        val m = Regex("(?i)/([a-f0-9]{32})/([a-f0-9]{32})/").find(url) ?: return url
        val payload = "${System.currentTimeMillis() / 1000 + 90}|${m.groupValues[1].lowercase()}/${m.groupValues[2].lowercase()}"
        val token = b64UrlNoPad(payload.toByteArray()) + "." + b64UrlNoPad(hmacSha256(MEGAPLAY_TOKEN_SECRET.toByteArray(), payload.toByteArray()))
        val base = url.replace(Regex("([?&])token=[^&]*&?"), "$1").trimEnd('?', '&')
        return base + (if (base.contains("?")) "&" else "?") + "token=$token"
    }

    /** /stream/getSources -> manifest url (+ tracks). [bcdn] is the AnimePahe variant (s=bcdn, no token). */
    private suspend fun megaplaySources(dataId: String, referer: String, bcdn: Boolean): Result {
        val url = "$MEGAPLAY/stream/getSources?id=$dataId" + if (bcdn) "&s=bcdn" else ""
        val r = app.get(url, headers = mapOf("Referer" to referer, "User-Agent" to DESKTOP_UA, "X-Requested-With" to "XMLHttpRequest",
            "Accept" to "application/json, text/javascript, */*; q=0.01"), timeout = 15L)
        val p = safe { JSONObject(r.text) } ?: return Result(emptyList())
        var file: String? = null
        val sources = p.opt("sources")
        when (sources) {
            is JSONArray -> file = sources.objs().firstNotNullOfOrNull { s -> (s.optString("file").ifBlank { s.optString("url") }).takeIf { it.startsWith("http") } }
            is JSONObject -> file = (sources.optString("file").ifBlank { sources.optString("url") }).takeIf { it.startsWith("http") }
        }
        if (file == null) file = p.optString("file").takeIf { it.startsWith("http") }
        if (file == null && p.optString("enc").isNotBlank()) {
            val text = safe { String(aesCbc(b64(p.optString("enc")), MEGAPLAY_KEY, MEGAPLAY_IV), Charsets.UTF_8).trim() }
            if (text != null) {
                file = safe {
                    val j = JSONObject(text)
                    j.optString("file").ifBlank { j.optString("url") }.takeIf { it.startsWith("http") }
                        ?: j.optJSONArray("sources").objs().firstNotNullOfOrNull { s -> s.optString("file").takeIf { it.startsWith("http") } }
                } ?: Regex(""""file"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
                    ?: Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(text)?.value
                if (file != null && !bcdn) file = signMegaplay(file)
            }
        }
        if (file == null) return Result(emptyList())
        val subs = p.optJSONArray("tracks").objs().mapNotNull { t ->
            val kind = t.optString("kind").lowercase()
            if (kind.isNotEmpty() && kind != "captions" && kind != "subtitles") return@mapNotNull null
            val u = t.optString("file").ifBlank { t.optString("url") }.takeIf { it.startsWith("http") } ?: return@mapNotNull null
            Sub(u, t.optString("label").ifBlank { "Subtitle" })
        }
        return Result(listOf(Src(file, "hls", "", mapOf("Referer" to "$MEGAPLAY/", "User-Agent" to DESKTOP_UA))), subs)
    }

    // ===================================================================== 9Anime

    private const val ANIKOTO = "https://anikototv.to"

    private suspend fun anikoto(url: String, json: Boolean): Any? {
        val h = linkedMapOf("User-Agent" to DESKTOP_UA, "Referer" to "$ANIKOTO/",
            "Accept" to if (json) "application/json, text/javascript, */*; q=0.01" else "text/html,*/*")
        if (json) h["X-Requested-With"] = "XMLHttpRequest"
        val r = app.get(url, headers = h, timeout = 15L)
        if (r.code !in 200..299 || isChallenge(r.text)) return null
        if (!json) return r.text
        val p = safe { JSONObject(r.text) } ?: return null
        val res = p.opt("result")
        return when {
            res is String -> res
            res is JSONObject && res.has("html") -> res.optString("html")
            res is JSONObject -> p
            p.has("html") -> p.optString("html")
            else -> p
        }
    }

    /** Returns sub and dub links for one episode. */
    suspend fun nineAnime(title: String, episode: Int): Result {
        val search = anikoto("$ANIKOTO/ajax/anime/search?keyword=${enc(title)}", true) as? String ?: return Result(emptyList())
        // prefer a result whose visible name matches; else the first /watch/ link
        val target = norm(title)
        val links = Regex("""<a\b[^>]*href=["'][^"']*/watch/([A-Za-z0-9][A-Za-z0-9-]*)[^>]*>([\s\S]*?)</a>""").findAll(search).toList()
        val slug = links.firstOrNull { norm(decodeEntities(it.groupValues[2].replace(Regex("<[^>]+>"), " "))).contains(target) }?.groupValues?.get(1)
            ?: Regex("""/watch/([A-Za-z0-9][A-Za-z0-9-]*)""").find(search)?.groupValues?.get(1)
            ?: return Result(emptyList())
        val watch = anikoto("$ANIKOTO/watch/${enc(slug)}/ep-1", false) as? String ?: return Result(emptyList())
        val animeId = Regex("""data-anime-id=["'](\d+)""").find(watch)?.groupValues?.get(1) ?: return Result(emptyList())
        val epHtml = anikoto("$ANIKOTO/ajax/episode/list/$animeId", true) as? String ?: return Result(emptyList())
        var first: String? = null
        var token: String? = null
        for (m in Regex("""<[a-zA-Z][^>]*\bdata-ids\b[^>]*>""").findAll(epHtml)) {
            val a = attrs(m.value)
            val ids = a["data-ids"] ?: continue
            if (first == null) first = ids
            if (a["data-num"]?.toIntOrNull() == episode) { token = ids; break }
        }
        if (token == null && episode == 1) token = first
        if (token == null) return Result(emptyList())
        val serverHtml = anikoto("$ANIKOTO/ajax/server/list?servers=${enc(token)}", true) as? String ?: return Result(emptyList())
        val servers = Regex("""<[a-zA-Z][^>]*\bdata-link-id\b[^>]*>""").findAll(serverHtml).mapNotNull { m ->
            val a = attrs(m.value)
            val link = a["data-link-id"] ?: return@mapNotNull null
            Triple((a["data-type"] ?: "sub").lowercase(), link, a["data-sv-id"] ?: a["data-server-id"] ?: "")
        }.toList()
        val out = ArrayList<Src>()
        val subs = ArrayList<Sub>()
        for (type in listOf("sub", "dub")) {
            for ((_, linkId, sv) in servers.filter { it.first == type }.take(4)) {
                safe {
                    val payload = anikoto("$ANIKOTO/ajax/server?get=${enc(linkId)}", true)
                    val res = (payload as? JSONObject)?.let { it.optJSONObject("result") ?: it }
                    val embed = (res?.optString("url") ?: (payload as? String))?.takeIf { it.startsWith("http") } ?: return@safe
                    if (!embed.contains("megaplay", true)) return@safe
                    val page = app.get(embed, headers = mapOf("Referer" to "$MEGAPLAY/", "User-Agent" to DESKTOP_UA, "X-Requested-With" to "XMLHttpRequest"), timeout = 15L).text
                    val dataId = megaplayDataId(page) ?: return@safe
                    val r = megaplaySources(dataId, "$MEGAPLAY/", false)
                    r.sources.forEach { out.add(it.copy(label = "${type.uppercase()}${if (sv.isNotBlank()) " $sv" else ""}")) }
                    subs += r.subs
                }
            }
        }
        return Result(out.distinctBy { it.url }, subs.distinctBy { it.url })
    }

    // ================================================================== AnimePahe

    private const val PAHE = "https://animepahe.ch"

    private suspend fun paheGet(url: String, referer: String?, extra: Map<String, String> = emptyMap()): String? {
        val h = LinkedHashMap<String, String>()
        if (referer != null) h["Referer"] = referer
        h["User-Agent"] = UA
        h.putAll(extra)
        val r = app.get(url, headers = h, timeout = 15L)
        return if (r.code in 200..299 && !isChallenge(r.text)) r.text else null
    }

    private suspend fun paheSeries(title: String): String? {
        val html = paheGet("$PAHE/?s=${enc(title)}", "$PAHE/") ?: return null
        val target = norm(title)
        val cands = Regex("""<a\b([^>]*href=["']($PAHE)?/series/[^"'/]+/["'][^>]*)>([\s\S]*?)</a>""").findAll(html).mapNotNull { m ->
            val a = attrs(m.groupValues[1])
            val href = resolve("$PAHE/", a["href"] ?: return@mapNotNull null) ?: return@mapNotNull null
            val text = decodeEntities((a["title"] ?: "") + " " + m.groupValues[3].replace(Regex("<[^>]+>"), " "))
            href to norm(text)
        }.toList()
        return (cands.firstOrNull { it.second == target } ?: cands.firstOrNull { it.second.startsWith(target) }
            ?: cands.firstOrNull { it.second.contains(target) })?.first
    }

    suspend fun animePahe(title: String, episode: Int): Result {
        val series = paheSeries(title) ?: return Result(emptyList())
        val seriesHtml = paheGet(series, "$PAHE/") ?: return Result(emptyList())
        val dir = Regex("""(?i)<div\b[^>]*class\s*=\s*(["'])[^"']*\beplister\b[^"']*\1[^>]*>[\s\S]*?<ul\b[^>]*>([\s\S]*?)</ul>""").find(seriesHtml)?.groupValues?.get(2)
            ?: return Result(emptyList())
        val episodeUrls = Regex("""(?i)<a\b([^>]*)>([\s\S]*?)</a>""").findAll(dir).mapNotNull { m ->
            val n = Regex("""(?i)class\s*=\s*(["'])epl-num\1[^>]*>\s*(\d+)\s*<""").find(m.groupValues[2])?.groupValues?.get(2)?.toIntOrNull()
            if (n != episode) null else attrs(m.groupValues[1])["href"]?.let { resolve("$PAHE/", it) }
        }.toList()
        val out = ArrayList<Src>()
        for (epUrl in episodeUrls.distinct().take(2)) {
            val kind = if (epUrl.contains("dubbed")) "Dub" else "Sub"
            val html = paheGet(epUrl, series) ?: continue
            for (p in pahePlayers(html)) {
                safe {
                    val list = paheResolve(p.first, p.second, p.third, epUrl)
                    list.forEach { out.add(it.copy(label = "$kind ${it.label}".trim())) }
                }
            }
        }
        return Result(out.distinctBy { it.url })
    }

    /** (server, url, token) per supported iframe on the episode page. */
    private fun pahePlayers(html: String): List<Triple<String, String, String>> {
        val fragments = ArrayList<String>()
        Regex("""(?i)<div\b[^>]*id\s*=\s*(["'])pembed\1[^>]*>([\s\S]*?)</div>""").find(html)?.let { fragments.add(it.groupValues[2]) }
        for (m in Regex("""(?i)<span\b[^>]*>""").findAll(html)) {
            val a = attrs(m.value)
            if (!Regex("(?:^|\\s)gov-the-embed(?:\\s|$)").containsMatchIn(a["class"] ?: "")) continue
            val e = Regex("""(?i)putMi\(this,\s*'([a-z0-9+/=]+)'\)""").find(a["onclick"] ?: "")?.groupValues?.get(1) ?: continue
            safe { fragments.add(String(Base64.decode(e, Base64.DEFAULT), Charsets.UTF_8)) }
        }
        val players = LinkedHashMap<String, Triple<String, String, String>>()
        for (f in fragments) for (m in Regex("""(?i)<iframe\b[^>]*>""").findAll(f)) {
            val src = attrs(m.value)["src"] ?: continue
            val u = resolve("$PAHE/", src) ?: continue
            if (!u.startsWith("https://")) continue
            val host = u.substringAfter("://").substringBefore("/").lowercase()
            val path = "/" + u.substringAfter("://").substringAfter("/", "").substringBefore("?")
            var token = ""
            val server = when {
                host == "www.blogger.com" && path == "/video.g" -> {
                    token = Regex("[?&]token=([A-Za-z0-9_-]+)").find(u)?.groupValues?.get(1) ?: ""
                    if (token.isNotEmpty()) "blogger" else ""
                }
                host == "ok.ru" && Regex("^/videoembed/\\d+/?$").matches(path) -> "okru"
                host == "megaplay.buzz" && Regex("^/stream/(?:(?:mal|ani)/\\d+/\\d+|s-2/\\d+)/(?:sub|dub)/?$").matches(path) -> "megaplay"
                host == "vkspeed.com" && Regex("^/embed-[a-z0-9]{12}\\.html$").matches(path) -> "vkspeed"
                host == "vidmoly.biz" && Regex("^/embed-[a-z0-9]{12}\\.html$").matches(path) -> "vidmoly"
                else -> "" // FlixCloud needs per-request playlist decoding, which Cloudstream's player can't do
            }
            if (server.isEmpty()) continue
            val key = "$server:${token.ifEmpty { u }}"
            if (!players.containsKey(key)) players[key] = Triple(server, u, token)
        }
        return players.values.take(8)
    }

    private suspend fun paheResolve(server: String, url: String, token: String, episodeUrl: String): List<Src> {
        val base = mapOf("Referer" to url, "User-Agent" to UA)
        return when (server) {
            "blogger" -> {
                val payload = JSONArray().put(JSONArray().put(JSONArray().put("WcwnYd").put(JSONArray().put(token).put(JSONObject.NULL).put(false).toString()).put(JSONObject.NULL).put("generic"))).toString()
                val r = app.post(
                    "https://www.blogger.com/_/BloggerVideoPlayerUi/data/batchexecute?rpcids=WcwnYd",
                    headers = mapOf("Referer" to url, "User-Agent" to UA, "Content-Type" to "application/x-www-form-urlencoded"),
                    requestBody = "f.req=${enc(payload)}".toRequestBody("application/x-www-form-urlencoded".toMediaType()), timeout = 15L
                )
                bloggerSources(r.text).map { (u, h) -> Src(u, "mp4", "${h}p", base, "Blogger") }
            }
            "okru" -> {
                val html = paheGet(url, episodeUrl) ?: return emptyList()
                okruSources(html, url).map { (u, h, t) -> Src(u, t, if (h > 0) "${h}p" else "", base, "OK.ru") }
            }
            "megaplay" -> {
                val html = paheGet(url, episodeUrl) ?: return emptyList()
                val id = Regex("""<div\b[^>]*>""").findAll(html).map { attrs(it.value) }.firstOrNull { it["id"] == "megaplay-player" }?.get("data-id")
                    ?: return emptyList()
                megaplaySources(id, url, true).sources.map { it.copy(label = "MegaPlay") }
            }
            "vkspeed" -> {
                val html = paheGet(url, null) ?: return emptyList()
                vkspeedSources(html).map { (u, h) -> Src(u, "mp4", if (h > 0) "${h}p" else "", base, "Vkspeed") }
            }
            "vidmoly" -> {
                val html = paheGet(url, episodeUrl) ?: return emptyList()
                listOfNotNull(jwFile(html, url)?.let { Src(it, "hls", "", base, "Vidmoly") })
            }
            else -> emptyList()
        }
    }

    private fun bloggerSources(body: String): List<Pair<String, Int>> {
        val heights = mapOf(18 to 360, 22 to 720, 37 to 1080)
        for (line in body.replace(Regex("^\\)]}'\\s*"), "").split("\n")) {
            if (!line.trim().startsWith("[")) continue
            val frame = safe { JSONArray(line) } ?: continue
            for (i in 0 until frame.length()) {
                val e = frame.optJSONArray(i) ?: continue
                if (e.optString(0) != "wrb.fr" || e.optString(1) != "WcwnYd") continue
                val resp = safe { JSONArray(e.optString(2)) } ?: return emptyList()
                val list = resp.optJSONArray(2) ?: return emptyList()
                val out = ArrayList<Pair<String, Int>>()
                for (j in 0 until list.length()) {
                    val entry = list.optJSONArray(j) ?: continue
                    val u = entry.optString(0)
                    val fmt = entry.optJSONArray(1)?.optInt(0) ?: continue
                    val h = heights[fmt] ?: continue
                    if (u.startsWith("https://") && u.contains(".googlevideo.com/videoplayback")) out.add(u to h)
                }
                return out.distinctBy { it.first }
            }
        }
        return emptyList()
    }

    private fun okruSources(html: String, playerUrl: String): List<Triple<String, Int, String>> {
        val heights = mapOf("mobile" to 144, "lowest" to 240, "low" to 360, "sd" to 480, "hd" to 720, "full" to 1080)
        for (m in Regex("""(?i)\bdata-options\s*=\s*(["'])([\s\S]*?)\1""").findAll(html)) {
            val options = safe { JSONObject(decodeEntities(m.groupValues[2])) } ?: continue
            val fv = options.optJSONObject("flashvars") ?: continue
            val meta = fv.opt("metadata").let { if (it is String) safe { JSONObject(it) } else it as? JSONObject } ?: continue
            val out = ArrayList<Triple<String, Int, String>>()
            meta.optString("hlsManifestUrl").takeIf { it.startsWith("https://") }?.let { out.add(Triple(it, 0, "hls")) }
            for (v in meta.optJSONArray("videos").objs()) {
                if (v.optBoolean("disallowed")) continue
                val h = heights[v.optString("name")] ?: continue
                val u = v.optString("url").takeIf { it.startsWith("https://") } ?: continue
                out.add(Triple(u, h, "mp4"))
            }
            if (out.isNotEmpty()) return out
        }
        return emptyList()
    }

    private fun jsLiteral(v: String): String = Regex("""\\(u[0-9a-fA-F]{4}|x[0-9a-fA-F]{2}|[\\'"/nrt])""").replace(v) { m ->
        val e = m.groupValues[1]
        when {
            e[0] == 'u' || e[0] == 'x' -> e.substring(1).toInt(16).toChar().toString()
            e == "n" -> "\n"
            e == "r" -> "\r"
            e == "t" -> "\t"
            else -> e
        }
    }

    private fun vkspeedSources(html: String): List<Pair<String, Int>> {
        val packed = Regex("""eval\(function\(p,a,c,k,e,d\)\{[\s\S]{0,1000}?\}\('((?:\\.|[^'\\])*)',36,(\d{1,4}),'((?:\\.|[^'\\])*)'\.split\('\|'\)\)\)""")
        for (m in packed.findAll(html)) {
            val dict = jsLiteral(m.groupValues[3]).split("|")
            val decoded = Regex("""\b[0-9a-z]+\b""").replace(jsLiteral(m.groupValues[1])) { t ->
                dict.getOrNull(t.value.toIntOrNull(36) ?: -1)?.takeIf { it.isNotEmpty() } ?: t.value
            }
            val list = Regex("""jwplayer\(["']vplayer["']\)\.setup\(\{\s*sources:\[([^\]]{1,16000})]""").find(decoded)?.groupValues?.get(1) ?: continue
            val out = Regex("""\{([^{}]+)}""").findAll(list).mapNotNull { e ->
                val file = Regex("""\bfile:\s*"([^"\\]+)"""").find(e.groupValues[1])?.groupValues?.get(1) ?: return@mapNotNull null
                val label = Regex("""\blabel:\s*"(\d{3,4})p"""").find(e.groupValues[1])?.groupValues?.get(1)?.toIntOrNull() ?: 0
                if (file.startsWith("https://")) file to label else null
            }.distinctBy { it.first }.toList()
            if (out.isNotEmpty()) return out
        }
        return emptyList()
    }

    /** First jwplayer-style `sources: [{file: "..."}]` entry. */
    private fun jwFile(html: String, base: String): String? {
        val m = Regex("""\bsources\s*:\s*\[\s*\{\s*(?:['"]file['"]|file)\s*:\s*(["'])((?:\\.|(?!\1)[^\\])*)\1""").find(html) ?: return null
        return resolve(base, decodeEntities(jsLiteral(m.groupValues[2])))
    }

    // ===================================================================== Dramika

    private const val DRAMIKA = "https://dramika.com"

    private suspend fun dramikaGet(url: String, referer: String): Pair<String, String>? {
        val r = app.get(url, headers = mapOf("Referer" to referer, "User-Agent" to UA), timeout = 15L)
        return if (r.code in 200..299 && !isChallenge(r.text)) r.text to r.url.ifBlank { url } else null
    }

    private suspend fun dramikaTitle(title: String, isMovie: Boolean): String? {
        val (html, _) = dramikaGet("$DRAMIKA/?s=${enc(title)}", "$DRAMIKA/") ?: return null
        val target = norm(title)
        val kinds = if (isMovie) "movie" else "(?:drama|tvshow)"
        val cands = Regex("""<a\b([^>]*href=["'](?:$DRAMIKA)?/$kinds/[^"'/]+/["'][^>]*)>([\s\S]*?)</a>""").findAll(html).mapNotNull { m ->
            val a = attrs(m.groupValues[1])
            val href = resolve("$DRAMIKA/", a["href"] ?: return@mapNotNull null) ?: return@mapNotNull null
            href to norm(decodeEntities((a["title"] ?: "") + " " + m.groupValues[2].replace(Regex("<[^>]+>"), " ")))
        }.toList()
        return (cands.firstOrNull { it.second == target } ?: cands.firstOrNull { it.second.startsWith(target) }
            ?: cands.firstOrNull { it.second.contains(target) })?.first
    }

    suspend fun dramika(title: String, isMovie: Boolean, episode: Int, extractor: suspend (String, String) -> Unit): Result {
        val page = dramikaTitle(title, isMovie) ?: return Result(emptyList())
        var (html, playUrl) = dramikaGet(page, "$DRAMIKA/") ?: return Result(emptyList())
        if (!isMovie) {
            val ep = Regex("""<a\b[^>]*>""").findAll(html).mapNotNull { attrs(it.value)["href"]?.let { h -> resolve(page, h) } }
                .firstOrNull { Regex("""^$DRAMIKA/[^/]+-episode-(\d+)/$""").find(it)?.groupValues?.get(1)?.toIntOrNull() == episode }
                ?: return Result(emptyList())
            val p = dramikaGet(ep, page) ?: return Result(emptyList())
            html = p.first; playUrl = p.second
        }
        val frame = Regex("""(?i)<iframe\b[^>]*>""").findAll(html).map { attrs(it.value) }.firstOrNull { it["id"] == "b" } ?: return Result(emptyList())
        val playerUrl = resolve(playUrl, frame["data-src"] ?: frame["data-litespeed-src"] ?: frame["src"] ?: return Result(emptyList())) ?: return Result(emptyList())
        val (player, _) = dramikaGet(playerUrl, playUrl) ?: return Result(emptyList())
        val servers = LinkedHashMap<String, String>()
        for (m in Regex("""<[^>]+\bdata-video\s*=[^>]+>""").findAll(player)) {
            val a = attrs(m.value)
            val s = a["data-server"]?.lowercase() ?: continue
            if (s !in setOf("mixdrop", "streamtape", "vidmoly") || servers.containsKey(s)) continue
            servers[s] = resolve(playerUrl, a["data-video"] ?: continue) ?: continue
        }
        val out = ArrayList<Src>()
        for ((server, url) in servers) {
            val got = safe {
                val (body, finalUrl) = dramikaGet(url, playerUrl) ?: return@safe null
                when (server) {
                    "mixdrop" -> mixdropSource(body, finalUrl)?.let { Src(it, "mp4", "", mapOf("Referer" to finalUrl, "User-Agent" to UA), "MixDrop") }
                    "streamtape" -> streamtapeSource(body, finalUrl)?.let { Src(it, "mp4", "", mapOf("Referer" to finalUrl, "User-Agent" to UA), "Streamtape") }
                    else -> jwFile(body, finalUrl)?.let { Src(it, "hls", "", mapOf("Referer" to finalUrl, "User-Agent" to UA), "Vidmoly") }
                }
            }
            if (got != null) out.add(got) else safe { extractor(url, playerUrl) } // Cloudstream's own extractor as a fallback
        }
        return Result(out)
    }

    private fun unpack(html: String): String {
        val m = Regex("""\}\s*\(\s*'((?:\\.|[^'\\])*)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'((?:\\.|[^'\\])*)'\.split\('\|'\)""").find(html) ?: return html
        val radix = m.groupValues[2].toInt()
        val count = m.groupValues[3].toInt()
        if (radix !in 2..36) return html
        val dict = jsLiteral(m.groupValues[4]).split("|")
        return Regex("""\b\w+\b""").replace(jsLiteral(m.groupValues[1])) { w ->
            val i = w.value.toIntOrNull(radix)
            if (i != null && i.toString(radix) == w.value && i < count && dict.getOrNull(i)?.isNotEmpty() == true) dict[i] else w.value
        }
    }

    private fun mixdropSource(html: String, base: String): String? {
        val m = Regex("""\bMDCore\.wurl\s*=\s*(["'])((?:\\.|(?!\1)[^\\])*)\1""").find(unpack(html)) ?: return null
        return resolve(base, jsLiteral(m.groupValues[2]))?.replace(Regex("^http:"), "https:")
    }

    private fun streamtapeSource(html: String, base: String): String? {
        val id = Regex("""\$\(['"]#(botlink|robotlink|ideoolink)['"]\)\.text\(\)\s*\+\s*['"]&stream=1['"]""").find(html)?.groupValues?.get(1) ?: return null
        val expr = Regex("""document\.getElementById\(['"]$id['"]\)\.innerHTML\s*=\s*([^;]+);""").findAll(html).lastOrNull()?.groupValues?.get(1) ?: return null
        val term = Regex("""\s*\(?\s*(['"])((?:\\.|(?!\1)[^\\])*)\1\s*\)?((?:\s*\.substring\(\d+\))*)\s*(\+|$)""")
        var pos = 0
        val value = StringBuilder()
        while (pos < expr.length) {
            val m = term.find(expr, pos)
            if (m == null || m.range.first != pos || m.range.last + 1 <= pos) return null
            var part = jsLiteral(m.groupValues[2])
            Regex("""substring\((\d+)\)""").findAll(m.groupValues[3]).forEach { part = part.substring(minOf(it.groupValues[1].toInt(), part.length)) }
            value.append(part)
            pos = m.range.last + 1
        }
        val u = resolve(base, value.toString()) ?: return null
        if (!u.contains("streamtape.com/get_video")) return null
        return if (u.contains("stream=")) u else "$u&stream=1"
    }

    // =================================================================== NetMirror

    private val netConfigUrls = listOf(
        "mobiledetects.com", "mobiledetect.app", "mobidetect.art", "mobidetect.cc", "mobidetect.click", "mobidetect.ink",
        "mobidetect.live", "mobidetect.pro", "mobidetect.shop", "mobidetect.site", "mobidetect.space", "mobidetect.store",
        "mobidetect.vip", "mobidetect.wiki", "mobidetect.xyz", "mobidetects.art", "mobidetects.cc", "mobidetects.info",
        "mobidetects.ink", "mobidetects.live", "mobidetects.pro", "mobidetects.store", "mobidetects.top", "mobidetects.xyz",
    ).map { "https://$it/check.php" }
    private const val NET_UA = "Mozilla/5.0 (Linux; Android 15; SM-S9060 Build/AP3A.240905.015.A2; wv) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/131.0.6778.260 Mobile Safari/537.36 /OS.Gatu v3.1"

    @Volatile private var netEntry: String? = null
    private val netCookies = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()
    @Volatile private var netTimestamp: String? = null
    private val netLock = kotlinx.coroutines.sync.Mutex()

    private fun netOrigin() = netEntry?.let { VeduSources.originOf(it) } ?: ""

    private fun netCookieHeader(): String = netCookies.entries.filter { it.value.second > System.currentTimeMillis() }
        .joinToString("; ") { "${it.key}=${it.value.first}" }

    private fun netReadCookies(headers: okhttp3.Headers) {
        for (line in headers.values("Set-Cookie")) {
            val parts = line.split(";").map { it.trim() }
            val pair = Regex("^(addhash|t_hash_t)=([^\\s;]*)$").find(parts.firstOrNull() ?: continue) ?: continue
            val maxAge = parts.firstOrNull { it.startsWith("max-age=", true) }?.substringAfter("=")?.toLongOrNull()
            val expires = when {
                pair.groupValues[2].isEmpty() -> 0L
                maxAge != null -> System.currentTimeMillis() + maxAge * 1000
                else -> System.currentTimeMillis() + 600_000L
            }
            netCookies[pair.groupValues[1]] = pair.groupValues[2] to expires
        }
    }

    private suspend fun netRequest(url: String, body: String? = null, withCookies: Boolean = true,
                                   extra: Map<String, String> = emptyMap(), follow: Boolean = true): Pair<Int, String> {
        val h = linkedMapOf("User-Agent" to NET_UA, "Accept-Encoding" to "identity")
        netEntry?.let { h["Referer"] = it }
        if (withCookies && VeduSources.originOf(url) == netOrigin()) netCookieHeader().takeIf { it.isNotEmpty() }?.let { h["Cookie"] = it }
        h.putAll(extra)
        val r = if (body != null) {
            app.post(url, headers = h, requestBody = body.toRequestBody("application/x-www-form-urlencoded".toMediaType()), timeout = 15L, allowRedirects = follow)
        } else {
            app.get(url, headers = h, timeout = 15L, allowRedirects = follow)
        }
        if (withCookies && VeduSources.originOf(url) == netOrigin()) netReadCookies(r.headers)
        return r.code to r.text
    }

    private fun bodyAttrs(page: String) = attrs(Regex("(?i)<body\\b[^>]*>").find(page)?.value ?: "")

    private suspend fun netEntryAddress(): String? {
        for (u in netConfigUrls) {
            val e = safe {
                val (c, b) = netRequest(u, withCookies = false, extra = mapOf("Cache-Control" to "no-cache", "Pragma" to "no-cache", "Expires" to "0"))
                if (c !in 200..299) null else String(Base64.decode(JSONObject(b).optString("token_hash"), Base64.DEFAULT)).trim()
            }
            if (e != null && Regex("""^https://net[0-9]+\.cc/mobile/home\?app=1$""").matches(e)) return e
        }
        return null
    }

    /** Opens the mobile site and passes its support-gate verification; returns the page timestamp. */
    private suspend fun netSession(): String? = netLock.withLockSafe {
        if (netTimestamp != null && (netCookies["t_hash_t"]?.second ?: 0L) > System.currentTimeMillis()) return@withLockSafe netTimestamp
        if (netEntry == null) netEntry = netEntryAddress() ?: return@withLockSafe null
        val entry = netEntry!!
        val host = entry.substringAfter("://").substringBefore("/")
        var (_, page) = netRequest(entry, extra = mapOf("Cache-Control" to "no-cache, no-store", "Pragma" to "no-cache"))
        if (isChallenge(page)) return@withLockSafe null
        var a = bodyAttrs(page)
        if (a["data-addhash"] != null && page.contains("open-support")) {
            netCookies.remove("t_hash_t")
            val sub = Regex("""var\s+Vsite2\s*=\s*['"](userver)['"]""").find(page)?.groupValues?.get(1) ?: return@withLockSafe null
            val param = Regex("""var\s+Qury\s*=\s*['"]([a-zA-Z0-9_]{1,32})['"]""").find(page)?.groupValues?.get(1) ?: return@withLockSafe null
            val hash = a["data-addhash"]!!
            safe { netRequest("https://$sub.$host/?$param=${enc(hash)}&a=y&t=${Math.random()}", withCookies = false, follow = false) }
            var ok = false
            for (i in 0 until 15) {
                delay(2000L)
                val res = safe { JSONObject(netRequest("https://$host/mobile/verify2.php", body = "verify=${enc(hash)}").second) }
                if (res?.optString("statusup") == "All Done") { ok = true; break }
            }
            if (!ok) return@withLockSafe null
            page = netRequest(entry).second
            a = bodyAttrs(page)
        }
        val time = a["data-time"]?.takeIf { Regex("^[0-9]{9,13}$").matches(it) } ?: return@withLockSafe null
        netTimestamp = time
        time
    }

    private suspend fun <T> kotlinx.coroutines.sync.Mutex.withLockSafe(block: suspend () -> T): T {
        lock()
        try { return block() } finally { unlock() }
    }

    private fun netPrefix(platform: String) = if (platform == "nf") "/mobile/" else "/mobile/$platform/"

    /** Searches Netflix, Prime and Hotstar catalogues on NetMirror and resolves the matching title. */
    suspend fun netMirror(title: String, isMovie: Boolean, season: Int, episode: Int): Result {
        val ts = netSession() ?: return Result(emptyList())
        val origin = netOrigin()
        val target = norm(title)
        val out = ArrayList<Src>()
        for (platform in listOf("nf", "pv", "hs")) {
            safe {
                val prefix = netPrefix(platform)
                val (_, sb) = netRequest("$origin${prefix}search.php?s=${enc(title)}&t=$ts")
                val hits = JSONObject(sb).optJSONArray("searchResult").objs()
                val id = hits.firstOrNull { norm(it.optString("t")) == target }?.optString("id")?.takeIf { it.isNotBlank() } ?: return@safe
                val detail = JSONObject(netRequest("$origin${prefix}post.php?id=${enc(id)}&t=$ts").second)
                if (detail.optString("status") != "y") return@safe
                val dType = detail.optString("type")
                if (isMovie != (dType == "m")) return@safe
                var videoId = id
                var lang = detail.optString("d_lang")
                if (!isMovie) {
                    val seasonObj = detail.optJSONArray("season").objs().firstOrNull { it.optString("s") == season.toString() } ?: return@safe
                    var pageObj: JSONObject = detail
                    var found: String? = null
                    for (attempt in 0 until 20) {
                        found = pageObj.optJSONArray("episodes").objs()
                            .firstOrNull { it.optString("s") == "S$season" && it.optString("ep") == "E$episode" }?.optString("id")
                        if (found != null) break
                        var q = "s=${enc(seasonObj.optString("id"))}&series=${enc(id)}&t=$ts"
                        if (attempt > 0 || pageObj.optString("nextPageSeason") == seasonObj.optString("id")) {
                            if (pageObj.optString("nextPageShow") != "1") break
                            q += "&page=${pageObj.optString("nextPage")}"
                        }
                        pageObj = JSONObject(netRequest("$origin${prefix}episodes.php?$q").second)
                        pageObj.optString("d_lang").takeIf { it.isNotBlank() }?.let { lang = it }
                    }
                    videoId = found ?: return@safe
                }
                var q = "id=${enc(videoId)}&t=${enc(detail.optString("title"))}&tm=$ts"
                if (platform == "pv") {
                    val hash = netCookies["t_hash_t"]?.first ?: return@safe
                    q += "&userhash=${enc(java.net.URLDecoder.decode(hash, "UTF-8"))}&hd=1&lang=${if (Regex("^[a-z]{2,3}$").matches(lang)) lang else "eng"}"
                }
                val playlist = JSONArray(netRequest("$origin${prefix}playlist.php?$q").second)
                val sources = playlist.optJSONObject(0)?.optJSONArray("sources").objs()
                val seen = HashSet<String>()
                for (s in sources) {
                    val u = resolve("$origin/", s.optString("file")) ?: continue
                    if (VeduSources.originOf(u) != origin || !u.contains("in=")) continue
                    val q2 = Regex("[?&]q=(\\d{3,4}p)").find(u)?.groupValues?.get(1) ?: "auto"
                    if (!seen.add(q2)) continue
                    val label = mapOf("nf" to "Netflix", "pv" to "Prime", "hs" to "Hotstar")[platform]
                    out.add(Src(u, "hls", q2, mapOf("Referer" to (netEntry ?: "$origin/"), "User-Agent" to NET_UA), label ?: platform))
                }
            }
            if (out.isNotEmpty()) break
        }
        return Result(out)
    }

    // =================================================================== ReelShort

    private const val REEL = "https://www.reelshort.com"
    private val reelDevId = (1..12).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("") + System.currentTimeMillis()

    data class Book(val id: String, val title: String, val cover: String?, val description: String?)

    private suspend fun reelApi(path: String, query: List<Pair<String, String>>): JSONObject? {
        val sig = linkedMapOf(
            "apiVersion" to "1.0.4", "channelId" to "WEB41001", "clientVer" to "2.4.00", "devId" to reelDevId,
            "lang" to "en", "ts" to (System.currentTimeMillis() / 1000).toString(), "uid" to "0",
        )
        val all = (query.toMap() + sig).filterValues { it.isNotEmpty() && it != "null" }.toSortedMap()
        val canonical = all.entries.joinToString("&") { "${it.key}=${it.value}" }
        val sign = hmacSha256("zj8N6zKEdrK8d1MxwHSvExdgQ868q1yT".toByteArray(), canonical.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        val headers = linkedMapOf("Accept" to "application/json, text/plain, */*", "Referer" to "$REEL/", "Origin" to REEL, "User-Agent" to DESKTOP_UA)
        headers.putAll(sig)
        headers["sign"] = sign
        val qs = query.joinToString("&") { "${enc(it.first)}=${enc(it.second)}" }
        val r = app.get("$REEL$path?$qs", headers = headers, timeout = 15L)
        val p = safe { JSONObject(r.text) } ?: return null
        return if (p.optInt("code", -1) == 0) p.optJSONObject("data") else null
    }

    /** Collects every {book_id, book_title} object in a page's __NEXT_DATA__. */
    private fun booksFromNext(html: String): List<Book> {
        val json = Regex("""<script id="__NEXT_DATA__"[^>]*>([\s\S]*?)</script>""").find(html)?.groupValues?.get(1) ?: return emptyList()
        val root = safe { JSONObject(json) } ?: return emptyList()
        val out = LinkedHashMap<String, Book>()
        fun walk(v: Any?, depth: Int) {
            if (depth > 14) return
            when (v) {
                is JSONObject -> {
                    val id = v.optString("book_id")
                    val t = v.optString("book_title")
                    if (id.isNotBlank() && t.isNotBlank() && !out.containsKey(id)) {
                        out[id] = Book(id, t, v.optString("book_pic").takeIf { it.startsWith("http") }, v.optString("special_desc").ifBlank { v.optString("description") }.takeIf { it.isNotBlank() })
                    }
                    for (k in v.keys()) walk(v.opt(k), depth + 1)
                }
                is JSONArray -> for (i in 0 until v.length()) walk(v.opt(i), depth + 1)
            }
        }
        walk(root, 0)
        return out.values.toList()
    }

    suspend fun reelSearch(query: String): List<Book> = safe {
        booksFromNext(app.get("$REEL/search?keywords=${enc(query)}", headers = mapOf("User-Agent" to DESKTOP_UA), timeout = 20L).text)
    } ?: emptyList()

    suspend fun reelHome(): List<Book> = safe {
        booksFromNext(app.get("$REEL/", headers = mapOf("User-Agent" to DESKTOP_UA), timeout = 20L).text)
    } ?: emptyList()

    data class Chapter(val id: String, val serial: Int)
    data class BookInfo(val title: String, val cover: String?, val description: String?, val chapters: List<Chapter>, val paidStart: Int?)

    suspend fun reelBook(bookId: String): BookInfo? {
        val d = reelApi("/api/video/book/getBookInfo", listOf("book_id" to bookId)) ?: return null
        val chapters = d.optJSONArray("online_base").objs().mapNotNull { c ->
            val id = c.optString("chapter_id").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val s = c.optInt("serial_number").takeIf { it > 0 } ?: return@mapNotNull null
            Chapter(id, s)
        }.sortedBy { it.serial }
        return BookInfo(d.optString("book_title"), d.optString("book_pic").takeIf { it.startsWith("http") },
            d.optString("special_desc").takeIf { it.isNotBlank() }, chapters, d.optInt("paid_start").takeIf { it > 0 })
    }

    private fun inflate(b: ByteArray): String {
        val input = if (b.size > 2 && b[0] == 0x1f.toByte() && b[1] == 0x8b.toByte()) GZIPInputStream(b.inputStream()) else InflaterInputStream(b.inputStream())
        val out = ByteArrayOutputStream()
        input.use { it.copyTo(out) }
        return out.toString("UTF-8")
    }

    suspend fun reelChapter(bookId: String, chapterId: String): Result {
        val headers = mapOf("Referer" to "$REEL/", "User-Agent" to DESKTOP_UA)
        val free = safe { reelApi("/api/video/book/getFreeChapter", listOf("book_id" to bookId, "chapter_id" to chapterId)) }
        val renditions = free?.optString("play_info")?.takeIf { it.isNotBlank() }?.let { enc0 ->
            safe {
                val plain = String(aesCbc(Base64.decode(enc0, Base64.DEFAULT), "VvRSNGFynLBW7aCP".toByteArray(), "gLn8sxqpzyNjehDP".toByteArray()), Charsets.UTF_8).trim()
                JSONArray(inflate(Base64.decode(plain, Base64.DEFAULT)))
            }
        }
        val out = renditions.objs().mapNotNull { r ->
            val u = r.optString("PlayURL").ifBlank { r.optString("playURL").ifBlank { r.optString("url") } }.takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val dpi = r.optInt("Dpi").takeIf { it > 0 } ?: r.optInt("dpi").takeIf { it > 0 }
            val codec = r.optString("Encode").ifBlank { r.optString("encode") }.uppercase()
            Src(u, "hls", dpi?.let { "${it}p" } ?: "", headers, if (codec == "H265" || codec == "HEVC") "HEVC" else "")
        }.distinctBy { it.url }.sortedWith(compareBy({ it.label == "HEVC" }, { -(it.quality.filter { c -> c.isDigit() }.toIntOrNull() ?: 0) }))
        if (out.isNotEmpty()) return Result(out)
        val info = safe { reelApi("/api/video/book/getChapterInfo", listOf("book_id" to bookId, "chapter_id" to chapterId)) }
        val u = info?.optString("video_url")?.takeIf { it.startsWith("http") } ?: return Result(emptyList())
        return Result(listOf(Src(u, "hls", "720p", headers)))
    }
}
