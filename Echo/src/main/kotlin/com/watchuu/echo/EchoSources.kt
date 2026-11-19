package com.watchuu.echo

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * The audio sources Echo Music uses besides YouTube Music, ported from its two add-on servers so
 * nothing has to be deployed:
 *
 *  - EchoMusicApp/lossless-extension (src/providers/): Qobuz-DL, LastBabe, Monochrome, Deezer,
 *    Eljohnzc and Instable - the six upstreams behind its /a.../f routes.
 *  - EchoMusicApp/320kbs-extension (modules/echo, common/helpers): JioSaavn at 320kbps.
 *
 * Every source answers the add-on contract the app reads (ExtensionManager.kt):
 *   search -> tracks { id, title, artist, duration }
 *   stream -> { url, format, quality, manifest, encrypted }
 */
object EchoSources {

    data class Track(
        val id: String,
        val title: String,
        val artist: String,
        /** seconds */
        val duration: Int?,
    )

    data class Stream(
        val url: String,
        val format: String? = null,
        val quality: String? = null,
        /** none | hls | dash */
        val manifest: String = "none",
        val lossless: Boolean,
        val headers: Map<String, String> = emptyMap(),
    )

    interface Source {
        val key: String
        val label: String
        suspend fun search(query: String): List<Track>
        suspend fun stream(id: String): Stream?
    }

    class UpstreamError(val status: Int, message: String) : Exception(message)

    /** config.ts defaults */
    private const val QOBUZ_DL_BASE = "https://qobuz-dll.foreyou.workers.dev"
    private const val LASTBABE_BASE = "https://lastbabe.velixir.run"
    private const val MONOCHROME_BASE = "https://tracks.monochrome.st"
    private const val DEEZER_BASE = "https://dzr.tabs-vs-spaces.wtf"
    private const val ELJOHNZC_BASE = "https://eljohnzc-addon.de.deplexo.com/4433c7caff7fd070309e"
    private const val INSTABLE_BASE = "https://instable.arcod.xyz"

    /** REQUEST_TIMEOUT_MS / UPSTREAM_RETRIES in config.ts, in seconds for nicehttp */
    private const val REQUEST_TIMEOUT = 6L
    private const val UPSTREAM_RETRIES = 1

    private val ID_RE = Regex("^[A-Za-z0-9_-]+$")

    // ------------------------------------------------------------------ http

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun url(base: String, path: String, params: Map<String, String>): String {
        val root = base.trimEnd('/') + "/" + path.trimStart('/')
        if (params.isEmpty()) return root
        return root + "?" + params.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
    }

    private fun retryable(status: Int) = status == 408 || status == 425 || status == 429 || status >= 500

    private fun parse(text: String): Any? {
        val t = text.trim()
        return when {
            t.startsWith("{") -> JSONObject(t)
            t.startsWith("[") -> JSONArray(t)
            else -> null
        }
    }

    /**
     * One GET with the retry loop the qobuzdl / lastbabe / instable providers share: retry on
     * 408/425/429/5xx and on a body that says success:false, backing off 200ms * 2^attempt.
     */
    private suspend fun getJson(
        url: String,
        headers: Map<String, String> = mapOf("Accept" to "application/json"),
        timeout: Long = REQUEST_TIMEOUT,
        retries: Int = UPSTREAM_RETRIES,
        checkSuccess: Boolean = false,
    ): Any? {
        var last: Exception? = null
        for (attempt in 0..retries) {
            try {
                val r = app.get(url, headers = headers, timeout = timeout)
                val body = runCatching { parse(r.text) }.getOrNull()
                    ?: throw UpstreamError(502, "invalid JSON")
                if (r.code !in 200..299) {
                    val err = UpstreamError(if (retryable(r.code)) 502 else r.code, "HTTP ${r.code}")
                    if (!retryable(r.code)) throw err
                    last = err
                } else if (checkSuccess && body is JSONObject && body.has("success") && !body.optBoolean("success", true)) {
                    last = UpstreamError(502, body.optString("error", "upstream error"))
                } else return body
            } catch (e: UpstreamError) {
                if (e.status != 502) throw e
                last = e
            } catch (e: Exception) {
                last = e
            }
            if (attempt < retries) delay(200L * (1 shl attempt))
        }
        throw last ?: UpstreamError(502, "request failed")
    }

    private fun JSONObject.str(key: String): String? =
        if (has(key) && !isNull(key)) opt(key)?.takeIf { it is String || it is Number }?.toString() else null

    private fun JSONObject.num(key: String): Int? =
        (opt(key) as? Number)?.toInt()

    private fun JSONArray.objects(): List<JSONObject> =
        (0 until length()).mapNotNull { optJSONObject(it) }

    private fun httpsUrl(v: String?): String? = v?.takeIf { it.startsWith("https://", true) }

    // ------------------------------------------------------------------ Qobuz-DL (/a)

    /** providers/qobuzdl.ts */
    object QobuzDl : Source {
        override val key = "qobuzdl"
        override val label = "Qobuz-DL"

        override suspend fun search(query: String): List<Track> {
            val body = getJson(url(QOBUZ_DL_BASE, "/api/get-music", mapOf("q" to query, "offset" to "0")), checkSuccess = true)
            val items = (body as? JSONObject)?.optJSONObject("data")?.optJSONObject("tracks")?.optJSONArray("items")
                ?: return emptyList()
            return items.objects().mapNotNull { qobuzTrack(it) }
        }

        override suspend fun stream(id: String): Stream? {
            // qualityId(): LOSSLESS -> 27, the app always asks for LOSSLESS
            val body = getJson(url(QOBUZ_DL_BASE, "/api/download-music", mapOf("track_id" to id, "quality" to "27")), checkSuccess = true)
            val u = httpsUrl((body as? JSONObject)?.optJSONObject("data")?.str("url")) ?: return null
            // the upstream returns only a url; nothing about codec or bit depth is inferred
            return Stream(u, lossless = true)
        }
    }

    /** toTrack() shared by qobuzdl.ts and instable.ts: Qobuz's own track object */
    private fun qobuzTrack(raw: JSONObject): Track? {
        val id = raw.str("id") ?: return null
        val title = raw.str("title")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return Track(
            id = id,
            title = title,
            artist = raw.optJSONObject("performer")?.str("name").orEmpty(),
            duration = raw.num("duration"),
        )
    }

    // ------------------------------------------------------------------ LastBabe (/b)

    /** providers/lastbabe.ts - the landing url mints a short-lived add-on url behind a proof of work */
    object LastBabe : Source {
        override val key = "lastbabe"
        override val label = "LastBabe"

        private val lock = Mutex()
        @Volatile private var resolvedBase: String? = null
        @Volatile private var resolvedExpiresAt = 0L

        override suspend fun search(query: String): List<Track> {
            val body = getJson(url(base(), "/search", mapOf("q" to query)))
            val tracks = (body as? JSONObject)?.optJSONArray("tracks") ?: return emptyList()
            return tracks.objects().mapNotNull { raw ->
                val id = raw.str("trackId") ?: raw.str("id") ?: return@mapNotNull null
                val title = raw.str("title")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val artist = raw.str("artist")
                    ?: raw.optJSONArray("artists")?.let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String }.joinToString(", ") }
                    ?: ""
                Track(id, title, artist, raw.num("duration"))
            }
        }

        override suspend fun stream(id: String): Stream? {
            val body = getJson(url(base(), "/stream/${enc(id)}", mapOf("quality" to "LOSSLESS"))) as? JSONObject ?: return null
            val u = httpsUrl(body.str("url")) ?: return null
            if (body.optBoolean("encrypted", false)) return null
            val quality = body.str("quality") ?: "LOSSLESS"
            return Stream(
                url = u,
                format = body.str("format") ?: body.str("codec"),
                quality = quality,
                manifest = body.str("manifest")?.takeIf { it == "hls" || it == "dash" } ?: "none",
                lossless = quality.equals("LOSSLESS", true) || body.str("format").equals("flac", true),
            )
        }

        private suspend fun base(): String {
            resolvedBase?.let { if (System.currentTimeMillis() < resolvedExpiresAt - 60_000) return it }
            return lock.withLock {
                resolvedBase?.let { if (System.currentTimeMillis() < resolvedExpiresAt - 60_000) return@withLock it }
                mintFreshUrl(LASTBABE_BASE)
            }
        }

        private suspend fun mintFreshUrl(landingUrl: String): String {
            val root = landingUrl.trimEnd('/') + "/"
            val ch = app.get("${root}challenge", headers = mapOf("Accept" to "application/json"), timeout = 15L)
            if (ch.code !in 200..299) throw UpstreamError(502, "LastBabe challenge failed")
            val challengeObj = JSONObject(ch.text)
            val challenge = challengeObj.opt("challenge") as? String
                ?: throw UpstreamError(502, "LastBabe challenge was malformed")
            val d = (challengeObj.opt("difficulty") as? Number)?.toInt()
            val difficulty = if (d != null && d in 1..8) d else 4
            val nonce = solve(challenge, difficulty)

            val payload = JSONObject().put("challenge", challenge).put("nonce", nonce).toString()
            val mint = app.post(
                "${root}mint",
                headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
                requestBody = payload.toRequestBody("application/json".toMediaType()),
                timeout = 15L,
            )
            if (mint.code !in 200..299) throw UpstreamError(502, "LastBabe mint failed")
            val minted = JSONObject(mint.text)
            val manifestUrl = (minted.opt("manifestUrl") as? String) ?: (minted.opt("addonUrl") as? String)
            if (manifestUrl == null || !manifestUrl.startsWith("https://", true)) {
                throw UpstreamError(502, "LastBabe mint response was malformed")
            }
            val base = manifestUrl.removeSuffix("/manifest.json").trimEnd('/')
            resolvedBase = base
            resolvedExpiresAt = (minted.opt("expiresAt") as? Number)?.let { it.toLong() * 1000 }
                ?: (System.currentTimeMillis() + 48L * 60 * 60 * 1000)
            return base
        }

        /** sha256(challenge + nonce) must start with `difficulty` hex zeros */
        private suspend fun solve(challenge: String, difficulty: Int): String = withContext(Dispatchers.Default) {
            val md = MessageDigest.getInstance("SHA-256")
            val prefix = "0".repeat(difficulty)
            var nonce = 0L
            var found: String? = null
            while (found == null) {
                nonce += 1
                val candidate = nonce.toString()
                val hex = md.digest((challenge + candidate).toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                if (hex.startsWith(prefix)) found = candidate
            }
            found!!
        }
    }

    // ------------------------------------------------------------------ Monochrome (/c)

    /** providers/monochrome.ts */
    object Monochrome : Source {
        override val key = "monochrome"
        override val label = "Monochrome"

        override suspend fun search(query: String): List<Track> {
            val body = getJson(
                url(MONOCHROME_BASE, "/search/tracks", mapOf("q" to query, "limit" to "10")),
                timeout = maxOf(REQUEST_TIMEOUT, 10L),
            )
            val tracks = (body as? JSONObject)?.optJSONArray("tracks") ?: return emptyList()
            return tracks.objects().mapNotNull { raw ->
                val id = raw.str("id") ?: raw.str("trackId") ?: return@mapNotNull null
                val title = raw.str("title")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val artists = raw.optJSONArray("artistNames")
                    ?.let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String } }.orEmpty()
                // duration comes in milliseconds
                val duration = (raw.opt("duration") as? Number)?.let { Math.round(it.toDouble() / 1000).toInt() }
                Track(id, title, artists.joinToString(", "), duration)
            }
        }

        override suspend fun stream(id: String): Stream? {
            if (!ID_RE.matches(id)) return null
            return Stream("$MONOCHROME_BASE/track/${enc(id)}", format = "flac", quality = "LOSSLESS", lossless = true)
        }
    }

    // ------------------------------------------------------------------ Deezer (/d), Eljohnzc (/e)

    /** providers/remote-addon.ts - another add-on server speaking the same contract */
    class RemoteAddon(override val key: String, override val label: String, private val base: String) : Source {
        override suspend fun search(query: String): List<Track> {
            val body = getJson(
                url(base, "/search", mapOf("q" to query, "quality" to "LOSSLESS", "atmos" to "auto")),
                retries = 0,
            )
            val items = (body as? JSONObject)?.optJSONArray("tracks") ?: (body as? JSONArray) ?: return emptyList()
            return items.objects().mapNotNull { raw ->
                val id = raw.str("id") ?: return@mapNotNull null
                val title = raw.str("title")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val artist = (raw.opt("artist") as? String) ?: raw.optJSONObject("artist")?.str("name") ?: ""
                Track(id, title, artist, raw.num("duration"))
            }
        }

        override suspend fun stream(id: String): Stream? {
            if (!ID_RE.matches(id)) return null
            val body = getJson(
                url(base, "/stream/${enc(id)}", mapOf("quality" to "LOSSLESS", "atmos" to "auto")),
                retries = 0,
            ) as? JSONObject ?: return null
            val u = httpsUrl(body.str("url")) ?: return null
            if (body.optBoolean("encrypted", false)) return null
            val format = body.str("format")
            val quality = body.str("quality") ?: "LOSSLESS"
            return Stream(
                url = u,
                format = format,
                quality = quality,
                manifest = body.str("manifest")?.takeIf { it == "hls" || it == "dash" } ?: "none",
                lossless = format.equals("flac", true) || quality.equals("LOSSLESS", true),
            )
        }
    }

    val Deezer = RemoteAddon("deezer", "Deezer", DEEZER_BASE)
    val Eljohnzc = RemoteAddon("eljohnzc", "Eljohnzc", ELJOHNZC_BASE)

    // ------------------------------------------------------------------ Instable (/f)

    /** providers/instable.ts */
    object Instable : Source {
        override val key = "instable"
        override val label = "Instable"

        private val headers = mapOf(
            "Accept" to "application/json",
            "Accept-Language" to "en-US,en;q=0.9",
            "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/131 Safari/537.36",
            "Referer" to "$INSTABLE_BASE/",
            "Origin" to INSTABLE_BASE,
        )

        // max(UPSTREAM_RETRIES, 2) and max(timeout, 15s)
        private suspend fun get(path: String, params: Map<String, String>) =
            getJson(url(INSTABLE_BASE, path, params), headers, timeout = 15L, retries = maxOf(UPSTREAM_RETRIES, 2), checkSuccess = true)

        override suspend fun search(query: String): List<Track> {
            val body = get("/api/search", mapOf("q" to query))
            val items = (body as? JSONObject)?.optJSONObject("data")?.optJSONObject("tracks")?.optJSONArray("items")
                ?: return emptyList()
            return items.objects().mapNotNull { qobuzTrack(it) }
        }

        override suspend fun stream(id: String): Stream? {
            val body = get("/api/player/stream/${enc(id)}", mapOf("quality" to "27")) as? JSONObject ?: return null
            val u = httpsUrl(body.str("url")) ?: return null
            val format = body.str("mimeType")?.substringAfterLast('/')
            return Stream(u, format = format, quality = body.str("quality"), lossless = true)
        }
    }

    // ------------------------------------------------------------------ JioSaavn (320kbs-extension)

    /** modules/echo + common/helpers of 320kbs-extension */
    object Saavn : Source {
        override val key = "saavn"
        override val label = "JioSaavn"

        private const val API = "https://www.jiosaavn.com/api.php"

        /** common/constants/user-agents.constant.ts (first entries) */
        private val userAgents = listOf(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36 Edg/134.0.0.0",
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0",
        )

        /** fetch.helper.ts useFetch */
        private suspend fun call(endpoint: String, params: Map<String, String>): JSONObject? {
            val all = linkedMapOf(
                "__call" to endpoint, "_format" to "json", "_marker" to "0",
                "api_version" to "4", "ctx" to "web6dot0",
            ) + params
            val r = app.get(
                API + "?" + all.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" },
                headers = mapOf("Content-Type" to "application/json", "User-Agent" to userAgents.random()),
                timeout = 10L,
            )
            return parse(r.text) as? JSONObject
        }

        override suspend fun search(query: String): List<Track> {
            // echo.controller.ts: searchSongs({ query, page: 0, limit: 20 })
            val data = call("search.getResults", mapOf("q" to query, "p" to "0", "n" to "20")) ?: return emptyList()
            val results = data.optJSONArray("results") ?: return emptyList()
            return results.objects().take(20).mapNotNull { song ->
                val id = song.str("id") ?: return@mapNotNull null
                val title = decodeEntities(song.str("title") ?: return@mapNotNull null)
                val more = song.optJSONObject("more_info")
                val map = more?.optJSONObject("artistMap")
                // createEchoTrack: primary artists, else all
                val primary = map?.optJSONArray("primary_artists")?.objects().orEmpty()
                val artists = primary.ifEmpty { map?.optJSONArray("artists")?.objects().orEmpty() }
                Track(
                    id = id,
                    title = title,
                    artist = artists.mapNotNull { it.str("name")?.let(::decodeEntities) }.joinToString(", "),
                    duration = more?.str("duration")?.toIntOrNull(),
                )
            }
        }

        override suspend fun stream(id: String): Stream? {
            if (!Regex("^[a-zA-Z0-9_-]{1,64}$").matches(id)) return null
            val data = call("song.getDetails", mapOf("pids" to id)) ?: return null
            val songs = data.optJSONArray("songs")?.objects().orEmpty()
            val song = songs.firstOrNull { it.str("id") == id } ?: return null
            val more = song.optJSONObject("more_info") ?: return null
            val encrypted = more.str("encrypted_media_url") ?: return null
            val supports320 = more.str("320kbps") == "true"

            // link.helper.ts createDownloadLinks, then createEchoStream picks the highest bitrate
            val decrypted = decrypt(encrypted) ?: return null
            val best = if (supports320) "_320" to 320 else "_160" to 160
            val u = decrypted.replace("_96", best.first).replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "https://")
            if (!u.startsWith("https://")) return null
            return Stream(u, format = "m4a", quality = "${best.second}kbps", lossless = false)
        }

        /** DES-ECB with the key '38346591' */
        private fun decrypt(encryptedMediaUrl: String): String? = runCatching {
            val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec("38346591".toByteArray(Charsets.UTF_8), "DES"))
            val bytes = android.util.Base64.decode(encryptedMediaUrl.trim(), android.util.Base64.DEFAULT)
            String(cipher.doFinal(bytes), Charsets.UTF_8).trim()
        }.getOrNull()?.takeIf { it.startsWith("http", true) }

        /** echo.helpers.ts decodeEntities, the common entities */
        private val entities = mapOf(
            "amp" to "&", "quot" to "\"", "apos" to "'", "lt" to "<", "gt" to ">", "nbsp" to " ",
            "ndash" to "–", "mdash" to "—", "hellip" to "…", "lsquo" to "‘", "rsquo" to "’",
            "ldquo" to "“", "rdquo" to "”", "middot" to "·", "bull" to "•",
        )

        private fun decodeEntities(value: String): String =
            Regex("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z][0-9a-zA-Z]*);").replace(value) { m ->
                val name = m.groupValues[1]
                if (!name.startsWith("#")) return@replace entities[name] ?: m.value
                val hex = name.length > 1 && name[1].lowercaseChar() == 'x'
                val cp = name.substring(if (hex) 2 else 1).toIntOrNull(if (hex) 16 else 10) ?: return@replace m.value
                if (cp <= 0 || cp > 0x10ffff || cp in 0xd800..0xdfff) m.value else String(Character.toChars(cp))
            }
    }

    /** lossless first, the way the app's racing provider lists them; JioSaavn is the 320kbps add-on */
    val all: List<Source> = listOf(Instable, Eljohnzc, Monochrome, Deezer, LastBabe, QobuzDl, Saavn)
}
