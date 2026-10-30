package com.watchuu.latestpornvideo

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class LatestPornVideoPlugin : Plugin() {
    override fun load(context: Context) {
        // The WebView transport needs an application context before any request is made.
        LatestPornVideoBrowser.init(context)
        registerMainAPI(LatestPornVideoProvider())
    }
}
