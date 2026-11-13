package com.watchuu.musicbox

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Deezer media is Blowfish-CBC encrypted (every third 2048-byte block). The ZARZ resolver hands back
 * the encrypted CDN URL, so playback goes through this loopback server that decrypts on the fly and
 * supports Range requests so seeking works.
 */
object DeezerProxy {
    private const val CHUNK = 2048
    private val iv = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "musicbox-proxy").apply { isDaemon = true } }

    @Volatile private var server: ServerSocket? = null

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

    /** Local URL that serves the decrypted audio of [upstream] (key derived from Deezer song id [songId]). */
    fun url(upstream: String, songId: String): String =
        "http://127.0.0.1:${port()}/d?u=${URLEncoder.encode(upstream, "UTF-8")}&id=${URLEncoder.encode(songId, "UTF-8")}"

    /** The Deezer song id is part of the CDN media path; the key belongs to that entry. */
    fun songIdFromUrl(url: String): String =
        Regex("""/media/(?:\d+/){1,6}(\d{3,})/[0-9a-f]{16,}\.[a-z0-9]+(?:[?#]|$)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1).orEmpty()

    private fun key(songId: String): SecretKeySpec {
        val md5 = java.security.MessageDigest.getInstance("MD5").digest(songId.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val secret = "g4el58wc0zvf9na1"
        return SecretKeySpec(ByteArray(16) { (md5[it].code xor md5[it + 16].code xor secret[it].code).toByte() }, "Blowfish")
    }

    private fun handle(sock: Socket) {
        sock.use { s ->
            val out = s.getOutputStream()
            try {
                s.soTimeout = 30_000
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
                val params = target.substringAfter("?", "").split("&").associate {
                    it.substringBefore("=") to URLDecoder.decode(it.substringAfter("=", ""), "UTF-8")
                }
                val upstream = params["u"].orEmpty()
                val songId = params["id"].orEmpty()
                if (upstream.isBlank() || songId.isBlank()) return fail(out, 400)
                val range = lines.firstOrNull { it.startsWith("range:", true) }?.substringAfter(":")?.trim()
                serve(out, upstream, songId, range)
            } catch (_: Throwable) {
                try { fail(out, 502) } catch (_: Throwable) {}
            }
        }
    }

    private fun fail(o: OutputStream, code: Int) {
        o.write("HTTP/1.1 $code Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        o.flush()
    }

    private fun serve(out: OutputStream, upstream: String, songId: String, rangeHeader: String?) {
        val start = rangeHeader?.let { Regex("""bytes=(\d+)-""").find(it)?.groupValues?.get(1)?.toLongOrNull() } ?: 0L
        val aligned = start / CHUNK * CHUNK
        val c = URL(upstream).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36")
        if (aligned > 0) c.setRequestProperty("Range", "bytes=$aligned-")
        val code = c.responseCode
        if (code !in 200..299) return fail(out, code)

        val total: Long = if (code == 206) {
            c.getHeaderField("Content-Range")?.substringAfter("/")?.toLongOrNull() ?: -1L
        } else c.contentLengthLong.takeIf { it >= 0 } ?: -1L
        val skip = start - aligned
        val partial = start > 0 && total > 0
        val head = StringBuilder()
        if (partial) {
            head.append("HTTP/1.1 206 Partial Content\r\n")
            head.append("Content-Range: bytes $start-${total - 1}/$total\r\nContent-Length: ${total - start}\r\n")
        } else {
            head.append("HTTP/1.1 200 OK\r\n")
            if (total > 0) head.append("Content-Length: $total\r\n")
        }
        head.append("Content-Type: application/octet-stream\r\nAccept-Ranges: bytes\r\nConnection: close\r\n\r\n")
        out.write(head.toString().toByteArray())

        val spec = key(songId)
        val cipher = Cipher.getInstance("Blowfish/CBC/NoPadding")
        var index = aligned / CHUNK
        var toSkip = skip
        val buf = ByteArray(CHUNK)
        c.inputStream.use { ins ->
            while (true) {
                val n = readFully(ins, buf)
                if (n <= 0) break
                var data = buf
                var len = n
                if (n == CHUNK && index % 3 == 0L) {
                    cipher.init(Cipher.DECRYPT_MODE, spec, IvParameterSpec(iv))
                    data = cipher.doFinal(buf, 0, CHUNK)
                }
                var off = 0
                if (toSkip > 0) {
                    val s = minOf(toSkip, len.toLong()).toInt()
                    off = s; toSkip -= s
                }
                if (len - off > 0) out.write(data, off, len - off)
                index++
                if (n < CHUNK) break
            }
        }
        out.flush()
    }

    private fun readFully(i: InputStream, b: ByteArray): Int {
        var read = 0
        while (read < b.size) {
            val n = i.read(b, read, b.size - read)
            if (n < 0) break
            read += n
        }
        return read
    }
}
