package com.watchuu.filmin

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FilminPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FilminProvider())
    }
}
