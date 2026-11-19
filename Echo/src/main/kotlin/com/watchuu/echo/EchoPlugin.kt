package com.watchuu.echo

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class EchoPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(EchoProvider())
    }
}
