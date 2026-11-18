package com.watchuu.arcod

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ArcodPlugin : Plugin() {
    override fun load(context: Context) {
        // the provider first, so anything that fails afterwards still leaves it registered
        registerMainAPI(ArcodProvider())
        runCatching { ArcodApi.init(context) }
        runCatching { openSettings = { ctx -> ArcodSettings.show(ctx) } }
    }
}
