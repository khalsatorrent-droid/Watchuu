package com.watchuu.hicn

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class HicnPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(HicnProvider())
    }
}
