package com.watchuu.flixvision

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FlixVisionPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FlixVisionProvider())
    }
}
