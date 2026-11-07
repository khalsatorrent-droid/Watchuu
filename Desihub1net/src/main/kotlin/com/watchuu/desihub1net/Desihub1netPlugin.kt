package com.watchuu.desihub1net

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Desihub1netPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Desihub1netProvider())
    }
}
