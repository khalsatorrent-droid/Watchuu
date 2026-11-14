package com.watchuu.musicbox

import com.lagradost.cloudstream3.CloudStreamApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Amazon's resolver returns a CENC-encrypted MP4 plus a key. The file is downloaded, decrypted
 * ([CencDecryptor]) into the app cache, then served from a loopback server with Range support, so the
 * player gets a plain FLAC-in-MP4 stream it can seek in.
 */
object AmazonProxy {
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "musicbox-amz").apply { isDaemon = true } }
    @Volatile private var server: ServerSocket? = null
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    private fun dir(): File {
        val base = CloudStreamApp.context?.cacheDir ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp")
        return File(base, "musicbox_amz").apply { mkdirs() }
    }

    @Synchronized
    private fun port(): Int {
        server?.let { if (!it.isClosed) return it.localPort }
        val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        server = s
        pool.execute {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: Throwable) { break }
                pool.execute { handle(c) }
            }
        }
        return s.localPort
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun cleanup(keep: File) {
        val files = dir().listFiles() ?: return
        val now = System.currentTimeMillis()
        files.filter { it != keep && now - it.lastModified() > 6 * 3600_000L }.forEach { it.delete() }
        files.filter { (it.name.endsWith(".m4a") || it.name.endsWith(".flac")) && it != keep }.sortedByDescending { it.lastModified() }.drop(6).forEach { it.delete() }
    }

    /**
     * Downloads + decrypts [upstream] (when [keyHex] is not blank) and returns a local playable URL.
     * Runs before the link is handed to the player so playback starts instantly.
     */
    suspend fun prepare(upstream: String, keyHex: String): String = withContext(Dispatchers.IO) {
        val base = sha1(upstream + keyHex)
        val lock = locks.getOrPut(base) { Any() }
        val name = synchronized(lock) {
            var out = File(dir(), "$base.flac")
            if (!out.exists() || out.length() == 0L) out = File(dir(), "$base.m4a")
            if (!out.exists() || out.length() == 0L) {
                val part = File(dir(), "$base.part")
                val c = URL(upstream).openConnection() as HttpURLConnection
                c.connectTimeout = 20_000
                c.readTimeout = 60_000
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/152.0.0.0 Safari/537.36")
                if (c.responseCode !in 200..299) throw Exception("Amazon CDN HTTP ${c.responseCode}")
                c.inputStream.use { i -> part.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
                if (keyHex.isNotBlank()) {
                    try {
                        CencDecryptor.decrypt(part, keyHex)
                    } catch (e: Exception) {
                        part.delete()
                        throw Exception("Amazon decrypt failed: ${e.message}")
                    }
                }
                // FLAC-in-MP4 plays unreliably; a native .flac stream is what players handle best
                val native = FlacRemux.convert(part)
                out = File(dir(), base + if (native) ".flac" else ".m4a")
                MusicBoxLog.d("AmazonProxy", "ready: ${part.length()} bytes, native flac=$native")
                if (!part.renameTo(out)) { part.copyTo(out, true); part.delete() }
                cleanup(out)
            }
            out.setLastModified(System.currentTimeMillis())
            out.name
        }
        return@withContext "http://127.0.0.1:${port()}/a?f=${URLEncoder.encode(name, "UTF-8")}"
    }

    private fun fail(o: OutputStream, code: Int) {
        o.write("HTTP/1.1 $code Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        o.flush()
    }

    private fun handle(sock: Socket) {
        sock.use { s ->
            val out = s.getOutputStream()
            try {
                s.soTimeout = 60_000
                val input = BufferedInputStream(s.getInputStream())
                val lines = ArrayList<String>()
                val sb = StringBuilder()
                while (true) {
                    val b = input.read()
                    if (b < 0) break
                    if (b == '\n'.code) {
                        val l = sb.toString().trimEnd('\r'); sb.setLength(0)
                        if (l.isEmpty()) break
                        lines.add(l)
                    } else sb.append(b.toChar())
                }
                val target = lines.firstOrNull()?.split(" ")?.getOrNull(1) ?: return
                val name = URLDecoder.decode(target.substringAfter("f=", "").substringBefore("&"), "UTF-8")
                if (name.isBlank() || name.contains("/") || name.contains("..")) return fail(out, 400)
                val file = File(dir(), name)
                if (!file.exists()) return fail(out, 404)
                val total = file.length()
                val range = lines.firstOrNull { it.startsWith("range:", true) }?.substringAfter(":")?.trim()
                val m = range?.let { Regex("""bytes=(\d*)-(\d*)""").find(it) }
                var start = m?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                var end = m?.groupValues?.get(2)?.toLongOrNull() ?: (total - 1)
                if (m != null && m.groupValues[1].isEmpty()) { // suffix range
                    start = maxOf(0, total - (m.groupValues[2].toLongOrNull() ?: 0)); end = total - 1
                }
                end = minOf(end, total - 1)
                if (start > end) return fail(out, 416)
                val partial = m != null
                val head = StringBuilder(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                if (partial) head.append("Content-Range: bytes $start-$end/$total\r\n")
                head.append("Content-Length: ${end - start + 1}\r\nContent-Type: ${if (name.endsWith(".flac")) "audio/flac" else "audio/mp4"}\r\nAccept-Ranges: bytes\r\nConnection: close\r\n\r\n")
                out.write(head.toString().toByteArray())
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(start)
                    var left = end - start + 1
                    val buf = ByteArray(64 * 1024)
                    while (left > 0) {
                        val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) break
                        out.write(buf, 0, n)
                        left -= n
                    }
                }
                out.flush()
            } catch (_: Throwable) {
                try { fail(out, 502) } catch (_: Throwable) {}
            }
        }
    }
}
