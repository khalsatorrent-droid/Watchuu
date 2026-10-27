package com.watchuu.vedu

import com.lagradost.cloudstream3.app
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Kotlin ports of the Vedu app's TMDB-keyed resolver bundles:
 *  - "Orbit" = assets/resolvers/67movies.js (v1.0.50): VidRock, Vidnest, VidKing/Videasy, VidLink,
 *    VidUp, 111Movies, 2Embed, Peachify, Cinezo, Vidlove, Vidsuper. (VidFast and vidsrc.mov are
 *    left out: one needs a multi-round packet cipher, the other a downloaded WebAssembly module.)
 *  - "Nova"  = assets/resolvers/bingr.js (v1.0.62): api.bingr.one/api/stream servers s40..s3, plus
 *    the AniList-keyed anime adapters (Multi via animesalt, Sub/Dub via animex).
 */
object VeduSources {

    const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    data class Src(
        val url: String,
        val type: String = "",
        val quality: String = "",
        val headers: Map<String, String> = emptyMap(),
        val label: String = "",
        val audio: String = "",   // audio language as the source states it (normalised by VeduQuality.languageName)
    )

    data class Sub(val url: String, val lang: String)

    class Target(
        val type: String,          // movie | tv
        val tmdbId: String,
        val season: Int?,
        val episode: Int?,
        val title: String,
        val year: String,
        val imdbId: String,
        val totalSeasons: Int?,
    ) {
        val isMovie get() = type == "movie"
        val coords get() = if (isMovie) "movie/$tmdbId" else "tv/$tmdbId/$season/$episode"
        val query get() = if (isMovie) "movie?id=$tmdbId" else "tv?id=$tmdbId&season=$season&episode=$episode"
    }

    class Result(val sources: List<Src>, val subs: List<Sub> = emptyList())

    // ------------------------------------------------------------------ helpers

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    private fun normalizedReferer(value: String): String {
        val origin = originOf(value) ?: return value
        val rest = value.removePrefix(origin)
        return if (rest.isEmpty() || rest == "/") "$origin/" else value
    }

    fun originOf(u: String): String? {
        val scheme = u.substringBefore("://", "")
        if (scheme != "http" && scheme != "https") return null
        val host = u.substringAfter("://").substringBefore("/").substringBefore("?").substringBefore("#")
        return if (host.isBlank()) null else "$scheme://$host"
    }

    private fun apiHeaders(referer: String, origin: String? = null): Map<String, String> {
        val h = linkedMapOf(
            "Accept" to "application/json, text/plain, */*",
            "Referer" to normalizedReferer(referer),
            "User-Agent" to UA,
        )
        if (origin != null) h["Origin"] = origin
        return h
    }

    private fun mediaHeaders(referer: String) = mapOf("Referer" to normalizedReferer(referer), "User-Agent" to UA)

    private fun sanitizedHeaders(obj: Any?): Map<String, String> {
        val o = obj as? JSONObject ?: return emptyMap()
        val out = linkedMapOf<String, String>()
        for (k in o.keys()) {
            val name = k.trim()
            val value = o.opt(k)?.takeIf { it != JSONObject.NULL }?.toString()?.trim() ?: continue
            if (name.isEmpty() || value.isEmpty()) continue
            if (name.lowercase() in setOf("connection", "content-length", "host")) continue
            out[name] = value
        }
        return out
    }

    private fun validUrl(v: Any?): String? {
        val s = (v as? String)?.trim() ?: return null
        return if (s.startsWith("http://") || s.startsWith("https://")) s else null
    }

    private fun JSONObject.str(vararg keys: String): String {
        for (k in keys) {
            val v = opt(k)
            if (v != null && v != JSONObject.NULL && v.toString().isNotBlank()) return v.toString()
        }
        return ""
    }

    private fun isPreview(o: JSONObject?, url: String): Boolean {
        if (o != null && (o.optBoolean("isPreview") || o.optBoolean("preview"))) return true
        val path = try { java.net.URLDecoder.decode(url.substringBefore("?"), "UTF-8") } catch (_: Exception) { url }
        return Regex("""(?i)(^|[/._-])preview([/._-]|$)""").containsMatchIn(path)
    }

    private fun isPlaceholder(url: String) = url.substringAfter("://").substringBefore("/").equals("macdn.aoneroom.com", true)

    private fun JSONArray?.objects(): List<JSONObject> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optJSONObject(it) }
    }

    private suspend fun getText(url: String, headers: Map<String, String>): Pair<Int, String> {
        val r = app.get(url, headers = headers, timeout = 20L)
        return r.code to r.text
    }

    private fun parseObj(text: String): JSONObject? = try { JSONObject(text.trim()) } catch (_: Exception) { null }

    /** Pulls subtitle tracks out of the common {subtitles|captions|tracks:[{url|file, lang|label}]} shapes. */
    private fun subsFrom(o: JSONObject?): List<Sub> {
        if (o == null) return emptyList()
        val out = ArrayList<Sub>()
        for (key in listOf("subtitles", "captions", "tracks", "subtitle")) {
            for (t in o.optJSONArray(key).objects()) {
                val kind = t.optString("kind")
                if (kind.isNotBlank() && kind !in setOf("captions", "subtitles")) continue
                val u = validUrl(t.opt("url")) ?: validUrl(t.opt("file")) ?: continue
                val lang = t.str("label", "lang", "language", "name").ifBlank { "Unknown" }
                out.add(Sub(u, lang))
            }
        }
        return out
    }

    /** Generic {url|file, type, quality|label, headers} source object as the JS adapters map it. */
    private fun genericSource(s: JSONObject, extra: Map<String, String>, fallbackQuality: String = ""): Src? {
        val url = validUrl(s.opt("url")) ?: validUrl(s.opt("file")) ?: return null
        if (isPreview(s, url) || isPlaceholder(url)) return null
        return Src(
            url = url,
            type = s.str("type", "label"),
            quality = s.str("quality", "label", "name").ifBlank { fallbackQuality },
            headers = extra + sanitizedHeaders(s.optJSONObject("headers")),
            label = s.str("label", "name"),
            audio = s.str("language", "lang", "audioLang", "audio_language", "audio"),
        )
    }

    // =============================================================== ORBIT (67Movies)

    class Server(val id: String, val name: String, val resolve: suspend (Target) -> Result)

    val orbitServers: List<Server> = listOf(
        Server("vidrock", "VidRock") { vidrock(it) },
        Server("vidnest", "Vidnest") { vidnest(it) },
        Server("vidking", "VidKing") { videasy(it, vidking = true) },
        Server("vidlinkpro", "VidLink") { vidlink(it) },
        Server("vidup", "VidUp") { embedPage(it, "https://vidup.to") },
        Server("videasy", "Videasy") { videasy(it, vidking = false) },
        Server("111movies", "111Movies") { oneElevenMovies(it) },
        Server("2embed", "2Embed") { twoEmbed(it) },
        Server("peachify", "Peachify") { peachify(it) },
        Server("cinezo", "Cinezo") { cinezo(it) },
        Server("vidlove", "Vidlove") { vidlove(it) },
        Server("vidsuper", "Vidsuper") { embedPage(it, "https://vidsuper.net") },
    )

    private const val SIXTY_SEVEN = "https://67movies.gd"

    // ---- VidRock: /api/{coords} -> {Luna|Orion: {type:"hls", url:<AES-GCM b64url>}}
    private suspend fun vidrock(t: Target): Result {
        val origin = "https://vidrock.net"
        val embed = "$origin/${t.coords}"
        val (code, body) = getText("$origin/api/${t.coords}", apiHeaders(embed, origin))
        if (code !in 200..299) return Result(emptyList())
        val payload = parseObj(body) ?: return Result(emptyList())
        val out = ArrayList<Src>()
        for (name in listOf("Luna", "Orion")) {
            val s = payload.optJSONObject(name) ?: continue
            if (s.optString("type") != "hls") continue
            val enc = s.optString("url").takeIf { it.isNotBlank() } ?: continue
            val url = validUrl(VeduCrypto.vidrockDecrypt(enc)) ?: continue
            if (isPreview(s, url)) continue
            out.add(Src(url, "hls", "", mediaHeaders(embed) + ("Origin" to origin), name))
        }
        return Result(out)
    }

    // ---- Vidnest: new.vidnest.fun/{providerPath}/{coords}, payload optionally alphabet-scrambled base64
    private val vidnestMovieOrder = listOf("catflix", "filxer", "prime", "gama", "zeta", "alfa", "ophim", "beta", "sigma", "lamda", "hexa", "delta")
    private val vidnestTvOrder = listOf("catflix", "zeta", "filxer", "prime", "ophim", "beta", "alfa", "sigma", "gama", "lamda", "hexa", "delta")
    private val vidnestPaths = mapOf(
        "catflix" to "yflix", "filxer" to "rogflix", "prime" to "vidrock", "gama" to "vidzee",
        "zeta" to "nextgencloudfabric", "alfa" to "videasy", "ophim" to "klikxxi", "beta" to "vidxyz",
        "sigma" to "hollymoviehd", "lamda" to "allmovies", "hexa" to "vidlink", "delta" to "allmovies",
    )
    private const val VIDNEST_MEGACLOUD = "https://megacloud.animanga.fun"
    private val vidnestProxies = listOf(
        "https://vidproxy.alfa-3bd.workers.dev", "https://vidproxy.beta-fa8.workers.dev",
        "https://vidproxy.gama-f83.workers.dev", "https://vidproxy.lamda-5d0.workers.dev",
        "https://dark-sky-796e.flixer-d6b.workers.dev", "https://autumn-fire-7c7b.zeta-7cc.workers.dev",
    )

    private suspend fun vidnest(t: Target): Result {
        val site = "https://vidnest.fun"
        val order = if (t.isMovie) vidnestMovieOrder else vidnestTvOrder
        for (provider in order) {
            try {
                val (code, body) = getText(
                    "https://new.vidnest.fun/${vidnestPaths[provider]}/${t.coords}",
                    apiHeaders("$site/${t.coords}", site)
                )
                if (code !in 200..299) continue
                var payload = parseObj(body) ?: continue
                if (payload.optBoolean("encrypted")) {
                    val data = payload.optString("data")
                    if (data.isBlank()) continue
                    payload = parseObj(VeduCrypto.vidnestDecode(data)) ?: continue
                }
                val sources = vidnestSources(provider, payload, t.isMovie)
                if (sources.isNotEmpty()) return Result(sources.map { it.copy(label = provider) }, subsFrom(payload))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        return Result(emptyList())
    }

    private fun vidnestSources(provider: String, payload: JSONObject, isMovie: Boolean): List<Src> {
        val streams = payload.optJSONArray("streams").objects()
        val sources = payload.optJSONArray("sources").objects()
        fun direct(s: JSONObject, type: String): List<Src> {
            val url = validUrl(s.opt("url")) ?: return emptyList()
            if (url.substringBefore("?").endsWith(".mkv", true) || isPreview(s, url) || isPlaceholder(url)) return emptyList()
            return listOf(Src(url, type, s.str("quality", "resolution"), sanitizedHeaders(s.optJSONObject("headers")),
                audio = s.str("language", "lang", "audio")))
        }
        fun proxy(s: JSONObject, origin: String): List<Src> = direct(s, "hls").map {
            val hdr = JSONObject(it.headers as Map<*, *>).toString()
            Src("$origin/proxy?url=${enc(it.url)}&headers=${enc(hdr)}", "hls", it.quality, audio = it.audio)
        }
        fun randomProxy() = vidnestProxies.random()
        return when (provider) {
            "catflix", "filxer", "alfa" -> direct(payload, "hls")
            "prime" -> {
                val c = if (isMovie) sources.filter { it.optString("type") == "mp4" } + sources.filter { it.optString("type") == "hls" } else sources
                c.flatMap { s ->
                    direct(s, if (s.optString("type") == "mp4") "mp4" else "hls")
                        .map { it.copy(quality = s.str("resolution").ifBlank { "Auto" }) }
                }.sortedByDescending { it.quality.filter { ch -> ch.isDigit() }.take(4).toIntOrNull() ?: 0 }
            }
            "gama" -> streams.flatMap { direct(it, it.optString("type").ifBlank { "mp4" }) }
            "zeta" -> {
                val urls = LinkedHashSet<String>()
                payload.optString("url").takeIf { it.isNotBlank() }?.let { urls.add(it) }
                payload.optJSONArray("all_urls")?.let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let { urls.add(it) } }
                urls.flatMap { u -> proxy(JSONObject(payload.toString()).put("url", u), randomProxy()) }
            }
            "ophim" -> sources.flatMap { direct(it, if (it.optString("type") == "hls") "hls" else "") }
            "beta" -> {
                val adaptive = streams.firstOrNull { it.optString("type") == "hls" && it.optString("url").isNotBlank() }
                val a = adaptive?.let {
                    val h = it.optJSONObject("headers") ?: JSONObject()
                    h.put("Referer", "https://moviesapi.to/")
                    proxy(JSONObject(it.toString()).put("headers", h), randomProxy())
                } ?: emptyList()
                a + streams.filter { it.optString("type") == "mp4" }.flatMap { direct(it, it.optString("type")) }
            }
            "sigma" -> {
                val s = streams.firstOrNull { it.optString("type") == "hls" && it.optString("language") == "GS-25" }
                if (s == null) emptyList() else proxy(s, VIDNEST_MEGACLOUD) + proxy(s, randomProxy())
            }
            "lamda", "delta" -> {
                val lang = if (provider == "lamda") "english" else "hindi"
                streams.firstOrNull { it.optString("language").lowercase() == lang }?.let { direct(it, "") } ?: emptyList()
            }
            "hexa" -> {
                val playlist = payload.optJSONObject("data")?.optJSONObject("stream")?.optString("playlist")?.replace(Regex("\\s"), "") ?: ""
                val h = JSONObject()
                sanitizedHeaders(payload.optJSONObject("headers")).forEach { (k, v) -> h.put(k.trim(), v.trim()) }
                proxy(JSONObject().put("url", playlist).put("headers", h), VIDNEST_MEGACLOUD)
            }
            else -> emptyList()
        }
    }

    // ---- Videasy / VidKing: seed -> sources-with-title (enc=2) -> custom stream cipher
    private suspend fun videasy(t: Target, vidking: Boolean, canRefresh: Boolean = true): Result {
        if (t.title.isBlank()) return Result(emptyList())
        val playerOrigin = if (vidking) "https://www.vidking.net" else "https://player.videasy.to"
        val embed = if (vidking) "$playerOrigin/embed/${t.type}/${t.tmdbId}" + (if (t.isMovie) "" else "/${t.season}/${t.episode}")
        else "$playerOrigin/${t.coords}"
        val api = "https://api.speedracelight.com"
        val (sc, sb) = getText("$api/seed?mediaId=${t.tmdbId}", apiHeaders(embed, playerOrigin))
        if (sc !in 200..299) return Result(emptyList())
        val seed = parseObj(sb)?.optString("seed")?.trim().orEmpty()
        if (seed.isEmpty()) return Result(emptyList())
        val q = listOf(
            "title" to t.title, "mediaType" to t.type, "year" to t.year,
            "totalSeasons" to (t.totalSeasons?.toString() ?: ""),
            "episodeId" to (t.episode?.toString() ?: "1"), "seasonId" to (t.season?.toString() ?: "1"),
            "tmdbId" to t.tmdbId, "imdbId" to t.imdbId, "enc" to "2", "seed" to seed,
        ).joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        val (code, body) = getText("$api/cdn/sources-with-title?$q", apiHeaders(embed, playerOrigin))
        if (code == 401 && canRefresh) return videasy(t, vidking, false)
        if (code !in 200..299) return Result(emptyList())
        val text = if (body.trim().startsWith("{")) body
        else VeduCrypto.videasyDecrypt(body.trim(), seed, t.tmdbId.toIntOrNull() ?: 0) ?: return Result(emptyList())
        val payload = parseObj(text) ?: return Result(emptyList())
        val list = payload.optJSONArray("sources").objects().mapNotNull { genericSource(it, mediaHeaders(embed)) }
        return Result(list, subsFrom(payload))
    }

    // ---- VidLink: /api/b/{type}/{salsa+poly1305 token}[/s/e]?multiLang=0 (mwVault only)
    private suspend fun vidlink(t: Target): Result {
        val token = VeduCrypto.vidlinkToken(t.tmdbId)
        val path = if (t.isMovie) "" else "/${t.season}/${t.episode}"
        val (code, body) = getText(
            "https://vidlink.pro/api/b/${t.type}/$token$path?multiLang=0",
            apiHeaders("https://vidlink.pro/") + ("X-Playback-Environment" to "standard")
        )
        if (code !in 200..299) return Result(emptyList())
        val payload = parseObj(body) ?: return Result(emptyList())
        if (payload.optString("sourceId") != "mwVault") return Result(emptyList())
        val stream = payload.optJSONObject("stream") ?: return Result(emptyList())
        val qualities = stream.optJSONObject("qualities") ?: return Result(emptyList())
        val out = ArrayList<Src>()
        for (k in qualities.keys()) {
            val s = qualities.optJSONObject(k) ?: continue
            genericSource(s, emptyMap(), k)?.let { out.add(it.copy(quality = s.str("quality").ifBlank { k })) }
        }
        return Result(out, subsFrom(stream))
    }

    // ---- public embed pages: scrape media URLs from the HTML (VidUp, Vidsuper)
    private suspend fun embedPage(t: Target, origin: String): Result {
        val embed = "$origin/${t.coords}"
        val r = app.get(embed, headers = apiHeaders(SIXTY_SEVEN), timeout = 20L)
        if (r.code !in 200..299) return Result(emptyList())
        return Result(extractMediaUrls(r.text, r.url.ifBlank { embed }))
    }

    private suspend fun oneElevenMovies(t: Target): Result {
        val embed = "https://111movies.net/${t.coords}"
        val r = app.get(embed, headers = apiHeaders(SIXTY_SEVEN), timeout = 20L)
        if (r.code !in 200..299) return Result(emptyList())
        if (r.url.contains("player.vidlove.cc")) return vidlove(t)
        return Result(extractMediaUrls(r.text, embed))
    }

    private fun extractMediaUrls(body: String, referer: String): List<Src> {
        val normalized = body.replace("\\u0026", "&").replace("\\/", "/")
        return Regex("""(?i)https?://[^\s"'<>]+?(?:\.m3u8|\.mpd|\.mp4)(?:\?[^\s"'<>]*)?""")
            .findAll(normalized).map { it.value.replace("&amp;", "&") }
            .filter { !isPlaceholder(it) && !isPreview(null, it) }
            .distinct().map { Src(it, "", "", mediaHeaders(referer)) }.toList()
    }

    // ---- 2Embed: vidsrc.buzz inner player, var Q = {...}; then /pl/api.php race/play
    private suspend fun twoEmbed(t: Target): Result {
        val player = "https://vidsrc.buzz"
        val embed = "$player/embed/${t.coords}"
        val outer = if (t.isMovie) "https://www.2embed.cc/embed/${t.tmdbId}"
        else "https://www.2embed.cc/embedtv/${t.tmdbId}&s=${t.season}&e=${t.episode}"
        val (code, html) = getText(embed, apiHeaders(outer))
        if (code !in 200..299) return Result(emptyList())
        val m = Regex("""\bvar\s+Q\s*=\s*(\{[^\r\n]*\})\s*;""").find(html) ?: return Result(emptyList())
        val cfg = parseObj(m.groupValues[1]) ?: return Result(emptyList())
        val cfgId = cfg.optString("id")
        val idOk = cfgId == t.tmdbId || (cfgId.matches(Regex("tt\\d{1,16}")) && (t.imdbId.isBlank() || cfgId == t.imdbId))
        if (cfg.optString("type") != t.type || !idOk) return Result(emptyList())
        if (!t.isMovie && (cfg.optInt("s") != t.season || cfg.optInt("e") != t.episode)) return Result(emptyList())
        val token = cfg.optString("t").takeIf { it.isNotBlank() } ?: return Result(emptyList())
        suspend fun api(q: String): JSONObject? {
            val (c, b) = getText("$player/pl/api.php?$q", apiHeaders(embed, player))
            return if (c in 200..299) parseObj(b) else null
        }
        var servers = cfg.optJSONObject("ssr")?.optJSONArray("servers").objects()
        if (servers.isEmpty()) {
            servers = api(
                "a=sources&type=${enc(cfg.optString("type"))}&id=${enc(cfgId)}&s=${enc(cfg.optString("s").ifBlank { "0" })}" +
                    "&e=${enc(cfg.optString("e").ifBlank { "0" })}&t=${enc(token)}"
            )?.optJSONArray("servers").objects()
        }
        if (servers.isEmpty()) return Result(emptyList())
        val preferred = cfg.optString("srv").uppercase()
        fun matchesPreferred(s: JSONObject) = preferred.isNotBlank() &&
            s.optString("name").replace(Regex("(?i)^Server\\s+"), "").split("-")[0].uppercase().startsWith(preferred)
        if (preferred.isNotBlank()) servers = servers.filter { matchesPreferred(it) } + servers.filterNot { matchesPreferred(it) }
        val refs = servers.map { it.optString("ref") }.filter { it.isNotBlank() && it.length <= 4096 }.distinct().take(6)
        if (refs.isEmpty()) return Result(emptyList())
        val prefLang = servers.firstOrNull()?.optString("lang")
        fun norm(c: JSONObject?): List<Src> {
            if (c == null) return emptyList()
            val raw = c.optString("url").takeIf { it.isNotBlank() } ?: return emptyList()
            val type = c.optString("type").ifBlank { "hls" }
            if (type !in setOf("hls", "mp4", "dash")) return emptyList()
            val abs = try { java.net.URL(java.net.URL("$player/pl/"), raw).toString() } catch (_: Exception) { return emptyList() }
            val u = validUrl(abs) ?: return emptyList()
            val srv = servers.firstOrNull { it.optString("ref") == c.optString("ref") }
            return listOf(Src(u, type, "", mediaHeaders(embed), srv?.optString("name") ?: "", audio = srv?.optString("lang") ?: ""))
        }
        try {
            val race = api("a=race&refs=${enc(refs.joinToString(","))}")
            val cands = race?.optJSONArray("cands").objects().take(6).filter { it.optString("ref") in refs }
                .sortedWith(compareBy<JSONObject>({ c ->
                    if (!prefLang.isNullOrBlank() && servers.firstOrNull { it.optString("ref") == c.optString("ref") }?.optString("lang") == prefLang) 0 else 1
                }, { refs.indexOf(it.optString("ref")) }))
            val out = cands.flatMap { norm(it) }
            if (out.isNotEmpty()) return Result(out)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        return Result(norm(api("a=play&ref=${enc(refs[0])}&t=${enc(token)}")))
    }

    // ---- Peachify: none.eat-peach.sbs/air/{coords}
    private suspend fun peachify(t: Target): Result {
        val origin = "https://peachify.top"
        val (code, body) = getText("https://none.eat-peach.sbs/air/${t.coords}", apiHeaders("$origin/embed/${t.coords}", origin))
        if (code !in 200..299) return Result(emptyList())
        val p = parseObj(body) ?: return Result(emptyList())
        if (p.optString("type") != t.type || p.optString("tmdbId") != t.tmdbId) return Result(emptyList())
        if (!t.isMovie && (p.optInt("season") != t.season || p.optInt("episode") != t.episode)) return Result(emptyList())
        val out = p.optJSONArray("sources").objects().mapNotNull { s ->
            if (s.optString("type") != "hls") return@mapNotNull null
            var url = validUrl(s.opt("url")) ?: return@mapNotNull null
            if (url.substringBefore("?").endsWith("/m3u8-proxy")) {
                if (originOf(url) != "https://a.eat-peach.sbs") return@mapNotNull null
                url = validUrl(Regex("[?&]url=([^&]+)").find(url)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") })
                    ?: return@mapNotNull null
            }
            if (isPreview(s, url)) return@mapNotNull null
            Src(url, "hls", s.optString("quality"), sanitizedHeaders(s.optJSONObject("headers")))
        }
        return Result(out, subsFrom(p))
    }

    // ---- Cinezo: proxy1/proxy3.flikhub.net/{query}&mode=json&sources=X&hevc=1
    private suspend fun cinezo(t: Target): Result {
        val player = "https://player.cinezo.live"
        val embed = "$player/embed/${t.coords}"
        val out = ArrayList<Src>()
        val subs = ArrayList<Sub>()
        for (internal in listOf("zendaya", "berlin", "moviebox")) {
            try {
                val base = if (internal == "zendaya") "https://proxy3.flikhub.net" else "https://proxy1.flikhub.net"
                val (code, body) = getText("$base/${t.query}&mode=json&sources=$internal&hevc=1", apiHeaders(embed, player))
                if (code !in 200..299) continue
                val p = parseObj(body) ?: continue
                subs += subsFrom(p)
                val s = p.optJSONObject("source") ?: continue
                val src = genericSource(s, mediaHeaders(embed), internal) ?: continue
                if (sourceKind(src.type, src.url) == "mp4") continue // the app rejects Cinezo MP4s
                out.add(src.copy(label = internal))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        return Result(out, subs)
    }

    // ---- Vidlove: api.tungtungtungtungsahur.app/{query}&mode=json&sources=X&hevc=1
    private suspend fun vidlove(t: Target): Result {
        val player = "https://player.vidlove.cc"
        val embed = "$player/embed/${t.coords}"
        for (provider in listOf("moviebox2", "warden", "cinefreak", "vidapi")) {
            try {
                val (code, body) = getText(
                    "https://api.tungtungtungtungsahur.app/${t.query}&mode=json&sources=$provider&hevc=1",
                    apiHeaders(embed, player)
                )
                if (code !in 200..299) continue
                val p = parseObj(body) ?: continue
                val raw = p.optJSONArray("sources")?.objects()
                    ?: p.optJSONArray("source")?.objects()
                    ?: listOfNotNull(p.optJSONObject("source"))
                val out = raw.mapNotNull { genericSource(it, mediaHeaders(embed)) }
                if (out.isNotEmpty()) return Result(out.map { it.copy(label = it.label.ifBlank { provider }) }, subsFrom(p))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        return Result(emptyList())
    }

    // ================================================================= NOVA (Bingr)

    private const val BINGR_API = "https://api.bingr.one/api"
    private const val BINGR_SITE = "https://bingr.one"
    val novaServers = listOf("s40", "s70", "s80", "s81", "s62", "s63", "s30", "s60", "s61", "s31", "s3")
    private val bingrWebsiteHosts = setOf("super.cinehub.one", "img.rousav.tech")

    private fun bingrHeaders() = mapOf(
        "Accept" to "application/json",
        "Content-Type" to "application/json",
        "Referer" to "$BINGR_SITE/",
        "User-Agent" to UA,
    )

    suspend fun bingr(server: String, t: Target): Result {
        val resp = if (server == "s40" && !t.isMovie) {
            app.get("$BINGR_API/stream/aphelion-tv/${t.tmdbId}/${t.season}/${t.episode}", headers = bingrHeaders(), timeout = 20L)
        } else {
            val query = JSONObject().put("title", t.title)
            if (t.year.isNotBlank()) query.put("year", t.year)
            if (!t.isMovie) {
                query.put("season", t.season.toString())
                query.put("episode", t.episode.toString())
            }
            val body = JSONObject().put("srv", server).put("t", t.type).put("id", t.tmdbId).put("query", query).toString()
            app.post(
                "$BINGR_API/stream", headers = bingrHeaders(),
                requestBody = body.toRequestBody("application/json".toMediaType()), timeout = 20L
            )
        }
        if (resp.code !in 200..299) return Result(emptyList())
        val p = parseObj(resp.text) ?: return Result(emptyList())
        val out = p.optJSONArray("sources").objects().mapNotNull { s ->
            val url = (validUrl(s.opt("url")) ?: validUrl(s.opt("file")))?.replace(Regex("^http:"), "https:") ?: return@mapNotNull null
            if (isPreview(s, url) || isPlaceholder(url)) return@mapNotNull null
            val headers = LinkedHashMap(sanitizedHeaders(s.optJSONObject("headers")))
            val host = url.substringAfter("://").substringBefore("/").substringBefore(":").lowercase()
            val names = headers.keys.map { it.lowercase() }
            if (host == "img.animecurx.tech" && "user-agent" !in names) headers["User-Agent"] = UA
            if (host in bingrWebsiteHosts) {
                if ("referer" !in names) headers["Referer"] = "$BINGR_SITE/"
                if ("origin" !in names) headers["Origin"] = BINGR_SITE
            }
            Src(url, s.str("type"), s.str("quality", "label", "name"), headers, s.str("label", "name"),
                audio = s.str("language", "lang", "audio"))
        }
        return Result(out, subsFrom(p))
    }

    // ---- Nova anime: Multi (animesalt by title) and Sub/Dub (animex by AniList id)
    private const val ANIME_MULTI = "https://hianime.filmu.in"
    private const val ANIME_PROXY = "https://wormhole.vumeto.xyz"
    private const val ANIME_CLIENT_ID = "a3f9c1d8e7b2c4d6f1a9b0c3d4e5f6789a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d"
    @Volatile private var multiToken: String = ""

    suspend fun animeMulti(titles: List<String>, episode: Int, isMovie: Boolean): Result {
        suspend fun loadToken(): String = try {
            val r = app.post("$ANIME_MULTI/token", headers = mapOf("User-Agent" to UA), timeout = 15L)
            parseObj(r.text)?.optString("token").orEmpty().also { multiToken = it }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ""
        }
        var token = multiToken.ifBlank { loadToken() }
        var refreshed = false
        for (title in titles.map { it.trim() }.filter { it.isNotBlank() }.distinct()) {
            try {
                var url = "$ANIME_MULTI/animesalt/streams?title=${enc(title)}"
                if (!isMovie) url += "&ep=$episode&season=1"
                var r = app.get(url, headers = if (token.isNotBlank()) mapOf("x-api-key" to token) else emptyMap(), timeout = 20L)
                if ((r.code == 401 || r.code == 403) && !refreshed) {
                    refreshed = true
                    token = loadToken()
                    r = app.get(url, headers = if (token.isNotBlank()) mapOf("x-api-key" to token) else emptyMap(), timeout = 20L)
                }
                if (r.code !in 200..299) continue
                val out = parseObj(r.text)?.optJSONArray("streams").objects().mapNotNull { s ->
                    val raw = s.str("proxyUrl", "url").ifBlank { return@mapNotNull null }
                    var abs = try { java.net.URL(java.net.URL("$ANIME_MULTI/"), raw).toString() } catch (_: Exception) { return@mapNotNull null }
                    if (token.isNotBlank()) abs += (if (abs.contains("?")) "&" else "?") + "apiKey=${enc(token)}"
                    val mp4 = s.optString("type") == "video" || abs.substringBefore("?").endsWith(".mp4")
                    Src(abs, if (mp4) "mp4" else "hls", s.str("quality").ifBlank { "HD" }, emptyMap(), "Multi")
                }
                if (out.isNotEmpty()) return Result(out)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        return Result(emptyList())
    }

    private suspend fun proxyJson(upstream: String): JSONObject? {
        val hdr = JSONObject().put("x-api-key", ANIME_CLIENT_ID).put("Accept", "application/json").toString()
        val r = app.get("$ANIME_PROXY/?url=${enc(upstream)}&headers=${enc(hdr)}", headers = mapOf("User-Agent" to UA), timeout = 15L)
        return if (r.code in 200..299) parseObj(r.text) else null
    }

    private val animexIds = java.util.concurrent.ConcurrentHashMap<Int, String>()

    suspend fun animeSubDub(anilistId: Int, episode: Int, dub: Boolean): Result {
        val internal = animexIds[anilistId] ?: run {
            val q = "query Anime(\$anilistId: Int) { anime(anilistId: \$anilistId) { id } }"
            val meta = proxyJson(
                "https://graphql.animex.one/graphql?query=${enc(q)}&variables=${enc(JSONObject().put("anilistId", anilistId).toString())}"
            )
            meta?.optJSONObject("data")?.optJSONObject("anime")?.optString("id")?.takeIf { it.isNotBlank() }
                ?.also { animexIds[anilistId] = it }
        } ?: return Result(emptyList())
        val coords = "id=${enc(internal)}&epNum=$episode"
        val catalog = proxyJson("https://pp.animex.one/rest/api/servers?$coords") ?: return Result(emptyList())
        val type = if (dub) "dub" else "sub"
        val providers = catalog.optJSONArray(if (dub) "dubProviders" else "subProviders").objects()
        val out = ArrayList<Src>()
        for (prov in providers) {
            try {
                val pid = prov.opt("id")?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: continue
                val payload = proxyJson("https://pp.animex.one/rest/api/sources?$coords&type=$type&providerId=${enc(pid)}") ?: continue
                val headers = sanitizedHeaders(payload.optJSONObject("headers"))
                val referer = headers["referer"] ?: headers["Referer"] ?: "https://pp.animex.one/"
                val origin = headers["origin"] ?: headers["Origin"] ?: originOf(referer) ?: ""
                for (s in payload.optJSONArray("sources").objects()) {
                    val addr = validUrl(s.opt("url"))?.replace(Regex("(?i)^http:"), "https:") ?: continue
                    val hls = addr.contains(".m3u8") || Regex("(?i)mpegurl|m3u8").containsMatchIn(s.optString("type"))
                    val proxied = if (hls) {
                        "$ANIME_PROXY/proxy/m3u8?url=${enc(addr)}&referer=${enc(referer)}&origin=${enc(origin)}"
                    } else {
                        val h = JSONObject().put("referer", referer)
                        headers.forEach { (k, v) -> h.put(k, v) }
                        "$ANIME_PROXY/proxy/file?url=${enc(addr)}&headers=${enc(h.toString())}"
                    }
                    out.add(Src(proxied, if (hls) "hls" else "mp4", s.str("quality").ifBlank { "HD" }, emptyMap(), "${type.uppercase()} $pid",
                        audio = if (dub) "English" else "Japanese"))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        return Result(out)
    }

    // ------------------------------------------------------------------ typing

    fun sourceKind(advertised: String, url: String): String {
        val t = advertised.lowercase()
        val u = url.lowercase()
        return when {
            t.contains("dash") || u.contains(".mpd") -> "dash"
            t.contains("hls") || t.contains("mpegurl") || t.contains("m3u8") || u.contains(".m3u8") || u.contains("/m3u8") -> "hls"
            t.contains("mp4") || t.contains("video") || u.contains(".mp4") -> "mp4"
            else -> "hls"
        }
    }
}
