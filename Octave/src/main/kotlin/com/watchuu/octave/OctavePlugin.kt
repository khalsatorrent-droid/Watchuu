package com.watchuu.octave

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class OctavePlugin : Plugin() {
    override fun load(context: Context) {
        // the provider first: anything that throws after this still leaves it registered
        registerMainAPI(OctaveProvider())
        runCatching { OctaveCf.init(context) }
        // gear icon in Extensions -> Octave: Cloudflare / account settings
        runCatching { openSettings = { ctx -> OctaveSettings.show(ctx) } }
    }
}
