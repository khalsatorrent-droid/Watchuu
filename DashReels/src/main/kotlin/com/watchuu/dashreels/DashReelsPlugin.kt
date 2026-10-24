package com.watchuu.dashreels

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DashReelsPlugin : Plugin() {
    override fun load(context: Context) {
        DashReelsAuth.init(context)
        registerMainAPI(DashReelsProvider())
    }
}
