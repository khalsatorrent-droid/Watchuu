package com.watchuu.shortfree

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ShortFreePlugin : Plugin() {
    override fun load(context: Context) {
        ShortFreeAuth.init(context)
        registerMainAPI(ShortFreeProvider())
        openSettings = { ctx -> ShortFreeSettings.show(ctx) }
    }
}
