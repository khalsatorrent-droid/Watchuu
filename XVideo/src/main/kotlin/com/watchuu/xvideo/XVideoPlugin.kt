package com.watchuu.xvideo

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class XVideoPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(XVideoProvider())
    }
}
