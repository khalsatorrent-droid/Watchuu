package com.watchuu.eightkporner

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
 * Transport for 8kporner.com.
 *
 * The capture is unambiguous: `GET https://8kporner.com/` answers a plain client with 503 and a
 * Cloudflare challenge, and the same URL answers the browser with 200 and 400 KB of HTML. Both the
 * 18+ gate and the listing grid are built by the site's own JavaScript. So a plain HTTP client can
 * never see this site, and the only way in is to render it.
 *
 * Two rungs, cheapest first:
 *   1. plain HTTP, carrying whatever cookies the WebView has already earned (this is what makes
 *      every page after the first one fast)
 *   2. the hidden WebView: load the page, wait for that navigation, read the DOM back
 */
object EightKPornerBrowser {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 16; SM-S911B Build/BP4A.251205.006) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/156.0.8078.47 Mobile Safari/537.36"

    private var ctx: Context? = null
    private var webView: WebView? = null
    private val domLock = Mutex()

    @Volatile private var finishedUrl: String? = null
    @Volatile private var lastUse = 0L

    fun init(context: Context) {
        if (ctx == null) ctx = context.applicationContext
    }

    private val killer by lazy { CloudflareKiller() }
    @Volatile private var consentDone = false

    suspend fun html(url: String, referer: String): String? = domLock.withLock {
        var body = http(url, referer) ?: viaKiller(url, referer) ?: dom(url, referer)
        if (body == null) return@withLock null

        // The 18+ gate is server side: the site calls GET /aj/main/yes_18?hash=<40 hex> and only
        // then do the listing pages render cards. Setting pop_up_18 is not enough on its own.
        if (!consentDone && !body.contains("keep-padding")) {
            val hash = Regex("[0-9a-f]{40}").find(body)?.value
            if (hash != null) {
                val accepted = runCatching {
                    app.get(
                        "https://8kporner.com/aj/main/yes_18?hash=$hash",
                        headers = mapOf(
                            "User-Agent" to UA,
                            "Referer" to referer,
                            "X-Requested-With" to "XMLHttpRequest",
                            "Cookie" to consentCookies() + cookiesFor(url)
                        ),
                        timeout = 15L
                    ).code in 200..299
                }.getOrDefault(false)
                if (accepted) {
                    consentDone = true
                    http(url, referer)?.let { body = it }
                }
            }
        }
        body
    }

    /** Cloudstream's own challenge solver - purpose built for Cloudflare, worth one try. */
    private suspend fun viaKiller(url: String, referer: String): String? = runCatching {
        val body = app.get(
            url,
            headers = mapOf("User-Agent" to UA, "Referer" to referer, "Cookie" to consentCookies() + cookiesFor(url)),
            interceptor = killer,
            timeout = 25L
        ).text
        if (isChallenge(body)) null else body
    }.getOrNull()

    // ── rung 1 ─────────────────────────────────────────────────────────────────

    private suspend fun http(url: String, referer: String): String? {
        val headers = HashMap<String, String>()
        headers["User-Agent"] = UA
        headers["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        headers["Accept-Language"] = "en-US,en;q=0.9"
        headers["Referer"] = referer
        // The 18+ answer the site remembers, plus whatever Cloudflare clearance the WebView earned.
        headers["Cookie"] = consentCookies() + cookiesFor(url)

        return runCatching {
            val body = app.get(url, headers = headers, timeout = 20L).text
            if (isChallenge(body)) null else body
        }.getOrNull()
    }

    private fun consentCookies(): String =
        "pop_up_18=yes; adb_user=true; ads_pageview=1; pageViews=1; isfadshow=1; mode=night; "

    private fun cookiesFor(url: String): String = try {
        CookieManager.getInstance().getCookie(url) ?: ""
    } catch (_: Throwable) {
        ""
    }

    private fun isChallenge(body: String?): Boolean {
        if (body == null || body.length < 600) return true
        val head = body.take(6000)
        return head.contains("Just a moment", ignoreCase = true) ||
            head.contains("challenge-platform") ||
            head.contains("cf_chl_opt") ||
            !head.contains("<html", ignoreCase = true)
    }

    // ── rung 2 ─────────────────────────────────────────────────────────────────

    private suspend fun dom(url: String, referer: String): String? {
        // init() is best-effort (the plugin guards it), so fall back to the live activity
        val context = ctx
            ?: try { com.lagradost.cloudstream3.CommonActivity.activity?.applicationContext } catch (_: Throwable) { null }
            ?: return null
        lastUse = System.currentTimeMillis()
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

            // Let the 18+ click and the grid render before the DOM is taken.
            delay(800L)
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
                // Accept the 18+ gate as soon as the page is there; it is rendered by the site's
                // own JS, so poke it a few times rather than once.
                var tries = 0
                val poke = object : Runnable {
                    override fun run() {
                        try {
                            view?.evaluateJavascript(CONSENT_JS, null)
                        } catch (_: Throwable) {
                        }
                        if (++tries < 8) view?.postDelayed(this, 500L)
                    }
                }
                view?.postDelayed(poke, 300L)
            }
        }
        wv.loadUrl("about:blank")
        return wv
    }

    private const val CONSENT_JS =
        "(function(){try{" +
            "var b=document.querySelector('#pop_up_18_yes,button[submit]#pop_up_18_yes,div#pop_up_18 button');" +
            "if(b){try{b.click()}catch(e){}}" +
            "var p=document.getElementById('pop_up_18');if(p){try{p.remove()}catch(e){}}" +
            "document.cookie='pop_up_18=yes;path=/';" +
            "}catch(e){}return true})()"

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
