package com.watchuu.mmsvibex

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class MmsvibexPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MmsvibexProvider())
    }
}
