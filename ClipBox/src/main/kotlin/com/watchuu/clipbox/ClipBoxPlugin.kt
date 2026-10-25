package com.watchuu.clipbox

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.watchuu.clipbox.providers.TioAnimeProvider

/**
 * ClipBox - a single multi-API extension bundling the streaming sites from StreamFlix Reborn
 * (the app this was ported from). Every site is registered as its own MainAPI, so each one shows
 * up as a separate selectable source in Cloudstream. Providers are added in batches; register each
 * new one here.
 */
@CloudstreamPlugin
class ClipBoxPlugin : Plugin() {
    override fun load(context: Context) {
        // ---- batch 1 ----
        registerMainAPI(TioAnimeProvider())
    }
}
