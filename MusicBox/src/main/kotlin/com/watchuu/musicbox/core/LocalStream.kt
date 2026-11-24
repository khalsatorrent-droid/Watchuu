package com.watchuu.musicbox.core

import java.io.BufferedInputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * A tiny HTTP server on 127.0.0.1 that hands the player decrypted audio.
 *
 * CloudStream can only apply a ClearKey to DASH streams, and Amazon's gateway serves a single
 * CENC-encrypted MP4. The player is given http://127.0.0.1:<port>/s/<token> instead; each request
 * fetches the real file and passes it through [Cenc] on the way. The output has the input's exact
 * length, so Content-Length is the upstream one and ranges are answered by decrypting from the
 * start and discarding the bytes before the requested offset.
 */
object LocalStream {

    private enum class Kind { CENC, DEEZER, TEXT, JOIN }

    private class Entry(
        val kind: Kind,
        val url: String,
        val headers: Map<String, String>,
        val key: ByteArray,
        val created: Long,
        val text: String = "",
        val mime: String = "audio/mp4",
        val parts: List<String> = emptyList(),
    )

    private const val TTL_MS = 6 * 60 * 60 * 1000L
    private val entries = ConcurrentHashMap<String, Entry>()
    private val random = SecureRandom()

    @Volatile private var server: ServerSocket? = null

    /** Where files whose index sits after the audio are buffered (the app's cache dir on Android). */
    @Volatile var spoolDir: File? = null

    /** Where problems are reported; Diag on Android, stdout in tests. */
    @Volatile var log: (String) -> Unit = { println(it) }

    /** Registers a CENC-encrypted MP4 (Amazon) and returns the local URL the player should open. */
    fun register(url: String, headers: Map<String, String>, key: ByteArray): String =
        add(Entry(Kind.CENC, url, headers, key, System.currentTimeMillis()), "mp4")

    /** Registers a Deezer CDN file scrambled with the per-track Blowfish key. */
    fun registerDeezer(url: String, headers: Map<String, String>, key: ByteArray, extension: String): String =
        add(Entry(Kind.DEEZER, url, headers, key, System.currentTimeMillis(), mime = if (extension == "mp3") "audio/mpeg" else "audio/flac"), extension)

    /**
     * Serves a DASH track (an init segment and numbered media segments) as one continuous MP4, the
     * file Mornye builds with file.downloadSegments. Any player, and the bridge, can play that.
     */
    fun registerJoined(parts: List<String>, headers: Map<String, String>, mime: String): String =
        add(Entry(Kind.JOIN, parts.firstOrNull() ?: "", headers, ByteArray(0), System.currentTimeMillis(), mime = mime, parts = parts), "mp4")

    /** Serves a document (a DASH manifest the gateway sent inline) at a local URL. */
    fun registerText(text: String, mime: String, extension: String): String =
        add(Entry(Kind.TEXT, "", emptyMap(), ByteArray(0), System.currentTimeMillis(), text = text, mime = mime), extension)

    private fun add(entry: Entry, extension: String): String {
        val now = entry.created
        val it = entries.entries.iterator()
        while (it.hasNext()) if (now - it.next().value.created > TTL_MS) it.remove()
        val token = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        entries[token] = entry
        return "http://127.0.0.1:${port()}/s/$token.$extension"
    }

    @Synchronized
    private fun port(): Int {
        server?.takeIf { !it.isClosed }?.let { return it.localPort }
        val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        server = socket
        Thread({
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: continue
                Thread({ serve(client) }, "musicbox-stream").apply { isDaemon = true }.start()
            }
        }, "musicbox-stream-accept").apply { isDaemon = true }.start()
        return socket.localPort
    }

    private fun serve(client: Socket) {
        client.use { sock ->
            try {
                sock.soTimeout = 60_000
                val input = BufferedInputStream(sock.getInputStream())
                val requestLine = readLine(input) ?: return
                val requestHeaders = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) requestHeaders[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                }
                val parts = requestLine.split(" ")
                val method = parts.getOrNull(0) ?: "GET"
                val token = parts.getOrNull(1)?.substringAfter("/s/", "")?.substringBefore('.')?.substringBefore('?') ?: ""
                val out = sock.getOutputStream().buffered(64 * 1024)
                val entry = entries[token]
                if (entry == null) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                    return
                }
                val start = Regex("""bytes=(\d+)-""").find(requestHeaders["range"] ?: "")?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                if (entry.kind == Kind.TEXT) {
                    val body = entry.text.toByteArray(Charsets.UTF_8)
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: ${entry.mime}\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    if (!method.equals("HEAD", true)) out.write(body)
                    out.flush()
                    return
                }

                if (entry.kind == Kind.JOIN) {
                    serveJoined(entry, method, start, out)
                    return
                }
                val conn = (URL(entry.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                    for ((k, v) in entry.headers) setRequestProperty(k, v)
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    log("upstream HTTP $code")
                    out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                    conn.disconnect()
                    return
                }
                val length = conn.contentLengthLong
                val head = StringBuilder()
                if (start > 0 && length > 0) {
                    head.append("HTTP/1.1 206 Partial Content\r\n")
                    head.append("Content-Range: bytes $start-${length - 1}/$length\r\n")
                    head.append("Content-Length: ${length - start}\r\n")
                } else {
                    head.append("HTTP/1.1 200 OK\r\n")
                    if (length > 0) head.append("Content-Length: $length\r\n")
                }
                head.append("Content-Type: ${entry.mime}\r\nAccept-Ranges: bytes\r\nConnection: close\r\n\r\n")
                out.write(head.toString().toByteArray())
                if (method.equals("HEAD", true)) {
                    out.flush()
                    conn.disconnect()
                    return
                }
                try {
                    conn.inputStream.use { upstream ->
                        val source = BufferedInputStream(upstream, 256 * 1024)
                        val sink = Skipping(out, start)
                        when (entry.kind) {
                            Kind.CENC -> Cenc(entry.key).also { it.spoolDir = spoolDir }.transform(source, sink)
                            Kind.DEEZER -> DeezerCrypt.transform(source, sink, entry.key)
                            Kind.TEXT, Kind.JOIN -> Unit
                        }
                    }
                } catch (e: java.net.SocketException) {
                    // The player closed the connection (seek, stop); nothing to report.
                } catch (e: Throwable) {
                    log("decrypting stream failed: ${e.javaClass.simpleName}: ${e.message}")
                } finally {
                    runCatching { out.flush() }
                    conn.disconnect()
                }
            } catch (e: java.net.SocketException) {
                // client went away
            } catch (e: Throwable) {
                log("local stream error: ${e.message}")
            }
        }
    }

    /** Init + media segments one after another; the length is unknown, so the reply ends with the connection. */
    private fun serveJoined(entry: Entry, method: String, start: Long, out: OutputStream) {
        out.write("HTTP/1.1 200 OK\r\nContent-Type: ${entry.mime}\r\nConnection: close\r\n\r\n".toByteArray())
        if (method.equals("HEAD", true)) { out.flush(); return }
        val sink = Skipping(out, start)
        try {
            for ((i, part) in entry.parts.withIndex()) {
                var done = false
                var attempt = 0
                while (!done) {
                    val conn = (URL(part).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 20_000
                        readTimeout = 60_000
                        for ((k, v) in entry.headers) setRequestProperty(k, v)
                    }
                    try {
                        val code = conn.responseCode
                        if (code !in 200..299) {
                            if (++attempt < 3) continue
                            log("segment ${i + 1}/${entry.parts.size} HTTP $code")
                            return
                        }
                        conn.inputStream.use { it.copyTo(sink, 64 * 1024) }
                        done = true
                    } finally {
                        conn.disconnect()
                    }
                }
            }
        } catch (e: java.net.SocketException) {
            // the player stopped reading
        } finally {
            runCatching { out.flush() }
        }
    }

    /** Drops the first [skip] bytes written, so a range request starts at the asked offset. */
    private class Skipping(out: OutputStream, private var skip: Long) : FilterOutputStream(out) {
        override fun write(b: Int) {
            if (skip > 0) { skip--; return }
            out.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (skip >= len) { skip -= len; return }
            val drop = skip.toInt()
            skip = 0
            out.write(b, off + drop, len - drop)
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return sb.toString()
        }
    }
}
