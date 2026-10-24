package com.watchuu.redflix

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Hidden WebView "sniffer": loads one embed player page and records every stream manifest
 * (HLS .m3u8 / DASH .mpd / dash-hls bridge) the player requests, with the Referer it used.
 *
 * - media segments are answered empty, so nothing is downloaded and nothing makes sound
 * - popups / redirects to other sites (ads) are refused
 * - after a few seconds a tap is simulated in the middle of the player for click-to-play embeds
 */
object RedFlixBrowser {
    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    class Hit(val url: String, val referer: String?, val origin: String?)

    private val segmentExt = listOf(
        ".ts", ".m4s", ".mp4", ".m4v", ".webm", ".mkv", ".aac", ".mp3", ".m4a", ".vtt.ts", ".jpg", ".png", ".gif",
        ".woff", ".woff2", ".ttf"
    )

    fun isManifest(u: String): Boolean {
        val l = u.lowercase()
        val path = l.substringBefore("#")
        if (path.contains(".m3u8") || path.contains(".mpd")) {
            // a bridge/proxy url carrying a manifest url in its query still counts
            return true
        }
        return l.contains("dash-hls-bridge") || l.contains("/manifest?") || l.contains("/playlist?")
    }

    // ads, trackers and analytics seen in the log: never needed, they only slow the players down
    private val blockedHosts = listOf(
        "google-analytics.com", "googletagmanager.com", "googlesyndication.com", "doubleclick.net",
        "cloudflareinsights.com", "umami.", "histats.com", "disqus.com", "facebook.net", "hotjar.com",
        "adsterra", "popads", "propellerads", "onclick", "juicyads", "exoclick", "flagsapi.com",
        "butchestusker.com", "firebaseapp.com", "apis.google.com", "fonts.googleapis.com", "fonts.gstatic.com"
    )
    // throw-away ad domains in the log all used these TLDs (stream hosts did not)
    private val blockedTlds = listOf(".cfd", ".rest", ".cyou", ".click", ".monster", ".sbs")

    private fun isBlocked(u: String): Boolean {
        val host = u.substringAfter("://").substringBefore("/").substringBefore(":").lowercase()
        return blockedHosts.any { host.contains(it) } || blockedTlds.any { host.endsWith(it) }
    }

    private fun isSegment(u: String): Boolean {
        val l = u.lowercase()
        if (isManifest(l)) return false
        val path = l.substringBefore("?").substringBefore("#")
        if (segmentExt.any { path.endsWith(it) }) return true
        return l.contains("/seg-") || l.contains("chunk-stream") || l.contains("init-stream")
    }

    private const val HUSH_JS = """
(function(){
  if (window.__rfHush) return; window.__rfHush = true;
  try { window.open = function(){ return null; }; } catch(e){}
  function hush(){
    try {
      var m = document.querySelectorAll('video,audio');
      for (var i = 0; i < m.length; i++) { try { m[i].muted = true; m[i].volume = 0; } catch(e){} }
    } catch(e){}
  }
  setInterval(hush, 300);
})();
"""

    private const val TAP_JS = """
(function(){
  try {
    var w = window.innerWidth || 640, h = window.innerHeight || 360;
    var el = document.elementFromPoint(w/2, h/2);
    var sel = ['.vjs-big-play-button','.jw-display-icon-container','.plyr__control--overlaid','[aria-label*=Play]','[class*=play]','button'];
    for (var i = 0; i < sel.length && !el; i++) { el = document.querySelector(sel[i]); }
    if (el) { el.click(); }
    var v = document.querySelector('video'); if (v) { v.muted = true; try { v.play(); } catch(e){} }
  } catch(e){}
})();
"""

    /**
     * Opens [url] and waits until a manifest shows up (plus a short grace period for the master
     * playlist / other qualities) or [timeoutMs] runs out. Returns every manifest seen, in order.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun sniff(url: String, referer: String, userAgent: String, timeoutMs: Long): List<Hit> {
        val ctx = appContext ?: return emptyList()
        val hits = CopyOnWriteArrayList<Hit>()
        val embedHost = url.substringAfter("://").substringBefore("/").lowercase()

        val wv: WebView = try {
            withContext(Dispatchers.Main) {
                val w = WebView(ctx)
                w.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = userAgent
                    mediaPlaybackRequiresUserGesture = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    setSupportMultipleWindows(true) // so popups come to onCreateWindow and are dropped
                    javaScriptCanOpenWindowsAutomatically = false
                    loadsImagesAutomatically = false
                }
                // give the page a real size so players lay out and the tap lands on them
                w.measure(
                    View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY)
                )
                w.layout(0, 0, 1280, 720)
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
                w.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, u: String?, favicon: Bitmap?) {
                        view?.evaluateJavascript(HUSH_JS, null)
                    }

                    override fun onPageFinished(view: WebView?, u: String?) {
                        view?.evaluateJavascript(HUSH_JS, null)
                    }

                    // top-level navigation away from the player (ad redirects) is refused
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val target = request?.url?.host?.lowercase() ?: return true
                        return request.isForMainFrame && target != embedHost
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val u = request?.url?.toString() ?: return null
                        if (isManifest(u)) {
                            val h = request.requestHeaders ?: emptyMap()
                            val ref = h["Referer"] ?: h["referer"]
                            val org = h["Origin"] ?: h["origin"]
                            if (hits.none { it.url == u }) hits.add(Hit(u, ref, org))
                            return null
                        }
                        if (isSegment(u) || isBlocked(u)) {
                            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        }
                        return null
                    }
                }
                w.webChromeClient = object : WebChromeClient() {
                    override fun onCreateWindow(
                        view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?
                    ): Boolean = false // drop every popup

                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        if (newProgress in 5..80) view?.evaluateJavascript(HUSH_JS, null)
                    }
                }
                w.loadUrl(url, mapOf("Referer" to referer))
                w
            }
        } catch (_: Throwable) {
            return emptyList()
        }

        try {
            val start = System.currentTimeMillis()
            var tapped = 0
            var firstHitAt = 0L
            while (System.currentTimeMillis() - start < timeoutMs) {
                delay(300L)
                val elapsed = System.currentTimeMillis() - start
                if (hits.isNotEmpty()) {
                    if (firstHitAt == 0L) firstHitAt = System.currentTimeMillis()
                    // grace period: the master playlist and other qualities usually follow quickly
                    if (System.currentTimeMillis() - firstHitAt > 1_000L) break
                } else if ((tapped == 0 && elapsed > 3_000L) || (tapped == 1 && elapsed > 7_000L)) {
                    tapped++
                    try {
                        withContext(Dispatchers.Main) { wv.evaluateJavascript(TAP_JS, null) }
                    } catch (_: Throwable) {
                    }
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                try {
                    wv.stopLoading()
                    wv.loadUrl("about:blank")
                    wv.destroy()
                } catch (_: Throwable) {
                }
            }
        }
        return hits.toList()
    }
}
