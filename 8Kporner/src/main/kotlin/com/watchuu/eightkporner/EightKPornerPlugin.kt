package com.watchuu.eightkporner

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class EightKPornerPlugin : Plugin() {
    override fun load(context: Context) {
        // Register first, so the provider is live even if the WebView transport cannot start.
        registerMainAPI(EightKPornerProvider())
        runCatching { EightKPornerSettings.init(context) }
        runCatching { EightKPornerBrowser.init(context) }
        // Gear icon in Extensions -> this plugin: paste the Cloudflare cookie.
        openSettings = { ctx -> EightKPornerSettings.show(ctx) }
    }
}
