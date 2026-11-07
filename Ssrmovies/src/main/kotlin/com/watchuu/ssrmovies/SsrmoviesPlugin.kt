package com.watchuu.ssrmovies

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SsrmoviesPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SsrmoviesProvider())
        runCatching { SsrmoviesBrowser.init(context) }
        openSettings = { ctx -> SsrmoviesSettings.show(ctx) }
    }
}
