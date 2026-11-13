package com.watchuu.musicbox

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.*
import android.widget.LinearLayout

/**
 * WebView-based browser for ZARZ verification challenges.
 *
 * When a ZARZ bootstrap returns a challenge URL, the user must complete a
 * Turnstile/hCaptcha/etc. verification in a browser. After verification,
 * the page redirects to a callback URL containing a `grant` token. This
 * class intercepts that redirect and returns the grant to the caller.
 */
object MusicBoxBrowser {

    private const val GRANT_CALLBACK_SCHEME = "mornye"
    private const val GRANT_PARAM = "grant"

    /**
     * Open a WebView dialog to complete the ZARZ verification challenge.
     *
     * @param activity  The current activity context.
     * @param url       The verification URL from bootstrap().
     * @param onGrant   Called with the grant token once verification succeeds.
     */
    fun openVerification(
        activity: Activity,
        url: String,
        onGrant: (String) -> Unit,
    ) {
        val webView = WebView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
            }
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Verification Required")
            .setView(webView)
            .setNegativeButton("Cancel", null)
            .setCancelable(true)
            .create()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val uri = request.url ?: return false
                return handleUrl(uri, dialog, onGrant)
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                return handleUrl(Uri.parse(url), dialog, onGrant)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Also check the URL on page navigation start
                if (url != null) {
                    val uri = Uri.parse(url)
                    handleUrl(uri, dialog, onGrant)
                }
            }
        }

        dialog.setOnDismissListener {
            webView.destroy()
        }

        dialog.show()
        webView.loadUrl(url)
    }

    /**
     * Check if the URL is a grant callback and extract the token.
     * Returns true if the URL was intercepted.
     */
    private fun handleUrl(
        uri: Uri,
        dialog: AlertDialog,
        onGrant: (String) -> Unit,
    ): Boolean {
        // Check for custom scheme callback (mornye://...)
        if (uri.scheme?.lowercase() == GRANT_CALLBACK_SCHEME) {
            val grant = uri.getQueryParameter(GRANT_PARAM)
                ?: uri.lastPathSegment
                ?: ""
            if (grant.isNotBlank()) {
                dialog.dismiss()
                onGrant(grant)
                return true
            }
        }

        // Check for grant parameter in any URL
        val grant = uri.getQueryParameter(GRANT_PARAM)
        if (!grant.isNullOrBlank()) {
            dialog.dismiss()
            onGrant(grant)
            return true
        }

        // Check for /session/exchange or /callback paths with grant
        val path = uri.path?.lowercase() ?: ""
        if (path.contains("callback") || path.contains("exchange") || path.contains("grant")) {
            val grantFromPath = uri.getQueryParameter(GRANT_PARAM)
                ?: uri.getQueryParameter("code")
                ?: uri.getQueryParameter("token")
            if (!grantFromPath.isNullOrBlank()) {
                dialog.dismiss()
                onGrant(grantFromPath)
                return true
            }
        }

        return false
    }
}
