package com.watchuu.echo

import org.json.JSONArray
import org.json.JSONObject

/**
 * InnerTube answers are deeply nested and the path to a song differs between a search shelf, a home
 * carousel and an album. Rather than hardcode three sets of paths that break whenever YouTube
 * reshuffles its tree, this walks the whole document and collects the renderers by name.
 *
 * Two node types carry everything needed:
 *   musicResponsiveListItemRenderer - a song row (videoId, title, artist line, cover)
 *   musicTwoRowItemRenderer         - an album, playlist or artist tile (browseId, title, subtitle)
 */
object EchoParser {

    data class Track(
        val videoId: String,
        val title: String,
        val subtitle: String,
        val cover: String?,
        val durationText: String?,
    )

    data class Tile(
        val browseId: String,
        val title: String,
        val subtitle: String,
        val cover: String?,
        val isPlaylist: Boolean,
        val isArtist: Boolean,
    )

    data class Shelf(val title: String, val tracks: List<Track>, val tiles: List<Tile>)

    // ------------------------------------------------------------------ walking

    private fun walk(node: Any?, visit: (String, JSONObject) -> Unit) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = node.opt(k)
                    if (v is JSONObject || v is JSONArray) {
                        visit(k, node)
                        walk(v, visit)
                    }
                }
            }
            is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), visit)
        }
    }

    fun findAll(root: JSONObject?, key: String): List<JSONObject> {
        if (root == null) return emptyList()
        val out = ArrayList<JSONObject>()
        walk(root) { k, parent ->
            if (k == key) parent.optJSONObject(k)?.let { out.add(it) }
        }
        return out
    }

    // ------------------------------------------------------------------ field readers

    private fun text(node: JSONObject?): String {
        if (node == null) return ""
        node.optString("simpleText").takeIf { it.isNotBlank() }?.let { return it }
        val runs = node.optJSONArray("runs") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until runs.length()) {
            val t = runs.optJSONObject(i)?.optString("text").orEmpty()
            if (t.isNotBlank()) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(t)
            }
        }
        return sb.toString().trim()
    }

    /** the flex columns of a song row: title first, then the artist/album/duration line */
    private fun flexColumns(item: JSONObject): List<String> {
        val cols = item.optJSONArray("flexColumns") ?: return emptyList()
        return (0 until cols.length()).map { i ->
            text(cols.optJSONObject(i)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text"))
        }
    }

    private fun thumb(item: JSONObject): String? {
        val arr = item.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails") ?: return null
        var best: String? = null
        var bestW = -1
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val w = o.optInt("width", 0)
            if (w >= bestW) {
                bestW = w
                best = o.optString("url").ifBlank { null } ?: best
            }
        }
        return best
    }

    private fun videoIdOf(item: JSONObject): String? {
        item.optJSONObject("playlistItemData")?.optString("videoId")?.takeIf { it.isNotBlank() }?.let { return it }
        val overlay = item.optJSONObject("overlay")?.optJSONObject("musicItemThumbnailOverlayRenderer")
            ?.optJSONObject("content")?.optJSONObject("musicPlayButtonRenderer")
            ?.optJSONObject("playNavigationEndpoint")?.optJSONObject("watchEndpoint")
            ?.optString("videoId")
        if (!overlay.isNullOrBlank()) return overlay
        // a plain watch endpoint anywhere in the node
        var found: String? = null
        walk(item) { k, parent ->
            if (found == null && k == "watchEndpoint") {
                parent.optJSONObject(k)?.optString("videoId")?.takeIf { it.isNotBlank() }?.let { found = it }
            }
        }
        return found
    }

    private fun browseIdOf(item: JSONObject): String? {
        val nav = item.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
            ?.optString("browseId")
        if (!nav.isNullOrBlank()) return nav
        var found: String? = null
        walk(item) { k, parent ->
            if (found == null && k == "browseEndpoint") {
                parent.optJSONObject(k)?.optString("browseId")?.takeIf { it.isNotBlank() }?.let { found = it }
            }
        }
        return found
    }

    private fun durationOf(line: String): String? =
        Regex("\\b(\\d{1,2}:\\d{2}(?::\\d{2})?)\\b").find(line)?.groupValues?.get(1)

    /** album and playlist rows carry the length in a fixed column instead of the subtitle line */
    private fun fixedDuration(item: JSONObject): String? {
        val cols = item.optJSONArray("fixedColumns") ?: return null
        for (i in 0 until cols.length()) {
            val t = text(cols.optJSONObject(i)?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")?.optJSONObject("text"))
            durationOf(t)?.let { return it }
        }
        return null
    }

    fun track(item: JSONObject): Track? {
        val id = videoIdOf(item) ?: return null
        val cols = flexColumns(item)
        val title = cols.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
        val sub = cols.getOrNull(1).orEmpty()
        return Track(id, title, sub, thumb(item), durationOf(sub) ?: fixedDuration(item))
    }

    private val TYPE_WORDS = setOf("song", "video", "single", "ep", "album", "episode", "podcast", "playlist", "artist")

    /**
     * The artist from a subtitle line such as "Song • Arijit Singh • Aashiqui 2 • 4:22": the first
     * part that is not the item type, a duration, a play/view count or a year.
     */
    fun artistOf(subtitle: String): String =
        subtitle.split('•').map { it.trim() }.firstOrNull { part ->
            part.isNotEmpty() &&
                part.lowercase() !in TYPE_WORDS &&
                durationOf(part) == null &&
                !Regex("(?i)\\b(views|plays|subscribers|songs)\\b").containsMatchIn(part) &&
                !Regex("^\\d{4}$").matches(part)
        }.orEmpty()

    /** "4:22" or "1:02:03" to seconds */
    fun seconds(duration: String?): Int? {
        val parts = duration?.split(':')?.map { it.toIntOrNull() ?: return null } ?: return null
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            else -> null
        }
    }

    fun tile(item: JSONObject): Tile? {
        val browseId = browseIdOf(item) ?: return null
        val title = text(item.optJSONObject("title"))
        if (title.isBlank()) return null
        val sub = text(item.optJSONObject("subtitle"))
        val cover = item.optJSONObject("thumbnailRenderer")?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")?.let { arr ->
                arr.optJSONObject(arr.length() - 1)?.optString("url")?.ifBlank { null }
            } ?: thumb(item)
        return Tile(
            browseId = browseId,
            title = title,
            subtitle = sub,
            cover = cover,
            isPlaylist = browseId.startsWith("VL"),
            isArtist = browseId.startsWith("UC") || browseId.startsWith("FEmusic_library"),
        )
    }

    /** every song row in the document, in order */
    fun tracks(root: JSONObject?): List<Track> =
        findAll(root, "musicResponsiveListItemRenderer").mapNotNull { track(it) }

    /** every album/playlist/artist tile in the document, in order */
    fun tiles(root: JSONObject?): List<Tile> =
        findAll(root, "musicTwoRowItemRenderer").mapNotNull { tile(it) }

    /** the home page, kept as its carousels so each one can become a row */
    fun shelves(root: JSONObject?): List<Shelf> {
        if (root == null) return emptyList()
        val out = ArrayList<Shelf>()
        walk(root) { k, parent ->
            if (k == "musicCarouselShelfRenderer" || k == "musicShelfRenderer") {
                val node = parent.optJSONObject(k) ?: return@walk
                val title = text(node.optJSONObject("header")?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")?.optJSONObject("title"))
                    .ifBlank { text(node.optJSONObject("title")) }
                val body = node.optJSONObject("contents") ?: node
                out.add(Shelf(title, tracks(body), tiles(body)))
            }
        }
        return out.filter { it.tracks.isNotEmpty() || it.tiles.isNotEmpty() }
    }
}
