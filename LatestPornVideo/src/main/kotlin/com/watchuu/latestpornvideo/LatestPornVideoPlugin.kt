package com.watchuu.latestpornvideo

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class LatestPornVideoPlugin : Plugin() {
    override fun load(context: Context) {
        // Register first, so the provider is live even if the WebView transport cannot start.
        registerMainAPI(LatestPornVideoProvider())
        runCatching { LatestPornVideoSettings.init(context) }
        runCatching { LatestPornVideoBrowser.init(context) }
        // Gear icon in Extensions -> this plugin: paste the Cloudflare cookie.
        openSettings = { ctx -> LatestPornVideoSettings.show(ctx) }
    }
}
