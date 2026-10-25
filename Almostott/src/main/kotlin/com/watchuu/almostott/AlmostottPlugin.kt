package com.watchuu.almostott

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AlmostottPlugin : Plugin() {
    override fun load(context: Context) {
        AlmostottBrowser.init(context)
        registerMainAPI(AlmostottProvider())
    }
}
