package com.watchuu.nonu

import android.content.Context
import com.lagradost.cloudstream3.BuildConfig

class WasmInstance(private val context: Context) {
    companion object {
        init {
            System.loadLibrary("hook")
        }
    }

    external fun hook_alloc(len: Int): Int
    external fun hook_free(ptr: Int, len: Int)
    external fun hook_error_ptr(): Int
    external fun hook_error_len(): Int
    external fun hook_patch_init(ptr: Int, len: Int)
    external fun hook_decrypt_cenc(initPtr: Int, initLen: Int, keyPtr: Int, ptr: Int, len: Int): Boolean
    external fun hook_decrypt_fragment(handle: Int, ptr: Int, len: Int): Boolean
    external fun hook_repair_alac(ptr: Int, len: Int, initPtr: Int, initLen: Int): Boolean
    external fun hook_prefetch_template(): Int
    external fun hook_template_load(ptr: Int, len: Int): Int
    external fun hook_template_free(handle: Int)

    private var memory: ByteArray? = null

    fun lastError(): String {
        val ptr = hook_error_ptr()
        val len = hook_error_len()
        return String(memory!!, ptr, len)
    }

    fun patchInit(bytes: ByteArray) {
        copyToMemory(bytes) { ptr, len ->
            hook_patch_init(ptr, len)
        }
    }

    fun decryptCenc(init: ByteArray, key: ByteArray, bytes: ByteArray) {
        copyToMemory(init) { initPtr, initLen ->
            copyToMemory(key) { keyPtr, _ ->
                copyToMemory(bytes) { ptr, len ->
                    if (!hook_decrypt_cenc(initPtr, initLen, keyPtr, ptr, len)) {
                        throw IllegalStateException("CENC decryption failed: ${lastError()}")
                    }
                }
            }
        }
    }

    fun decryptFragment(handle: Int, bytes: ByteArray, init: ByteArray?) {
        copyToMemory(bytes) { ptr, len ->
            if (handle != 0 && !hook_decrypt_fragment(handle, ptr, len)) {
                throw IllegalStateException("Fragment decryption failed: ${lastError()}")
            }
            if (init != null) {
                copyToMemory(init) { initPtr, initLen ->
                    if (!hook_repair_alac(ptr, len, initPtr, initLen)) {
                        throw IllegalStateException("ALAC repair failed: ${lastError()}")
                    }
                }
            }
        }
    }

    private inline fun copyToMemory(bytes: ByteArray, block: (Int, Int) -> Unit) {
        val ptr = hook_alloc(bytes.size)
        try {
            memory?.let {
                System.arraycopy(bytes, 0, it, ptr, bytes.size)
                block(ptr, bytes.size)
                System.arraycopy(it, ptr, bytes, 0, bytes.size)
            }
        } finally {
            hook_free(ptr, bytes.size)
        }
    }
}