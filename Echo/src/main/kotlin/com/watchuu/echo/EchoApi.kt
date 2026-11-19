package com.watchuu.echo

import com.lagradost.cloudstream3.app
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Echo - YouTube Music through InnerTube.
 *
 * The app this is modelled on (Echo Music) talks to the same endpoint the music.youtube.com web
 * client uses, and so does this:
 *
 *   POST https://music.youtube.com/youtubei/v1/search?prettyPrint=false
 *   POST https://music.youtube.com/youtubei/v1/browse?prettyPrint=false
 *
 * with the WEB_REMIX client identity in the headers and in the body. No api key is involved, and no
 * login is needed for search, home, albums or playlists - the catalogue is public.
 *
 * Only the catalogue is fetched here. Playback is handed to Cloudstream's own YouTube extractor,
 * which already solves the signature and throttling problems that make a hand-rolled player endpoint
 * unreliable.
 */
object EchoApi {
    const val SITE = "https://music.youtube.com"
    const val BUILD = "v1"

    private const val CLIENT_NAME = "WEB_REMIX"
    private const val CLIENT_VERSION = "1.20260213.01.00"
    private const val CLIENT_ID = "67"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /** gl/hl decide the catalogue region and the language names come back in */
    var region: String = "IN"
    var language: String = "en"

    private fun headers(): Map<String, String> = mapOf(
        "Content-Type" to "application/json",
        "Accept" to "application/json",
        "Accept-Language" to "$language-$region,en;q=0.9",
        "X-YouTube-Client-Name" to CLIENT_ID,
        "X-YouTube-Client-Version" to CLIENT_VERSION,
        "X-Goog-Api-Format-Version" to "1",
        "X-Origin" to SITE,
        "Referer" to "$SITE/",
        "User-Agent" to UA,
    )

    private fun context(): JSONObject = JSONObject()
        .put(
            "client", JSONObject()
                .put("clientName", CLIENT_NAME)
                .put("clientVersion", CLIENT_VERSION)
                .put("gl", region)
                .put("hl", language)
        )

    private suspend fun post(endpoint: String, body: JSONObject): JSONObject? {
        val url = "$SITE/youtubei/v1/$endpoint?prettyPrint=false"
        return try {
            val r = app.post(
                url,
                headers = headers(),
                requestBody = body.toString().toRequestBody("application/json".toMediaType()),
                timeout = 25L
            )
            if (r.code !in 200..299) null else runCatching { JSONObject(r.text) }.getOrNull()
        } catch (e: Throwable) {
            null
        }
    }

    suspend fun search(query: String, params: String? = null): JSONObject? =
        post("search", JSONObject().put("context", context()).put("query", query).apply {
            if (params != null) put("params", params)
        })

    suspend fun browse(browseId: String, params: String? = null): JSONObject? =
        post("browse", JSONObject().put("context", context()).put("browseId", browseId).apply {
            if (params != null) put("params", params)
        })

    /** the music home page, a tree of carousels */
    suspend fun home(): JSONObject? = browse("FEmusic_home")

    fun albumBrowseId(albumId: String) = if (albumId.startsWith("MPRE")) albumId else "MPREb_$albumId"

}
