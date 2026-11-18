package com.watchuu.nonu

import android.content.Context
import android.util.Log

/**
 * Pure Kotlin replacement for the WASM-based decryption instance.
 * Manages template handles and delegates to TemariCipher, Mp4Patcher, and AlacRepair.
 */
class WasmInstance(private val context: Context) {

    companion object {
        private const val TAG = "WasmInstance"
        private var prefetchTpl: TemariCipher.Template? = null
        private val templates = HashMap<Int, TemariCipher.Template>()
        private var nextHandle = 1

        // ALAC track info parsed from init segment, cached per-instance
        private var cachedAlacTrack: AlacRepair.AlacTrack? = null
        private var cachedAlacInitHash: Int = 0
    }

    /**
     * Load the embedded prefetch template from assets.
     * Returns a handle (always 0xFFFF for prefetch).
     */
    fun hook_prefetch_template(): Int {
        if (prefetchTpl == null) {
            try {
                val data = context.assets.open("prefetch_template.bin").use { it.readBytes() }
                prefetchTpl = TemariTemplate.prefetchTemplate(data)
                Log.d(TAG, "Prefetch template loaded: ${data.size} bytes")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load prefetch template", e)
                return 0
            }
        }
        return 0xFFFF
    }

    /**
     * Parse a JSON template string and return a handle.
     */
    fun loadTemplate(templateBytes: ByteArray): Int {
        return try {
            val json = String(templateBytes, Charsets.UTF_8)
            val tpl = TemariTemplate.templateFromJson(json)
            val handle = synchronized(templates) {
                val h = nextHandle++
                templates[h] = tpl
                h
            }
            Log.d(TAG, "Template loaded, handle=$handle")
            handle
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load template: ${e.message}", e)
            val trace = e.stackTraceToString().take(500)
            NonuDecrypt.log("ERROR: loadTemplate failed: ${e::class.simpleName}: ${e.message}\n$trace")
            0
        }
    }

    /**
     * Free a template handle.
     */
    fun hook_template_free(handle: Int) {
        if (handle == 0xFFFF) return // prefetch template is static
        synchronized(templates) { templates.remove(handle) }
    }

    /**
     * Patch init segment in-place: remove DRM box markers (enca→original codec, sinf→free, pssh→free).
     */
    fun patchInit(bytes: ByteArray) {
        try {
            Mp4Patcher.patchInitInPlace(bytes)
            Log.d(TAG, "Init segment patched")
        } catch (e: Exception) {
            Log.e(TAG, "patchInit failed: ${e.message}", e)
        }
    }

    /**
     * Decrypt a fragment in-place using the given template handle, then repair ALAC if applicable.
     * @param handle Template handle (0xFFFF for prefetch, >0 for track template, 0 for no decryption)
     * @param bytes Fragment data, modified in-place
     * @param init Patched init segment for ALAC repair (may be null)
     */
    fun decryptFragment(handle: Int, bytes: ByteArray, init: ByteArray?) {
        try {
            // Get the template
            val tpl = when {
                handle == 0xFFFF -> prefetchTpl
                handle > 0 -> synchronized(templates) { templates[handle] }
                else -> null
            }

            // Decrypt the fragment using the template
            if (tpl != null) {
                Mp4Patcher.decryptFragmentInPlace(tpl, bytes)
            }

            // Repair ALAC if init segment is available
            if (init != null) {
                repairAlac(bytes, init)
            }
        } catch (e: Exception) {
            Log.e(TAG, "decryptFragment failed: ${e.message}", e)
        }
    }

    private fun repairAlac(fragData: ByteArray, initData: ByteArray) {
        try {
            // Cache the ALAC track info from the init segment
            val initHash = initData.contentHashCode()
            if (cachedAlacTrack == null || cachedAlacInitHash != initHash) {
                cachedAlacTrack = AlacRepair.parseAlacTrack(initData)
                cachedAlacInitHash = initHash
            }
            val track = cachedAlacTrack ?: return
            AlacRepair.repairAlacFragment(track, fragData)
        } catch (e: Exception) {
            Log.d(TAG, "ALAC repair skipped: ${e.message}")
        }
    }
}
