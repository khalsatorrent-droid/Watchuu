package com.watchuu.cinelite

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A hidden WebView that loads the cinelite embed player (which passes the Cloudflare
 * Turnstile check by itself) and lets Kotlin
 *   1. see every response the player gets from api.cinelite.xyz / the site proxy, and
 *   2. run its own fetch() calls from inside that page (same cookies, same auth headers
 *      the player used), so every server can be queried, not only the first one.
 *
 * Same idea as the AniChan WebView helper: the API only answers a "real" browser, so the
 * requests are made from inside one.
 */
object CineLiteBrowser {
    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    /** One response seen (or fetched) inside the page. kind = provider|servers|subtitles|verify|proxy|template */
    class Captured(val kind: String, val url: String, val body: String, val status: Int)

    class Session internal constructor(internal val webView: WebView) {
        val events = CopyOnWriteArrayList<Captured>()
        val playlists = CopyOnWriteArrayList<String>()
        internal val pending = ConcurrentHashMap<String, CompletableDeferred<Pair<Int, String>>>()

        @Volatile
        var closed = false

        /** Once the data is in, the page only needs to reach the api and small playlists. */
        @Volatile
        var strict = false

        /** Stop the player for good: silence/pause all media and cut every non-essential request. */
        suspend fun quiet() {
            strict = true
            if (closed) return
            try {
                withContext(Dispatchers.Main) {
                    webView.evaluateJavascript("try{window.__cineQuiet&&window.__cineQuiet();}catch(e){}", null)
                }
            } catch (_: Throwable) {
            }
        }

        fun has(kind: String): Boolean = events.any { it.kind == kind }

        /** Runs [js] in the page and returns its result (JSON-encoded by the WebView), or null. */
        suspend fun eval(js: String, timeoutMs: Long = 5_000L): String? {
            if (closed) return null
            val d = CompletableDeferred<String?>()
            try {
                withContext(Dispatchers.Main) { webView.evaluateJavascript(js) { r -> d.complete(r) } }
            } catch (_: Throwable) {
                return null
            }
            return withTimeoutOrNull(timeoutMs) { d.await() }
        }

        suspend fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                if (cond()) return true
                delay(250L)
            }
            return cond()
        }

        /**
         * fetch() from inside the page. With [useTemplate] the headers/credentials of the
         * player's own first provider request are reused (this is what carries the auth).
         */
        suspend fun get(url: String, useTemplate: Boolean, timeoutMs: Long = 20_000L): Pair<Int, String>? {
            if (closed) return null
            val id = UUID.randomUUID().toString()
            val d = CompletableDeferred<Pair<Int, String>>()
            pending[id] = d
            val js = "try{window.__cineGet(" + JSONObject.quote(id) + "," + JSONObject.quote(url) + "," +
                useTemplate + ");}catch(e){}"
            try {
                withContext(Dispatchers.Main) { webView.evaluateJavascript(js, null) }
            } catch (_: Throwable) {
                pending.remove(id)
                return null
            }
            val r = withTimeoutOrNull(timeoutMs) { d.await() }
            pending.remove(id)
            return r
        }

        suspend fun close() {
            if (closed) return
            closed = true
            withContext(NonCancellable + Dispatchers.Main) {
                try {
                    webView.stopLoading()
                    webView.loadUrl("about:blank")
                    webView.removeJavascriptInterface("cineBridge")
                    webView.destroy()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private class Bridge(val session: Session) {
        @JavascriptInterface
        fun onData(kind: String, url: String, body: String, status: Int) {
            session.events.add(Captured(kind, url, body, status))
        }

        @JavascriptInterface
        fun onFetched(id: String, status: Int, body: String) {
            session.pending.remove(id)?.complete(Pair(status, body))
        }
    }

    private fun isPlaylistUrl(u: String): Boolean {
        val l = u.lowercase()
        return l.contains(".m3u8") || l.contains("/proxy/hls")
    }

    // Only actual media is blocked (video/audio chunks, hidden behind fake names too), so the
    // hidden player can never produce sound. Everything else loads exactly as in a normal page.
    private val mediaExt = listOf(".ts", ".m4s", ".mp4", ".m4v", ".webm", ".mkv", ".aac", ".mp3", ".m4a", ".ogg")

    private fun allowedWhenStrict(u: String): Boolean {
        val l = u.lowercase()
        val host = l.substringAfter("://").substringBefore("/").substringBefore(":")
        return host == "api.cinelite.xyz" || host.endsWith("cloudflare.com") ||
            isPlaylistUrl(l) || l.contains("/vault/proxy?") || l.contains("/vault/hls.m3u8")
    }

    private fun isHeavy(u: String): Boolean {
        val l = u.lowercase()
        if (isPlaylistUrl(l)) return false
        val path = l.substringBefore("?").substringBefore("#")
        if (mediaExt.any { path.endsWith(it) }) return true
        // segments hidden behind fake names (.../pipe/.../00001.png?v=.ts, /vault/segment.ts)
        return l.contains("/pipe/") || l.contains("segment") || l.contains("v=.ts")
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun open(url: String, userAgent: String, referer: String): Session? {
        val ctx = appContext ?: return null
        return try {
            withContext(Dispatchers.Main) {
                val wv = WebView(ctx)
                // a real size, so the player lays out its menus (they are clicked from Kotlin for anime)
                wv.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY)
                )
                wv.layout(0, 0, 1080, 1920)
                val session = Session(wv)
                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = userAgent
                    mediaPlaybackRequiresUserGesture = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
                wv.addJavascriptInterface(Bridge(session), "cineBridge")
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        view?.evaluateJavascript(HOOK_JS, null)
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val u = request?.url?.toString() ?: return null
                        if (isPlaylistUrl(u)) session.playlists.add(u)
                        // only the page scripts, the Turnstile check, the api calls and the (small)
                        // playlists are needed; everything else is answered empty to save bandwidth
                        if (isHeavy(u) || (session.strict && !allowedWhenStrict(u))) {
                            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        }
                        return null
                    }
                }
                // the hook is idempotent, injecting again while the page loads makes sure it is in
                // place before the app bundle starts making requests
                wv.webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        if (newProgress in 5..70) view?.evaluateJavascript(HOOK_JS, null)
                    }
                }
                wv.loadUrl(url, mapOf("Referer" to referer))
                session
            }
        } catch (_: Throwable) {
            null
        }
    }

    // NOTE: no dollar signs in here, this is a Kotlin raw string.
    private const val HOOK_JS = """
(function(){
  if (window.__cineHook) return;
  window.__cineHook = true;
  var B = window.cineBridge;
  window.__cineTmpl = null;

  function emit(kind, url, body, status){
    try { B.onData(String(kind), String(url), String(body || ''), status | 0); } catch(e){}
  }
  function abs(u){
    try { return new URL(String(u), location.href).href; } catch(e){ return String(u); }
  }
  function classify(u){
    if (/api\.cinelite\.xyz\/provider\//.test(u)) return 'provider';
    if (/api\.cinelite\.xyz\/servers/.test(u)) return 'servers';
    if (/api\.cinelite\.xyz\/subtitles/.test(u)) return 'subtitles';
    if (/api\.cinelite\.xyz\/api\/turnstile\/verify/.test(u)) return 'verify';
    if (/\/vault\/proxy\?/.test(u)) return 'proxy';
    if (/\/vault\/hls\.m3u8/.test(u)) return 'hls';
    return null;
  }
  function hdrObj(h){
    var o = {};
    try {
      if (!h) return o;
      if (Array.isArray(h)) { h.forEach(function(p){ o[p[0]] = p[1]; }); return o; }
      if (typeof h.forEach === 'function') { h.forEach(function(v, k){ o[k] = v; }); return o; }
      for (var k in h) { o[k] = h[k]; }
    } catch(e){}
    return o;
  }
  function setTmpl(url, headers, creds){
    if (window.__cineTmpl) return;
    window.__cineTmpl = { headers: headers || {}, credentials: creds || 'same-origin' };
    emit('template', url, JSON.stringify(window.__cineTmpl), 0);
  }

  // nothing here is ever watched, never start playback
  try {
    HTMLMediaElement.prototype.play = function(){ return Promise.resolve(); };
    HTMLMediaElement.prototype.load = function(){};
  } catch(e){}

  // never make sound: autoplay attributes do not call play(), so pause/mute every media
  // element on a timer (read-only on the page, nothing is overridden)
  function hush(){
    try {
      var m = document.querySelectorAll('video,audio');
      for (var i = 0; i < m.length; i++) {
        var v = m[i];
        try { v.autoplay = false; v.muted = true; v.volume = 0; if (!v.paused) v.pause(); } catch(e){}
      }
    } catch(e){}
  }
  setInterval(hush, 200);
  window.__cineQuiet = function(){
    hush();
    try { if (window.AudioContext) { AudioContext.prototype.resume = function(){ return Promise.resolve(); }; } } catch(e){}
  };

  var origFetch = window.fetch;
  window.fetch = function(input, init){
    var url = '';
    try { url = abs((typeof input === 'string') ? input : (input && input.url) ? input.url : input); } catch(e){}
    var kind = classify(url);
    if (kind === 'provider') {
      try {
        setTmpl(url,
          hdrObj((init && init.headers) || (input && input.headers)),
          (init && init.credentials) || (input && input.credentials));
      } catch(e){}
    }
    var p = origFetch.apply(this, arguments);
    if (kind) {
      p.then(function(r){
        try { r.clone().text().then(function(t){ emit(kind, url, t, r.status); }, function(){}); } catch(e){}
      }, function(){});
    }
    return p;
  };

  var XO = XMLHttpRequest.prototype.open;
  var XS = XMLHttpRequest.prototype.send;
  var XH = XMLHttpRequest.prototype.setRequestHeader;
  XMLHttpRequest.prototype.open = function(m, u){
    try { this.__cu = abs(u); this.__ch = {}; } catch(e){}
    return XO.apply(this, arguments);
  };
  XMLHttpRequest.prototype.setRequestHeader = function(k, v){
    try { if (this.__ch) this.__ch[k] = v; } catch(e){}
    return XH.apply(this, arguments);
  };
  XMLHttpRequest.prototype.send = function(){
    var x = this;
    try {
      var kind = classify(x.__cu || '');
      if (kind === 'provider') setTmpl(x.__cu, x.__ch, x.withCredentials ? 'include' : 'same-origin');
      if (kind) {
        x.addEventListener('loadend', function(){
          try {
            var t = (x.responseType === '' || x.responseType === 'text') ? x.responseText : '';
            emit(kind, x.__cu, t, x.status);
          } catch(e){}
        });
      }
    } catch(e){}
    return XS.apply(this, arguments);
  };

  // used from Kotlin: fetch a url from inside the page and hand the body back
  window.__cineGet = function(id, url, useTmpl){
    var init = { mode: 'cors' };
    var t = window.__cineTmpl;
    if (useTmpl && t) {
      init.headers = t.headers;
      init.credentials = t.credentials;
    }
    fetch(url, init).then(function(r){
      return r.text().then(function(txt){
        try { B.onFetched(id, r.status, txt); } catch(e){}
      });
    }).catch(function(e){
      try { B.onFetched(id, -1, String(e)); } catch(e2){}
    });
  };
})();
"""
}
