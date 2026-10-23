package com.watchuu.kiligdrama

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ShortTV / ShortMax ("hls-enc") streams use normal HLS playlists, but every .ts segment under
 * /hls-encrypted/ is scrambled:
 *
 *   bytes 0..1023 : header. ascii digits at [16,20) = key offset A, at [20,24) = cipher length L,
 *                   the AES-128 key is header-text[A, A+16)
 *   bytes 1024..  : AES-CBC(iv = "shortmax00000000", PKCS7) of the first L bytes, then plain bytes
 *                   ("hls-encrypted-full" = everything after the header is ciphertext)
 *
 * The app's player can't do that by itself, so this tiny localhost server sits in between: it
 * rewrites playlists so every entry points back here, and unscrambles segments on the fly.
 * Same algorithm as the site's own ShortTvLoader.
 */
object KiligProxy {
    private val iv = IvParameterSpec("shortmax00000000".toByteArray(Charsets.US_ASCII))
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "kilig-proxy").apply { isDaemon = true } }

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    var userAgent: String = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private const val SITE = "https://kiligdrama.com"

    @Synchronized
    private fun port(): Int {
        server?.let { if (!it.isClosed) return it.localPort }
        val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        server = s
        pool.execute {
            while (!s.isClosed) {
                val c = try {
                    s.accept()
                } catch (_: Throwable) {
                    break
                }
                pool.execute { handle(c) }
            }
        }
        return s.localPort
    }

    private fun enc(u: String) = URLEncoder.encode(u, "UTF-8")

    /** Local playlist url for an (already proxy-wrapped) upstream playlist. */
    fun playlist(upstream: String): String = "http://127.0.0.1:${port()}/p.m3u8?u=${enc(upstream)}"

    private fun segment(upstream: String): String = "http://127.0.0.1:${port()}/s.ts?u=${enc(upstream)}"

    // ------------------------------------------------------------------ http plumbing

    private fun open(url: String, range: String?): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", userAgent)
        val host = url.substringAfter("://").substringBefore("/")
        // the site's proxies want to see kiligdrama.com, the origin CDNs want no referrer
        if (host.contains("dramadizilerim") || host.contains("b-cdn.net")) {
            c.setRequestProperty("Origin", SITE)
            c.setRequestProperty("Referer", "$SITE/")
        }
        if (range != null) c.setRequestProperty("Range", range)
        return c
    }

    private fun readAll(i: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = i.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun respond(o: OutputStream, code: Int, type: String, body: ByteArray) {
        val reason = if (code in 200..299) "OK" else "Error"
        val head = "HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            "Access-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n"
        o.write(head.toByteArray(Charsets.US_ASCII))
        o.write(body)
        o.flush()
    }

    private fun handle(sock: Socket) {
        sock.use { s ->
            try {
                s.soTimeout = 30_000
                val input = BufferedInputStream(s.getInputStream())
                val lines = ArrayList<String>()
                val sb = StringBuilder()
                while (true) {
                    val b = input.read()
                    if (b < 0) break
                    if (b == '\n'.code) {
                        val l = sb.toString().trimEnd('\r')
                        sb.setLength(0)
                        if (l.isEmpty()) break
                        lines.add(l)
                    } else sb.append(b.toChar())
                }
                val req = lines.firstOrNull() ?: return
                val target = req.split(" ").getOrNull(1) ?: return
                val upstream = target.substringAfter("u=", "").substringBefore("&").let { URLDecoder.decode(it, "UTF-8") }
                if (upstream.isBlank()) return respond(s.getOutputStream(), 400, "text/plain", ByteArray(0))
                val out = s.getOutputStream()
                if (target.startsWith("/p.m3u8")) servePlaylist(out, upstream) else serveSegment(out, upstream)
            } catch (_: Throwable) {
                try {
                    respond(s.getOutputStream(), 502, "text/plain", ByteArray(0))
                } catch (_: Throwable) {
                }
            }
        }
    }

    // ------------------------------------------------------------------ playlists

    private fun resolve(base: String, ref: String): String = try {
        URI(base).resolve(ref.trim()).toString()
    } catch (_: Throwable) {
        ref
    }

    private fun servePlaylist(out: OutputStream, upstream: String) {
        val c = open(upstream, null)
        val code = c.responseCode
        if (code !in 200..299) return respond(out, code, "text/plain", ByteArray(0))
        val text = String(readAll(c.inputStream), Charsets.UTF_8)
        val base = c.url.toString()
        val rewritten = text.lines().joinToString("\n") { raw ->
            val line = raw.trimEnd('\r')
            when {
                line.isBlank() -> line
                line.startsWith("#") -> Regex("""URI="([^"]+)"""").replace(line) { m ->
                    val abs = resolve(base, m.groupValues[1])
                    val local = if (abs.substringBefore("?").endsWith(".m3u8")) playlist(abs) else segment(abs)
                    "URI=\"$local\""
                }

                else -> {
                    val abs = resolve(base, line)
                    if (abs.substringBefore("?").endsWith(".m3u8")) playlist(abs) else segment(abs)
                }
            }
        }
        respond(out, 200, "application/vnd.apple.mpegurl", rewritten.toByteArray(Charsets.UTF_8))
    }

    // ------------------------------------------------------------------ segments

    private fun unscramble(data: ByteArray, full: Boolean): ByteArray {
        if (data.size < 1040) return data
        val head = String(data, 0, 1024, Charsets.ISO_8859_1)
        val keyOff = head.substring(16, 20).trim().toIntOrNull() ?: return data
        val len = head.substring(20, 24).trim().toIntOrNull() ?: return data
        val key = head.substring(keyOff, keyOff + 16).toByteArray(Charsets.ISO_8859_1)
        val cLen = if (full) data.size - 1024 else minOf(len, data.size - 1024)
        if (key.size != 16 || cLen <= 0 || cLen % 16 != 0) return data
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), iv)
        val plain = cipher.doFinal(data, 1024, cLen)
        val restStart = 1024 + cLen
        val res = ByteArray(plain.size + (data.size - restStart))
        System.arraycopy(plain, 0, res, 0, plain.size)
        System.arraycopy(data, restStart, res, plain.size, data.size - restStart)
        return res
    }

    private fun serveSegment(out: OutputStream, upstream: String) {
        val c = open(upstream, null)
        val code = c.responseCode
        if (code !in 200..299) return respond(out, code, "text/plain", ByteArray(0))
        var data = readAll(c.inputStream)
        val path = upstream.substringBefore("?")
        if (upstream.contains("hls-encrypted") && path.endsWith(".ts")) {
            data = try {
                unscramble(data, upstream.contains("hls-encrypted-full"))
            } catch (_: Throwable) {
                data
            }
        }
        val type = if (path.endsWith(".ts")) "video/mp2t" else (c.contentType ?: "application/octet-stream")
        respond(out, 200, type, data)
    }
}
