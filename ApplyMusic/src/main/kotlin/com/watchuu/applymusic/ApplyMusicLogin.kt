package com.watchuu.applymusic

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.view.ViewGroup
import android.view.Window
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.lagradost.cloudstream3.CommonActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Cloudflare solver.
 *
 * The site sits behind a Cloudflare check. The clearance cookie has to be earned by a real browser
 * once - this opens the site in a WebView, the user ticks the box, and whatever cookies the session
 * ends up with are kept and sent with every later request.
 *
 * cf_clearance is bound to the user agent it was issued to, so the WebView here is given exactly the
 * user agent the extension sends.
 */
object ApplyMusicLogin {

    fun open(activity: Activity?, done: CompletableDeferred<String>) {
        val act = activity ?: CommonActivity.activity
        if (act == null) {
            done.complete("")
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
            setBackgroundColor(Color.rgb(28, 28, 28))
        }
        val info = TextView(act).apply {
            text = "Tick the Cloudflare box, then press Done. This window does not close by itself."
            setTextColor(Color.WHITE)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val close = Button(act).apply { text = "Done"; isAllCaps = false }
        bar.addView(info)
        bar.addView(close)

        val wv = WebView(act)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString = ApplyMusicApi.userAgent()
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.webChromeClient = WebChromeClient()
        wv.webViewClient = WebViewClient()

        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(wv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        dialog.setContentView(root)

        var finished = false
        fun finish() {
            if (finished) return
            finished = true
            val cookies = runCatching {
                val cm = CookieManager.getInstance()
                cm.flush()
                listOf(
                    cm.getCookie(ApplyMusicApi.SITE),
                    cm.getCookie("https://ak1ra.de5.net"),
                    cm.getCookie("https://de5.net"),
                ).filterNotNull().joinToString("; ")
            }.getOrDefault("")
            done.complete(cookies)
            runCatching { dialog.dismiss() }
        }

        close.setOnClickListener { finish() }
        dialog.setOnCancelListener { finish() }
        dialog.show()
        wv.loadUrl(ApplyMusicApi.SITE)
    }

    /** runs the window and stores what it captured; returns a sentence for the user */
    suspend fun runAndSave(activity: Activity?): String {
        val result = CompletableDeferred<String>()
        withContext(Dispatchers.Main) { open(activity, result) }
        val cookies = withTimeoutOrNull(10 * 60_000L) { result.await() }.orEmpty()

        if (cookies.isBlank()) return "Nothing captured - the box was not passed, or the window was closed"
        ApplyMusicApi.savedCookies = cookies
        return if (cookies.contains("cf_clearance")) {
            "Cloudflare cookie captured - requests should be accepted now"
        } else {
            "Cookies captured, but no cf_clearance among them - try again and tick the box"
        }
    }
}
