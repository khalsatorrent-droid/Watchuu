package com.watchuu.latestpornvideo

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class LatestPornVideoPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(LatestPornVideoProvider())
    }
}
