package com.watchuu.xvideotube

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class XVideoTubePlugin : Plugin() {
    override fun load(context: Context) {
        // register first, so the provider is live even if the WebView transport cannot start
        registerMainAPI(XVideoTubeProvider())
        runCatching { XVideoTubeBrowser.init(context) }
        // the gear icon: bypass mode, solve now, test connection, cookies
        openSettings = { ctx -> XVideoTubeSettings.show(ctx) }
    }
}
