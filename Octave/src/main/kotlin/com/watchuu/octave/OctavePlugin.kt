package com.watchuu.octave

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class OctavePlugin : Plugin() {
    override fun load(context: Context) {
        OctaveCf.init(context)
        registerMainAPI(OctaveProvider())
        // gear icon in Extensions -> Octave: Cloudflare / account settings
        openSettings = { ctx -> OctaveSettings.show(ctx) }
    }
}
