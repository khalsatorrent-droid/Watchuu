package com.watchuu.eightkporner

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class EightKPornerPlugin : Plugin() {
    override fun load(context: Context) {
        // Register first, so the provider is live even if the WebView transport cannot start.
        // A throw from the transport used to take the whole plugin down, which the app reports as
        // "could not load extension" - i.e. it looked like an install failure.
        registerMainAPI(EightKPornerProvider())
        runCatching { EightKPornerBrowser.init(context) }
    }
}
