package com.watchuu.musicbox.providers

import com.watchuu.musicbox.core.Album
import com.watchuu.musicbox.core.Artist
import com.watchuu.musicbox.core.Creds
import com.watchuu.musicbox.core.Diag
import com.watchuu.musicbox.core.Hit
import com.watchuu.musicbox.core.Http
import com.watchuu.musicbox.core.MusicProvider
import com.watchuu.musicbox.core.Stream
import com.watchuu.musicbox.core.Track
import com.watchuu.musicbox.core.array
import com.watchuu.musicbox.core.artistNames
import com.watchuu.musicbox.core.jsonOrNull
import com.watchuu.musicbox.core.objects
import com.watchuu.musicbox.core.optIntOrNull
import com.watchuu.musicbox.core.optLongOrNull
import com.watchuu.musicbox.core.optStringOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * JioSaavn, ported from Mornye-Extension sources/jiosaavn/index.js
 *  - API base        https://www.jiosaavn.com/api.php   (API_BASE)
 *  - common params   _format=json, _marker=0, api_version=4, ctx=web6dot0   (API_DEFAULTS)
 *  - headers         Accept / Accept-Language / X-Requested-With / Referer   (DEFAULT_HEADERS)
 *  - cookie          "DL=english; geo=19.0760%2C72.8777%2CIN%2CMaharashtra%2CMumbai%2C400001; L=<lang>"
 *  - calls           search.getResults, song.getDetails, webapi.get, content.getAlbumDetails,
 *                    search.getArtistResults, webapi.get?type=artist
 *  - stream          more_info.encrypted_media_url -> DES(38346591) -> https://aac.saavncdn.com/...,
 *                    then "_<bitrate>.mp4" substitution (getDownloadUrl / getBitrate)
 */
object JioSaavn : MusicProvider {
    override val id = "jiosaavn"
    override val label = "JioSaavn"
    override val canCatalogue = true
    override val canStream = true

    private const val HOST = "https://www.jiosaavn.com"
    private const val API = "$HOST/api.php"
    private const val REFERER = "$HOST/"
    private const val UA = "Mozilla/5.0"
    private const val COOKIE_BASE =
        "DL=english; geo=19.0760%2C72.8777%2CIN%2CMaharashtra%2CMumbai%2C400001"
    private const val DES_KEY = "38346591"

    private fun lang(): String = Creds.get("jiosaavn.lang", "english") ?: "english"

    private fun headers(): Map<String, String> = mapOf(
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "en-US,en;q=0.9",
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to REFERER,
        "User-Agent" to UA,
        "Cookie" to "$COOKIE_BASE; L=${lang()}",
    )

    private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

    /** callAPI(): __call plus the fixed defaults, in query-string form. */
    private fun call(call: String, extra: List<Pair<String, String>> = emptyList()): JSONObject? {
        val params = mutableListOf(
            "__call" to call,
            "_format" to "json",
            "_marker" to "0",
            "api_version" to "4",
            "ctx" to "web6dot0",
        )
        params.addAll(extra)
        val url = API + "?" + params.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        val res = Http.get(url, headers())
        if (!res.ok) {
            Diag.log("jiosaavn", "$call HTTP ${res.code}")
            return null
        }
        val parsed = jsonOrNull(res.body)
        if (parsed == null) Diag.log("jiosaavn", "$call returned non-JSON")
        return parsed
    }

    // ------------------------------------------------------------------ helpers

    private fun decodeEntities(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        return s.replace("&quot;", "\"").replace("&#039;", "'").replace("&nbsp;", " ")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace(Regex("&#(x[0-9a-fA-F]+|\\d+);")) { m ->
                val d = m.groupValues[1]
                val value = if (d.startsWith("x", true)) d.substring(1).toIntOrNull(16)
                else d.toIntOrNull()
                if (value != null && value <= 0x10ffff) String(Character.toChars(value)) else m.value
            }
            .replace("&amp;", "&").trim()
    }

    private fun extractToken(permaUrl: String?): String? {
        if (permaUrl.isNullOrBlank()) return null
        val clean = permaUrl.split("?", "#")[0].trimEnd('/')
        return clean.split("/").lastOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun mediaUrl(value: String?): String {
        val url = (value ?: "").trim().replaceFirst(Regex("^http:"), "https:")
        return if (url.startsWith("https://aac.saavncdn.com/", true)) url else ""
    }

    private fun highRes(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return url.replaceFirst(Regex("^http:"), "https:")
            .replace(Regex("(?:50|150)x(?:50|150)(\\.[a-z]+)(?=[?#]|\$)"), "500x500$1")
    }

    /** decryptMediaUrl(): base64 -> DES/ECB/NoPadding -> strip PKCS#7 -> mediaUrl(). */
    private fun decryptMediaUrl(encrypted: String?): String {
        if (encrypted.isNullOrBlank()) return ""
        val cipherText = try {
            android.util.Base64.decode(encrypted, android.util.Base64.DEFAULT)
        } catch (e: Throwable) {
            return ""
        }
        if (cipherText.isEmpty() || cipherText.size % 8 != 0) return ""
        val plain = try {
            val cipher = Cipher.getInstance("DES/ECB/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(DES_KEY.toByteArray(Charsets.UTF_8), "DES"))
            cipher.doFinal(cipherText)
        } catch (e: Throwable) {
            Diag.log("jiosaavn", "DES failed: ${e.message}")
            return ""
        }
        val pad = if (plain.isNotEmpty()) plain[plain.size - 1].toInt() and 0xFF else 0
        val cut = if (pad in 1..8 && pad <= plain.size) plain.size - pad else plain.size
        return mediaUrl(String(plain, 0, cut, Charsets.UTF_8))
    }

    private fun artistsOf(song: JSONObject): List<String> {
        val more = song.optJSONObject("more_info")
        val map = more?.optJSONObject("artistMap")
        val names = ArrayList<String>()
        val primary = map?.array("primary_artists")
        val featured = map?.array("featured_artists")
        for (arr in listOf(primary, featured)) {
            arr?.objects()?.forEach { a ->
                val n = decodeEntities(a.optStringOrNull("name"))
                if (n.isNotBlank() && !names.contains(n)) names.add(n)
            }
        }
        if (names.isEmpty()) {
            val fallback = decodeEntities(
                song.optStringOrNull("primary_artists")
                    ?: more?.optStringOrNull("primary_artists")
                    ?: (song.optStringOrNull("subtitle") ?: "").split(" - ").firstOrNull()
            )
            if (fallback.isNotBlank()) names.add(fallback)
        }
        return names
    }

    private fun formatSong(song: JSONObject, album: Album? = null, index: Int? = null): Track? {
        val token = extractToken(song.optStringOrNull("perma_url"))
        val songId = song.optStringOrNull("id") ?: token ?: return null
        val more = song.optJSONObject("more_info")
        val durationSec = (more?.optLongOrNull("duration") ?: song.optLongOrNull("duration") ?: 0L)
        val albumId = more?.optStringOrNull("album_id")
            ?: song.optStringOrNull("albumid")
            ?: album?.id
            ?: extractToken(more?.optStringOrNull("album_url"))
        val cover = highRes(song.optStringOrNull("image") ?: album?.cover)
        val release = more?.optStringOrNull("release_date")
            ?: song.optStringOrNull("release_date")
            ?: song.optStringOrNull("year")
            ?: album?.year?.toString()
        val explicit = song.optStringOrNull("explicit_content") == "1"
        return Track(
            provider = id,
            id = songId,
            title = decodeEntities(song.optStringOrNull("title") ?: song.optStringOrNull("song")),
            artists = artistsOf(song),
            albumName = decodeEntities(more?.optStringOrNull("album") ?: song.optStringOrNull("album") ?: album?.title),
            albumId = albumId,
            cover = cover,
            durationMs = if (durationSec > 0) durationSec * 1000 else null,
            isrc = (more?.optStringOrNull("isrc") ?: song.optStringOrNull("isrc"))?.uppercase()?.replace("-", ""),
            trackNumber = more?.optIntOrNull("track_number") ?: song.optIntOrNull("track_number")
                ?: index?.plus(1),
            discNumber = more?.optIntOrNull("disc_number") ?: song.optIntOrNull("disc_number") ?: 1,
            year = release?.take(4)?.toIntOrNull(),
            explicit = explicit,
            webUrl = song.optStringOrNull("perma_url"),
        )
    }

    // ------------------------------------------------------------------ catalogue

    override suspend fun search(query: String, limit: Int): List<Hit> {
        val out = ArrayList<Hit>()
        val songs = call("search.getResults", listOf("q" to query, "p" to "1", "n" to limit.toString()))
        songs?.array("results")?.objects()?.forEach { item ->
            formatSong(item)?.let { t ->
                out.add(Hit(Hit.Kind.TRACK, t.title, t.artistLine, t.cover, com.watchuu.musicbox.core.Links.track(id, t.id), track = t))
            }
        }
        val albums = call("search.getAlbumResults", listOf("q" to query, "p" to "1", "n" to "10"))
        albums?.array("results")?.objects()?.forEach { item ->
            val albumId = item.optStringOrNull("albumid") ?: item.optStringOrNull("id") ?: return@forEach
            val album = Album(
                provider = id,
                id = albumId,
                title = decodeEntities(item.optStringOrNull("title") ?: item.optStringOrNull("name")),
                artists = artistNames(item.optJSONObject("more_info")?.opt("artistMap")),
                cover = highRes(item.optStringOrNull("image")),
                year = (item.optStringOrNull("year") ?: "").take(4).toIntOrNull(),
            )
            out.add(Hit(Hit.Kind.ALBUM, album.title, album.artists.joinToString(", "), album.cover, com.watchuu.musicbox.core.Links.album(id, album.id), album = album))
        }
        val artists = call("search.getArtistResults", listOf("q" to query, "p" to "1", "n" to "10"))
        artists?.array("results")?.objects()?.forEach { item ->
            if (item.optStringOrNull("type") != "artist") return@forEach
            val artistId = item.optStringOrNull("id") ?: return@forEach
            val artist = Artist(
                provider = id,
                id = artistId,
                name = decodeEntities(item.optStringOrNull("name") ?: item.optStringOrNull("title")),
                image = highRes(item.optStringOrNull("image")),
            )
            out.add(Hit(Hit.Kind.ARTIST, artist.name, "Artist", artist.image, com.watchuu.musicbox.core.Links.artist(id, artist.id), artist = artist))
        }
        return out
    }

    override suspend fun track(id: String): Track? = songDetails(id)?.let { formatSong(it) }

    override suspend fun album(id: String): Album? {
        val result = call("content.getAlbumDetails", listOf("albumid" to id)) ?: return null
        val title = decodeEntities(result.optStringOrNull("title") ?: result.optStringOrNull("name"))
        val cover = highRes(result.optStringOrNull("image"))
        val year = (result.optStringOrNull("year") ?: "").take(4).toIntOrNull()
        val album = Album(
            provider = this.id,
            id = id,
            title = title,
            artists = artistNames(result.optStringOrNull("primary_artists") ?: result.optStringOrNull("subtitle")),
            cover = cover,
            year = year,
            trackCount = result.optIntOrNull("song_count"),
            albumType = "album",
        )
        val tracks = ArrayList<Track>()
        result.array("songs")?.objects()?.forEachIndexed { index, song ->
            formatSong(song, album, index)?.let { tracks.add(it) }
        }
        return album.copy(tracks = tracks)
    }

    override suspend fun artist(id: String): Artist? {
        val result = call(
            "webapi.get",
            listOf(
                "token" to id, "type" to "artist", "p" to "0", "n_song" to "50",
                "n_album" to "50", "sub_type" to "", "category" to "popular", "sort_order" to "asc"
            )
        ) ?: return null
        val name = decodeEntities(result.optStringOrNull("name") ?: result.optStringOrNull("title"))
        val image = highRes(result.optStringOrNull("image"))
        val top = ArrayList<Track>()
        result.array("topSongs")?.objects()?.forEach { s -> formatSong(s)?.let { top.add(it) } }
        result.array("songs")?.objects()?.forEach { s -> formatSong(s)?.let { top.add(it) } }
        val albums = ArrayList<Album>()
        result.array("topAlbums")?.objects()?.forEach { a ->
            val aid = a.optStringOrNull("albumid") ?: a.optStringOrNull("id") ?: return@forEach
            albums.add(
                Album(
                    provider = this.id, id = aid,
                    title = decodeEntities(a.optStringOrNull("title") ?: a.optStringOrNull("name")),
                    artists = listOf(name),
                    cover = highRes(a.optStringOrNull("image")),
                    year = (a.optStringOrNull("year") ?: "").take(4).toIntOrNull(),
                )
            )
        }
        return Artist(this.id, id, name, image, albums, top)
    }

    // ------------------------------------------------------------------ streams

    /** getBitrate(): AAC_320 -> "320" and so on, matching the manifest's qualityOptions. */
    private fun bitrate(quality: String): String? = when (quality.uppercase()) {
        "AAC_320", "320" -> "320"
        "AAC_160", "160" -> "160"
        "AAC_96", "96" -> "96"
        "AAC_48", "48" -> "48"
        "AAC_12", "12" -> "12"
        else -> "320"
    }

    override suspend fun streams(track: Track, quality: String): List<Stream> {
        val song = songDetails(track.id) ?: return emptyList()
        val plain = decryptMediaUrl(song.optJSONObject("more_info")?.optStringOrNull("encrypted_media_url"))
        if (plain.isBlank()) return emptyList()
        val rate = bitrate(quality) ?: return emptyList()
        // getDownloadUrl(): the decrypted URL carries "_<n>.mp4"; swap in the requested bitrate.
        val finalUrl = plain.replace(Regex("_\\d+\\.mp4(?=[?#]|$)"), "_$rate.mp4")
        return listOf(
            Stream(
                url = finalUrl,
                quality = "${rate}kbps",
                mime = "audio/mp4",
                headers = mapOf("User-Agent" to UA, "Referer" to REFERER),
                provider = id,
            )
        )
    }

    /** getSong(): song.getDetails by id, webapi.get by token, both keyed by id or perma_url. */
    private fun songDetails(fetchId: String): JSONObject? {
        val byIdFirst = Regex("^[A-Za-z0-9_-]{8}$").matches(fetchId)
        for (attempt in 0..1) {
            val byId = if (attempt == 0) byIdFirst else !byIdFirst
            val result = if (byId) call("song.getDetails", listOf("pids" to fetchId))
            else call("webapi.get", listOf("token" to fetchId, "type" to "song", "includeMetaTags" to "0"))
            val songs = result?.array("songs") ?: continue
            for (song in songs.objects()) {
                if (song.optStringOrNull("id") == fetchId ||
                    extractToken(song.optStringOrNull("perma_url")) == fetchId
                ) {
                    return song
                }
            }
        }
        return null
    }
}
