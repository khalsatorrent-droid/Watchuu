package com.watchuu.goplay

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class GoPlayPlugin : Plugin() {
    override fun load(context: Context) {
        // register first, so the provider is live even if the WebView transport cannot start
        registerMainAPI(GoPlayProvider())
        runCatching { GoPlayBrowser.init(context) }
        // the gear icon: bypass mode, solve now, test connection, cookies
        openSettings = { ctx -> GoPlaySettings.show(ctx) }
    }
}
