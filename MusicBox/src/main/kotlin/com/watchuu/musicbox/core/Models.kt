package com.watchuu.musicbox.core

import org.json.JSONArray
import org.json.JSONObject

/** One audio source returned for a track. */
data class Stream(
    val url: String,
    val quality: String,
    val mime: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val provider: String = "",
    val sizeBytes: Long? = null,
    /** True when the URL is an HLS playlist (SoundCloud AAC, TIDAL segments). */
    val hls: Boolean = false,
)

/** Canonical track record shared by every provider port. */
data class Track(
    val provider: String,
    val id: String,
    val title: String,
    val artists: List<String> = emptyList(),
    val albumName: String? = null,
    val albumId: String? = null,
    val cover: String? = null,
    val durationMs: Long? = null,
    val isrc: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val explicit: Boolean = false,
    val webUrl: String? = null,
) {
    val artistLine: String get() = if (artists.isEmpty()) "Unknown artist" else artists.joinToString(", ")
}

data class Album(
    val provider: String,
    val id: String,
    val title: String,
    val artists: List<String> = emptyList(),
    val cover: String? = null,
    val year: Int? = null,
    val trackCount: Int? = null,
    val albumType: String? = null,
    val label: String? = null,
    val tracks: List<Track> = emptyList(),
    val webUrl: String? = null,
)

data class Artist(
    val provider: String,
    val id: String,
    val name: String,
    val image: String? = null,
    val albums: List<Album> = emptyList(),
    val topTracks: List<Track> = emptyList(),
    val webUrl: String? = null,
)

/** A search hit: exactly one of the three payloads is set. */
data class Hit(
    val kind: Kind,
    val title: String,
    val subtitle: String?,
    val cover: String?,
    val url: String,
    val track: Track? = null,
    val album: Album? = null,
    val artist: Artist? = null,
) {
    enum class Kind { TRACK, ALBUM, ARTIST }
}

/** Uniform result wrapper so the UI can show why a provider failed. */
sealed class Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>()
    data class Err(val message: String, val cause: Throwable? = null) : Outcome<Nothing>()

    fun valueOrNull(): T? = (this as? Ok)?.value
}

fun jsonOrNull(text: String): JSONObject? = try {
    JSONObject(text)
} catch (e: Throwable) {
    null
}

fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val v = optString(key, "")
    return if (v.isBlank() || v == "null") null else v
}

fun JSONObject.optLongOrNull(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    val v = optLong(key, Long.MIN_VALUE)
    return if (v == Long.MIN_VALUE) null else v
}

fun JSONObject.optIntOrNull(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    val v = optInt(key, Int.MIN_VALUE)
    return if (v == Int.MIN_VALUE) null else v
}

fun JSONObject.array(key: String): JSONArray? = optJSONArray(key)

fun JSONArray.objects(): List<JSONObject> {
    val out = ArrayList<JSONObject>(length())
    for (i in 0 until length()) optJSONObject(i)?.let { out.add(it) }
    return out
}

/** Join artist names from the shapes Mornye's providers actually return. */
fun artistNames(value: Any?): List<String> {
    when (value) {
        null -> return emptyList()
        is String -> return if (value.isBlank()) emptyList() else listOf(value)
        is JSONArray -> {
            val out = ArrayList<String>()
            for (o in value.objects()) {
                val name = o.optStringOrNull("name")
                    ?: o.optStringOrNull("title")
                    ?: o.optStringOrNull("artistName")
                if (name != null) out.add(name)
            }
            return out
        }
        is JSONObject -> {
            val name = value.optStringOrNull("name") ?: value.optStringOrNull("title")
            return if (name == null) emptyList() else listOf(name)
        }
        else -> return emptyList()
    }
}
