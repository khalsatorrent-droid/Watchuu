package com.watchuu.applymusic

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ApplyMusicPlugin : Plugin() {
    override fun load(context: Context) {
        // the provider first, so anything that fails afterwards still leaves it registered
        registerMainAPI(ApplyMusicProvider())
        runCatching { ApplyMusicApi.init(context) }
        runCatching { openSettings = { ctx -> ApplyMusicSettings.show(ctx) } }
    }
}
