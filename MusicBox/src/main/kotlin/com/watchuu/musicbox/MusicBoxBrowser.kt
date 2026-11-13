package com.watchuu.musicbox

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.Toast
import com.lagradost.cloudstream3.CommonActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * In-app browser for the Mornye / api.zarz.moe verification challenge.
 *
 * The challenge page finishes by navigating to `mornye://session-grant?...&state=..&grant=..`
 * (the callback the host app registers). A WebView cannot open that scheme, so we intercept it,
 * check `state`, and hand the grant to [ZarzSession.exchange].
 */
object MusicBoxBrowser {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastPrompt = HashMap<ZarzHub.Provider, Long>()

    fun toast(msg: String) = main.post {
        (CommonActivity.activity as? Activity)?.let { Toast.makeText(it, msg, Toast.LENGTH_LONG).show() }
    }

    /**
     * Make sure [p] has a usable session; when the gateway wants verification, open the challenge.
     * [onDone] is called on the main thread with a human readable result.
     */
    fun verify(p: ZarzHub.Provider, activity: Activity? = null, onDone: (String) -> Unit = {}) {
        scope.launch {
            val session = ZarzHub.session(p)
            val msg = try {
                when (val b = session.bootstrap()) {
                    ZarzSession.Bootstrap.Ready -> "${p.label}: verified"
                    is ZarzSession.Bootstrap.Challenge -> {
                        val act = activity ?: CommonActivity.activity as? Activity
                        if (act == null) "${p.label}: open MusicBox settings to verify"
                        else {
                            main.post { openChallenge(act, p, b) { onDone(it) } }
                            return@launch
                        }
                    }
                }
            } catch (e: Exception) {
                "${p.label}: ${e.message}"
            }
            main.post { onDone(msg) }
        }
    }

    /** Called from playback when a provider reports VERIFY_REQUIRED (throttled). */
    fun promptFromPlayback(p: ZarzHub.Provider) {
        val now = System.currentTimeMillis()
        synchronized(lastPrompt) {
            if (now - (lastPrompt[p] ?: 0L) < 90_000) return
            lastPrompt[p] = now
        }
        toast("MusicBox: ${p.label} needs verification")
        verify(p) { toast(it) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openChallenge(
        activity: Activity,
        p: ZarzHub.Provider,
        challenge: ZarzSession.Bootstrap.Challenge,
        onDone: (String) -> Unit,
    ) {
        val web = WebView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.userAgentString = UA
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        }
        var finished = false
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Verify ${p.label} (api.zarz.moe)")
            .setView(web)
            .setNegativeButton("Cancel", null)
            .create()

        fun complete(grant: String) {
            if (finished) return
            finished = true
            dialog.dismiss()
            scope.launch {
                val msg = try {
                    ZarzHub.session(p).exchange(grant)
                    "${p.label}: verification complete"
                } catch (e: Exception) {
                    "${p.label}: ${e.message}"
                }
                main.post { onDone(msg) }
            }
        }

        fun intercept(uri: Uri): Boolean {
            if (uri.scheme?.lowercase() != "mornye") return false
            val state = uri.getQueryParameter("state").orEmpty()
            if (state.isNotEmpty() && state != challenge.state) return true // stale callback, ignore
            val grant = uri.getQueryParameter("grant") ?: uri.getQueryParameter("code")
            if (!grant.isNullOrBlank()) complete(grant)
            else {
                val err = uri.getQueryParameter("error") ?: "no grant in callback"
                if (!finished) { finished = true; dialog.dismiss(); main.post { onDone("${p.label}: $err") } }
            }
            return true
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = intercept(request.url)
        }
        dialog.setOnDismissListener {
            if (!finished) { finished = true; main.post { onDone("${p.label}: verification cancelled") } }
            web.destroy()
        }
        dialog.show()
        web.loadUrl(challenge.url)
    }
}
