package com.watchuu.octave

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class OctavePlugin : Plugin() {
    override fun load(context: Context) {
        // the provider first, so anything that fails afterwards still leaves it registered
        registerMainAPI(OctaveProvider())
        runCatching {
            OctaveApi.init(context)
            OctaveApi.attach(context)
        }
        runCatching { openSettings = { ctx -> OctaveSettings.show(ctx) } }
    }
}
