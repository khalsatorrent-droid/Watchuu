package com.watchuu.nartodrama

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NartoDramaPlugin : Plugin() {
    override fun load(context: Context) {
        NartoBrowser.init(context)
        registerMainAPI(NartoDramaProvider())
    }
}
