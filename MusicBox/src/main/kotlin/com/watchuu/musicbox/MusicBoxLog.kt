package com.watchuu.musicbox

import android.util.Log

/**
 * A log you can read inside the extension.
 *
 * logcat is not reachable on a phone without a cable, and every question about this extension has
 * come down to "which step failed" - the resolver handshake, a descriptor that came back encrypted,
 * a source that timed out. So the same lines go to a small in-memory ring buffer that the settings
 * screen can show.
 *
 * Kept in memory on purpose: a few hundred lines, no disk writes, nothing to grow out of hand.
 */
object MusicBoxLog {
    private const val MAX_LINES = 400
    private val lines = ArrayDeque<String>()

    @Synchronized
    private fun add(level: String, tag: String, message: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        lines.addLast("$stamp $level/$tag: $message")
        while (lines.size > MAX_LINES) lines.removeFirst()
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        add("D", tag, message)
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
        add("W", tag, message)
    }

    fun e(tag: String, message: String) {
        Log.e(tag, message)
        add("E", tag, message)
    }

    @Synchronized
    fun size(): Int = lines.size

    @Synchronized
    fun dump(): String =
        if (lines.isEmpty()) "No entries yet. Play a track, then come back."
        else lines.joinToString("\n")

    @Synchronized
    fun clear() = lines.clear()
}
