package com.watchuu.vivarium

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Base64
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
import com.lagradost.cloudstream3.app
import java.io.ByteArrayInputStream
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
 * vivarium.su sits behind Cloudflare ("Checking your browser..." / Turnstile) and signs its own
 * API requests in JavaScript. The extension never signs anything itself: it loads the real site in
 * a WebView so the site's own JS passes Cloudflare, signs the calls and decrypts the stream list,
 * then captures the HLS manifest the player loads.
 *
 * Ways through, chosen in the extension settings (see [VivariumSettings.Mode]):
 *  1. a hidden WebView that loads the site and passes non-interactive checks on its own
 *  2. Cloudstream is not used for the signed API, so the browser is the only path
 *  3. a VISIBLE solver window: the real site full-screen, the user ticks the Turnstile box once,
 *     it closes by itself once the cf_clearance cookie appears
 *  4. a manual cf_clearance cookie pasted in the settings
 */
object VivariumBrowser {
    private const val IDLE_CLOSE_MS = 3 * 60_000L
    private const val FALLBACK_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private var appContext: Context? = null
    private var webView: WebView? = null
    private val lock = Mutex()
    private val solveLock = Mutex()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var lastUse = 0L
    @Volatile private var defaultUa: String? = null

    @Volatile var lastTrace = ""
        private set
    @Volatile var lastMethod = "-"
        private set

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
        VivariumSettings.init(context)
    }

    private fun site() = VivariumSettings.siteUrl
    private fun home() = site() + "/"

    // ------------------------------------------------------------------ user agent + cookies

    fun userAgent(): String {
        val custom = VivariumSettings.customUa
        var ua = custom.ifBlank {
            defaultUa ?: (try {
                appContext?.let { WebSettings.getDefaultUserAgent(it) }
            } catch (_: Throwable) {
                null
            } ?: FALLBACK_UA).also { defaultUa = it }
        }
        if (custom.isBlank() && VivariumSettings.stripWv) {
            ua = ua.replace("; wv)", ")").replace(" Version/4.0", "")
        }
        return ua
    }

    private fun applyManualCookie() {
        val raw = VivariumSettings.manualCookie
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
        val manual = VivariumSettings.manualCookie
        return listOfNotNull(fromWv?.ifBlank { null }, manual.ifBlank { null }).joinToString("; ").ifBlank { null }
    }

    fun hasClearance(): Boolean = cookies()?.contains("cf_clearance") == true

    private fun isChallengeTitle(title: String): Boolean =
        Regex("just a moment|attention required|security verification|checking your browser|un instant|einen moment", RegexOption.IGNORE_CASE)
            .containsMatchIn(title)

    // ------------------------------------------------------------------ shared WebView setup

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

    private fun loadSite(wv: WebView) = wv.loadUrl(home(), mapOf("X-Requested-With" to ""))

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
        "(function(){try{return !/just a moment|attention required|security verification|checking your browser|un instant|einen moment/i.test(document.title)&&" +
            "document.readyState!=='loading'&&!document.querySelector('#challenge-form,#cf-challenge-running,#challenge-stage')}catch(e){return false}})()"

    private suspend fun pageReady(wv: WebView?): Boolean = eval(wv, READY_JS) == "true"

    // ------------------------------------------------------------------ hidden browser (CF pass)

    private fun createHidden(ctx: Context): WebView {
        applyManualCookie()
        val wv = WebView(ctx)
        configure(wv, VivariumSettings.lightweight)
        val dm = ctx.resources.displayMetrics
        wv.layout(0, 0, dm.widthPixels.coerceAtLeast(360), dm.heightPixels.coerceAtLeast(640))
        wv.webViewClient = WebViewClient()
        loadSite(wv)
        return wv
    }

    private fun destroyHidden() {
        val wv = webView ?: return
        webView = null
        try {
            wv.stopLoading()
            wv.loadUrl("about:blank")
            wv.destroy()
        } catch (_: Throwable) {
        }
    }

    /** Opens (or reuses) the hidden page and waits until Cloudflare lets it through. */
    suspend fun ensure(timeoutMs: Long = VivariumSettings.timeoutSec * 1000L): Boolean = lock.withLock {
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
        withContext(Dispatchers.Main) { destroyHidden() }
        false
    }

    private fun scheduleIdleClose() {
        main.postDelayed(object : Runnable {
            override fun run() {
                if (webView == null) return
                if (System.currentTimeMillis() - lastUse >= IDLE_CLOSE_MS) destroyHidden()
                else main.postDelayed(this, 30_000L)
            }
        }, 30_000L)
    }

    suspend fun reset(clearCookies: Boolean, uaChanged: Boolean = false) {
        withContext(Dispatchers.Main) {
            destroyHidden()
            if (clearCookies || uaChanged) {
                try {
                    val cm = CookieManager.getInstance()
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

    /** Make sure Cloudflare is passed before a sniff: hidden browser, then (if allowed) the solver window. */
    private suspend fun prepare(trace: StringBuilder): Boolean {
        val mode = VivariumSettings.mode
        if (mode == VivariumSettings.Mode.COOKIE) {
            if (hasClearance()) { trace.append("manual cookie; "); return true }
            trace.append("no manual cookie; "); return false
        }
        if (ensure()) { trace.append("hidden browser passed; "); return true }
        trace.append("hidden browser did not pass in ").append(VivariumSettings.timeoutSec).append("s; ")
        val prompt = mode == VivariumSettings.Mode.SOLVER ||
            (VivariumSettings.autoPrompt && mode == VivariumSettings.Mode.AUTO)
        if (prompt) {
            trace.append("solver window; ")
            if (solveInteractive(null)) { trace.append("solved; "); return true }
            trace.append("not solved; ")
        }
        return hasClearance()
    }

    // ------------------------------------------------------------------ player sniffer

    class Sub(val url: String, val lang: String)
    class Sniffed(val media: List<String>, val subs: List<Sub>)

    private class SniffBridge(val media: MutableList<String>) {
        @JavascriptInterface
        fun onMedia(url: String) {
            if (url.startsWith("http") && !media.contains(url)) media.add(url)
        }
    }

    private const val SNIFF_POKE =
        "(function(){try{var v=document.querySelector('video');if(v){var s=v.currentSrc||v.src;" +
            "if(s&&s.indexOf('http')==0)vvSniff.onMedia(s);try{v.muted=true;v.play()}catch(e){}}" +
            "var b=document.querySelector('button[aria-label*=\"play\" i],[data-play],.play-button,.vjs-big-play-button');if(b)b.click();}catch(e){}})()"

    private fun isStreamHost(u: String): Boolean {
        val host = u.lowercase().substringAfter("://").substringBefore("/").substringBefore(":")
        return host.endsWith("itsmeowenthankyouforyourservice.online")
    }

    private fun isMediaUrl(u: String): Boolean {
        val p = u.lowercase().substringBefore("?")
        if (u.lowercase().contains(".m3u8") || p.endsWith(".mpd")) return true
        return isStreamHost(u) && (u.contains("/api/cs/") || p.endsWith(".m3u8"))
    }

    private fun isSegment(u: String): Boolean {
        val p = u.lowercase().substringBefore("?")
        return p.endsWith(".ts") || p.endsWith(".m4s") || p.endsWith(".mp4") || p.endsWith(".m4v") ||
            (isStreamHost(u) && p.contains("/seg"))
    }

    private fun subLang(u: String): String {
        // /api/os?dl=<base64 of {"s":"o","l":"en","i":"..."}>
        val dl = Regex("""[?&]dl=([^&]+)""").find(u)?.groupValues?.get(1) ?: return "Subtitle"
        return try {
            val json = String(Base64.decode(dl, Base64.URL_SAFE or Base64.NO_PADDING))
            Regex(""""l"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)?.uppercase() ?: "Subtitle"
        } catch (_: Throwable) {
            "Subtitle"
        }
    }

    private fun isSubUrl(u: String): Boolean {
        val p = u.lowercase().substringBefore("?")
        return p.endsWith(".vtt") || p.endsWith(".srt") || u.contains("/api/os?dl=")
    }

    /** Opens the player page like a viewer would (after passing Cloudflare) and collects the HLS it loads. */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun sniffPlayer(playerUrl: String, timeoutMs: Long = VivariumSettings.timeoutSec * 1000L): Sniffed {
        val ctx = appContext ?: return Sniffed(emptyList(), emptyList())
        val trace = StringBuilder()
        prepare(trace)
        lastMethod = if (hasClearance()) "browser + cookie" else "browser"

        val media = java.util.Collections.synchronizedList(ArrayList<String>())
        val subs = java.util.Collections.synchronizedList(ArrayList<Sub>())
        var wv: WebView? = null
        withContext(Dispatchers.Main) {
            applyManualCookie()
            val w = WebView(ctx)
            configure(w, light = false)
            w.settings.mediaPlaybackRequiresUserGesture = false
            val dm = ctx.resources.displayMetrics
            w.layout(0, 0, dm.widthPixels.coerceAtLeast(360), dm.heightPixels.coerceAtLeast(640))
            w.addJavascriptInterface(SniffBridge(media), "vvSniff")
            w.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val u = request?.url?.toString() ?: return null
                    if (isMediaUrl(u) && !media.contains(u)) media.add(u)
                    if (isSubUrl(u) && subs.none { it.url == u }) subs.add(Sub(u, subLang(u)))
                    // never pull actual video bytes in the background
                    if (isSegment(u)) {
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }
                    return null
                }
            }
            w.webChromeClient = WebChromeClient()
            w.loadUrl(playerUrl, mapOf("X-Requested-With" to ""))
            wv = w
        }
        try {
            val end = System.currentTimeMillis() + timeoutMs
            var gotAt = 0L
            while (System.currentTimeMillis() < end) {
                delay(800L)
                eval(wv, SNIFF_POKE)
                if (media.isNotEmpty()) {
                    // give the player a moment to expose a master playlist over a single-quality one
                    if (gotAt == 0L) gotAt = System.currentTimeMillis()
                    if (media.any { it.contains("master", true) } || System.currentTimeMillis() - gotAt > 5_000L) break
                }
            }
        } finally {
            withContext(Dispatchers.Main) {
                try {
                    wv?.stopLoading(); wv?.loadUrl("about:blank"); wv?.removeJavascriptInterface("vvSniff"); wv?.destroy()
                } catch (_: Throwable) {
                }
            }
        }
        trace.append("media=").append(media.size).append(", subs=").append(subs.size)
        lastTrace = trace.toString()
        return Sniffed(media.toList(), subs.toList())
    }

    /** For the settings "Test connection" button: can we reach the site past Cloudflare? */
    suspend fun test(): String {
        val start = System.currentTimeMillis()
        val trace = StringBuilder()
        val ok = prepare(trace)
        val secs = (System.currentTimeMillis() - start) / 1000
        return if (ok)
            "OK in ${secs}s\nCloudflare passed: ${hasClearance()}\n$trace\n\nUser agent:\n${userAgent()}"
        else
            "Could not pass Cloudflare in ${secs}s.\n$trace\n\nTry \"Solve Cloudflare now\", or paste a cf_clearance cookie.\n\nUser agent:\n${userAgent()}"
    }

    // ------------------------------------------------------------------ visible solver window

    @Volatile private var solving: CompletableDeferred<Boolean>? = null
    @Volatile private var lastSolvedAt = 0L

    suspend fun solveInteractive(activity: Activity?): Boolean {
        solving?.let { return it.await() }
        val result = solveLock.withLock {
            solving?.let { return@withLock it.await() }
            if (System.currentTimeMillis() - lastSolvedAt < 15_000L) return@withLock true
            val act = activity ?: VivariumSettings.currentActivity() ?: return@withLock false
            val d = CompletableDeferred<Boolean>()
            solving = d
            withContext(Dispatchers.Main) { openSolver(act, d) }
            val ok = withTimeoutOrNull(5 * 60_000L) { d.await() } ?: false
            solving = null
            ok
        }
        if (result) {
            lastSolvedAt = System.currentTimeMillis()
            withContext(Dispatchers.Main) { destroyHidden() }
            lastMethod = "-"
            VivariumSettings.solvedUa = userAgent()
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
            text = "Vivarium: tick \"Verify you are human\" below (if shown). This window closes by itself when done."
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
            VivariumSettings.toast(if (ok) "Vivarium: Cloudflare passed" else "Vivarium: Cloudflare not solved")
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
