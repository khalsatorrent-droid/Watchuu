package com.watchuu.filmin

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.security.MessageDigest
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Filmin — the "Shortin / DramaRush" app (package com.dramarush.shortin, app_id "filmin").
 *
 * Decompiled from the app:
 *  - API: https://filmin.ajfysu.com/api/… , form-encoded POSTs (Retrofit interface ac.a)
 *  - Every request carries device headers plus sign = MD5(secret + device_id + cur_time).upper(),
 *    secret = 3DES-decrypted "MxASAkl/yHTGg+/Tw1R7u96nGqkWsOZ2" = "47Q8tBqO4YqrMHf4" (dd.e.y / yp.f)
 *  - Response bodies are Base64 AES-128-CBC, key "0123456789123456", IV "2015030120123456" (yp.a)
 *  - JSON: {code, message, result}
 *  - vod_url in vod_collection is a plain CDN .m3u8/.mp4; the app only wraps it in its local
 *    P2P proxy (127.0.0.1/resource.m3u8?src=…) when is_p2p == 1, so it is played directly here.
 */
class FilminProvider : MainAPI() {
    override var mainUrl = "https://filmin.ajfysu.com"
    override var name = "Filmin"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    companion object {
        private const val SECRET = "47Q8tBqO4YqrMHf4"
        private const val AES_KEY = "0123456789123456"
        private const val AES_IV = "2015030120123456"
        private const val SEARCH_PAGES = 3

        /** 22 chars of [0-9a-f], the same shape the app generates when android_id is missing. */
        private val deviceId: String by lazy {
            val chars = "0123456789abcdef"
            (1..22).map { chars.random() }.joinToString("")
        }
        private var token: String = ""
        private var apiBase: String = "https://filmin.ajfysu.com"
        private val fallbackDomains = mutableListOf<String>()
        private var initDone = false
    }

    // ─── Transport ──────────────────────────────────────────────────────────

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun headers(time: String) = mapOf(
        "app_id" to "filmin",
        "package_name" to "com.dramarush.shortin",
        "version" to "40000",
        "sys_platform" to "2",
        "mob_mfr" to "samsung",
        "mobmodel" to "SM-S918B",
        "sysrelease" to "14",
        "device_id" to deviceId,
        "gaid" to "",
        "channel_code" to "google",
        "androidid" to deviceId.take(16),
        "cur_time" to time,
        "token" to token,
        "sign" to md5(SECRET + deviceId + time).uppercase(),
        "is_vvv" to "0",
        "is_language" to "en",
        "is_display" to "en",
        "app_language" to "en",
        "en_al" to "0",
        "User-Agent" to "okhttp/4.12.0",
    )

    private fun decrypt(body: String): String {
        val t = body.trim()
        if (t.startsWith("{") || t.startsWith("[")) return t
        return runCatching {
            val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(AES_KEY.toByteArray(), "AES"), IvParameterSpec(AES_IV.toByteArray()))
            String(c.doFinal(Base64.decode(t, Base64.DEFAULT)), Charsets.UTF_8)
        }.getOrDefault("")
    }

    /** POSTs a form to /api/{path} and returns the decrypted "result" JSON (or null). */
    private suspend fun api(path: String, form: Map<String, String> = emptyMap()): String? {
        ensureInit()
        return runCatching { rawApi(apiBase, path, form) }.getOrNull()
            ?: run {
                // same recovery as the app's RetryWithDelay: walk api_conf, then ask error.png
                for (d in fallbackDomains) {
                    val r = runCatching { rawApi(d, path, form) }.getOrNull()
                    if (r != null) { apiBase = d; return r }
                }
                remoteDomain()?.takeIf { it != apiBase }?.let { d ->
                    runCatching { rawApi(d, path, form) }.getOrNull()?.also { apiBase = d }
                }
            }
    }

    /** Success is code 10000 (BaseResponse.isOk). */
    private suspend fun rawApi(base: String, path: String, form: Map<String, String>): String? {
        val time = System.currentTimeMillis().toString()
        val res = app.post("${base.trimEnd('/')}/api/$path", headers = headers(time), data = form)
        val base0 = tryParseJson<Base>(decrypt(res.text)) ?: return null
        if (base0.code != 10000) return null
        return base0.result?.toJson()
    }

    /** The app's last-resort domain lookup: a plain-text body holding the live API domain. */
    private suspend fun remoteDomain(): String? = runCatching {
        app.get("https://filmin.h3x2.com/error.png").text.trim()
            .takeIf { it.startsWith("http") && !it.contains('<') && !it.contains(' ') }
    }.getOrNull()

    private suspend fun ensureInit() {
        if (initDone) return
        initDone = true
        val form = mapOf("is_install" to "1")
        var res = runCatching { rawApi(apiBase, "public/init", form) }.getOrNull()
        if (res == null) {
            remoteDomain()?.let { d ->
                res = runCatching { rawApi(d, "public/init", form) }.getOrNull()
                if (res != null) apiBase = d
            }
        }
        val init = res?.let { tryParseJson<InitResult>(it) } ?: return
        init.userInfo?.token?.takeIf { it.isNotBlank() }?.let { token = it }
        init.sysConf?.apiConf.orEmpty().filter { it.isOpen == 1 && !it.api.isNullOrBlank() }
            .forEach { fallbackDomains += it.api!! }
        // after init the app moves every call to sys_conf.api_url2
        init.sysConf?.apiUrl2?.takeIf { it.startsWith("http") }?.let { apiBase = it }
    }

    // ─── Home ───────────────────────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "recommend" to "Recommended",
        "short" to "Short Dramas",
        "screen:1" to "Movies",
        "screen:2" to "TV Series",
        "screen:4" to "Anime",
        "screen:3" to "Shows",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pn = page.toString()
        val items: List<SearchResponse> = when {
            request.data == "recommend" -> vodList("search/recommend", mapOf("pn" to pn))
            request.data == "short" -> api("short_play/list", mapOf("pn" to pn))
                ?.let { tryParseJson<List<Vod>>(it) }.orEmpty().mapNotNull { it.toCard(short = true) }
            request.data.startsWith("screen:") -> vodList(
                "search/screen",
                mapOf(
                    "type_id" to request.data.substringAfter(':'), "type" to "", "area" to "",
                    "year" to "", "sort" to "", "pn" to pn,
                )
            )
            else -> emptyList()
        }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private suspend fun vodList(path: String, form: Map<String, String>): List<SearchResponse> =
        api(path, form)?.let { tryParseJson<List<Vod>>(it) }.orEmpty().mapNotNull { it.toCard() }

    // ─── Search (several pages merged; the paged overload breaks older app builds) ─

    override suspend fun search(query: String): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        for (p in 1..SEARCH_PAGES) {
            val page = vodList("search/result", mapOf("kw" to query, "pn" to p.toString()))
            if (page.isEmpty()) break
            out += page
        }
        return out.distinctBy { it.url }
    }

    // ─── Cards ──────────────────────────────────────────────────────────────

    private fun Vod.typeOf(): TvType = when (typePid) {
        1 -> TvType.Movie
        4 -> TvType.Anime
        else -> TvType.TvSeries
    }

    private fun Vod.toCard(short: Boolean = false): SearchResponse? {
        val vid = id ?: return null
        val title = name?.takeIf { it.isNotBlank() } ?: return null
        val ref = Ref(vid, title, pic, url, if (short) 99 else typePid).toJson()
        return newMovieSearchResponse(title, ref, if (short) TvType.AsianDrama else typeOf()) {
            posterUrl = pic
        }
    }

    // ─── Detail ─────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        val ref = parseJson<Ref>(url)
        val time = System.currentTimeMillis().toString()
        val vod = api(
            "vod/info_new",
            mapOf(
                "vod_id" to ref.id.toString(),
                "cur_time" to time,
                "sign" to md5(SECRET + deviceId + ref.id + time).uppercase(),
                "audio_type" to "0",
            )
        )?.let { tryParseJson<Vod>(it) }

        val title = vod?.name ?: ref.name
        val poster = vod?.pic ?: ref.pic
        val plot = (vod?.blurb ?: vod?.content)?.replace(Regex("<[^>]+>"), "")?.trim()
        val year = vod?.year?.toIntOrNull()
        val tags = vod?.tag?.split(',', '/', ' ')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
        val actors = vod?.actor?.split(',', '/')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()

        val episodes = vod?.collection.orEmpty().filter { !it.url.isNullOrBlank() }
            .ifEmpty {
                // detail sign rejected / nothing returned: fall back to the URL the list carried
                listOfNotNull(ref.url?.takeIf { it.isNotBlank() }?.let { Episode(ref.name, it, 1) })
            }

        val pid = vod?.typePid ?: ref.pid
        return if (pid == 1 && episodes.size <= 1) {
            newMovieLoadResponse(title, url, TvType.Movie, episodes.firstOrNull()?.url.orEmpty()) {
                posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                addActors(actors)
            }
        } else {
            val eps = episodes.mapIndexed { i, e ->
                newEpisode(e.url!!) {
                    name = e.title?.takeIf { it.isNotBlank() } ?: "Episode ${i + 1}"
                    episode = e.number ?: (i + 1)
                    season = 1
                }
            }
            val type = when (pid) { 4 -> TvType.Anime; 99 -> TvType.AsianDrama; else -> TvType.TvSeries }
            newTvSeriesLoadResponse(title, url, type, eps) {
                posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                addActors(actors)
            }
        }
    }

    // ─── Links ──────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val link = data.trim()
        if (!link.startsWith("http")) return false
        val path = link.substringBefore('?').lowercase()
        if (path.endsWith(".mp4")) {
            callback(newExtractorLink(name, name, link, ExtractorLinkType.VIDEO) {
                quality = Regex("""(\d{3,4})[pP]""").find(link)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Qualities.Unknown.value
            })
            return true
        }
        val (best, height) = bestVariant(link)
        callback(newExtractorLink(name, name, best, ExtractorLinkType.M3U8) {
            quality = height ?: Qualities.Unknown.value
        })
        return true
    }

    /** Collapses a master playlist to its top variant, labelled with that variant's real height. */
    private suspend fun bestVariant(master: String): Pair<String, Int?> {
        val text = runCatching { app.get(master).text }.getOrNull() ?: return master to null
        if (!text.contains("#EXT-X-STREAM-INF")) return master to null
        val lines = text.lines()
        var bestUrl: String? = null
        var bestH = -1
        var bestBw = -1L
        lines.forEachIndexed { i, l ->
            if (!l.startsWith("#EXT-X-STREAM-INF")) return@forEachIndexed
            val uri = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: return@forEachIndexed
            val h = Regex("""RESOLUTION=\d+x(\d+)""").find(l)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            val bw = Regex("""BANDWIDTH=(\d+)""").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
            if (h > bestH || (h == bestH && bw > bestBw)) { bestH = h; bestBw = bw; bestUrl = uri.trim() }
        }
        val chosen = bestUrl ?: return master to null
        val abs = when {
            chosen.startsWith("http") -> chosen
            chosen.startsWith("/") -> java.net.URI(master).let { "${it.scheme}://${it.host}$chosen" }
            else -> master.substringBefore('?').substringBeforeLast('/') + "/" + chosen
        }
        return abs to bestH.takeIf { it > 0 }
    }

    // ─── Models ─────────────────────────────────────────────────────────────

    data class Base(
        @JsonProperty("code") val code: Int? = null,
        @JsonProperty("message") val message: String? = null,
        @JsonProperty("result") val result: Any? = null,
    )

    data class InitResult(
        @JsonProperty("user_info") val userInfo: UserInfo? = null,
        @JsonProperty("sys_conf") val sysConf: SysConf? = null,
    )
    data class SysConf(
        @JsonProperty("api_url2") val apiUrl2: String? = null,
        @JsonProperty("api_conf") val apiConf: List<ApiConf>? = null,
    )
    data class ApiConf(
        @JsonProperty("api") val api: String? = null,
        @JsonProperty("is_open") val isOpen: Int? = null,
    )
    data class UserInfo(@JsonProperty("token") val token: String? = null)

    data class Ref(
        @JsonProperty("id") val id: Int,
        @JsonProperty("name") val name: String,
        @JsonProperty("pic") val pic: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("pid") val pid: Int? = null,
    )

    data class Vod(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("vod_name") val name: String? = null,
        @JsonProperty("vod_pic") val pic: String? = null,
        @JsonProperty("vod_url") val url: String? = null,
        @JsonProperty("vod_blurb") val blurb: String? = null,
        @JsonProperty("vod_content") val content: String? = null,
        @JsonProperty("vod_year") val year: String? = null,
        @JsonProperty("vod_tag") val tag: String? = null,
        @JsonProperty("vod_actor") val actor: String? = null,
        @JsonProperty("type_pid") val typePid: Int? = null,
        @JsonProperty("vod_collection") val collection: List<Episode>? = null,
    )

    data class Episode(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("vod_url") val url: String? = null,
        @JsonProperty("collection") val number: Int? = null,
    )
}
