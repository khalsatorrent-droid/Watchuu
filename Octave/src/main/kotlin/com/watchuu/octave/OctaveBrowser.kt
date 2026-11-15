package com.watchuu.octave

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
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

/** One answer from the site: status, body and whether Cloudflare blocked it. */
class OResp(val code: Int, val text: String, val challenge: Boolean)

/**
 * The hidden browser. It is the SAME WebView engine, cookie jar, local storage and user agent the
 * login window uses (so it is logged in and past Cloudflare), and every API call is made with
 * fetch() from INSIDE the site's own page - the site answers OkHttp with a bot challenge but
 * answers its own page. The playback key and the song links are captured this way too.
 */
object OctaveBrowser {
    private var appContext: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private val lock = Mutex()

    @Volatile private var webView: WebView? = null
    @Volatile private var lastUse = 0L
    private val pending = ConcurrentHashMap<String, CompletableDeferred<OResp>>()

    /** Audio links the page itself requested (url -> seen), for debugging and as a fallback source. */
    val seenAudio = java.util.concurrent.CopyOnWriteArrayList<String>()

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private class Bridge {
        @JavascriptInterface
        fun onFetched(id: String, status: Int, body: String) {
            pending.remove(id)?.complete(OResp(status, body, OctaveCf.looksLikeChallenge(status, body)))
        }
    }

    private val imageExt = listOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".svg", ".avif", ".ico", ".woff", ".woff2")

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(ctx: Context): WebView {
        val wv = WebView(ctx)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = true
            userAgentString = OctaveCf.userAgent() // cf_clearance is bound to it
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.addJavascriptInterface(Bridge(), "octBridge")
        wv.webChromeClient = WebChromeClient()
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val u = request?.url?.toString() ?: return null
                val path = u.substringBefore("?").lowercase()
                if (path.contains("/audio/")) {
                    // the page never plays anything here; remember the link it would have used
                    seenAudio.add(u)
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                if (imageExt.any { path.endsWith(it) }) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }
        }
        return wv
    }

    private suspend fun eval(wv: WebView?, js: String): String? = withContext(Dispatchers.Main) {
        val d = CompletableDeferred<String?>()
        try {
            wv?.evaluateJavascript(js) { d.complete(it) } ?: d.complete(null)
        } catch (_: Throwable) {
            d.complete(null)
        }
        withTimeoutOrNull(5_000L) { d.await() }
    }

    private const val READY_JS =
        "(function(){try{return !/just a moment|attention required|security verification|un instant|einen moment/i.test(document.title)&&" +
            "document.readyState!=='loading'&&!document.querySelector('#challenge-form,#cf-challenge-running,#challenge-stage')&&" +
            "document.body&&document.body.innerText.length>50&&location.hostname.indexOf('octavestreaming')>=0}catch(e){return false}})()"

    // NOTE: raw string, no dollar signs.
    private const val FETCH_JS = """
(function(){
  if (window.__octFetch) return;
  var B = window.octBridge;
  function findKey(){
    try {
      var re = /octv_[0-9a-f]{16,}/;
      for (var i = 0; i < localStorage.length; i++) {
        var m = re.exec(localStorage.getItem(localStorage.key(i)) || '');
        if (m) return m[0];
      }
    } catch(e){}
    return '';
  }
  window.__octFetch = function(id, method, url, body, headers){
    var h = {};
    try { for (var k in headers) h[k] = headers[k]; } catch(e){}
    if (!h['Authorization'] && url.indexOf('octavestreaming.com/api/') >= 0) {
      var key = findKey();
      if (key) h['Authorization'] = 'Bearer ' + key;
    }
    var init = { method: method, headers: h, credentials: 'include', mode: 'cors' };
    if (body) init.body = body;
    fetch(url, init).then(function(r){
      return r.text().then(function(t){ try { B.onFetched(id, r.status, t); } catch(e){} });
    }).catch(function(e){
      try { B.onFetched(id, -1, String(e)); } catch(e2){}
    });
  };
})();
"""

    /** Makes sure the hidden page is open and past Cloudflare. */
    private suspend fun ensure(timeoutMs: Long = 30_000L): Boolean = lock.withLock {
        val ctx = appContext ?: return@withLock false
        lastUse = System.currentTimeMillis()
        if (webView == null) {
            withContext(Dispatchers.Main) {
                val wv = create(ctx)
                webView = wv
                wv.loadUrl("${OctaveCf.SITE}/", mapOf("X-Requested-With" to ""))
            }
            scheduleIdleClose()
        }
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (eval(webView, READY_JS) == "true") {
                eval(webView, FETCH_JS)
                return@withLock true
            }
            delay(500L)
        }
        false
    }

    /** fetch() from inside the page. Returns null when the browser is unavailable or still blocked. */
    suspend fun fetch(method: String, url: String, body: String?, headers: Map<String, String>): OResp? {
        if (appContext == null) return null
        if (!ensure()) return null
        val id = UUID.randomUUID().toString()
        val d = CompletableDeferred<OResp>()
        pending[id] = d
        val allowed = headers.filterKeys { it.equals("Authorization", true) || it.equals("Content-Type", true) || it.equals("Accept", true) }
        val js = "try{window.__octFetch(" + JSONObject.quote(id) + "," + JSONObject.quote(method) + "," +
            JSONObject.quote(url) + "," + (if (body != null) JSONObject.quote(body) else "null") + "," +
            JSONObject(allowed).toString() + ");}catch(e){}"
        withContext(Dispatchers.Main) {
            try { webView?.evaluateJavascript(js, null) } catch (_: Throwable) {}
        }
        lastUse = System.currentTimeMillis()
        val r = withTimeoutOrNull(35_000L) { d.await() }
        pending.remove(id)
        return r
    }

    /** Throw the hidden page away (new cookies / new login) - it is rebuilt on the next request. */
    suspend fun reset() = lock.withLock {
        withContext(Dispatchers.Main) { destroy() }
    }

    private fun destroy() {
        try {
            webView?.stopLoading()
            webView?.removeJavascriptInterface("octBridge")
            webView?.destroy()
        } catch (_: Throwable) {
        }
        webView = null
    }

    private fun scheduleIdleClose() {
        main.postDelayed(object : Runnable {
            override fun run() {
                if (webView == null) return
                if (System.currentTimeMillis() - lastUse > 120_000L) destroy() else main.postDelayed(this, 30_000L)
            }
        }, 30_000L)
    }
}
