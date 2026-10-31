package com.watchuu.pornxday

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class PornxdayPlugin : Plugin() {
    override fun load(context: Context) {
        // Register first, so the provider is live even if the WebView transport cannot start.
        registerMainAPI(PornxdayProvider())
        runCatching { PornxdaySettings.init(context) }
        runCatching { PornxdayBrowser.init(context) }
        // Gear icon in Extensions -> this plugin: paste the Cloudflare cookie.
        openSettings = { ctx -> PornxdaySettings.show(ctx) }
    }
}
