package com.watchuu.octave

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
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
 * THE browser: one WebView for everything. It is the page that passes Cloudflare, the page the user
 * logs in on (the solver window just hosts this very view, see [attach] / [detach]) and the page
 * every API call is made from with fetch() - so the JS state, cookies, local storage, user agent
 * and TLS session that earned the clearance are exactly the ones that use it. Nothing is rebuilt
 * after the user closes the solver window. The site answers OkHttp with a bot challenge but answers
 * its own page; the playback key and the song links are captured the same way.
 */
object OctaveBrowser {
    private var appContext: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private val lock = Mutex()

    @Volatile private var webView: WebView? = null
    @Volatile private var lastUse = 0L
    /** True while the page is shown inside the solver window (the user is working in it). */
    @Volatile var visible = false
        private set
    private val pending = ConcurrentHashMap<String, CompletableDeferred<OResp>>()

    // Cloudflare "warm-up" state. A fresh hidden page is challenged on its FIRST api call (403) and the
    // site's own script clears it ~1-2 s later - exactly what the site's player does. Until that has
    // happened nothing may fire, otherwise every home-page row fails (and reloads the page) at once.
    private val warmLock = Mutex()
    @Volatile private var apiReady = false
    @Volatile private var readyAt = 0L
    @Volatile private var brokenAt = 0L  // last time warm-up/recovery gave up (needs the user's manual check)
    @Volatile private var epoch = 0      // bumped on every page reload
    @Volatile private var pageLoads = 0  // bumped by onPageStarted

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

    /** A real size: a page without layout renders no text and some scripts never start. */
    private fun measureHidden(wv: WebView) {
        wv.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY)
        )
        wv.layout(0, 0, 1080, 1920)
    }

    private val imageExt = listOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".svg", ".avif", ".ico", ".woff", ".woff2")

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(ctx: Context): WebView {
        // MutableContextWrapper: the same view is later hosted by the solver window (an Activity
        // context is needed for dialogs, pickers, keyboard) and taken back to the app context after.
        val wv = WebView(MutableContextWrapper(ctx))
        measureHidden(wv)
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
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                pageLoads++
            }

            // while the user works in the window: keep the cookies and the logged-in account key saved
            override fun onPageFinished(view: WebView?, url: String?) {
                if (!visible) return
                OctaveCf.snapshotCookies()
                view?.evaluateJavascript(OctaveCf.KEY_JS) { r -> OctaveCf.saveKeyFrom(r) }
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val u = request?.url?.toString() ?: return null
                val path = u.substringBefore("?").lowercase()
                if (path.contains("/audio/")) {
                    // the page never plays anything here; remember the link it would have used
                    seenAudio.add(u)
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                if (!visible && imageExt.any { path.endsWith(it) }) { // the user sees the real page
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

    /** Main thread. The one page: created and started if it does not exist yet. */
    private fun createAndLoad(ctx: Context): WebView {
        webView?.let { return it }
        val wv = create(ctx)
        webView = wv
        apiReady = false
        wv.loadUrl("${OctaveCf.SITE}/", mapOf("X-Requested-With" to "")) // the app package in X-Requested-With gives a WebView away
        scheduleIdleClose()
        return wv
    }

    /**
     * Main thread. Moves THE page into [container] (the solver window) so Cloudflare is ticked and the
     * login is done in the very WebView that makes the requests afterwards. Returns null if there is
     * no app context yet.
     */
    fun attach(act: Activity, container: ViewGroup): WebView? {
        val ctx = appContext ?: return null
        val wv = createAndLoad(ctx)
        (wv.parent as? ViewGroup)?.removeView(wv)
        (wv.context as? MutableContextWrapper)?.baseContext = act
        if (wv.url.isNullOrBlank()) wv.loadUrl("${OctaveCf.SITE}/", mapOf("X-Requested-With" to ""))
        visible = true
        lastUse = System.currentTimeMillis()
        container.addView(wv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        wv.requestFocus()
        return wv
    }

    /** Main thread. Takes the page back out of the window. It is NOT destroyed - it keeps serving requests. */
    fun detach() {
        visible = false
        lastUse = System.currentTimeMillis()
        val wv = webView ?: return
        (wv.parent as? ViewGroup)?.removeView(wv)
        appContext?.let { (wv.context as? MutableContextWrapper)?.baseContext = it }
        measureHidden(wv)
    }

    /** The user closed the solver window: same page, new cookies/login. Only makes sure it is back on the site. */
    /** the account key the logged-in page currently holds (octv_…), or null */
    suspend fun evaluateKey(): String? {
        if (!ensure(10_000L)) return null
        val raw = eval(webView, OctaveCf.KEY_JS) ?: return null
        return raw.trim().trim('"').takeIf { it.startsWith("octv_") }
    }

    suspend fun afterSolve() {
        apiReady = false
        brokenAt = 0L
        val wv = webView ?: return
        // login redirects may have taken it off the site; fetch() needs the site's origin
        val origin = eval(wv, "location.origin")?.trim('"')
        if (origin != OctaveCf.SITE) {
            withContext(Dispatchers.Main) { try { wv.loadUrl("${OctaveCf.SITE}/", mapOf("X-Requested-With" to "")) } catch (_: Throwable) {} }
        }
    }

    /** Makes sure the page is open and past Cloudflare. */
    private suspend fun ensure(timeoutMs: Long = 30_000L): Boolean = lock.withLock {
        val ctx = appContext ?: return@withLock false
        lastUse = System.currentTimeMillis()
        if (webView == null) withContext(Dispatchers.Main) { createAndLoad(ctx) }
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
    private suspend fun attempt(method: String, url: String, body: String?, headers: Map<String, String>, timeoutMs: Long = 35_000L): OResp? {
        val id = UUID.randomUUID().toString()
        val d = CompletableDeferred<OResp>()
        pending[id] = d
        val allowed = headers.filterKeys { it.equals("Authorization", true) || it.equals("Content-Type", true) || it.equals("Accept", true) }
        // FETCH_JS is idempotent: re-injecting it makes a call survive a page reload that wiped it
        val js = FETCH_JS + "try{window.__octFetch(" + JSONObject.quote(id) + "," + JSONObject.quote(method) + "," +
            JSONObject.quote(url) + "," + (if (body != null) JSONObject.quote(body) else "null") + "," +
            JSONObject(allowed).toString() + ");}catch(e){}"
        withContext(Dispatchers.Main) {
            try { webView?.evaluateJavascript(js, null) } catch (_: Throwable) {}
        }
        lastUse = System.currentTimeMillis()
        val r = withTimeoutOrNull(timeoutMs) { d.await() }
        pending.remove(id)
        lastInfo = if (r == null) "page fetch timed out: $method ${url.substringBefore("?").takeLast(60)}"
        else "page fetch $method ${url.substringBefore("?").takeLast(60)} -> HTTP ${r.code}" +
            (if (r.code !in 200..299) " body=${r.text.take(120).replace("\n", " ")}" else "")
        return r
    }

    /** A probe counts as "not through yet" on a 403/429/503 too: the first call is refused before the page's script clears it. */
    private fun notThrough(r: OResp?) = failed(r) || r!!.code in listOf(403, 429, 503)

    /**
     * Polls a cheap public endpoint from inside the page until Cloudflare lets it through. This is a
     * real wait (a request every ~1.2 s), unlike checking for the cf_clearance cookie - that cookie
     * is already there from the site itself while the api host is still being challenged.
     */
    private suspend fun probeUntilReady(ms: Long): Boolean {
        val end = System.currentTimeMillis() + ms
        while (true) {
            val r = attempt("GET", "${OctaveCf.API}/api/search/trending", null, mapOf("Accept" to "application/json"), 12_000L)
            if (!notThrough(r)) {
                apiReady = true
                brokenAt = 0L
                readyAt = System.currentTimeMillis()
                OctaveCf.snapshotCookies() // keep what the page earned
                return true
            }
            if (System.currentTimeMillis() + 1_200L >= end) return false
            delay(1_200L)
        }
    }

    /** First fetch on a fresh page waits here (one prober, everyone else queues behind it). */
    private suspend fun warmUp(): Boolean {
        if (apiReady) return true
        if (isBroken() || visible) return false
        return warmLock.withLock {
            if (apiReady) true
            else if (isBroken()) false
            else probeUntilReady(20_000L).also { if (!it) brokenAt = System.currentTimeMillis() }
        }
    }

    /** Warm-up/recovery failed in the last 30 s: do not make every queued row repeat the long wait. */
    private fun isBroken() = brokenAt != 0L && System.currentTimeMillis() - brokenAt < 30_000L

    private suspend fun reloadPage() {
        val before = pageLoads
        withContext(Dispatchers.Main) { try { webView?.reload() } catch (_: Throwable) {} }
        val end = System.currentTimeMillis() + 6_000L
        while (pageLoads == before && System.currentTimeMillis() < end) delay(200L)
    }

    /**
     * A request failed even though the page was warm. Exactly ONE caller repairs the page; the rest
     * queue behind it and simply retry. First just wait for Cloudflare's script (soft), and only if
     * that does not help reload the page once (hard). A reload wipes the other in-flight fetches, so
     * those are released at once instead of waiting out their timeout.
     */
    private suspend fun recover(seen: Int) = warmLock.withLock {
        if (visible) return@withLock // never reload the page under the user
        if (epoch != seen) return@withLock // somebody already reloaded since this request started
        if (apiReady && System.currentTimeMillis() - readyAt < 5_000L) return@withLock // just repaired
        if (isBroken()) return@withLock // already tried, gave up
        apiReady = false
        if (probeUntilReady(12_000L)) return@withLock
        if (visible) return@withLock
        epoch++
        val dead = pending.values.toList()
        pending.clear()
        dead.forEach { it.complete(OResp(-1, "page reloaded", false)) }
        reloadPage()
        if (!(ensure(25_000L) && probeUntilReady(20_000L))) brokenAt = System.currentTimeMillis()
    }

    /**
     * fetch() from inside the page. Waits for the one-time Cloudflare clearance first, then makes the
     * call; on failure one caller repairs the page (see [recover]) and the call is retried, at most
     * twice. Returns the last answer (or null) when it still fails - the caller then asks the user.
     */
    suspend fun fetch(method: String, url: String, body: String?, headers: Map<String, String>): OResp? {
        if (appContext == null) return null
        if (visible) return null // the user is working in the page: never disturb it
        if (!ensure()) return null
        warmUp()
        var r: OResp? = null
        for (round in 0 until 3) {
            val seen = epoch
            r = attempt(method, url, body, headers)
            if (!failed(r)) return r
            if (round == 2) break
            recover(seen)
            if (isBroken() || !ensure()) return r // still blocked: let the caller fall back / ask the user
        }
        return r
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
            webView?.let { (it.parent as? ViewGroup)?.removeView(it) }
            visible = false
            webView?.stopLoading()
            webView?.removeJavascriptInterface("octBridge")
            webView?.destroy()
        } catch (_: Throwable) {
        }
        webView = null
        apiReady = false
        brokenAt = 0L
    }

    private fun scheduleIdleClose() {
        main.postDelayed(object : Runnable {
            override fun run() {
                if (webView == null) return
                if (!visible && System.currentTimeMillis() - lastUse > 900_000L) destroy() else main.postDelayed(this, 30_000L)
            }
        }, 30_000L)
    }
}
