package com.watchuu.kiligdrama

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KiligDramaPlugin : Plugin() {
    override fun load(context: Context) {
        KiligBrowser.init(context)
        registerMainAPI(KiligDramaProvider())
    }
}
