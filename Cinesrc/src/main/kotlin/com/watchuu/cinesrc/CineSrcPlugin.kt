package com.watchuu.cinesrc

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CineSrcPlugin : Plugin() {
    override fun load(context: Context) {
        CineSrcBrowser.init(context)
        registerMainAPI(CineSrcProvider())
    }
}
