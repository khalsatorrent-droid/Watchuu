package com.watchuu.cinelite

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CineLitePlugin : Plugin() {
    override fun load(context: Context) {
        CineLiteBrowser.init(context)
        registerMainAPI(CineLiteProvider())
    }
}
