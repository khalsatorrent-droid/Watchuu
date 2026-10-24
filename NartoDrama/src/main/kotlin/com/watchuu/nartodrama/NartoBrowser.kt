package com.watchuu.nartodrama

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Message
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Hidden WebView helper for narto-drama.com.
 *
 *  - html(url): loads a page like a browser (passes the "Checking your browser" cookie gate and
 *    Cloudflare by itself; the cookies are shared with Cloudstream's own requests afterwards) and
 *    returns the rendered HTML. Used when a plain request gets a challenge page.
 *  - sniff(url): loads an episode page and records the video urls the site's player requests
 *    (.m3u8 / .mp4 / .mpd). Media is answered empty, so nothing is downloaded and nothing plays.
 */
object NartoBrowser {
    private var appContext: Context? = null
    private val htmlLock = Mutex()

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    fun cookies(url: String): String? = try {
        CookieManager.getInstance().getCookie(url)
    } catch (_: Throwable) {
        null
    }

    fun isChallenge(body: String): Boolean {
        val head = body.take(4000)
        return head.contains("Checking your browser") || head.contains("Just a moment") ||
            head.contains("cf-challenge") || head.contains("challenge-platform/h/") && !head.contains("csrf-token")
    }

    // ------------------------------------------------------------------ shared WebView setup

    private val blockedHosts = listOf(
        "google-analytics.com", "googletagmanager.com", "googlesyndication.com", "doubleclick.net",
        "cloudflareinsights.com", "facebook.", "tiktok.com", "youtube.com", "juicyads", "exoclick",
        "untimely-hello.com", "bancadeltempoidea.org", "fonts.googleapis.com", "fonts.gstatic.com"
    )

    private fun hostOf(u: String) = u.substringAfter("://").substringBefore("/").substringBefore(":").lowercase()
    private fun isBlocked(u: String) = hostOf(u).let { h -> blockedHosts.any { h.contains(it) } }

    private fun isVideo(u: String): Boolean {
        val l = u.lowercase()
        val path = l.substringBefore("?").substringBefore("#")
        if (l.contains(".m3u8") || path.endsWith(".mpd")) return true
        return path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".webm") || path.endsWith(".mkv")
    }

    private fun isSegment(u: String): Boolean {
        val path = u.lowercase().substringBefore("?").substringBefore("#")
        return path.endsWith(".ts") || path.endsWith(".m4s") || path.endsWith(".aac") || path.endsWith(".m4a")
    }

    private val empty get() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(ctx: Context, userAgent: String?): WebView {
        val w = WebView(ctx)
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            if (userAgent != null) userAgentString = userAgent
        }
        w.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
        )
        w.layout(0, 0, 1080, 1920)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?) = false
        }
        return w
    }

    private suspend fun destroy(w: WebView) = withContext(NonCancellable + Dispatchers.Main) {
        try {
            w.stopLoading(); w.loadUrl("about:blank"); w.destroy()
        } catch (_: Throwable) {
        }
    }

    private suspend fun eval(w: WebView, js: String): String? {
        val d = CompletableDeferred<String?>()
        try {
            withContext(Dispatchers.Main) { w.evaluateJavascript(js) { d.complete(it) } }
        } catch (_: Throwable) {
            return null
        }
        return withTimeoutOrNull(5_000L) { d.await() }
    }

    private fun unquote(r: String?): String? = try {
        if (r == null || r == "null") null else JSONObject("{\"v\":$r}").getString("v")
    } catch (_: Exception) {
        null
    }

    // ------------------------------------------------------------------ html(url)

    /** Loads [url] in a hidden browser until a real page (not a challenge) is there, returns its HTML. */
    suspend fun html(url: String, userAgent: String?, timeoutMs: Long = 35_000L): String? = htmlLock.withLock {
        val ctx = appContext ?: return null
        val w = try {
            withContext(Dispatchers.Main) {
                newWebView(ctx, userAgent).also { v ->
                    v.settings.loadsImagesAutomatically = false
                    v.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val u = request?.url?.toString() ?: return null
                            return if (isBlocked(u) || isVideo(u) || isSegment(u)) empty else null
                        }
                    }
                    v.loadUrl(url)
                }
            }
        } catch (_: Throwable) {
            return null
        }
        try {
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                delay(700L)
                val ready = unquote(eval(w, "(function(){return document.readyState+'|'+(document.querySelector('meta[name=csrf-token]')?'1':'0');})()"))
                if (ready == "complete|1") {
                    delay(300L)
                    val h = unquote(eval(w, "document.documentElement.outerHTML"))
                    if (h != null && !isChallenge(h)) return h
                }
            }
            null
        } finally {
            destroy(w)
        }
    }

    // ------------------------------------------------------------------ sniff(url)

    class Hit(val url: String, val referer: String?)

    private const val TAP_JS = """
(function(){
  try {
    document.querySelectorAll('video,audio').forEach(function(v){ v.muted = true; try { v.play(); } catch(e){} });
    var sel = ['.vjs-big-play-button','.plyr__control--overlaid','.jw-display-icon-container','[class*=play-btn]','[class*=btn-play]','[aria-label*=Play]','[class*=play]'];
    for (var i = 0; i < sel.length; i++) { var el = document.querySelector(sel[i]); if (el) { el.click(); break; } }
  } catch(e){}
})();
"""

    /** Opens an episode page and returns the video urls its player requested. */
    suspend fun sniff(url: String, userAgent: String?, timeoutMs: Long = 30_000L): List<Hit> {
        val ctx = appContext ?: return emptyList()
        val hits = CopyOnWriteArrayList<Hit>()
        val site = hostOf(url)
        val w = try {
            withContext(Dispatchers.Main) {
                newWebView(ctx, userAgent).also { v ->
                    v.settings.loadsImagesAutomatically = false
                    v.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, u: String?) {
                            view?.evaluateJavascript(TAP_JS, null)
                        }

                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val h = request?.url?.host?.lowercase() ?: return true
                            return request.isForMainFrame && !h.endsWith(site.removePrefix("www."))
                        }

                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val u = request?.url?.toString() ?: return null
                            if (isVideo(u)) {
                                val ref = request.requestHeaders?.let { it["Referer"] ?: it["referer"] }
                                if (hits.none { it.url == u }) hits.add(Hit(u, ref))
                                // playlists must load so the player keeps going; files are not downloaded
                                return if (u.lowercase().contains(".m3u8") || u.lowercase().contains(".mpd")) null else empty
                            }
                            if (isSegment(u) || isBlocked(u)) return empty
                            return null
                        }
                    }
                    v.loadUrl(url)
                }
            }
        } catch (_: Throwable) {
            return emptyList()
        }
        try {
            val start = System.currentTimeMillis()
            var taps = 0
            var firstAt = 0L
            while (System.currentTimeMillis() - start < timeoutMs) {
                delay(300L)
                val el = System.currentTimeMillis() - start
                if (hits.isNotEmpty()) {
                    if (firstAt == 0L) firstAt = System.currentTimeMillis()
                    if (System.currentTimeMillis() - firstAt > 1_500L) break
                } else if ((taps == 0 && el > 4_000L) || (taps == 1 && el > 9_000L) || (taps == 2 && el > 15_000L)) {
                    taps++
                    try {
                        withContext(Dispatchers.Main) { w.evaluateJavascript(TAP_JS, null) }
                    } catch (_: Throwable) {
                    }
                }
            }
        } finally {
            destroy(w)
        }
        return hits.toList()
    }
}
