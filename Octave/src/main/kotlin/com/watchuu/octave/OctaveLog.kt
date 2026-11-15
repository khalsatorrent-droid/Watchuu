package com.watchuu.octave

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Every step the extension takes, kept in memory and shown in settings.
 *
 * Nothing secret goes in here: the account key and the playback token are only ever reported as
 * "set" or "absent", never by value. What is recorded is the shape of each step - which endpoint,
 * which route (http or page), what came back, how long it took - so a failure can be read off the
 * screen instead of guessed at.
 */
object OctaveLog {
    private const val MAX = 400
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun add(tag: String, message: String) {
        val stamp = fmt.format(Date())
        lines.addLast("$stamp $tag: $message")
        while (lines.size > MAX) lines.removeFirst()
    }

    fun step(message: String) = add("step", message)
    fun net(message: String) = add("net", message)
    fun auth(message: String) = add("auth", message)
    fun warn(message: String) = add("WARN", message)

    @Synchronized
    fun dump(): String = if (lines.isEmpty()) "nothing logged yet" else lines.joinToString("\n")

    @Synchronized
    fun clear() {
        lines.clear()
        add("step", "log cleared")
    }
}
