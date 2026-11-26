package com.watchuu.applymusic

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Apply Music - the Apple Music catalogue served by music.ak1ra.de5.net.
 *
 * Everything below was read off the site's own traffic:
 *
 *   GET /amp/v1/catalog/{cc}/search?term=&types=songs,albums,artists&limit=&l=
 *        -> results.{songs,albums,artists}.data[]
 *   GET /amp/v1/catalog/{cc}/songs/{id}?extend=extendedAssetUrls&include=albums,artists&l=
 *        -> data[0].attributes{name, artistName, albumName, artwork.url, durationInMillis,
 *                              extendedAssetUrls.enhancedHls, previews[0].url}
 *   GET /amp/v1/catalog/{cc}/albums/{id}?extend=extendedAssetUrls&include=tracks&l=
 *        -> data[0].attributes + data[0].relationships.tracks.data[]
 *   GET /amp/v1/catalog/{cc}/artists/{id}?views=top-songs&l=
 *        -> data[0].views["top-songs"].data[]
 *
 * Playback: enhancedHls is an HLS master on aod.itunes.apple.com. It is a plain playlist; the
 * variants inside it are what play, and the request needs nothing but a Referer.
 */
object ApplyMusicApi {
    const val SITE = "https://music.ak1ra.de5.net"
    const val BUILD = "v2"

    private const val UA_FALLBACK =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null
    @Volatile private var uaCache: String? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences("applymusic_prefs", Context.MODE_PRIVATE)
        }
    }

    private fun str(k: String) = prefs?.getString(k, "")?.trim().orEmpty()
    private fun put(k: String, v: String) {
        prefs?.edit()?.putString(k, v.trim())?.apply()
    }

    /** storefront, e.g. in, us, gb - the catalogue is per country */
    var storefront: String
        get() = str("storefront").ifBlank { "in" }
        set(v) = put("storefront", v.ifBlank { "in" })

    /** a cf_clearance cookie pasted by hand */
    var manualCookie: String
        get() = str("cookie")
        set(v) = put("cookie", v)

    /** saved cookies from the Cloudflare WebView flow */
    var savedCookies: String
        get() = str("saved_cookies")
        set(v) = put("saved_cookies", v)

    /** optional extension password */
    var password: String
        get() = str("password")
        set(v) = put("password", v)

    fun clearCookies() {
        runCatching {
            val cm = CookieManager.getInstance()
            listOf(SITE, "https://ak1ra.de5.net", "https://de5.net").forEach {
                cm.setCookie(it, "cf_clearance=; Max-Age=0")
            }
            cm.flush()
        }
        savedCookies = ""
        manualCookie = ""
    }

    fun userAgent(): String {
        uaCache?.let { return it }
        val base = runCatching { appContext?.let { WebSettings.getDefaultUserAgent(it) } }.getOrNull() ?: UA_FALLBACK
        val clean = base.replace("; wv)", ")").replace(" Version/4.0", "")
        uaCache = clean
        return clean
    }

    fun cookieHeader(): String {
        val jar = LinkedHashMap<String, String>()
        fun add(raw: String?) {
            raw?.split(";")?.map { it.trim() }?.filter { it.contains("=") }?.forEach {
                jar.putIfAbsent(it.substringBefore("="), it.substringAfter("="))
            }
        }
        add(manualCookie)
        add(savedCookies)
        runCatching {
            val cm = CookieManager.getInstance()
            add(cm.getCookie(SITE))
            add(cm.getCookie("https://ak1ra.de5.net"))
            add(cm.getCookie("https://de5.net"))
        }
        return jar.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun headers(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["User-Agent"] = userAgent()
        h["Accept"] = "application/json"
        h["Origin"] = SITE
        h["Referer"] = "$SITE/"
        val c = cookieHeader()
        if (c.isNotBlank()) h["Cookie"] = c
        h.putAll(extra)
        return h
    }

    // ------------------------------------------------------------------ log

    private const val MAX = 300
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun log(tag: String, message: String) {
        lines.addLast("${fmt.format(Date())} $tag: $message")
        while (lines.size > MAX) lines.removeFirst()
    }

    @Synchronized
    fun logDump(): String = if (lines.isEmpty()) "nothing logged yet" else lines.joinToString("\n")

    @Synchronized
    fun logClear() {
        lines.clear()
        log("step", "log cleared")
    }

    // ------------------------------------------------------------------ requests

    /**
     * JSON from the site. Plain http first; when Cloudflare refuses that client - which it does on
     * this site, the capture shows a bot challenge on the very first request - the same url is
     * fetched by navigating a WebView to it, which is the browser itself.
     */
    suspend fun json(path: String, allowPage: Boolean = true): JSONObject? {
        val url = if (path.startsWith("http")) path else "$SITE$path"
        val short = path.substringBefore("?").takeLast(46)
        val t0 = System.currentTimeMillis()
        runCatching {
            val r = app.get(url, headers = headers(), timeout = 20L)
            log("net", "http $short -> ${r.code} (${System.currentTimeMillis() - t0}ms)")
            if (r.code in 200..299) return runCatching { JSONObject(r.text) }.getOrNull()
        }.onFailure { log("WARN", "http $short threw ${it.message}") }

        if (!allowPage) return null
        val body = pageText(url) ?: return null
        return runCatching { JSONObject(body) }.onFailure { log("WARN", "page $short was not json") }.getOrNull()
    }

    /** reads a url by navigating a WebView to it and taking the page text */
    suspend fun pageText(url: String): String? = withContext(Dispatchers.Main) {
        val ctx = appContext ?: return@withContext null
        val done = CompletableDeferred<String?>()
        val wv = WebView(ctx)
        try {
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.userAgentString = userAgent()
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, u: String?) {
                    view?.evaluateJavascript("document.body.innerText") { raw ->
                        val text = (raw ?: "").removeSurrounding("\"").replace("\\\"", "\"").replace("\\n", " ")
                        if (!done.isCompleted) done.complete(text)
                    }
                }
            }
            log("net", "page ${url.substringBefore("?").takeLast(46)}")
            wv.loadUrl(url)
            val out = withTimeoutOrNull(25_000L) { done.await() }
            log("net", "page answer ${out?.length ?: 0} chars")
            out?.takeIf { it.trimStart().startsWith("{") || it.trimStart().startsWith("[") }
        } catch (e: Throwable) {
            log("WARN", "page threw ${e.message}")
            null
        } finally {
            runCatching { wv.stopLoading(); wv.destroy() }
        }
    }

    /** plain text, used for the HLS master playlist */
    suspend fun text(url: String): String? {
        val short = url.substringAfterLast("/").take(40)
        return runCatching {
            val r = app.get(url, headers = headers(mapOf("Accept" to "*/*")), timeout = 20L)
            log("net", "text $short -> ${r.code}")
            if (r.code in 200..299) r.text else null
        }.getOrNull()
    }

    fun report(): String = buildString {
        append("Apply Music build: $BUILD\n")
        append("storefront: ${storefront}\n")
        append("cookie: ${if (cookieHeader().contains("cf_clearance")) "cf_clearance present" else if (cookieHeader().isNotBlank()) "present, no cf_clearance" else "none"}\n")
        append("user agent: ${userAgent().take(110)}")
    }
}
