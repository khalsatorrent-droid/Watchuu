package com.watchuu.nonu

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NonuPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(NonuProvider())
        runCatching { NonuApi.init(context) }
        runCatching { openSettings = { ctx -> NonuSettings.show(ctx) } }
    }
}
