package com.watchuu.nonu

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import org.json.JSONObject

object NonuDecrypt {
    private const val TAG = "NonuDecrypt"

    /** Widevine KEYFORMAT, not written in /webplayback song m3u8s */
    private const val WIDEVINE_KEYFORMAT = "urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"

    /** Cache for FairPlay templates (adamId+uri -> JSON text) */
    private val templateCache = HashMap<String, String>()

    fun log(msg: String) {
        Log.d(TAG, msg)
        NonuApi.log("decrypt", msg)
    }

    // ------------------------------------------------------------------ loopback server

    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "nonu-srv").apply { isDaemon = true } }
    @Volatile private var server: ServerSocket? = null

    @Synchronized
    private fun port(): Int {
        server?.let { if (!it.isClosed) return it.localPort }
        val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        server = s
        pool.execute {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: Throwable) { break }
                pool.execute { handleConn(c) }
            }
        }
        return s.localPort
    }

    private fun handleConn(socket: java.net.Socket) {
        try {
            socket.soTimeout = 10_000
            val reader = socket.getInputStream().bufferedReader()
            val line = reader.readLine() ?: return
            val parts = line.split(" ")
            if (parts.size < 2 || parts[0] != "GET") {
                fail(socket.getOutputStream(), 400); return
            }
            val path = parts[1]
            val params = path.substringAfter("?", "").split("&")
                .filter { it.contains("=") }
                .associate {
                    val (k, v) = it.split("=", limit = 2)
                    k to java.net.URLDecoder.decode(v, "UTF-8")
                }

            val fileName = params["f"] ?: run { fail(socket.getOutputStream(), 404); return }

            // Read remaining headers to find Range
            var range: String? = null
            while (true) {
                val h = reader.readLine() ?: break
                if (h.isBlank()) break
                if (h.lowercase().startsWith("range:")) range = h.substringAfter(":").trim()
            }

            val dir = cacheDir ?: run { fail(socket.getOutputStream(), 500); return }
            val file = File(dir, fileName)
            if (!file.exists()) { fail(socket.getOutputStream(), 404); return }

            val total = file.length()
            var start = 0L
            var end = total - 1
            var partial = false

            if (range != null) {
                val m = Regex("""bytes=(\d*)-(\d*)""").find(range)
                if (m != null) {
                    partial = true
                    if (m.groupValues[1].isNotBlank()) start = m.groupValues[1].toLong()
                    if (m.groupValues[2].isNotBlank()) end = m.groupValues[2].toLong()
                }
            }
            if (end >= total) end = total - 1

            val out = socket.getOutputStream()
            val head = StringBuilder()
            head.append("HTTP/1.1 ${if (partial) "206 Partial Content" else "200 OK"}\r\n")
            if (partial) head.append("Content-Range: bytes $start-$end/$total\r\n")
            head.append("Content-Length: ${end - start + 1}\r\n")
            head.append("Content-Type: audio/mp4\r\n")
            head.append("Accept-Ranges: bytes\r\n")
            head.append("Connection: close\r\n\r\n")
            out.write(head.toString().toByteArray())

            java.io.RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                val buf = ByteArray(64 * 1024)
                var remaining = end - start + 1
                while (remaining > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    remaining -= n
                }
            }
            out.flush()
        } catch (_: Throwable) {
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun fail(o: OutputStream, code: Int) {
        o.write("HTTP/1.1 $code Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        o.flush()
    }

    private var cacheDir: File? = null

    /** Returns a localhost URL that the player can stream from (with Range support). */
    fun localUrl(file: File): String {
        cacheDir = file.parentFile
        return "http://127.0.0.1:${port()}/a?f=${URLEncoder.encode(file.name, "UTF-8")}"
    }

    // ------------------------------------------------------------------ m3u8 parsing

    /**
     * Parse Apple's raw media m3u8 (with EXT-X-KEY), returning init segment and byte/time ranges of each segment.
     */
    fun parseMediaPlaylist(text: String, playlistUrl: String, adamIdHint: String?): MediaPlaylist {
        val base = playlistUrl.substringBeforeLast("/") + "/"
        val fileName = playlistUrl.substringAfterLast("/")
        val adamId = adamIdHint ?: "_A(\\d+)[._]".toRegex().find(fileName)?.groupValues?.get(1) ?: ""

        /** Resolve a URI (may be relative) against the playlist base URL. */
        fun resolveUri(uri: String): String =
            if (uri.startsWith("http://") || uri.startsWith("https://")) uri else base + uri

        var init: MediaSegment? = null
        var keyUri: String? = null
        var currentKey: String? = null
        var duration = 0f
        var pendingDuration: Float? = null
        var next = 0L
        val segments = mutableListOf<MediaSegment>()

        fun byteRange(value: String, fallbackOffset: Long): Pair<Long, Long> {
            val parts = value.replace("\"", "").trim().split("@")
            val len = parts[0].toLong()
            val start = if (parts.size > 1) parts[1].toLongOrNull() ?: fallbackOffset else fallbackOffset
            return start to start + len - 1
        }

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-KEY:") -> {
                    val method = "METHOD=([^,]+)".toRegex().find(line)?.groupValues?.get(1)
                    val uri = "URI=\"([^\"]+)\"".toRegex().find(line)?.groupValues?.get(1)
                    val format = "KEYFORMAT=\"([^\"]*)\"".toRegex().find(line)?.groupValues?.get(1)
                    if (uri?.startsWith("data:") == true && format?.lowercase() != WIDEVINE_KEYFORMAT) continue
                    currentKey = if (method == "NONE" || uri == null) null else uri
                    if (currentKey != null && currentKey != "skd://itunes.apple.com/P000000000/s1/e1" && keyUri == null) keyUri = currentKey
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    val uri = "URI=\"([^\"]+)\"".toRegex().find(line)?.groupValues?.get(1)
                    val range = "BYTERANGE=\"([^\"]+)\"".toRegex().find(line)?.groupValues?.get(1)
                    if (uri == null || range == null) throw IllegalArgumentException("Invalid EXT-X-MAP: $line")
                    val (start, end) = byteRange(range, 0)
                    init = MediaSegment(resolveUri(uri), start, end, 0f, 0f, null, isInit = true)
                    next = end + 1
                }
                line.startsWith("#EXTINF:") -> {
                    pendingDuration = line.substring(8).trimEnd(',').toFloatOrNull()
                }
                line.startsWith("#EXT-X-BYTERANGE:") -> {
                    val (start, end) = byteRange(line.substring(17), next)
                    next = end + 1
                    val dur = pendingDuration ?: 0f
                    val key = when (currentKey) {
                        "skd://itunes.apple.com/P000000000/s1/e1" -> "prefetch"
                        null -> null
                        else -> "track"
                    }
                    segments += MediaSegment(init?.url ?: "", start, end, duration, dur, key)
                    duration += dur
                    pendingDuration = null
                }
            }
        }

        if (init == null || segments.isEmpty()) throw IllegalArgumentException("Invalid media playlist")
        if (segments.any { it.key == "track" } && (keyUri == null || adamId.isEmpty())) throw IllegalArgumentException("Missing key URI or adamId")

        val cenc = keyUri?.startsWith("data:") == true
        return MediaPlaylist(init!!.url, adamId, keyUri, cenc, init!!, segments, duration, segments.last().end + 1)
    }

    // ------------------------------------------------------------------ segment download

    /**
     * Download a single segment via HTTP Range request from Apple CDN.
     * Uses java.net.HttpURLConnection for binary data (CloudStream's app.get returns String).
     * Retries up to 3 times on failure with exponential backoff.
     */
    private suspend fun downloadPiece(piece: MediaSegment): ByteArray = withContext(Dispatchers.IO) {
        val length = (piece.end - piece.start + 1).toInt()
        for (attempt in 0..3) {
            try {
                val conn = URL(piece.url).openConnection() as HttpURLConnection
                conn.connectTimeout = 20_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("Range", "bytes=${piece.start}-${piece.end}")
                conn.setRequestProperty("User-Agent", NonuApi.userAgent())
                conn.setRequestProperty("Accept", "*/*")

                val code = conn.responseCode
                if (code !in 200..299) throw Exception("HTTP $code for segment")

                val bytes = conn.inputStream.use { it.readBytes() }

                // If server ignores Range and returns the full file, slice it
                if (code == 200 && bytes.size > length) {
                    return@withContext bytes.copyOfRange(piece.start.toInt(), piece.end.toInt() + 1)
                }
                if (bytes.size != length) {
                    throw Exception("Segment length mismatch: got ${bytes.size}, want $length")
                }
                return@withContext bytes
            } catch (e: Exception) {
                if (attempt >= 3) throw e
                delay(500L * (1 shl attempt))
            }
        }
        throw Exception("Failed to download segment after retries")
    }

    // ------------------------------------------------------------------ key / template fetching

    /**
     * Fetch the FairPlay decryption template from the 1412 proxy.
     * Calls GET /key?adamId=X&uri=Y which returns JSON text used by hook_template_load.
     */
    private suspend fun fetchTemplate(adamId: String, keyUri: String): String = withContext(Dispatchers.IO) {
        val cacheKey = "$adamId $keyUri"
        templateCache[cacheKey]?.let { return@withContext it }

        val encAdamId = URLEncoder.encode(adamId, "UTF-8")
        val encUri = URLEncoder.encode(keyUri, "UTF-8")
        val url = "${NonuApi.SITE}/key?adamId=$encAdamId&uri=$encUri"

        log("fetching template for adamId=$adamId")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        NonuApi.headers().forEach { (k, v) -> conn.setRequestProperty(k, v) }

        val code = conn.responseCode
        if (code !in 200..299) throw Exception("Template fetch failed: HTTP $code")

        val text = conn.inputStream.use { it.bufferedReader().readText() }
        if (text.isBlank()) throw Exception("Empty template response")

        // Return the raw JSON text — templateFromJson handles both
        // contentKey format and legacy ctx/state format
        val template = text

        templateCache[cacheKey] = template
        log("template fetched: ${template.length} chars")
        template
    }

    // ------------------------------------------------------------------ CENC key exchange

    /**
     * Extract PSSH data from a data: URI (base64-encoded PSSH box).
     * Returns the raw PSSH bytes for MediaDrm, or null on parse failure.
     */
    fun parsePssh(dataUri: String): ByteArray? {
        // data:text/plain;base64,AAAA...
        val b64 = dataUri.substringAfter("base64,", "")
        if (b64.isBlank()) return null
        return try {
            android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        } catch (_: Exception) { null }
    }

    /**
     * Attempt Widevine license exchange with the proxy to obtain a 16-byte content key.
     *
     * Flow:
     *   1. Use Android MediaDrm to generate a Widevine challenge from the PSSH
     *   2. POST the challenge to the proxy's /mv/license endpoint
     *   3. Feed the license response back to MediaDrm
     *   4. Attempt to extract the raw key via CryptoSession
     *
     * Returns the 16-byte key or null if extraction is not possible.
     */
    suspend fun obtainCencKey(adamId: String, psshBytes: ByteArray): ByteArray? = withContext(Dispatchers.IO) {
        val widevineUuid = java.util.UUID(-0x121074568629b532L, -0x6386f2893e3cbc19L)
        var drm: android.media.MediaDrm? = null
        var session: ByteArray? = null

        try {
            if (!android.media.MediaDrm.isCryptoSchemeSupported(widevineUuid)) {
                log("MediaDrm: Widevine not supported on this device")
                return@withContext null
            }

            drm = android.media.MediaDrm(widevineUuid)
            // Force L3 (software) so we might get access to keys
            runCatching { drm.setPropertyString("securityLevel", "L3") }
            session = drm.openSession()

            // 1. Generate challenge
            val keyRequest = drm.getKeyRequest(
                session, psshBytes, "cenc",
                android.media.MediaDrm.KEY_TYPE_STREAMING, null
            )
            val challenge = keyRequest.data
            log("MediaDrm: challenge generated, ${challenge.size} bytes")

            // 2. Exchange with proxy
            val challengeB64 = android.util.Base64.encodeToString(challenge, android.util.Base64.NO_WRAP)
            val body = JSONObject().apply {
                put("adamId", adamId)
                put("uri", keyRequest.defaultUrl ?: "")
                put("challenge", challengeB64)
            }

            val conn = URL("${NonuApi.SITE}/mv/license").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 20_000
            conn.doOutput = true
            NonuApi.headers(mapOf("Content-Type" to "application/json")).forEach { (k, v) ->
                conn.setRequestProperty(k, v)
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            if (code !in 200..299) {
                log("MediaDrm: license exchange failed HTTP $code")
                return@withContext null
            }

            val respText = conn.inputStream.use { it.bufferedReader().readText() }
            val respJson = JSONObject(respText)
            val licenseB64 = respJson.optJSONObject("data")?.optString("license")
                ?: respJson.optString("license")
            if (licenseB64.isNullOrBlank()) {
                log("MediaDrm: no license in response")
                return@withContext null
            }

            val licenseBytes = android.util.Base64.decode(licenseB64, android.util.Base64.DEFAULT)
            log("MediaDrm: license received, ${licenseBytes.size} bytes")

            // 3. Provide license to MediaDrm
            drm.provideKeyResponse(session, licenseBytes)
            log("MediaDrm: key response loaded")

            // 4. Try to extract the key
            // Method A: queryKeyStatus - some implementations expose key material
            val status = drm.queryKeyStatus(session)
            log("MediaDrm: keyStatus entries=${status.size}")
            for ((k, v) in status) {
                log("MediaDrm: status[$k] = ${v.take(60)}")
            }

            // Method B: CryptoSession decrypt of known plaintext
            // If the CDM exposes content keys through CryptoSession, we can recover the key
            // by encrypting a known block and comparing
            try {
                val cs = drm.getCryptoSession(session, "AES/CTR/NoPadding", "HmacSHA256")
                // Try decrypting 16 zero bytes with zero IV - if it returns non-zero, the CDM
                // is doing AES-CTR decryption and we can derive the key
                val testIv = ByteArray(16)
                val testData = ByteArray(16)
                val kid = ByteArray(16) // Will be replaced with actual KID
                val result = cs.decrypt(kid, testData, testIv)
                if (result != null && result.size == 16 && !result.all { it == 0.toByte() }) {
                    // The CDM decrypted with the content key! The result is AES-CTR(key, 0x00...)
                    // For AES-CTR: decrypt(key, plaintext, iv) = plaintext XOR AES(key, iv)
                    // So decrypt(key, 0x00..., 0x00...) = AES(key, 0x00...)
                    // We can't directly get the key from this, but it means CryptoSession works
                    log("MediaDrm: CryptoSession decrypt works, but raw key extraction not available")
                }
            } catch (e: Exception) {
                log("MediaDrm: CryptoSession not available: ${e.message?.take(80)}")
            }

            // Raw key extraction not available through standard MediaDrm APIs
            // The CENC variant fallback to FairPlay AAC will be used instead
            log("MediaDrm: raw key not extractable, will fall back to FairPlay variant")
            null

        } catch (e: Exception) {
            log("MediaDrm: error: ${e::class.simpleName}: ${e.message?.take(100)}")
            null
        } finally {
            try {
                if (session != null && drm != null) drm.closeSession(session)
            } catch (_: Exception) {}
            try {
                drm?.close()
            } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------------ decrypt pipeline

    /**
     * Decrypt all segments of a FairPlay media playlist using the temari cipher.
     * Returns the path to a decrypted M4A file in the app's cache directory.
     */
    suspend fun decryptTrack(context: Context, track: MediaPlaylist): File = withContext(Dispatchers.IO) {
        val wasm = WasmInstance(context)
        val dir = File(context.cacheDir, "nonu_dec").apply { mkdirs() }
        val outFile = File(dir, "nonu_${track.adamId}.m4a")

        // Clean up old files (keep max 6, delete older than 6 hours)
        dir.listFiles()?.let { files ->
            val now = System.currentTimeMillis()
            files.filter { now - it.lastModified() > 6 * 3600_000L }.forEach { it.delete() }
            files.sortedByDescending { it.lastModified() }.drop(6).forEach { it.delete() }
        }

        log("starting decrypt: adamId=${track.adamId}, segments=${track.segments.size}, cenc=${track.cenc}, initUrl=${track.init.url.take(100)}")

        // Pre-fetch the template/key if this track needs it (FairPlay only)
        var templateHandle = 0
        if (!track.cenc && track.keyUri != null) {
            try {
                val templateJson = fetchTemplate(track.adamId, track.keyUri)
                templateHandle = wasm.loadTemplate(templateJson.toByteArray(Charsets.UTF_8))
                log("template loaded, handle=$templateHandle")
            } catch (e: Exception) {
                log("WARNING: template fetch failed: ${e::class.simpleName}: ${e.message}")
            }
        }

        try {
            val outputStream = ByteArrayOutputStream((track.size + 1024).toInt())

            // 1. Download and process init segment
            log("downloading init segment: url=${track.init.url.take(100)}, range=${track.init.start}-${track.init.end}")
            val initBytes = downloadPiece(track.init)
            log("init downloaded: ${initBytes.size} bytes")

            // For CENC, save raw init before patching (needed for CencDecrypt)
            if (track.cenc) {
                track.rawInit = initBytes.clone()
            }

            // Patch init segment (rewrites MP4 box structure to remove DRM markers)
            val patchedInit = initBytes.clone()
            wasm.patchInit(patchedInit)
            outputStream.write(patchedInit)
            log("init segment patched and written")

            // Save patched init for ALAC repair on fragments
            val patchedInitForRepair = patchedInit.clone()

            // 2. Download and decrypt each audio segment
            for ((idx, piece) in track.segments.withIndex()) {
                if (idx % 10 == 0 || idx == track.segments.size - 1) {
                    log("segment ${idx + 1}/${track.segments.size}: key=${piece.key}")
                }

                val segBytes = downloadPiece(piece)

                when (piece.key) {
                    "prefetch" -> {
                        val prefetchHandle = wasm.hook_prefetch_template()
                        wasm.decryptFragment(prefetchHandle, segBytes, patchedInitForRepair)
                    }
                    "track" -> {
                        if (track.cenc) {
                            log("WARNING: CENC segment in FairPlay pipeline, not decrypted")
                        } else {
                            // FairPlay decryption using template
                            if (templateHandle != 0) {
                                wasm.decryptFragment(templateHandle, segBytes, patchedInitForRepair)
                            } else {
                                log("WARNING: no template handle, segment not decrypted")
                            }
                        }
                    }
                    null -> {
                        // Unencrypted segment - still needs ALAC repair if init present
                        wasm.decryptFragment(0, segBytes, patchedInitForRepair)
                    }
                }

                outputStream.write(segBytes)
            }

            // Write the concatenated decrypted data to file
            outFile.writeBytes(outputStream.toByteArray())
            log("decrypted file written: ${outFile.length()} bytes -> ${outFile.absolutePath}")

        } finally {
            if (templateHandle != 0) {
                wasm.hook_template_free(templateHandle)
            }
        }

        outFile
    }

    /**
     * Decrypt all segments of a CENC media playlist using AES-128-CTR with the provided key.
     * Returns the path to a decrypted M4A file in the app's cache directory.
     */
    suspend fun decryptCencTrack(context: Context, track: MediaPlaylist, cencKey: ByteArray): File = withContext(Dispatchers.IO) {
        require(cencKey.size == 16) { "CENC key must be 16 bytes" }
        val wasm = WasmInstance(context)
        val dir = File(context.cacheDir, "nonu_dec").apply { mkdirs() }
        val outFile = File(dir, "nonu_${track.adamId}.m4a")

        // Clean up old files
        dir.listFiles()?.let { files ->
            val now = System.currentTimeMillis()
            files.filter { now - it.lastModified() > 6 * 3600_000L }.forEach { it.delete() }
            files.sortedByDescending { it.lastModified() }.drop(6).forEach { it.delete() }
        }

        log("starting CENC decrypt: adamId=${track.adamId}, segments=${track.segments.size}")

        val outputStream = ByteArrayOutputStream((track.size + 1024).toInt())

        // 1. Download init segment and extract CENC track info BEFORE patching
        log("downloading init segment")
        val initBytes = downloadPiece(track.init)
        log("init downloaded: ${initBytes.size} bytes")

        val trackInfo = CencDecrypt.parseTrackInfo(initBytes)
            ?: throw IllegalStateException("failed to parse CENC track info from init segment")
        log("CENC track info: ivSize=${trackInfo.ivSize}, codec=${trackInfo.originalCodec}, kid=${trackInfo.kid.joinToString("") { "%02x".format(it) }}")

        // Patch init (removes DRM boxes, rewrites enca→original codec)
        val patchedInit = initBytes.clone()
        wasm.patchInit(patchedInit)
        outputStream.write(patchedInit)
        log("init segment patched and written")

        // 2. Download and decrypt each fragment
        for ((idx, piece) in track.segments.withIndex()) {
            if (idx % 10 == 0 || idx == track.segments.size - 1) {
                log("CENC segment ${idx + 1}/${track.segments.size}")
            }

            val segBytes = downloadPiece(piece)

            // CENC AES-128-CTR decryption + DRM box neutralisation
            CencDecrypt.decryptFragment(segBytes, cencKey, trackInfo)

            outputStream.write(segBytes)
        }

        outFile.writeBytes(outputStream.toByteArray())
        log("CENC decrypted file written: ${outFile.length()} bytes -> ${outFile.absolutePath}")
        outFile
    }
}

data class MediaPlaylist(
    val url: String,
    val adamId: String,
    val keyUri: String?,
    val cenc: Boolean,
    val init: MediaSegment,
    val segments: List<MediaSegment>,
    val duration: Float,
    val size: Long
) {
    var rawInit: ByteArray? = null
}

data class MediaSegment(
    val url: String,
    val start: Long,
    val end: Long,
    val time: Float,
    val duration: Float,
    val key: String?,
    val isInit: Boolean = false,
    var decryptedUrl: String? = null
)
