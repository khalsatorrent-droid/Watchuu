package com.watchuu.nartodrama

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.Window
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * narto-drama.com sits behind Cloudflare ("Just a moment..." / Turnstile) and its own JS cookie
 * gate ("Checking your browser...", sets nd_ck and reloads). Same design as KiligDrama.
 *
 * Ways through, chosen in the extension settings (see [NartoSettings.Mode]):
 *  1. plain HTTP with the saved cf_clearance cookie + the exact same user agent (fast path, always tried first)
 *  2. a hidden WebView that loads the site and runs fetch() from inside the page
 *  3. Cloudstream's own CloudflareKiller
 *  4. a VISIBLE solver window: the real site in a full-screen WebView, the user ticks the
 *     Turnstile box once, it closes by itself as soon as the cf_clearance cookie appears
 *  5. a manual cookie pasted in the settings
 */
object NartoBrowser {
    private const val IDLE_CLOSE_MS = 3 * 60_000L
    private const val FALLBACK_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private var appContext: Context? = null
    private var webView: WebView? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Pair<Int, String>>>()
    private val lock = Mutex()
    private val solveLock = Mutex()
    private val main = Handler(Looper.getMainLooper())
    private val killer by lazy { CloudflareKiller() }

    @Volatile
    private var lastUse = 0L

    @Volatile
    private var defaultUa: String? = null

    /** Step-by-step notes of the last get() (shown when links fail / in the test dialog). */
    @Volatile
    var lastTrace = ""
        private set

    /** Which way the last successful request went (shown by "Test connection"). */
    @Volatile
    var lastMethod = "-"
        private set

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
        NartoSettings.init(context)
    }

    private fun site() = NartoSettings.siteUrl
    private fun home() = site() + "/?lang=en-US"

    // ------------------------------------------------------------------ user agent + cookies

    /** One user agent for everything - cf_clearance is bound to it. */
    fun userAgent(): String {
        val custom = NartoSettings.customUa
        var ua = custom.ifBlank {
            defaultUa ?: (try {
                appContext?.let { WebSettings.getDefaultUserAgent(it) }
            } catch (_: Throwable) {
                null
            } ?: FALLBACK_UA).also { defaultUa = it }
        }
        if (custom.isBlank() && NartoSettings.stripWv) {
            ua = ua.replace("; wv)", ")").replace(" Version/4.0", "")
        }
        return ua
    }

    private fun ensureGateCookie() {
        try {
            val cm = CookieManager.getInstance()
            if (cm.getCookie(site())?.contains("nd_ck=") != true) {
                val v = (1..18).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
                cm.setCookie(site(), "nd_ck=$v; path=/; max-age=7200")
                cm.flush()
            }
        } catch (_: Throwable) {
        }
    }

    private fun applyManualCookie() {
        ensureGateCookie()
        val raw = NartoSettings.manualCookie
        if (raw.isBlank()) return
        try {
            val cm = CookieManager.getInstance()
            raw.split(";").map { it.trim() }.filter { it.contains("=") }.forEach {
                cm.setCookie(site(), "$it; path=/; secure")
            }
            cm.flush()
        } catch (_: Throwable) {
        }
    }

    fun cookies(): String? {
        applyManualCookie()
        val fromWv = try {
            CookieManager.getInstance().getCookie(site())
        } catch (_: Throwable) {
            null
        }
        val manual = NartoSettings.manualCookie
        return listOfNotNull(fromWv?.ifBlank { null }, manual.ifBlank { null }).joinToString("; ").ifBlank { null }
    }

    fun hasClearance(): Boolean = cookies()?.contains("cf_clearance") == true

    fun isChallenge(code: Int, body: String): Boolean {
        val head = body.take(4000)
        return code == 403 || code == 503 || code == 429 ||
            head.contains("<title>Just a moment", true) ||
            head.contains("Performing security verification", true) ||
            head.contains("Checking your browser", true) ||
            (head.contains("challenge-platform") && head.contains("cf_chl", true))
    }

    // ------------------------------------------------------------------ main entry

    /** GET a narto-drama url as text, going through Cloudflare the way the settings say. */
    suspend fun get(url: String, referer: String): String {
        val mode = NartoSettings.mode
        val trace = StringBuilder()
        fun done(method: String, body: String): String {
            lastMethod = method
            trace.append("-> ok via ").append(method)
            lastTrace = trace.toString()
            return body
        }
        direct(url, referer, trace)?.let { return done("cookie (fast)", it) }

        when (mode) {
            NartoSettings.Mode.COOKIE -> {}
            NartoSettings.Mode.CLOUDSTREAM -> viaKiller(url, referer, trace)?.let { return done("Cloudstream bypass", it) }
            NartoSettings.Mode.AUTO, NartoSettings.Mode.HIDDEN ->
                viaHidden(url, trace)?.let { return done("hidden browser", it) }

            NartoSettings.Mode.SOLVER -> {}
        }

        val prompt = mode == NartoSettings.Mode.SOLVER ||
            (NartoSettings.autoPrompt && (mode == NartoSettings.Mode.AUTO || mode == NartoSettings.Mode.CLOUDSTREAM))
        if (prompt) {
            trace.append("solver window; ")
            if (solveInteractive(null)) {
                direct(url, referer, trace)?.let { return done("solver + cookie", it) }
                viaHidden(url, trace)?.let { return done("solver + hidden browser", it) }
            } else trace.append("not solved; ")
        }
        lastTrace = trace.toString()
        throw ErrorLoadingException(
            if (mode == NartoSettings.Mode.COOKIE)
                "NartoDrama: the manual cookie was rejected. Paste a fresh cf_clearance + matching user agent in the extension settings."
            else "NartoDrama: Cloudflare not passed. Open Extensions > NartoDrama > settings and tap \"Solve Cloudflare now\"."
        )
    }

    private suspend fun direct(url: String, referer: String, trace: StringBuilder): String? {
        val cookie = cookies()
        return try {
            val h = HashMap<String, String>()
            h["User-Agent"] = userAgent()
            h["Referer"] = referer
            h["Accept-Language"] = "en-US,en;q=0.9"
            if (url.contains("/home/providers/") || url.contains("/extras") || url.contains("/e/rs/")) {
                // look like the player's own fetch()
                h["Accept"] = "*/*"
                h["Sec-Fetch-Site"] = "same-origin"
                h["Sec-Fetch-Mode"] = "cors"
                h["Sec-Fetch-Dest"] = "empty"
                h["X-Requested-With"] = "XMLHttpRequest"
            } else {
                h["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            }
            if (!cookie.isNullOrBlank()) h["Cookie"] = cookie
            val r = app.get(url, headers = h, timeout = 20L)
            val body = r.text
            if (r.code in 200..299 && !isChallenge(r.code, body)) body
            else {
                trace.append("direct HTTP ").append(r.code).append(if (isChallenge(r.code, body)) " (challenge)" else "").append("; ")
                null
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            trace.append("direct error ").append(e.javaClass.simpleName).append("; ")
            null
        }
    }

    private suspend fun viaKiller(url: String, referer: String, trace: StringBuilder): String? = try {
        val r = app.get(url, headers = mapOf("Referer" to referer), interceptor = killer, timeout = 60L)
        val body = r.text
        if (r.code in 200..299 && !isChallenge(r.code, body)) body
        else {
            trace.append("cloudstream HTTP ").append(r.code).append("; "); null
        }
    } catch (e: Throwable) {
        if (e is CancellationException) throw e
        trace.append("cloudstream error ").append(e.javaClass.simpleName).append("; ")
        null
    }

    private suspend fun viaHidden(url: String, trace: StringBuilder): String? {
        val r = fetch(url)
        if (r == null) {
            trace.append("hidden browser did not pass in ").append(NartoSettings.timeoutSec).append("s; ")
            return null
        }
        if (r.first in 200..299 && !isChallenge(r.first, r.second)) return r.second
        trace.append("hidden fetch HTTP ").append(r.first).append("; ")
        return null
    }

    /** For the settings "Test connection" button. */
    suspend fun test(): String {
        val start = System.currentTimeMillis()
        return try {
            val body = get("${site()}/search?lang=en-US&q=love", home())
            val cards = Regex(""""url":"https://[^"]*/detail/watch/""").findAll(body).count()
            "OK via $lastMethod in ${(System.currentTimeMillis() - start) / 1000}s\n$cards dramas found when searching \"love\".\n\nCookie saved: ${hasClearance()}\nUser agent:\n${userAgent()}"
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            "FAILED: ${e.message}\n\nMode: ${NartoSettings.mode.label}\nCookie saved: ${hasClearance()}\nUser agent:\n${userAgent()}\n\nTry \"Solve Cloudflare now\", or switch mode."
        }
    }

    suspend fun reset(clearCookies: Boolean, uaChanged: Boolean = false) {
        withContext(Dispatchers.Main) {
            destroyHidden()
            if (clearCookies || uaChanged) {
                try {
                    val cm = CookieManager.getInstance()
                    // drop only this site's cookies
                    cm.getCookie(site())?.split(";")?.map { it.substringBefore("=").trim() }?.forEach { name ->
                        cm.setCookie(site(), "$name=; Max-Age=0; path=/")
                        cm.setCookie(site(), "$name=; Max-Age=0; path=/; domain=.${site().substringAfter("://")}")
                    }
                    cm.flush()
                } catch (_: Throwable) {
                }
            }
        }
        lastMethod = "-"
    }

    // ------------------------------------------------------------------ shared WebView setup

    private fun isNeeded(url: String): Boolean {
        val l = url.lowercase()
        val host = l.substringAfter("://").substringBefore("/").substringBefore(":")
        if (host.endsWith("cloudflare.com") || host.endsWith("cloudflareinsights.com")) return true
        val siteHost = site().substringAfter("://").substringBefore("/").lowercase()
        if (host != siteHost && !host.endsWith(".$siteHost")) return !NartoSettings.lightweight && !isMedia(l)
        val path = l.substringBefore("?")
        if (path.contains("/presence/") || path.contains("/wrapper-guard/")) return false
        if (NartoSettings.lightweight && (isImage(path) || path.contains("/_next/image"))) return false
        return !isMedia(path)
    }

    private fun isImage(p: String) = listOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".svg", ".avif").any { p.endsWith(it) }
    private fun isMedia(p: String): Boolean {
        val path = p.substringBefore("?")
        return listOf(".mp4", ".m3u8", ".ts", ".m4s", ".webm").any { path.endsWith(it) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(wv: WebView, light: Boolean) {
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = true
            loadsImagesAutomatically = !light
            blockNetworkImage = light
            userAgentString = userAgent()
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
    }

    // the app package name in X-Requested-With gives a WebView away
    private fun loadSite(wv: WebView) = wv.loadUrl(home(), mapOf("X-Requested-With" to ""))

    // ------------------------------------------------------------------ hidden browser

    private class Bridge {
        @JavascriptInterface
        fun onFetched(id: String, status: Int, body: String) {
            pending.remove(id)?.complete(Pair(status, body))
        }
    }

    private fun createHidden(ctx: Context): WebView {
        applyManualCookie()
        val wv = WebView(ctx)
        configure(wv, NartoSettings.lightweight)
        // give it a real size - some challenges don't run in a 0x0 view
        val dm = ctx.resources.displayMetrics
        wv.layout(0, 0, dm.widthPixels.coerceAtLeast(360), dm.heightPixels.coerceAtLeast(640))
        wv.addJavascriptInterface(Bridge(), "ndBridge")
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val u = request?.url?.toString() ?: return null
                if (isNeeded(u)) return null
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
        }
        loadSite(wv)
        return wv
    }

    private fun destroyHidden() {
        val wv = webView ?: return
        webView = null
        try {
            wv.stopLoading()
            wv.loadUrl("about:blank")
            wv.removeJavascriptInterface("ndBridge")
            wv.destroy()
        } catch (_: Throwable) {
        }
    }

    private suspend fun eval(wv: WebView?, js: String): String? = withContext(Dispatchers.Main) {
        if (wv == null) return@withContext null
        val d = CompletableDeferred<String?>()
        try {
            wv.evaluateJavascript(js) { d.complete(it) }
        } catch (_: Throwable) {
            d.complete(null)
        }
        withTimeoutOrNull(5_000L) { d.await() }
    }

    private const val READY_JS =
        "(function(){try{return !/just a moment|attention required|security verification|un instant|einen moment/i.test(document.title)&&" +
            "document.readyState!=='loading'&&!document.querySelector('#challenge-form,#cf-challenge-running,#challenge-stage')&&" +
            "!/Checking your browser/i.test(document.body?document.body.innerText.slice(0,300):'')&&" +
            "!!document.querySelector('meta[name=\"csrf-token\"],a[href*=\"/detail/watch/\"]')}catch(e){return false}})()"

    private suspend fun pageReady(wv: WebView?): Boolean = eval(wv, READY_JS) == "true"

    /** Opens (or reuses) the hidden page and waits until Cloudflare lets it through. */
    suspend fun ensure(timeoutMs: Long = NartoSettings.timeoutSec * 1000L): Boolean = lock.withLock {
        val ctx = appContext ?: return@withLock false
        lastUse = System.currentTimeMillis()
        if (webView == null) {
            withContext(Dispatchers.Main) { webView = createHidden(ctx) }
            scheduleIdleClose()
        }
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (pageReady(webView)) {
                try {
                    CookieManager.getInstance().flush()
                } catch (_: Throwable) {
                }
                return@withLock true
            }
            delay(700L)
        }
        // stuck on the challenge: drop it so the next try starts clean
        withContext(Dispatchers.Main) { destroyHidden() }
        false
    }

    /** fetch() from inside the (already cleared) page. Returns status + body, or null. */
    suspend fun fetch(url: String, timeoutMs: Long = 25_000L): Pair<Int, String>? {
        if (!ensure()) return null
        lastUse = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val d = CompletableDeferred<Pair<Int, String>>()
        pending[id] = d
        val js = "(function(){var id=" + JSONObject.quote(id) + ";fetch(" + JSONObject.quote(url) +
            ",{credentials:'include'}).then(function(r){return r.text().then(function(t){ndBridge.onFetched(id,r.status,t)})})" +
            ".catch(function(e){ndBridge.onFetched(id,0,String(e))})})()"
        if (eval(webView, js) == null && webView == null) {
            pending.remove(id)
            return null
        }
        val r = withTimeoutOrNull(timeoutMs) { d.await() }
        pending.remove(id)
        return r
    }

    private fun scheduleIdleClose() {
        main.postDelayed(object : Runnable {
            override fun run() {
                if (webView == null) return
                if (System.currentTimeMillis() - lastUse >= IDLE_CLOSE_MS && pending.isEmpty()) destroyHidden()
                else main.postDelayed(this, 30_000L)
            }
        }, 30_000L)
    }

    // ------------------------------------------------------------------ episode sniffer (last resort)

    /** video = what the <video> element plays (most reliable), media = requests seen (may include next-episode preload). */
    class Hit(val url: String)

    class Sniffed(val apiBodies: List<String>, val video: List<String>, val media: List<String>)

    private class SniffBridge(val api: MutableList<String>, val media: MutableList<String>) {
        @JavascriptInterface
        fun onApi(body: String) {
            api.add(body)
        }

        @JavascriptInterface
        fun onMedia(url: String) {
            if (url.startsWith("http") && !media.contains(url)) media.add(url)
        }
    }

    private const val SNIFF_HOOK = "(function(){if(window.__ndHook)return;window.__ndHook=1;try{var of=window.fetch;" +
        "window.fetch=function(i,o){var u=(typeof i==='string')?i:(i&&i.url)||'';var p=of.apply(this,arguments);" +
        "if(/\\/(api|e)\\/[^?]*(source|play|stream|video)/i.test(String(u))){p.then(function(r){r.clone().text().then(function(t){try{ndSniff.onApi(t)}catch(e){}})}).catch(function(){})}" +
        "return p};}catch(e){}})()"

    private const val SNIFF_POKE = "(function(){try{var v=document.querySelector('video');if(v){var s=v.currentSrc||v.src;" +
        "if(s&&s.indexOf('http')==0)ndSniff.onMedia(s);try{v.muted=true;v.play()}catch(e){}}" +
        "var b=document.querySelector('button[aria-label=\"Play\"],button[aria-label*=\"play\" i],[data-play],.play-button');if(b)b.click();}catch(e){}})()"

    private fun isMediaUrl(u: String): Boolean {
        val p = u.lowercase().substringBefore("?")
        if (u.lowercase().contains(".m3u8") || p.endsWith(".mp4") || p.endsWith(".mpd") || p.endsWith(".m4v") || p.endsWith(".webm")) return true
        return isStreamHost(u)
    }

    /** The site's own video hosts have no file extension: dramabox-stream.narto-drama.com/{token}. */
    fun isStreamHost(u: String): Boolean {
        val host = u.lowercase().substringAfter("://").substringBefore("/").substringBefore(":")
        val path = u.substringAfter("://").substringAfter("/", "")
        return host.contains("stream") && path.length > 30
    }

    /** Opens the episode page like a viewer would and collects what the site's player loads. */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun sniff(episodeUrl: String, timeoutMs: Long = 30_000L): Sniffed {
        val ctx = appContext ?: return Sniffed(emptyList(), emptyList(), emptyList())
        val api = java.util.Collections.synchronizedList(ArrayList<String>())
        val media = java.util.Collections.synchronizedList(ArrayList<String>())
        val video = java.util.Collections.synchronizedList(ArrayList<String>())
        var wv: WebView? = null
        withContext(Dispatchers.Main) {
            applyManualCookie()
            val w = WebView(ctx)
            configure(w, light = false)
            w.settings.mediaPlaybackRequiresUserGesture = false
            val dm = ctx.resources.displayMetrics
            w.layout(0, 0, dm.widthPixels.coerceAtLeast(360), dm.heightPixels.coerceAtLeast(640))
            w.addJavascriptInterface(SniffBridge(api, video), "ndSniff")
            w.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    view?.evaluateJavascript(SNIFF_HOOK, null)
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val u = request?.url?.toString() ?: return null
                    if (isMediaUrl(u) && !media.contains(u)) media.add(u)
                    val p = u.lowercase().substringBefore("?")
                    // never download actual video data in the background
                    if (p.endsWith(".ts") || p.endsWith(".m4s") || p.endsWith(".mp4") || p.endsWith(".m4v") || isStreamHost(u) ||
                        p.contains("/presence/") || p.contains("/wrapper-guard/")) {
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }
                    return null
                }
            }
            w.webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (newProgress in 5..90) view?.evaluateJavascript(SNIFF_HOOK, null)
                }
            }
            w.loadUrl(episodeUrl, mapOf("X-Requested-With" to ""))
            wv = w
        }
        try {
            val end = System.currentTimeMillis() + timeoutMs
            var gotAt = 0L
            while (System.currentTimeMillis() < end) {
                delay(700L)
                eval(wv, SNIFF_HOOK)
                eval(wv, SNIFF_POKE)
                if (api.isNotEmpty() || video.isNotEmpty()) break
                if (media.isNotEmpty()) {
                    // give the player a few seconds to attach the real source to <video>
                    if (gotAt == 0L) gotAt = System.currentTimeMillis()
                    if (System.currentTimeMillis() - gotAt > 6_000L) break
                }
            }
        } finally {
            withContext(Dispatchers.Main) {
                try {
                    wv?.stopLoading(); wv?.loadUrl("about:blank"); wv?.removeJavascriptInterface("ndSniff"); wv?.destroy()
                } catch (_: Throwable) {
                }
            }
        }
        return Sniffed(api.toList(), video.toList(), media.toList())
    }

    // ------------------------------------------------------------------ visible solver window

    @Volatile
    private var solving: CompletableDeferred<Boolean>? = null

    @Volatile
    private var lastSolvedAt = 0L

    /**
     * Shows the real site full-screen so the user can tick the Cloudflare box.
     * Closes by itself once the page is through and a cf_clearance cookie exists.
     * Concurrent callers share one window.
     */
    suspend fun solveInteractive(activity: Activity?): Boolean {
        solving?.let { return it.await() }
        val result = solveLock.withLock {
            solving?.let { return@withLock it.await() }
            // someone else just solved it while we waited for the lock
            if (System.currentTimeMillis() - lastSolvedAt < 15_000L) return@withLock true
            val act = activity ?: NartoSettings.currentActivity() ?: return@withLock false
            val d = CompletableDeferred<Boolean>()
            solving = d
            withContext(Dispatchers.Main) { openSolver(act, d) }
            val ok = withTimeoutOrNull(5 * 60_000L) { d.await() } ?: false
            solving = null
            ok
        }
        if (result) {
            lastSolvedAt = System.currentTimeMillis()
            withContext(Dispatchers.Main) { destroyHidden() } // restart it with the new cookie
            lastMethod = "-"
            NartoSettings.solvedUa = userAgent()
        }
        return result
    }

    @SuppressLint("SetTextI18n")
    private fun openSolver(act: Activity, done: CompletableDeferred<Boolean>) {
        if (act.isFinishing) {
            done.complete(false); return
        }
        applyManualCookie()
        val density = act.resources.displayMetrics.density
        val dialog = Dialog(act, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val bar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            val p = (10 * density).toInt()
            setPadding(p, p, p, p)
            setBackgroundColor(Color.rgb(30, 30, 30))
        }
        val info = TextView(act).apply {
            text = "NartoDrama: tick \"Verify you are human\" below (if shown). This window closes by itself when done."
            setTextColor(Color.WHITE)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val close = Button(act).apply {
            text = "Close"
            isAllCaps = false
        }
        bar.addView(info)
        bar.addView(close)

        val wv = WebView(act)
        configure(wv, light = false)
        wv.webChromeClient = WebChromeClient()
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
        }
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(wv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        dialog.setContentView(root)

        var finished = false
        fun finish(ok: Boolean) {
            if (finished) return
            finished = true
            try {
                CookieManager.getInstance().flush()
            } catch (_: Throwable) {
            }
            try {
                wv.stopLoading(); wv.destroy()
            } catch (_: Throwable) {
            }
            try {
                if (dialog.isShowing) dialog.dismiss()
            } catch (_: Throwable) {
            }
            done.complete(ok)
            NartoSettings.toast(if (ok) "NartoDrama: Cloudflare passed" else "NartoDrama: Cloudflare not solved")
        }

        close.setOnClickListener { finish(hasClearance()) }
        dialog.setOnDismissListener { finish(hasClearance()) }

        var readyCount = 0
        val poll = object : Runnable {
            override fun run() {
                if (finished) return
                try {
                    wv.evaluateJavascript(READY_JS) { r ->
                        readyCount = if (r == "true") readyCount + 1 else 0
                        // through: cookie issued, or the site simply isn't challenging right now
                        if (r == "true" && (hasClearance() || readyCount >= 4)) {
                            info.text = "Passed - closing..."
                            main.postDelayed({ finish(true) }, 800L)
                        } else if (!finished) main.postDelayed(this, 1000L)
                    }
                } catch (_: Throwable) {
                    if (!finished) main.postDelayed(this, 1000L)
                }
            }
        }
        try {
            dialog.show()
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            loadSite(wv)
            main.postDelayed(poll, 2000L)
        } catch (_: Throwable) {
            finish(false)
        }
    }
}
