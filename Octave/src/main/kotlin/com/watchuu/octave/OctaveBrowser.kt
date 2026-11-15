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

    /** What happened in the last browser call - shown in Settings -> "Show last report". */
    @Volatile var lastInfo: String = "no request yet"

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
        // a real size: a page without layout renders no text and some scripts never start
        wv.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY)
        )
        wv.layout(0, 0, 1080, 1920)
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
            "document.readyState==='complete'&&document.body&&document.body.innerHTML.length>2000&&location.hostname.indexOf('octavestreaming')>=0}catch(e){return false}})()"

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
        val title = eval(webView, "document.title") ?: "?"
        val loc = eval(webView, "location.href") ?: "?"
        lastInfo = "hidden page NOT ready after ${timeoutMs / 1000}s: title=$title url=$loc"
        false
    }

    private fun failed(r: OResp?) = r == null || r.challenge || r.code == -1

    /** One fetch() from inside the page. */
    private suspend fun attempt(method: String, url: String, body: String?, headers: Map<String, String>): OResp? {
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
        lastInfo = if (r == null) "page fetch timed out: $method ${url.substringBefore("?").takeLast(60)}"
        else "page fetch $method ${url.substringBefore("?").takeLast(60)} -> HTTP ${r.code}" +
            (if (r.code !in 200..299) " body=${r.text.take(120).replace("\n", " ")}" else "")
        return r
    }

    /** Waits (up to [ms]) for Cloudflare's own script in the hidden page to earn the cf_clearance cookie. */
    private suspend fun waitClearance(ms: Long): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (OctaveCf.hasClearance()) return true
            delay(500L)
        }
        return OctaveCf.hasClearance()
    }

    /**
     * fetch() from inside the page. The first call to the API can be answered with a Cloudflare
     * challenge that the page's own script then solves silently (the site's player does exactly
     * this: one 403, then it works) - so on failure wait for the cookie, retry, and finally reload
     * the page once. Returns null when it still fails (the caller then asks the user).
     */
    suspend fun fetch(method: String, url: String, body: String?, headers: Map<String, String>): OResp? {
        if (appContext == null) return null
        if (!ensure()) return null
        var r = attempt(method, url, body, headers)
        if (!failed(r)) return r

        waitClearance(15_000L)
        r = attempt(method, url, body, headers)
        if (!failed(r)) return r

        // reload once: the page restarts its Cloudflare script with the cookies it has now
        withContext(Dispatchers.Main) { try { webView?.reload() } catch (_: Throwable) {} }
        delay(1500L)
        if (ensure(25_000L)) {
            waitClearance(10_000L)
            r = attempt(method, url, body, headers)
        }
        return if (failed(r)) r else r
    }

    /** Cookie NAMES the browser holds per host (never the values) - for the report. */
    fun cookieNames(): String = try {
        val cm = CookieManager.getInstance()
        listOf("api" to OctaveCf.API, "site" to OctaveCf.SITE, "root" to "https://octavestreaming.com").joinToString("; ") { (n, u) ->
            val names = cm.getCookie(u)?.split(";")?.map { it.trim().substringBefore("=") }?.filter { it.isNotBlank() } ?: emptyList()
            "$n=[${names.joinToString(",")}]"
        }
    } catch (_: Throwable) {
        "?"
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
