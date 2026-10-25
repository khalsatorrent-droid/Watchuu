package com.watchuu.vivarium

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class VivariumPlugin : Plugin() {
    override fun load(context: Context) {
        VivariumBrowser.init(context)
        registerMainAPI(VivariumProvider())
        // gear icon in Extensions -> Vivarium: Cloudflare bypass settings
        openSettings = { ctx -> VivariumSettings.show(ctx) }
    }
}
