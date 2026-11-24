package com.watchuu.musicbox.core

import android.util.Log

/**
 * Diagnostics ring buffer. The settings screen shows it, so a failed authorization
 * can be read back on the device instead of guessed at.
 */
object Diag {
    private const val TAG = "MusicBox"
    private const val MAX = 400
    private val lines = ArrayDeque<String>()

    @Synchronized
    fun log(area: String, message: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        val line = "$stamp [$area] $message"
        lines.addLast(line)
        while (lines.size > MAX) lines.removeFirst()
        Log.d(TAG, line)
    }

    @Synchronized
    fun dump(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() = lines.clear()
}
