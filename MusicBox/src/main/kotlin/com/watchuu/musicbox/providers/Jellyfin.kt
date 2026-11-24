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
import com.watchuu.musicbox.core.optIntOrNull
import com.watchuu.musicbox.core.optLongOrNull
import com.watchuu.musicbox.core.optStringOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Jellyfin, ported from Mornye-Extension sources/jellyfin/index.js
 *  - auth       API key (GET /Users) or username + password
 *               (POST /Users/AuthenticateByName -> AccessToken, User.Id)
 *  - requests   MediaBrowser authorization header with a persisted device id
 *  - catalogue  /Items?SearchTerm=…&IncludeItemTypes=Audio|MusicAlbum, /Artists, /Items?Ids=
 *  - streams    /Audio/{id}/stream?static=true&api_key=…  (original)
 *               or ?static=false&container=mp3&audioCodec=mp3&audioBitRate=320000&maxAudioChannels=2
 */
object Jellyfin : MusicProvider {
    override val id = "jellyfin"
    override val label = "Jellyfin"
    override val canCatalogue = true
    override val canStream = true

    private fun serverUrl(): String {
        var url = (Creds.get("jellyfin.serverUrl") ?: "").trim()
        if (url.isEmpty()) return ""
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) url = "https://$url"
        return url.trimEnd('/')
    }

    private fun username(): String = (Creds.get("jellyfin.username") ?: "").trim()
    private fun password(): String = Creds.get("jellyfin.password") ?: ""
    private fun apiKey(): String = (Creds.get("jellyfin.apiKey") ?: "").trim()

    override fun configured(): Boolean = serverUrl().isNotEmpty() && (apiKey().isNotEmpty() || username().isNotEmpty())

    override fun statusDetail(): String {
        if (serverUrl().isEmpty()) return "server URL missing"
        if (apiKey().isNotEmpty()) return "API key set"
        if (username().isEmpty()) return "username missing"
        return if (Creds.has("jellyfin.token")) "signed in as ${username()}" else "not signed in yet"
    }

    // ------------------------------------------------------------------ auth

    private class Session(val token: String, val userId: String, val name: String)

    private var session: Session? = null

    private fun deviceId(): String {
        val existing = Creds.get("jellyfin.deviceId")
        if (!existing.isNullOrBlank()) return existing
        val fresh = "musicbox-" + Zarz.randomHex(8)
        Creds.set("jellyfin.deviceId", fresh)
        return fresh
    }

    private fun authHeaders(token: String?): Map<String, String> {
        var header = "MediaBrowser Client=\"MusicBox\", Device=\"MusicBox\", DeviceId=\"${deviceId()}\", Version=\"1.0.0\""
        if (!token.isNullOrBlank()) header += ", Token=\"$token\""
        val map = mutableMapOf("Authorization" to header, "Accept" to "application/json")
        if (!token.isNullOrBlank()) map["X-Emby-Token"] = token
        return map
    }

    /** login(): API key first, then a cached token, then username + password. */
    private fun login(force: Boolean = false): Session {
        val base = serverUrl()
        if (base.isEmpty()) throw IllegalStateException("Jellyfin server URL is missing in the settings")
        if (!force && session != null) return session!!

        val key = apiKey()
        if (key.isNotEmpty()) {
            val res = Http.get("$base/Users", authHeaders(key))
            if (!res.ok) throw IllegalStateException("API key rejected (HTTP ${res.code})")
            val users = runCatching { org.json.JSONArray(res.body) }.getOrNull()
            var userId = ""
            var name = username()
            if (users != null) {
                for (i in 0 until users.length()) {
                    val user = users.optJSONObject(i) ?: continue
                    val userName = user.optStringOrNull("Name") ?: ""
                    if (name.isEmpty() || userName.equals(name, true)) {
                        userId = user.optStringOrNull("Id") ?: ""
                        if (name.isEmpty()) name = userName
                        break
                    }
                }
            }
            if (userId.isEmpty()) throw IllegalStateException("User '${username()}' not found on the server")
            return Session(key, userId, name).also { session = it }
        }

        if (username().isEmpty()) throw IllegalStateException("Jellyfin username is missing in the settings")
        if (!force) {
            val cachedToken = Creds.get("jellyfin.token")
            val cachedUser = Creds.get("jellyfin.userId")
            if (!cachedToken.isNullOrBlank() && !cachedUser.isNullOrBlank()) {
                return Session(cachedToken, cachedUser, username()).also { session = it }
            }
        }
        val body = JSONObject().put("Username", username()).put("Pw", password()).toString()
        val res = Http.postJson("$base/Users/AuthenticateByName", body, authHeaders(null))
        if (!res.ok) throw IllegalStateException("Login failed (HTTP ${res.code})")
        val payload = jsonOrNull(res.body) ?: throw IllegalStateException("Unexpected login response")
        val token = payload.optStringOrNull("AccessToken") ?: throw IllegalStateException("No access token in login response")
        val user = payload.optJSONObject("User") ?: throw IllegalStateException("No user in login response")
        val userId = user.optStringOrNull("Id") ?: ""
        val name = user.optStringOrNull("Name") ?: username()
        Creds.set("jellyfin.token", token)
        Creds.set("jellyfin.userId", userId)
        Diag.log("jellyfin", "signed in as $name")
        return Session(token, userId, name).also { session = it }
    }

    private fun apiGet(path: String, params: List<Pair<String, String>> = emptyList(), retried: Boolean = false): JSONObject? {
        val s = login(false)
        val query = params.filter { it.second.isNotEmpty() }
            .joinToString("&") { "${URLEncoder.encode(it.first, "UTF-8")}=${URLEncoder.encode(it.second, "UTF-8")}" }
        val url = serverUrl() + path + if (query.isEmpty()) "" else "?$query"
        val res = Http.get(url, authHeaders(s.token))
        if (res.code == 401 && apiKey().isEmpty() && !retried) {
            session = null
            Creds.set("jellyfin.token", null)
            login(true)
            return apiGet(path, params, true)
        }
        if (!res.ok) {
            Diag.log("jellyfin", "GET $path HTTP ${res.code}")
            return null
        }
        return jsonOrNull(res.body)
    }

    private fun items(extra: List<Pair<String, String>>, limit: Int): List<JSONObject> {
        val s = login(false)
        val params = mutableListOf(
            "UserId" to s.userId,
            "Recursive" to "true",
            "Limit" to limit.toString(),
        )
        params.addAll(extra)
        return apiGet("/Items", params)?.array("Items")?.objects() ?: emptyList()
    }

    private fun item(itemId: String, fields: String = ""): JSONObject? =
        apiGet("/Items", listOf("UserId" to login(false).userId, "Ids" to itemId, "Fields" to fields))
            ?.array("Items")?.objects()?.firstOrNull()

    // ------------------------------------------------------------------ mapping

    private fun coverUrl(it: JSONObject): String? {
        val imageTags = it.optJSONObject("ImageTags")
        var imageId: String? = null
        var tag: String? = null
        if (imageTags?.optStringOrNull("Primary") != null) {
            imageId = it.optStringOrNull("Id")
            tag = imageTags.optStringOrNull("Primary")
        } else if (it.optStringOrNull("AlbumPrimaryImageTag") != null) {
            imageId = it.optStringOrNull("AlbumId")
            tag = it.optStringOrNull("AlbumPrimaryImageTag")
        }
        if (imageId == null) return null
        return "${serverUrl()}/Items/$imageId/Images/Primary?maxHeight=600&quality=90&tag=$tag"
    }

    private fun stringArray(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "").trim()
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    private fun artistString(it: JSONObject): String {
        val artists = stringArray(it.optJSONArray("Artists"))
        if (artists.isNotEmpty()) return artists.joinToString(", ")
        return it.optStringOrNull("AlbumArtist") ?: ""
    }

    private fun releaseDate(it: JSONObject): String {
        it.optStringOrNull("PremiereDate")?.let { return it.take(10) }
        it.optIntOrNull("ProductionYear")?.let { return it.toString() }
        return ""
    }

    private fun trackOf(it: JSONObject): Track? {
        val itemId = it.optStringOrNull("Id") ?: return null
        val artists = stringArray(it.optJSONArray("Artists")).ifEmpty {
            listOfNotNull(it.optStringOrNull("AlbumArtist"))
        }
        val release = releaseDate(it)
        return Track(
            provider = id,
            id = itemId,
            title = it.optStringOrNull("Name") ?: "",
            artists = artists,
            albumName = it.optStringOrNull("Album"),
            albumId = it.optStringOrNull("AlbumId"),
            cover = coverUrl(it),
            durationMs = it.optLongOrNull("RunTimeTicks")?.let { it / 10000 },
            trackNumber = it.optIntOrNull("IndexNumber"),
            discNumber = it.optIntOrNull("ParentIndexNumber") ?: 1,
            year = release.take(4).toIntOrNull(),
            webUrl = it.optStringOrNull("Path"),
        )
    }

    private fun albumOf(it: JSONObject): Album? {
        val albumId = it.optStringOrNull("Id") ?: return null
        val release = releaseDate(it)
        return Album(
            provider = id,
            id = albumId,
            title = it.optStringOrNull("Name") ?: "",
            artists = listOfNotNull(it.optStringOrNull("AlbumArtist") ?: artistString(it).ifBlank { null }),
            cover = coverUrl(it),
            year = release.take(4).toIntOrNull(),
            trackCount = it.optIntOrNull("ChildCount"),
            albumType = "album",
        )
    }

    // ------------------------------------------------------------------ catalogue

    override suspend fun search(query: String, limit: Int): List<Hit> {
        if (!configured()) return emptyList()
        val out = ArrayList<Hit>()
        items(listOf("SearchTerm" to query, "IncludeItemTypes" to "Audio"), limit).forEach { it ->
            trackOf(it)?.let { t -> out.add(Hit(Hit.Kind.TRACK, t.title, t.artistLine, t.cover, Links.track(id, t.id), track = t)) }
        }
        items(listOf("SearchTerm" to query, "IncludeItemTypes" to "MusicAlbum"), 10).forEach { it ->
            albumOf(it)?.let { a -> out.add(Hit(Hit.Kind.ALBUM, a.title, a.artists.joinToString(", "), a.cover, Links.album(id, a.id), album = a)) }
        }
        val artistPayload = apiGet(
            "/Artists",
            listOf("UserId" to login(false).userId, "SearchTerm" to query, "Limit" to "8")
        )
        artistPayload?.array("Items")?.objects()?.forEach { it ->
            val artistId = it.optStringOrNull("Id") ?: return@forEach
            val artist = Artist(
                provider = id, id = artistId,
                name = it.optStringOrNull("Name") ?: "",
                image = coverUrl(it),
            )
            out.add(Hit(Hit.Kind.ARTIST, artist.name, "Artist", artist.image, Links.artist(id, artist.id), artist = artist))
        }
        return out
    }

    override suspend fun track(id: String): Track? {
        if (!configured()) return null
        return item(id)?.let { trackOf(it) }
    }

    override suspend fun album(id: String): Album? {
        if (!configured()) return null
        val album = item(id) ?: return null
        val base = albumOf(album) ?: return null
        val tracks = items(
            listOf(
                "ParentId" to id,
                "IncludeItemTypes" to "Audio",
                "SortBy" to "ParentIndexNumber,IndexNumber,SortName",
            ),
            500
        ).mapNotNull { t ->
            val track = trackOf(t)
            track?.copy(
                albumName = track.albumName ?: base.title,
                cover = track.cover ?: base.cover,
            )
        }
        return base.copy(tracks = tracks, trackCount = tracks.size)
    }

    override suspend fun artist(id: String): Artist? {
        if (!configured()) return null
        val profile = item(id)
        val albums = items(
            listOf(
                "ArtistIds" to id,
                "IncludeItemTypes" to "MusicAlbum",
                "SortBy" to "ProductionYear,SortName",
                "SortOrder" to "Descending",
            ),
            200
        ).mapNotNull { albumOf(it) }
        return Artist(
            provider = this.id,
            id = id,
            name = profile?.optStringOrNull("Name") ?: "",
            image = profile?.let { coverUrl(it) },
            albums = albums,
        )
    }

    // ------------------------------------------------------------------ streams

    private fun transcode(quality: String): Triple<String, String, String>? = when (quality.uppercase()) {
        "MP3_320" -> Triple("mp3", "mp3", "320000")
        "MP3_192" -> Triple("mp3", "mp3", "192000")
        "OPUS_128" -> Triple("opus", "opus", "128000")
        else -> null
    }

    override suspend fun streams(track: Track, quality: String): List<Stream> {
        if (!configured()) return emptyList()
        val s = login(false)
        val base = serverUrl()
        val key = URLEncoder.encode(s.token, "UTF-8")
        val transcoded = transcode(quality)
        val out = ArrayList<Stream>()
        if (transcoded != null) {
            val (container, codec, bitrate) = transcoded
            val url = "$base/Audio/${track.id}/stream?static=false&container=$container&audioCodec=$codec" +
                "&audioBitRate=$bitrate&maxAudioChannels=2&api_key=$key"
            out.add(Stream(url, quality.uppercase().replace('_', ' '), null, provider = id))
        }
        // ORIGINAL (and the fallback when transcoding is not requested): the file as stored.
        val original = "$base/Audio/${track.id}/stream?static=true&api_key=$key"
        val info = item(track.id, "MediaSources,Path")
        val ext = originalExtension(info)
        val mime = when (ext) {
            "flac" -> "audio/flac"
            "mp3" -> "audio/mpeg"
            "m4a", "mp4" -> "audio/mp4"
            "opus" -> "audio/opus"
            "ogg" -> "audio/ogg"
            "wav" -> "audio/wav"
            else -> null
        }
        out.add(Stream(original, "Original${if (ext.isNotEmpty()) " · $ext" else ""}", mime, provider = id))
        return out
    }

    /** originalExt(): the stored container, from MediaSources.Path or Container. */
    private fun originalExtension(info: JSONObject?): String {
        val media = info?.array("MediaSources")?.optJSONObject(0)
        val path = media?.optStringOrNull("Path") ?: info?.optStringOrNull("Path") ?: ""
        Regex("\\.([A-Za-z0-9]{2,5})$").find(path)?.let { return it.groupValues[1].lowercase() }
        val container = (media?.optStringOrNull("Container") ?: "").lowercase()
        if (container.contains("m4a") || container.contains("mp4")) return "m4a"
        return container.split(",").firstOrNull()?.trim() ?: ""
    }
}
