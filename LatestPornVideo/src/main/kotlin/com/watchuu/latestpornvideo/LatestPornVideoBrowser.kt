package com.watchuu.latestpornvideo

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Transport for latestpornvideo.com and its embed hosts (luluvdo / luluvido).
 *
 * The capture of 2026-10-30 22:47 shows Cloudflare answering a plain client with a 503 bot
 * challenge on the detail pages — the same URL is 200 for the browser — so OkHttp alone can never
 * see those pages, and without the detail page there are no iframes and therefore no video links.
 *
 * Three rungs, cheapest first:
 *   1. plain HTTP, with whatever cookies the WebView has already earned
 *   2. Cloudstream's CloudflareKiller
 *   3. the hidden WebView itself: load the page like a browser, then read the DOM back
 *
 * A WebView DOM is enough for this site: the embed pages keep their packed player config in a
 * <script> tag, and outerHTML still contains it, so the existing unpacker works unchanged.
 */
object LatestPornVideoBrowser {
    private const val IDLE_CLOSE_MS = 3 * 60_000L

    private const val UA =
        "Mozilla/5.0 (Linux; Android 16; SM-S911B Build/BP4A.251205.006) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/156.0.8078.47 Mobile Safari/537.36"

    private var appContext: Context? = null
    private var webView: WebView? = null
    private val lock = Mutex()
    private val domLock = Mutex()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var lastFinishedUrl: String? = null
    @Volatile private var lastUse = 0L
    @Volatile var lastMethod = "-"
        private set

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    // ── entry point ────────────────────────────────────────────────────────────

    /**
     * Page HTML for [url].
     *
     * Budget matters as much as correctness here: Cloudstream gives up on a link request long
     * before a stacked ladder finishes. The previous version allowed 25s + 60s (killer) + 45s +
     * 25s per page, which is why links came back "not found" — it never got an answer in time.
     * So: one short plain attempt, then the WebView. No killer rung; the capture shows it never
     * gets these hosts through anyway.
     */
    suspend fun html(url: String, referer: String): String? = domLock.withLock {
        http(url, referer)?.let {
            lastMethod = "http"
            return@withLock it
        }
        dom(url, referer)?.also { lastMethod = "webview" }
    }

    // ── rung 1: plain HTTP, carrying the cookies the WebView earned ─────────────

    private suspend fun http(url: String, referer: String): String? {
        val headers = HashMap<String, String>()
        headers["User-Agent"] = UA
        headers["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        headers["Accept-Language"] = "en-US,en;q=0.9"
        headers["Referer"] = referer
        cookiesFor(url)?.let { headers["Cookie"] = it }

        val plain = runCatching { app.get(url, headers = headers, timeout = 20L).text }.getOrNull()
        if (!isChallenge(plain)) return plain

        return null
    }

    private fun cookiesFor(url: String): String? = try {
        CookieManager.getInstance().getCookie(url)?.ifBlank { null }
    } catch (_: Throwable) {
        null
    }

    private fun isChallenge(body: String?): Boolean {
        if (body == null || body.length < 600) return true
        val head = body.take(6000)
        return head.contains("Just a moment", ignoreCase = true) ||
            head.contains("challenge-platform") ||
            head.contains("cf_chl_opt") ||
            head.contains("Attention Required", ignoreCase = true) ||
            !head.contains("<html", ignoreCase = true)
    }

    // ── rung 3: the hidden WebView ─────────────────────────────────────────────

    private suspend fun dom(url: String, referer: String): String? {
        val ctx = appContext ?: return null
        return lock.withLock {
            lastUse = System.currentTimeMillis()
            try {
                val wv = withContext(Dispatchers.Main) {
                    webView ?: create(ctx).also {
                        webView = it
                        scheduleIdleClose()
                    }
                }
                lastFinishedUrl = null
                withContext(Dispatchers.Main) {
                    try {
                        wv.loadUrl(url, mapOf("Referer" to referer))
                    } catch (_: Throwable) {
                    }
                }

                val deadline = System.currentTimeMillis() + 15_000L
                while (System.currentTimeMillis() < deadline) {
                    delay(400L)
                    if (lastFinishedUrl != null) break
                }
                if (lastFinishedUrl == null) return@withLock null

                // Give the page's own JS a moment to build the player config.
                delay(900L)
                val html = readDom(wv)
                try {
                    CookieManager.getInstance().flush()
                } catch (_: Throwable) {
                }
                html
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                null
            }
        }
    }

    private suspend fun readDom(wv: WebView): String? = withContext(Dispatchers.Main) {
        val done = CompletableDeferred<String?>()
        try {
            wv.evaluateJavascript("document.documentElement.outerHTML") { done.complete(it) }
        } catch (_: Throwable) {
            done.complete(null)
        }
        val raw = withTimeoutOrNull(10_000L) { done.await() } ?: return@withContext null
        decodeJsString(raw)?.takeIf { it.length > 500 }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(ctx: Context): WebView {
        val wv = WebView(ctx)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = UA
        }
        try {
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(wv, true)
            }
        } catch (_: Throwable) {
        }
        val dm = ctx.resources.displayMetrics
        wv.layout(0, 0, dm.widthPixels.coerceAtLeast(360), dm.heightPixels.coerceAtLeast(640))
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                lastFinishedUrl = url
            }
        }
        wv.loadUrl("about:blank")
        return wv
    }

    private fun scheduleIdleClose() {
        main.postDelayed(object : Runnable {
            override fun run() {
                val wv = webView ?: return
                if (System.currentTimeMillis() - lastUse >= IDLE_CLOSE_MS) destroy()
                else main.postDelayed(this, 30_000L)
            }
        }, 30_000L)
    }

    private fun destroy() {
        val wv = webView ?: return
        webView = null
        try {
            wv.stopLoading()
            wv.loadUrl("about:blank")
            wv.destroy()
        } catch (_: Throwable) {
        }
    }

    /** evaluateJavascript hands back a JSON string literal; turn it back into plain HTML. */
    private fun decodeJsString(raw: String?): String? {
        if (raw == null) return null
        var s = raw
        if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length - 1)
        if (!s.contains('\\')) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> { out.append('\n'); i += 2 }
                    'r' -> { out.append('\r'); i += 2 }
                    't' -> { out.append('\t'); i += 2 }
                    'b' -> { out.append('\b'); i += 2 }
                    'f' -> { out.append('\u000C'); i += 2 }
                    'u' -> {
                        val hex = s.substring(i + 2, minOf(i + 6, s.length))
                        val code = hex.toIntOrNull(16)
                        if (code != null) out.append(code.toChar())
                        i += 6
                    }
                    else -> { out.append(n); i += 2 }
                }
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}
