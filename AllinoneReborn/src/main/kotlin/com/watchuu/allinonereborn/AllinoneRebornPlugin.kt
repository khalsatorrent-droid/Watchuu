package com.watchuu.allinonereborn

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AllinoneRebornPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(JioTvProvider())
        registerMainAPI(FancodeProvider())
    }
}
