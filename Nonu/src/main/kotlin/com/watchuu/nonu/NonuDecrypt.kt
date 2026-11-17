package com.watchuu.nonu

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object NonuDecrypt {
    private const val TAG = "NonuDecrypt"

    /** Widevine KEYFORMAT, not written in /webplayback song m3u8s */
    private const val WIDEVINE_KEYFORMAT = "urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"

    fun log(msg: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, msg)
    }

    /**
     * Parse Apple's raw media m3u8 (with EXT-X-KEY), returning init segment and byte/time ranges of each segment.
     * Each segment records its applicable key: prefetch (embedded preacquisition template), track (track template or CENC
     * content key) or null (unencrypted).
     * When cenc is true, keyUri is a data: URI (Widevine KID), else it's a FairPlay skd:// URI.
     * adamId is taken from filename (..._A<adamId>_... or ..._A<adamId>....), or can be given by #adamId= in the URL.
     */
    fun parseMediaPlaylist(text: String, playlistUrl: String, adamIdHint: String?): MediaPlaylist {
        val fileName = playlistUrl.substringAfterLast("/")
        val adamId = adamIdHint ?: "/_A(\\d+)[._]/".toRegex().find(fileName)?.groupValues?.get(1) ?: ""

        var init: MediaSegment? = null
        var keyUri: String? = null
        var currentKey: String? = null
        var duration = 0f
        var pendingDuration: Float? = null
        var next = 0L
        val segments = mutableListOf<MediaSegment>()

        fun byteRange(value: String, fallbackOffset: Long): Pair<Long, Long> {
            val (len, off) = value.replace("\"", "").trim().split("@")
            val start = off?.toLongOrNull() ?: fallbackOffset
            return start to start + len.toLong() - 1
        }

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-KEY:") -> {
                    val method = "METHOD=([^,]+)".toRegex().find(line)?.groupValues?.get(1)
                    val uri = "URI=\"([^\"]+)\"".toRegex().find(line)?.groupValues?.get(1)
                    val format = "KEYFORMAT=\"([^\"]*)\"".toRegex().find(line)?.groupValues?.get(1)
                    // When multiple DRM keys are listed, only take the Widevine one
                    if (uri?.startsWith("data:") == true && format?.lowercase() != WIDEVINE_KEYFORMAT) continue
                    currentKey = if (method == "NONE" || uri == null) null else uri
                    if (currentKey != null && currentKey != "skd://itunes.apple.com/P000000000/s1/e1" && keyUri == null) keyUri = currentKey
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    val uri = "URI=\"([^\"]+)\"".toRegex().find(line)?.groupValues?.get(1)
                    val range = "BYTERANGE=\"([^\"]+)\"".toRegex().find(line)?.groupValues?.get(1)
                    if (uri == null || range == null) throw IllegalArgumentException("Invalid EXT-X-MAP: $line")

                    val (start, end) = byteRange(range, 0)
                    init = MediaSegment(uri, start, end, 0f, 0f, true)
                    next = end + 1
                }
                line.startsWith("#EXTINF:") -> {
                    pendingDuration = line.substring(8).toFloatOrNull()
                }
                line.startsWith("#EXT-X-BYTERANGE:") -> {
                    val (start, end) = byteRange(line.substring(17), next)
                    next = end + 1
                    val dur = pendingDuration ?: 0f
                    val key = when (currentKey) {
                        "skd://itunes.apple.com/P000000000/s1/e1" -> "prefetch"
                        null -> null
                        else -> "track"
                    }
                    segments += MediaSegment(init?.url ?: "", start, end, duration, dur, key)
                    duration += dur
                    pendingDuration = null
                }
            }
        }

        if (init == null || segments.isEmpty()) throw IllegalArgumentException("Invalid media playlist")
        if (segments.any { it.key == "track" } && (keyUri == null || adamId.isEmpty())) throw IllegalArgumentException("Missing key URI or adamId")

        val cenc = keyUri?.startsWith("data:") == true
        return MediaPlaylist(init!!.url, adamId, keyUri, cenc, init!!, segments, duration, segments.last().end + 1)
    }

    suspend fun decrypt(context: Context, track: MediaPlaylist, aborted: Boolean = false) = withContext(Dispatchers.IO) {
        val wasm = WasmInstance(context)

        // Download and decrypt each segment
        val pieces = listOf(track.init) + track.segments
        for (piece in pieces) {
            if (aborted) break

            // TODO: Implement downloadPiece
            val bytes = ByteArray(0) // Placeholder
            if (piece.init) {
                if (track.cenc) track.rawInit = bytes.clone()
                // TODO: Define patchInit in WasmInstance
                wasm.patchInit(bytes)
            } else {
                val init = if (track.repair == "false") null else track.initData()
                when (piece.key) {
                    "prefetch" -> wasm.decryptFragment(wasm.hook_prefetch_template(), bytes, init)
                    "track" -> if (track.cenc) {
                            val key = fetchContentKey(track.adamId, track.keyUri!!)
                            wasm.decryptCenc(track.rawInit, key, bytes)
                        } else {
                            val template = fetchTemplate(track.adamId, track.keyUri!!)
                            val handle = wasm.hook_template_load(template.toByteArray(), template.length)
                            try {
                                wasm.decryptFragment(handle, bytes, init)
                            } finally {
                                wasm.hook_template_free(handle)
                            }
                        }
                    null -> if (init != null) wasm.decryptFragment(0, bytes, init)
                }
            }

            val decryptedFile = File(context.cacheDir, "${track.adamId}_${piece.start}.dec")
            decryptedFile.writeBytes(bytes)
            piece.decryptedUrl = decryptedFile.absolutePath
        }
    }

    private suspend fun fetchContentKey(adamId: String, keyUri: String): ByteArray {
        // TODO
        return ByteArray(0)
    }

    private suspend fun fetchTemplate(adamId: String, keyUri: String): String {
        // TODO
        return ""
    }
}

data class MediaPlaylist(
    val url: String,
    val adamId: String,
    val keyUri: String?,
    val cenc: Boolean,
    val init: MediaSegment,
    val segments: List<MediaSegment>,
    val duration: Float,
    val size: Long
)

data class MediaSegment(
    val url: String,
    val start: Long,
    val end: Long,
    val time: Float,
    val duration: Float,
    val key: String?, // "prefetch", "track" or null
    var decryptedUrl: String? = null
)

private val LICENSE_SYSTEMS = listOf(
    LicenseSystem("widevine", emptyMap()),
    LicenseSystem("playready", mapOf("drm-type" to "pr"))
)

data class LicenseSystem(val type: String, val body: Map<String, String>)

data class LicenseChallenge(val adamId: String, val uri: String, val challenge: String)

private suspend fun fetchContentKey(adamId: String, keyUri: String): ByteArray {
    val data = NonuApi.license(adamId, keyUri, getLicenseChallenge(keyUri)).body()
    return mediaLicense(data.license)
}

private suspend fun fetchTemplate(adamId: String, keyUri: String): String =
    NonuApi.key(adamId, keyUri).body()

private suspend fun getLicenseChallenge(keyUri: String): String {
    val drm = LICENSE_SYSTEMS.first()
    val challenge = mediaChallenge(keyUri, drm.type)
    return mapOf(
        "adamId" to challenge.adamId,
        "uri" to challenge.uri,
        "challenge" to challenge.challenge
    ) + drm.body
}

private suspend fun mediaChallenge(keyUri: String, drm: String): LicenseChallenge {
    // TODO: Port media.wasm challenge generation
}

private suspend fun mediaLicense(license: String): ByteArray {
    // TODO: Port media.wasm license parsing
}