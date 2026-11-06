package com.watchuu.desihubskin

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DesihubskinPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DesihubskinProvider())
    }
}
