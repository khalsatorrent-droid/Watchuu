package com.watchuu.dropmms

import android.content.Context
import com.lagradost.cloudstream3.extractors.LuluStream
import com.lagradost.cloudstream3.extractors.StreamTape
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DropMMSPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DropMMSProvider())
        // current mirror domains of built-in extractors (same code, newer domain)
        registerExtractorAPI(Luluvido())
        registerExtractorAPI(TapeAdsEnjoyer())
    }
}

class Luluvido : LuluStream() {
    override val mainUrl = "https://luluvido.com"
}

class TapeAdsEnjoyer : StreamTape() {
    override var mainUrl = "https://tapeadsenjoyer.com"
}
