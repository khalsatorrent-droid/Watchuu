package com.watchuu.cinephile

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CinephilePlugin : Plugin() {
    override fun load(context: Context) {
        // register first, so the provider is live even if the WebView transport cannot start
        registerMainAPI(CinephileProvider())
        runCatching { CinephileBrowser.init(context) }
        // the gear icon: challenge mode, solve now, test connection, cookies
        openSettings = { ctx -> CinephileSettings.show(ctx) }
    }
}
