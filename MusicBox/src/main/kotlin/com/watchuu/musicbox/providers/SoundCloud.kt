package com.watchuu.musicbox.providers

import com.watchuu.musicbox.core.Album
import com.watchuu.musicbox.core.Artist
import com.watchuu.musicbox.core.Creds
import com.watchuu.musicbox.core.Diag
import com.watchuu.musicbox.core.Hit
import com.watchuu.musicbox.core.Http
import com.watchuu.musicbox.core.Links
import com.watchuu.musicbox.core.MusicProvider
import com.watchuu.musicbox.core.Stream
import com.watchuu.musicbox.core.Track
import com.watchuu.musicbox.core.Zarz
import com.watchuu.musicbox.core.array
import com.watchuu.musicbox.core.jsonOrNull
import com.watchuu.musicbox.core.objects
import com.watchuu.musicbox.core.optLongOrNull
import com.watchuu.musicbox.core.optStringOrNull
import org.json.JSONObject
import java.net.URLEncoder

/**
 * SoundCloud, ported from Mornye-Extension sources/soundcloud/index.js
 *  - client_id  scraped from soundcloud.com, then from the a-v2.sndcdn.com bundles
 *               (fetchClientId); cached, refreshed on 401/403
 *  - catalogue  api-v2.soundcloud.com/search/{tracks,albums,users,playlists}
 *               ?q=…&limit=…&offset=…&access=playable
 *  - streams    track.transcodings ranked by rankTranscodings(), then the transcoding
 *               endpoint is asked with client_id + track_authorization and answers {url}
 */
object SoundCloud : MusicProvider {
    override val id = "soundcloud"
    override val label = "SoundCloud"
    override val canCatalogue = true
    override val canStream = true

    private const val API = "https://api-v2.soundcloud.com"
    private const val HOME = "https://soundcloud.com/"

    private var clientId: String? = null
    private var clientIdExpiry: Long = 0L

    override fun configured(): Boolean = true

    override fun statusDetail(): String {
        val cached = Creds.get("soundcloud.clientId")
        return when {
            clientId != null || !cached.isNullOrBlank() -> "client id ready"
            else -> "client id discovered on first use"
        }
    }

    // ------------------------------------------------------------------ client id

    private fun remember(id: String) {
        clientId = id
        clientIdExpiry = System.currentTimeMillis() + 24L * 60 * 60 * 1000
        Creds.set("soundcloud.clientId", id)
        Diag.log("soundcloud", "client_id acquired")
    }

    private fun ensureClientId(): String {
        clientId?.let { if (System.currentTimeMillis() < clientIdExpiry) return it }
        if (clientId == null) {
            val cached = Creds.get("soundcloud.clientId")
            if (!cached.isNullOrBlank()) {
                clientId = cached
                clientIdExpiry = System.currentTimeMillis() + 6L * 60 * 60 * 1000
                return cached
            }
        }
        return fetchClientId()
    }

    private fun clientIdPattern(text: String): String? {
        Regex("[\"']?client_id[\"']?\\s*[:=]\\s*[\"']([a-zA-Z0-9]{32})[\"']").find(text)?.let {
            return it.groupValues[1]
        }
        Regex("\\(\"client_id=([a-zA-Z0-9]{32})\"\\)").find(text)?.let { return it.groupValues[1] }
        val idx = text.indexOf("client_id=")
        if (idx >= 0) {
            val start = idx + 10
            val allowed = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
            var end = start
            while (end < text.length && end - start < 32 && allowed.indexOf(text[end]) >= 0) end++
            if (end - start == 32) return text.substring(start, end)
        }
        return null
    }

    private fun fetchClientId(): String {
        val page = Http.get(HOME, mapOf("User-Agent" to Http.userAgent))
        if (!page.ok) throw IllegalStateException("soundcloud.com did not answer (HTTP ${page.code})")
        val body = page.body

        // Strategy 1: the id is in the page itself.
        clientIdPattern(body)?.let { remember(it); return it }

        // Strategy 2: read the JS bundles, last ones first.
        val bundleRegex = Regex("src=\"(https://a-v2\\.sndcdn\\.com/assets/[^\"]+\\.js)\"")
        var bundles = bundleRegex.findAll(body).map { it.groupValues[1] }.toList()
        if (bundles.isEmpty()) {
            bundles = Regex("src=\"(https://[^\"]*sndcdn\\.com[^\"]*\\.js)\"").findAll(body)
                .map { it.groupValues[1] }.toList()
        }
        var lastError: String? = null
        val window = bundles.takeLast(8).reversed()
        for (bundle in window) {
            val res = runCatching { Http.get(bundle, mapOf("User-Agent" to Http.userAgent)) }.getOrElse { e ->
                lastError = e.message
                null
            } ?: continue
            if (!res.ok) {
                lastError = "HTTP ${res.code}"
                continue
            }
            clientIdPattern(res.body)?.let { remember(it); return it }
        }
        throw IllegalStateException("Could not find a SoundCloud client_id" + (lastError?.let { ": $it" } ?: ""))
    }

    // ------------------------------------------------------------------ requests

    private fun scGet(path: String, extra: String? = null, retried: Boolean = false): JSONObject {
        var id = ensureClientId()
        val separator = if (path.contains("?")) "&" else "?"
        var url = "$API/$path${separator}client_id=$id"
        if (!extra.isNullOrBlank()) url += "&$extra"
        val res = Http.get(url, mapOf("User-Agent" to Http.userAgent, "Accept" to "application/json"))
        if ((res.code == 401 || res.code == 403) && !retried) {
            Diag.log("soundcloud", "authorization rejected, refreshing client_id")
            clientId = null
            clientIdExpiry = 0
            Creds.set("soundcloud.clientId", null)
            id = ensureClientId()
            return scGet(path, extra, true)
        }
        if (!res.ok) throw IllegalStateException("SoundCloud API HTTP ${res.code}")
        return jsonOrNull(res.body) ?: throw IllegalStateException("SoundCloud API returned non-JSON")
    }

    // ------------------------------------------------------------------ mapping

    private fun originalArtwork(url: String?): String? =
        if (url.isNullOrBlank()) null else url.replace("-large.", "-original.")

    private fun hiResArtwork(url: String?): String? =
        if (url.isNullOrBlank()) null else url.replace("-large.", "-t500x500.")

    private fun trackOf(item: JSONObject): Track? {
        val trackId = item.optStringOrNull("id") ?: return null
        val user = item.optJSONObject("user")
        val publisher = item.optJSONObject("publisher_metadata")
        val artist = publisher?.optStringOrNull("artist")
            ?: item.optStringOrNull("metadata_artist")
            ?: user?.optStringOrNull("username")
        val cover = originalArtwork(item.optStringOrNull("artwork_url"))
            ?: hiResArtwork(user?.optStringOrNull("avatar_url"))
        return Track(
            provider = id,
            id = trackId,
            title = item.optStringOrNull("title") ?: "",
            artists = listOfNotNull(artist),
            albumName = publisher?.optStringOrNull("album_title") ?: publisher?.optStringOrNull("release_title"),
            cover = cover,
            durationMs = item.optLongOrNull("full_duration") ?: item.optLongOrNull("duration"),
            isrc = publisher?.optStringOrNull("isrc") ?: item.optStringOrNull("isrc"),
            year = (item.optStringOrNull("display_date") ?: item.optStringOrNull("created_at") ?: "").take(4).toIntOrNull(),
            webUrl = item.optStringOrNull("permalink_url"),
        )
    }

    private fun playlistOf(item: JSONObject): Album? {
        val playlistId = item.optStringOrNull("id") ?: return null
        val user = item.optJSONObject("user")
        val tracks = ArrayList<Track>()
        item.array("tracks")?.objects()?.forEach { t ->
            // Playlist entries sometimes wrap the track in {track: …}.
            trackOf(t.optJSONObject("track") ?: t)?.let { tracks.add(it) }
        }
        return Album(
            provider = id,
            id = playlistId,
            title = item.optStringOrNull("title") ?: "",
            artists = listOfNotNull(user?.optStringOrNull("username")),
            cover = originalArtwork(item.optStringOrNull("artwork_url")),
            year = (item.optStringOrNull("display_date") ?: item.optStringOrNull("created_at") ?: "").take(4).toIntOrNull(),
            trackCount = item.optStringOrNull("track_count")?.toIntOrNull() ?: tracks.size,
            albumType = item.optStringOrNull("playlist_type") ?: "album",
            tracks = tracks,
            webUrl = item.optStringOrNull("permalink_url"),
        )
    }

    private fun userOf(item: JSONObject): Artist? {
        val userId = item.optStringOrNull("id") ?: return null
        return Artist(
            provider = id,
            id = userId,
            name = item.optStringOrNull("username") ?: "",
            image = hiResArtwork(item.optStringOrNull("avatar_url")),
            webUrl = item.optStringOrNull("permalink_url"),
        )
    }

    // ------------------------------------------------------------------ catalogue

    override suspend fun search(query: String, limit: Int): List<Hit> {
        val out = ArrayList<Hit>()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val capped = limit.coerceIn(1, 50)

        fun collection(type: String, size: Int): List<JSONObject> {
            val payload = runCatching {
                scGet("search/$type?q=$encoded", "limit=$size&offset=0&access=playable")
            }.getOrElse {
                Diag.log("soundcloud", "search/$type failed: ${it.message}")
                null
            } ?: return emptyList()
            return payload.array("collection")?.objects() ?: emptyList()
        }

        collection("tracks", capped).forEach { item ->
            trackOf(item)?.let { t -> out.add(Hit(Hit.Kind.TRACK, t.title, t.artistLine, t.cover, Links.track(id, t.id), track = t)) }
        }
        collection("albums", 5).forEach { item ->
            playlistOf(item)?.let { a -> out.add(Hit(Hit.Kind.ALBUM, a.title, a.artists.joinToString(", "), a.cover, Links.album(id, a.id), album = a)) }
        }
        collection("users", 5).forEach { item ->
            userOf(item)?.let { u -> out.add(Hit(Hit.Kind.ARTIST, u.name, "Artist", u.image, Links.artist(id, u.id), artist = u)) }
        }
        collection("playlists", 5).forEach { item ->
            playlistOf(item)?.let { a -> out.add(Hit(Hit.Kind.ALBUM, a.title, a.artists.joinToString(", "), a.cover, Links.album(id, a.id), album = a)) }
        }
        return out
    }

    override suspend fun track(id: String): Track? =
        runCatching { trackOf(scGet("tracks/$id")) }.getOrNull()

    override suspend fun album(id: String): Album? =
        runCatching { playlistOf(scGet("playlists/$id")) }.getOrNull()

    override suspend fun artist(id: String): Artist? {
        val user = runCatching { userOf(scGet("users/$id")) }.getOrNull() ?: return null
        val tracks = ArrayList<Track>()
        runCatching { scGet("users/$id/tracks", "limit=20&offset=0") }
            .getOrNull()?.array("collection")?.objects()?.forEach { item ->
                trackOf(item)?.let { tracks.add(it) }
            }
        return user.copy(topTracks = tracks)
    }

    // ------------------------------------------------------------------ streams

    /** transcodingFormat(): m4a, opus or mp3, from the mime type. */
    private fun transcodingFormat(transcoding: JSONObject): String? {
        val mime = transcoding.optJSONObject("format")?.optStringOrNull("mime_type") ?: return null
        return when {
            mime.contains("mp4") || mime.contains("aac") -> "m4a"
            mime.contains("opus") || mime.contains("ogg") -> "opus"
            mime.contains("mpeg") -> "mp3"
            else -> null
        }
    }

    private class Candidate(val value: JSONObject, val format: String, val protocol: String, val score: Double)

    /** rankTranscodings(): m4a > opus > mp3, preferred format boosted, then bitrate, then hq. */
    private fun rankTranscodings(transcodings: List<JSONObject>, preferred: String): List<Candidate> {
        val ranked = ArrayList<Candidate>()
        for (t in transcodings) {
            val url = t.optStringOrNull("url") ?: continue
            val format = t.optJSONObject("format") ?: continue
            if (url.isEmpty()) continue
            if (t.optBoolean("snipped", false)) continue
            val preset = t.optStringOrNull("preset") ?: ""
            if (preset.contains("preview")) continue
            val protocol = format.optStringOrNull("protocol") ?: continue
            if (protocol != "progressive" && protocol != "hls") continue
            val kind = transcodingFormat(t) ?: continue
            var score = when (kind) {
                "m4a" -> 60.0
                "opus" -> 40.0
                else -> 20.0
            }
            if (kind == preferred || (preferred == "aac" && kind == "m4a")) score += 100
            Regex("_(\\d+)k").find(preset)?.let { score += minOf(320, it.groupValues[1].toIntOrNull() ?: 0) / 100.0 }
            if (t.optStringOrNull("quality") == "hq") score += 5
            ranked.add(Candidate(t, kind, protocol, score))
        }
        return ranked.sortedByDescending { it.score }
    }

    override suspend fun streams(track: Track, quality: String): List<Stream> {
        var detail = runCatching { scGet("tracks/${track.id}") }.getOrNull() ?: return emptyList()
        val preferred = when (quality.lowercase()) {
            "aac", "m4a", "aac_160", "aac_256" -> "m4a"
            "opus" -> "opus"
            else -> "m4a"
        }
        var candidates = rankTranscodings(detail.array("transcodings")?.objects() ?: emptyList(), preferred)
        if (candidates.isEmpty()) return emptyList()

        val client = ensureClientId()
        val authorization = detail.optStringOrNull("track_authorization")
        for (candidate in candidates) {
            val endpoint = candidate.value.optStringOrNull("url") ?: continue
            val separator = if (endpoint.contains("?")) "&" else "?"
            var url = "$endpoint${separator}client_id=$client"
            if (!authorization.isNullOrBlank()) url += "&track_authorization=${URLEncoder.encode(authorization, "UTF-8")}"
            var res = Http.get(url, mapOf("User-Agent" to Http.userAgent, "Accept" to "application/json"))
            if ((res.code == 401 || res.code == 403)) {
                // resolveStreamURL(): refresh the client id and the track authorization once.
                clientId = null
                clientIdExpiry = 0
                val fresh = ensureClientId()
                detail = runCatching { scGet("tracks/${track.id}") }.getOrNull() ?: return emptyList()
                candidates = rankTranscodings(detail.array("transcodings")?.objects() ?: emptyList(), preferred)
                if (candidates.isEmpty()) return emptyList()
                val auth = detail.optStringOrNull("track_authorization")
                val sep2 = if (endpoint.contains("?")) "&" else "?"
                var retryUrl = "$endpoint${sep2}client_id=$fresh"
                if (!auth.isNullOrBlank()) retryUrl += "&track_authorization=${URLEncoder.encode(auth, "UTF-8")}"
                res = Http.get(retryUrl, mapOf("User-Agent" to Http.userAgent, "Accept" to "application/json"))
            }
            if (!res.ok) continue
            val streamUrl = jsonOrNull(res.body)?.optStringOrNull("url") ?: continue
            val mime = when (candidate.format) {
                "m4a" -> "audio/mp4"
                "opus" -> "audio/ogg"
                else -> "audio/mpeg"
            }
            return listOf(
                Stream(
                    url = streamUrl,
                    quality = "${candidate.format.uppercase()} · ${candidate.protocol}",
                    mime = mime,
                    provider = id,
                    hls = candidate.protocol == "hls",
                )
            )
        }
        return emptyList()
    }
}
