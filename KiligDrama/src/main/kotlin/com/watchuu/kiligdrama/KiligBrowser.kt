package com.watchuu.kiligdrama

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * kiligdrama.com sits behind a Cloudflare managed challenge ("Just a moment...").
 *
 * A hidden WebView opens the site once, lets the challenge pass by itself, and then:
 *   - its cf_clearance cookie (+ the same WebView user agent) is reused for plain HTTP calls, and
 *   - if those still get challenged, the request is made with fetch() from inside the page.
 *
 * Same idea as the CineLite helper, with the same "only load what is needed" rule: images,
 * fonts, media and analytics are answered empty so the hidden page stays light.
 */
object KiligBrowser {
    const val HOME = "https://kiligdrama.com/en"
    private const val IDLE_CLOSE_MS = 3 * 60_000L

    private var appContext: Context? = null
    private var webView: WebView? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Pair<Int, String>>>()
    private val lock = Mutex()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var lastUse = 0L

    @Volatile
    private var cachedUa: String? = null

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    /** The WebView's own user agent - cf_clearance is bound to it, so plain requests must match. */
    fun userAgent(): String {
        cachedUa?.let { return it }
        val ua = try {
            appContext?.let { WebSettings.getDefaultUserAgent(it) }
        } catch (_: Throwable) {
            null
        } ?: "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        cachedUa = ua
        return ua
    }

    fun cookies(): String? = try {
        CookieManager.getInstance().getCookie("https://kiligdrama.com")
    } catch (_: Throwable) {
        null
    }

    fun hasClearance(): Boolean = cookies()?.contains("cf_clearance") == true

    private class Bridge {
        @JavascriptInterface
        fun onFetched(id: String, status: Int, body: String) {
            pending.remove(id)?.complete(Pair(status, body))
        }
    }

    private fun isNeeded(url: String): Boolean {
        val l = url.lowercase()
        val host = l.substringAfter("://").substringBefore("/").substringBefore(":")
        if (host.endsWith("cloudflare.com")) return true
        if (host != "kiligdrama.com" && host != "www.kiligdrama.com") return false
        val path = l.substringBefore("?")
        return !(path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") ||
            path.endsWith(".webp") || path.endsWith(".gif") || path.endsWith(".svg") ||
            path.endsWith(".woff") || path.endsWith(".woff2") || path.endsWith(".mp4") ||
            path.contains("/_next/image") || path.contains("/api/play"))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(ctx: Context): WebView {
        val wv = WebView(ctx)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            loadsImagesAutomatically = false
            blockNetworkImage = true
        }
        cachedUa = wv.settings.userAgentString
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.addJavascriptInterface(Bridge(), "kdBridge")
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val u = request?.url?.toString() ?: return null
                if (isNeeded(u)) return null
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
        }
        wv.loadUrl(HOME)
        return wv
    }

    private suspend fun eval(js: String): String? = withContext(Dispatchers.Main) {
        val wv = webView ?: return@withContext null
        val d = CompletableDeferred<String?>()
        try {
            wv.evaluateJavascript(js) { d.complete(it) }
        } catch (_: Throwable) {
            d.complete(null)
        }
        withTimeoutOrNull(5_000L) { d.await() }
    }

    private suspend fun pageReady(): Boolean {
        val r = eval(
            "(function(){try{return location.hostname.indexOf('kiligdrama.com')>=0&&" +
                "!/just a moment|attention required|security verification/i.test(document.title)&&" +
                "document.readyState!=='loading'&&!document.querySelector('#challenge-form,#cf-challenge-running')}catch(e){return false}})()"
        )
        return r == "true"
    }

    /** Opens (or reuses) the hidden page and waits until Cloudflare lets it through. */
    suspend fun ensure(timeoutMs: Long = 45_000L): Boolean = lock.withLock {
        val ctx = appContext ?: return@withLock false
        lastUse = System.currentTimeMillis()
        if (webView == null) {
            withContext(Dispatchers.Main) { webView = create(ctx) }
            scheduleIdleClose()
        }
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (pageReady()) {
                try {
                    CookieManager.getInstance().flush()
                } catch (_: Throwable) {
                }
                return@withLock true
            }
            delay(500L)
        }
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
        if (eval(js) == null && webView == null) {
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
                val wv = webView ?: return
                if (System.currentTimeMillis() - lastUse >= IDLE_CLOSE_MS && pending.isEmpty()) {
                    try {
                        wv.stopLoading()
                        wv.loadUrl("about:blank")
                        wv.removeJavascriptInterface("kdBridge")
                        wv.destroy()
                    } catch (_: Throwable) {
                    }
                    webView = null
                } else {
                    main.postDelayed(this, 30_000L)
                }
            }
        }, 30_000L)
    }
}
