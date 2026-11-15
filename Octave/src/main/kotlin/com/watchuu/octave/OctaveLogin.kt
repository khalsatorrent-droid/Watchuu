package com.watchuu.octave

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
 * The one thing the extension cannot invent: a Cloudflare clearance cookie and a logged-in session.
 *
 * This opens the site in a real WebView, lets the user tick the box and sign in, and then keeps
 * whatever cookies and account key the session ended up with. Nothing else in the extension uses a
 * WebView - playback is plain http - so a broken WebView can never stop a song from playing.
 */
object OctaveLogin {

    /** the octv_… key the page holds, read from its storage */
    private const val KEY_JS =
        "(function(){try{var f=function(s){var m=/octv_[0-9a-f]{16,}/.exec(s||'');return m?m[0]:null};" +
            "for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i);var r=f(localStorage.getItem(k))||f(k);if(r)return r}" +
            "return f(document.cookie)||''}catch(e){return ''}})()"

    fun open(activity: Activity?, done: CompletableDeferred<Pair<String, String>>) {
        val act = activity ?: CommonActivity.activity
        if (act == null) {
            done.complete("" to "")
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
            text = "Tick the Cloudflare box, sign in to Octave, then press Done. The window does not close by itself."
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
            userAgentString = OctaveApi.userAgent()
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.webChromeClient = WebChromeClient()
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.evaluateJavascript(KEY_JS) { null }
            }
        }

        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(wv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        dialog.setContentView(root)

        var finished = false
        fun finish() {
            if (finished) return
            finished = true
            wv.evaluateJavascript(KEY_JS) { raw ->
                val key = raw?.trim()?.trim('"')?.takeIf { it.startsWith("octv_") }.orEmpty()
                val cookies = runCatching {
                    val cm = CookieManager.getInstance()
                    cm.flush()
                    listOf(cm.getCookie(OctaveApi.API), cm.getCookie(OctaveApi.SITE))
                        .filterNotNull().joinToString("; ")
                }.getOrDefault("")
                done.complete(key to cookies)
                runCatching { dialog.dismiss() }
            }
        }

        close.setOnClickListener { finish() }
        dialog.setOnCancelListener { finish() }
        dialog.show()
        wv.loadUrl(OctaveApi.SITE)
    }

    /**
     * Runs the window and stores what it captured. Returns a sentence for the user.
     */
    suspend fun runAndSave(activity: Activity?): String {
        val result = CompletableDeferred<Pair<String, String>>()
        withContext(Dispatchers.Main) { open(activity, result) }
        val (key, cookies) = withTimeoutOrNull(10 * 60_000L) { result.await() } ?: ("" to "")

        if (cookies.isNotBlank()) OctaveApi.savedCookies = cookies
        if (key.isNotBlank()) OctaveApi.accountKey = key

        val clearance = OctaveApi.hasClearance()
        if (key.isBlank()) {
            return if (clearance) "Cookie captured, but no account key - sign in to Octave, not just the box"
            else "Nothing captured - the box was not passed"
        }
        val problem = OctaveApi.validateKey(key)
        return when {
            problem == null -> "Cookie and key captured, key verified - lossless should work"
            clearance -> "Cookie captured, key $problem - sign in again"
            else -> "Key captured but $problem, and no Cloudflare cookie"
        }
    }
}
