package com.watchuu.musicbox.core

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.util.TypedValue
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView

/**
 * In-app authorization surface.
 *
 * Two flows, both driven from the settings screen:
 *  - grant flow: the ZARZ challenge URL is opened, and the redirect that carries `grant`
 *    is intercepted, so the user never copies a token by hand.
 *  - login flow: a provider's own sign-in page is opened, and the resulting cookies stay
 *    in the shared WebView jar that [Http] reads from.
 */
object WebAuth {

    /**
     * The page has to be given a real height: inside an AlertDialog the custom view is only as tall as
     * its content, so a WebView sized by weight (height 0) collapsed to nothing and the verification
     * page never appeared.
     */
    private fun pageHeight(context: Context): Int {
        val m = context.resources.displayMetrics
        return minOf((460 * m.density).toInt(), (m.heightPixels * 0.62f).toInt())
    }

    /** the dialog theme's own text colour, so the hint reads on dark and light dialogs alike */
    private fun textColor(context: Context): Int {
        val tv = TypedValue()
        if (!context.theme.resolveAttribute(android.R.attr.textColorPrimary, tv, true)) return Color.GRAY
        if (tv.resourceId != 0) {
            @Suppress("DEPRECATION")
            return runCatching { context.resources.getColorStateList(tv.resourceId).defaultColor }.getOrDefault(Color.GRAY)
        }
        return tv.data
    }

    private fun newWebView(context: Context): WebView {
        val web = WebView(context)
        @SuppressLint("SetJavaScriptEnabled")
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = Http.userAgent
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        return web
    }

    /**
     * Opens [authUrl] and waits for a redirect that carries a `grant` query parameter.
     * [onResult] gets the grant, or null when the user closed the dialog.
     */
    fun grantFlow(context: Context, authUrl: String, onResult: (String?) -> Unit) {
        val web = newWebView(context)
        val status = TextView(context).apply {
            text = "Complete the verification, then this closes by itself."
            setTextColor(textColor(context))
            setPadding(24, 16, 24, 16)
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, pageHeight(context)))
        }
        var finished = false
        val dialog = AlertDialog.Builder(context)
            .setTitle("Authorize MusicBox")
            .setView(container)
            .setNegativeButton("Cancel") { _, _ -> if (!finished) { finished = true; onResult(null) } }
            .setOnCancelListener { if (!finished) { finished = true; onResult(null) } }
            .create()

        fun finish(grant: String?) {
            if (finished) return
            finished = true
            Diag.log("auth", if (grant != null) "grant captured" else "grant flow cancelled")
            runCatching { dialog.dismiss() }
            onResult(grant)
        }

        fun inspect(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            val parsed = runCatching { Uri.parse(url) }.getOrNull() ?: return false
            val grant = parsed.getQueryParameter("grant")
            if (!grant.isNullOrBlank()) {
                finish(grant)
                return true
            }
            val state = parsed.getQueryParameter("state")
            if (url.startsWith("mornye://session-grant") && grant.isNullOrBlank()) {
                status.text = if (state.isNullOrBlank()) "Callback reached without a grant." else "Callback reached without a grant (state=$state)."
            }
            return false
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
                inspect(request?.url?.toString())

            @Deprecated("kept for older WebView")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = inspect(url)

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                inspect(url)
            }
        }
        web.loadUrl(authUrl)
        dialog.show()
    }

    /**
     * Opens a provider sign-in page. When the user taps Done the cookies for [cookieHost]
     * are read from the shared jar; [onResult] receives them (or null).
     */
    fun loginFlow(context: Context, loginUrl: String, cookieHost: String, onResult: (String?) -> Unit) {
        val web = newWebView(context)
        val status = TextView(context).apply {
            text = "Sign in, then tap Done. Cookies are read from this session only."
            setTextColor(textColor(context))
            setPadding(24, 16, 24, 16)
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, pageHeight(context)))
        }
        var finished = false
        val dialog = AlertDialog.Builder(context)
            .setTitle("Sign in")
            .setView(container)
            .setPositiveButton("Done") { _, _ ->
                if (finished) return@setPositiveButton
                finished = true
                val cookies = runCatching { CookieManager.getInstance().getCookie(cookieHost) }.getOrNull()
                Diag.log("auth", "login flow finished, ${cookies?.length ?: 0} cookie chars for $cookieHost")
                onResult(cookies)
            }
            .setNegativeButton("Cancel") { _, _ -> if (!finished) { finished = true; onResult(null) } }
            .setOnCancelListener { if (!finished) { finished = true; onResult(null) } }
            .create()
        web.loadUrl(loginUrl)
        dialog.show()
    }

    fun isActivity(context: Context): Boolean = context is Activity
}
