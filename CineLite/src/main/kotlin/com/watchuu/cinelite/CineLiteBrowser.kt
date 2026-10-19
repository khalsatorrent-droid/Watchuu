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

        fun has(kind: String): Boolean = events.any { it.kind == kind }

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

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun open(url: String, userAgent: String, referer: String): Session? {
        val ctx = appContext ?: return null
        return try {
            withContext(Dispatchers.Main) {
                val wv = WebView(ctx)
                val session = Session(wv)
                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = userAgent
                    mediaPlaybackRequiresUserGesture = false
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
                        val u = request?.url?.toString()
                        if (u != null && isPlaylistUrl(u)) session.playlists.add(u)
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
