package com.watchuu.latestpornvideo

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
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
 * The capture shows an asymmetry worth knowing: the listing and search pages (`/`,
 * `/?filter=latest`, `/?s=…`) answer a plain client directly with 200, while the detail pages are
 * challenged — `/1188551/` returns a 503 bot challenge to the client and 200 to the browser. And
 * without the detail page there is no iframe, so without it there is no video.
 *
 * Four rungs, cheapest first:
 *   1. plain HTTP, carrying the cookies the WebView has already earned
 *   2. CloudstreamKiller - Cloudstream's own challenge solver
 *   3. a hidden WebView, reading its DOM back
 *   4. the visible solver: the real site opens, you tick the challenge, the clearance cookie is
 *      kept and rung 1 then works. This is the only rung that beats an interactive challenge, and
 *      it is why a solve is trusted for ten minutes rather than re-prompting per page.
 *
 * A pasted cookie (gear icon) skips rungs 2-4 entirely.
 */
object LatestPornVideoBrowser {
    private const val SITE = "https://latestpornvideo.com"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 16; SM-S911B Build/BP4A.251205.006) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/156.0.8078.47 Mobile Safari/537.36"

    /** Once a solve has worked, the clearance lasts a while - do not re-prompt for it. */
    private const val SOLVED_TRUST_MS = 10 * 60_000L

    /** After a window is dismissed unsolved, do not nag again immediately. */
    private const val PROMPT_BACKOFF_MS = 60_000L

    private var ctx: Context? = null
    private var webView: WebView? = null
    private val domLock = Mutex()

    @Volatile private var finishedUrl: String? = null

    @Volatile private var solving: CompletableDeferred<Boolean>? = null
    private val solveLock = Mutex()
    @Volatile private var lastSolvedAt = 0L
    @Volatile private var lastPromptAt = 0L

    private val killer by lazy { CloudflareKiller() }

    fun init(context: Context) {
        if (ctx == null) ctx = context.applicationContext
    }

    // ── entry point ────────────────────────────────────────────────────────────

    suspend fun html(url: String, referer: String): String? = domLock.withLock {
        val hasCookie = LatestPornVideoSettings.cookie.isNotBlank()

        http(url, referer)?.let { return@withLock it }
        // A pasted cookie is the user's own answer to Cloudflare; do not open windows over it.
        if (hasCookie) return@withLock null

        viaKiller(url, referer)?.let { return@withLock it }
        dom(url, referer)?.let { return@withLock it }

        // Everything automatic failed. Ask the user to solve it once, then retry plain HTTP.
        if (!solveInteractive(LatestPornVideoSettings.currentActivity())) return@withLock null
        http(url, referer)
    }

    // ── rung 1 ─────────────────────────────────────────────────────────────────

    private suspend fun http(url: String, referer: String): String? {
        val headers = HashMap<String, String>()
        headers["User-Agent"] = LatestPornVideoSettings.userAgent.ifBlank { UA }
        headers["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        headers["Accept-Language"] = "en-US,en;q=0.9"
        headers["Referer"] = referer
        val manual = LatestPornVideoSettings.cookie
        val jar = cookiesFor(url).orEmpty()
        val combined = listOf(manual, jar).filter { it.isNotBlank() }.joinToString("; ")
        if (combined.isNotBlank()) headers["Cookie"] = combined

        return runCatching {
            val body = app.get(url, headers = headers, timeout = 20L).text
            if (isChallenge(body)) null else body
        }.getOrNull()
    }

    // ── rung 2 ─────────────────────────────────────────────────────────────────

    private suspend fun viaKiller(url: String, referer: String): String? = runCatching {
        val headers = mapOf(
            "User-Agent" to LatestPornVideoSettings.userAgent.ifBlank { UA },
            "Referer" to referer
        )
        val body = app.get(url, headers = headers, interceptor = killer, timeout = 25L).text
        if (isChallenge(body)) null else body
    }.getOrNull()

    // ── rung 3 ─────────────────────────────────────────────────────────────────

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
            userAgentString = LatestPornVideoSettings.userAgent.ifBlank { UA }
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

    // ── rung 4: the visible solver ─────────────────────────────────────────────

    /**
     * Shows the real site full-screen so the challenge can be ticked. Closes by itself once the
     * page is through and a cf_clearance cookie exists.
     */
    suspend fun solveInteractive(activity: Activity?): Boolean {
        solving?.let { return it.await() }

        val result = solveLock.withLock {
            solving?.let { return@withLock it.await() }
            if (System.currentTimeMillis() - lastSolvedAt < SOLVED_TRUST_MS) return@withLock true
            if (System.currentTimeMillis() - lastPromptAt < PROMPT_BACKOFF_MS) return@withLock false
            lastPromptAt = System.currentTimeMillis()

            // CommonActivity.activity can be null until the host activity is attached; retry briefly
            // instead of failing straight away.
            var act = activity ?: LatestPornVideoSettings.currentActivity()
            var waited = 0
            while (act == null && waited < 6_000) {
                delay(500L)
                waited += 500
                act = LatestPornVideoSettings.currentActivity()
            }
            if (act == null) return@withLock false

            val d = CompletableDeferred<Boolean>()
            solving = d
            withContext(Dispatchers.Main) { openSolver(act, d) }
            val ok = withTimeoutOrNull(5 * 60_000L) { d.await() } ?: false
            solving = null
            ok
        }

        if (result) {
            lastSolvedAt = System.currentTimeMillis()
            withContext(Dispatchers.Main) { destroyHidden() }
        }
        return result
    }

    private fun openSolver(act: Activity, done: CompletableDeferred<Boolean>) {
        if (act.isFinishing) {
            done.complete(false)
            return
        }
        val density = act.resources.displayMetrics.density
        val dialog = Dialog(act, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val bar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            val p = (10 * density).toInt()
            setPadding(p, p, p, p)
            setBackgroundColor(Color.rgb(30, 30, 30))
        }
        val info = TextView(act).apply {
            text = "LatestPornVideo: tick \"Verify you are human\" below if it is shown. " +
                "This window closes by itself once the check passes."
            setTextColor(Color.WHITE)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val close = Button(act).apply {
            text = "Close"
            isAllCaps = false
        }
        bar.addView(info)
        bar.addView(close)

        val wv = WebView(act)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = LatestPornVideoSettings.userAgent.ifBlank { UA }
        }
        try {
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(wv, true)
            }
        } catch (_: Throwable) {
        }

        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(wv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        dialog.setContentView(root)

        // Declared before the client below, which needs to call it.
        var finished = false
        fun finish(ok: Boolean) {
            if (finished) return
            finished = true
            try { CookieManager.getInstance().flush() } catch (_: Throwable) {}
            try { wv.stopLoading(); wv.destroy() } catch (_: Throwable) {}
            try { if (dialog.isShowing) dialog.dismiss() } catch (_: Throwable) {}
            done.complete(ok)
            LatestPornVideoSettings.toast(
                if (ok) "LatestPornVideo: Cloudflare passed" else "LatestPornVideo: challenge not passed"
            )
        }

        wv.webChromeClient = WebChromeClient()
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: android.webkit.WebResourceRequest?
            ): Boolean = false

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (hasClearance()) finish(true)
            }
        }

        close.setOnClickListener { finish(hasClearance()) }
        dialog.setOnDismissListener { finish(hasClearance()) }

        dialog.show()
        wv.loadUrl("$SITE/")

        // A tick can pass without a new page load, so poll for the cookie as well.
        wv.postDelayed(object : Runnable {
            private var ticks = 0
            override fun run() {
                if (finished) return
                if (hasClearance()) {
                    finish(true)
                    return
                }
                if (++ticks < 120) wv.postDelayed(this, 1_000L)
            }
        }, 2_000L)
    }

    /** True once Cloudflare has handed out its clearance cookie for this site. */
    private fun hasClearance(): Boolean {
        val cookie = try {
            CookieManager.getInstance().getCookie(SITE)
        } catch (_: Throwable) {
            null
        } ?: return false
        return cookie.contains("cf_clearance")
    }

    private fun destroyHidden() {
        val wv = webView ?: return
        webView = null
        try {
            wv.stopLoading()
            wv.destroy()
        } catch (_: Throwable) {
        }
    }

    // ── shared helpers ─────────────────────────────────────────────────────────

    /**
     * Headers for the thumbnails. The artwork sits on hosts behind the same protection, so the
     * image loader needs the same user agent and clearance cookie the page requests use - without
     * them the urls are right but every image request is refused and the cards render blank.
     */
    fun posterHeaders(): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["User-Agent"] = LatestPornVideoSettings.userAgent.ifBlank { UA }
        h["Referer"] = "$SITE/"
        val manual = LatestPornVideoSettings.cookie
        val jar = cookiesFor(SITE).orEmpty()
        val combined = listOf(manual, jar).filter { it.isNotBlank() }.joinToString("; ")
        if (combined.isNotBlank()) h["Cookie"] = combined
        return h
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
            head.contains("Performing security verification", ignoreCase = true) ||
            !head.contains("<html", ignoreCase = true)
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
