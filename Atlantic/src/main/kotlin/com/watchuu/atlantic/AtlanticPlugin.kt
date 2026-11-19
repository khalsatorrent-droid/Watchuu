package com.watchuu.atlantic

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AtlanticPlugin : Plugin() {
    override fun load(context: Context) {
        // Register first, so the provider is live even if the setup below fails.
        registerMainAPI(AtlanticProvider())
        runCatching { AtlanticTmdb.init(context) }
        // playback loads the resolver library on first use and needs a context to find its folder
        runCatching { AtlanticNative.init(context) }
        // Gear icon in Extensions -> this plugin.
        openSettings = { ctx -> AtlanticSettings.show(ctx) }
    }
}
