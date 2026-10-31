package com.watchuu.latestpornvideo

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Transport for latestpornvideo.com and its embed hosts (luluvdo.com / luluvido.com).
 *
 * The capture shows an asymmetry worth knowing: the listing and search pages (`/`, `/?filter=latest`,
 * `/?s=…`) answer a plain client directly with 200, while the detail pages are challenged — for
 * example `/1188551/` returns a 503 bot challenge to the client and 200 to the browser. Without the
 * detail page there is no iframe, and without the iframe there is no video, so the WebView is
 * required for that step only.
 *
 * Two rungs: plain HTTP with the cookies the WebView earned (fast, and enough for listings), then
 * the hidden WebView reading back its DOM.
 */
object LatestPornVideoBrowser {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 16; SM-S911B Build/BP4A.251205.006) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/156.0.8078.47 Mobile Safari/537.36"

    private var ctx: Context? = null
    private var webView: WebView? = null
    private val domLock = Mutex()

    @Volatile private var finishedUrl: String? = null

    fun init(context: Context) {
        if (ctx == null) ctx = context.applicationContext
    }

    private val killer by lazy { CloudflareKiller() }

    suspend fun html(url: String, referer: String): String? = domLock.withLock {
        http(url, referer)
            ?: viaKiller(url, referer)
            ?: dom(url, referer)
    }

    /** Cloudstream's own challenge solver - purpose built for Cloudflare, worth one try. */
    private suspend fun viaKiller(url: String, referer: String): String? = runCatching {
        val headers = mapOf("User-Agent" to UA, "Referer" to referer)
        val body = app.get(url, headers = headers, interceptor = killer, timeout = 25L).text
        if (isChallenge(body)) null else body
    }.getOrNull()

    private suspend fun http(url: String, referer: String): String? {
        val headers = HashMap<String, String>()
        headers["User-Agent"] = UA
        headers["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        headers["Accept-Language"] = "en-US,en;q=0.9"
        headers["Referer"] = referer
        cookiesFor(url)?.let { headers["Cookie"] = it }

        return runCatching {
            val body = app.get(url, headers = headers, timeout = 20L).text
            if (isChallenge(body)) null else body
        }.getOrNull()
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
            !head.contains("<html", ignoreCase = true)
    }

    private suspend fun dom(url: String, referer: String): String? {
        // init() is best-effort (the plugin guards it), so fall back to the live activity
        val context = ctx
            ?: try { com.lagradost.cloudstream3.CommonActivity.activity?.applicationContext } catch (_: Throwable) { null }
            ?: return null
        return try {
            val wv = withContext(Dispatchers.Main) {
                webView ?: create(context).also { webView = it }
            }
            finishedUrl = null
            withContext(Dispatchers.Main) {
                try {
                    wv.loadUrl(url, mapOf("Referer" to referer))
                } catch (_: Throwable) {
                }
            }

            val deadline = System.currentTimeMillis() + 15_000L
            var navigated = false
            while (System.currentTimeMillis() < deadline) {
                delay(350L)
                if (finishedUrl != null) {
                    navigated = true
                    break
                }
            }
            if (!navigated) return null

            delay(700L)
            val raw = evalDom(wv) ?: return null
            try {
                CookieManager.getInstance().flush()
            } catch (_: Throwable) {
            }
            raw.takeIf { it.length > 500 }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            null
        }
    }

    private suspend fun evalDom(wv: WebView): String? = withContext(Dispatchers.Main) {
        val done = CompletableDeferred<String?>()
        try {
            wv.evaluateJavascript("document.documentElement.outerHTML") { done.complete(it) }
        } catch (_: Throwable) {
            done.complete(null)
        }
        withTimeoutOrNull(10_000L) { done.await() }?.let { decodeJsString(it) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(context: Context): WebView {
        val wv = WebView(context)
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
        val dm = context.resources.displayMetrics
        wv.layout(0, 0, dm.widthPixels.coerceAtLeast(360), dm.heightPixels.coerceAtLeast(640))
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                finishedUrl = url
            }
        }
        wv.loadUrl("about:blank")
        return wv
    }

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
