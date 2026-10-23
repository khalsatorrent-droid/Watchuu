package com.watchuu.kiligdrama

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
 * kiligdrama.com sits behind a Cloudflare managed challenge ("Just a moment..." / Turnstile).
 *
 * Ways through, chosen in the extension settings (see [KiligSettings.Mode]):
 *  1. plain HTTP with the saved cf_clearance cookie + the exact same user agent (fast path, always tried first)
 *  2. a hidden WebView that loads the site and runs fetch() from inside the page
 *  3. Cloudstream's own CloudflareKiller
 *  4. a VISIBLE solver window: the real site in a full-screen WebView, the user ticks the
 *     Turnstile box once, it closes by itself as soon as the cf_clearance cookie appears
 *  5. a manual cookie pasted in the settings
 */
object KiligBrowser {
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

    /** Which way the last successful request went (shown by "Test connection"). */
    @Volatile
    var lastMethod = "-"
        private set

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
        KiligSettings.init(context)
    }

    private fun site() = KiligSettings.siteUrl
    private fun home() = site() + "/en"

    // ------------------------------------------------------------------ user agent + cookies

    /** One user agent for everything - cf_clearance is bound to it. */
    fun userAgent(): String {
        val custom = KiligSettings.customUa
        var ua = custom.ifBlank {
            defaultUa ?: (try {
                appContext?.let { WebSettings.getDefaultUserAgent(it) }
            } catch (_: Throwable) {
                null
            } ?: FALLBACK_UA).also { defaultUa = it }
        }
        if (custom.isBlank() && KiligSettings.stripWv) {
            ua = ua.replace("; wv)", ")").replace(" Version/4.0", "")
        }
        return ua
    }

    private fun applyManualCookie() {
        val raw = KiligSettings.manualCookie
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
        val manual = KiligSettings.manualCookie
        return listOfNotNull(fromWv?.ifBlank { null }, manual.ifBlank { null }).joinToString("; ").ifBlank { null }
    }

    fun hasClearance(): Boolean = cookies()?.contains("cf_clearance") == true

    fun isChallenge(code: Int, body: String): Boolean {
        val head = body.take(4000)
        return code == 403 || code == 503 || code == 429 ||
            head.contains("<title>Just a moment", true) ||
            head.contains("Performing security verification", true) ||
            (head.contains("challenge-platform") && head.contains("cf_chl", true))
    }

    // ------------------------------------------------------------------ main entry

    /** GET a kiligdrama url as text, going through Cloudflare the way the settings say. */
    suspend fun get(url: String, referer: String): String {
        val mode = KiligSettings.mode
        direct(url, referer)?.let { lastMethod = "cookie (fast)"; return it }

        when (mode) {
            KiligSettings.Mode.COOKIE -> throw ErrorLoadingException(
                "KiligDrama: the manual cookie was rejected. Paste a fresh cf_clearance + matching user agent in the extension settings."
            )

            KiligSettings.Mode.CLOUDSTREAM -> viaKiller(url, referer)?.let { lastMethod = "Cloudstream bypass"; return it }
            KiligSettings.Mode.AUTO, KiligSettings.Mode.HIDDEN ->
                viaHidden(url)?.let { lastMethod = "hidden browser"; return it }

            KiligSettings.Mode.SOLVER -> {}
        }

        val prompt = mode == KiligSettings.Mode.SOLVER ||
            (KiligSettings.autoPrompt && (mode == KiligSettings.Mode.AUTO || mode == KiligSettings.Mode.CLOUDSTREAM))
        if (prompt && solveInteractive(null)) {
            direct(url, referer)?.let { lastMethod = "solver + cookie"; return it }
            viaHidden(url)?.let { lastMethod = "solver + hidden browser"; return it }
        }
        throw ErrorLoadingException(
            "KiligDrama: Cloudflare not passed. Open Extensions > KiligDrama > settings and tap \"Solve Cloudflare now\"."
        )
    }

    private suspend fun direct(url: String, referer: String): String? {
        val cookie = cookies()
        return try {
            val h = HashMap<String, String>()
            h["User-Agent"] = userAgent()
            h["Referer"] = referer
            h["Accept-Language"] = "en-US,en;q=0.9"
            if (!cookie.isNullOrBlank()) h["Cookie"] = cookie
            val r = app.get(url, headers = h, timeout = 20L)
            val body = r.text
            if (r.code in 200..299 && !isChallenge(r.code, body)) {
                if (cookie.isNullOrBlank()) lastMethod = "plain"
                body
            } else null
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            null
        }
    }

    private suspend fun viaKiller(url: String, referer: String): String? = try {
        val r = app.get(url, headers = mapOf("Referer" to referer), interceptor = killer, timeout = 60L)
        val body = r.text
        if (r.code in 200..299 && !isChallenge(r.code, body)) body else null
    } catch (e: Throwable) {
        if (e is CancellationException) throw e
        null
    }

    private suspend fun viaHidden(url: String): String? {
        val r = fetch(url) ?: return null
        return if (r.first in 200..299 && !isChallenge(r.first, r.second)) r.second else null
    }

    /** For the settings "Test connection" button. */
    suspend fun test(): String {
        val start = System.currentTimeMillis()
        return try {
            val body = get("${site()}/en/genre/popular", home())
            val cards = Regex("""aria-label="[^"]+"\s+href="/en/drama/""").findAll(body).count()
            "OK via $lastMethod in ${(System.currentTimeMillis() - start) / 1000}s\n$cards dramas found on the Popular page.\n\nCookie saved: ${hasClearance()}\nUser agent:\n${userAgent()}"
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            "FAILED: ${e.message}\n\nMode: ${KiligSettings.mode.label}\nCookie saved: ${hasClearance()}\nUser agent:\n${userAgent()}\n\nTry \"Solve Cloudflare now\", or switch mode."
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
        if (host != siteHost && host != "www.$siteHost") return !KiligSettings.lightweight && !isMedia(l)
        val path = l.substringBefore("?")
        if (path.contains("/api/play")) return false
        if (KiligSettings.lightweight && (isImage(path) || path.contains("/_next/image"))) return false
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
        configure(wv, KiligSettings.lightweight)
        // give it a real size - some challenges don't run in a 0x0 view
        val dm = ctx.resources.displayMetrics
        wv.layout(0, 0, dm.widthPixels.coerceAtLeast(360), dm.heightPixels.coerceAtLeast(640))
        wv.addJavascriptInterface(Bridge(), "kdBridge")
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
            wv.removeJavascriptInterface("kdBridge")
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
            "!!document.querySelector('a[href*=\"/drama/\"],a[href*=\"/genre/\"]')}catch(e){return false}})()"

    private suspend fun pageReady(wv: WebView?): Boolean = eval(wv, READY_JS) == "true"

    /** Opens (or reuses) the hidden page and waits until Cloudflare lets it through. */
    suspend fun ensure(timeoutMs: Long = KiligSettings.timeoutSec * 1000L): Boolean = lock.withLock {
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
            ",{credentials:'include'}).then(function(r){return r.text().then(function(t){kdBridge.onFetched(id,r.status,t)})})" +
            ".catch(function(e){kdBridge.onFetched(id,0,String(e))})})()"
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
            val act = activity ?: KiligSettings.currentActivity() ?: return@withLock false
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
            KiligSettings.solvedUa = userAgent()
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
            text = "KiligDrama: tick \"Verify you are human\" below. This window closes by itself when done."
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
            KiligSettings.toast(if (ok) "KiligDrama: Cloudflare passed" else "KiligDrama: Cloudflare not solved")
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
