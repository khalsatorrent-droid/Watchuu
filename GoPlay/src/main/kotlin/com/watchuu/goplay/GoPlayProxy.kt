package com.watchuu.goplay

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
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

/**
 * GoPlay serves Viki's DASH manifest, and every track in it carries ContentProtection:
 *
 *   <AdaptationSet contentType="audio" ...>   5 × ContentProtection
 *   <AdaptationSet contentType="video" ...>   5 × ContentProtection
 *
 * ExoPlayer refuses a manifest like that - it expects to acquire a licence, and there is no
 * licence server anywhere in the capture. The site's own player copes because Shaka is handed the
 * manifest with that block ignored, and the segments themselves come back 206 in the clear.
 *
 * So this little localhost server sits in between, the same trick KiligProxy uses in this repo:
 *
 *   /mpd?u=<upstream>   fetch the manifest, drop every ContentProtection element, point each
 *                       BaseURL back at /seg, and hand the player the rewritten one
 *   /seg?u=<upstream>   forward the segment request upstream with the site's Referer and cookies,
 *                       passing Range straight through and returning whatever status comes back
 *
 * If a title is genuinely encrypted, stripping the declaration will not decrypt anything and it
 * still will not play - but nothing is lost by trying, and the clear case works.
 */
object GoPlayProxy {
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "goplay-proxy").apply { isDaemon = true } }

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    var port: Int = 0
        private set

    /** Upstream headers the site expects, applied to manifest and segment requests alike. */
    @Volatile
    private var referer: String = "https://goplay.su/"

    @Volatile
    private var cookie: String? = null

    @Synchronized
    fun ensureStarted(): Int {
        server?.let { if (!it.isClosed) return port }
        val s = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        server = s
        port = s.localPort
        pool.execute {
            while (!s.isClosed) {
                val socket = runCatching { s.accept() }.getOrNull() ?: break
                pool.execute { runCatching { handle(socket) } }
            }
        }
        return port
    }

    fun setHeaders(refererUrl: String, cookies: String?) {
        referer = refererUrl
        cookie = cookies
    }

    fun proxiedMpd(upstream: String): String {
        val p = ensureStarted()
        // the suffix matters: ExoPlayer decides which media source to build from the uri's
        // extension, so a bare "/mpd?u=..." is not recognised as DASH at all and is handed to the
        // progressive parser, which then fails. "/stream.mpd" is what makes it a DASH stream.
        return "http://127.0.0.1:$p/stream.mpd?u=" + URLEncoder.encode(upstream, "UTF-8")
    }

    // ── server ─────────────────────────────────────────────────────────────────

    private fun handle(socket: Socket) {
        socket.use { s ->
            val input = BufferedInputStream(s.getInputStream())
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return

            var rangeHeader: String? = null
            var contentLength = 0
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                val name = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                when {
                    name.equals("Range", true) -> rangeHeader = value
                    name.equals("Content-Length", true) -> contentLength = value.toIntOrNull() ?: 0
                }
            }

            val path = parts[1]
            val query = path.substringAfter('?', "")
            val upstream = query.split('&')
                .firstOrNull { it.startsWith("u=") }
                ?.substring(2)
                ?.let { URLDecoder.decode(it, "UTF-8") }

            val out = s.getOutputStream()
            if (upstream == null) {
                writeText(out, 400, "text/plain", "missing u")
                return
            }

            if (path.startsWith("/stream.mpd") || path.startsWith("/mpd")) {
                val body = fetch(upstream, null)
                if (body == null) { writeText(out, 502, "text/plain", "manifest unavailable"); return }
                val rewritten = rewriteManifest(String(body, Charsets.UTF_8))
                writeText(out, 200, "application/dash+xml", rewritten)
            } else {
                proxySegment(out, upstream, rangeHeader)
            }
        }
    }

    private fun rewriteManifest(xml: String): String {
        var out = xml
        // drop the protection declarations entirely - this is the part ExoPlayer chokes on
        out = out.replace(Regex("""<ContentProtection[^>]*/>"""), "")
        out = out.replace(Regex("""<ContentProtection[^>]*>.*?</ContentProtection>""", RegexOption.DOT_MATCHES_ALL), "")
        // point every BaseURL at this server so segments carry the site's headers too
        out = out.replace(Regex("""<BaseURL>([^<]+)</BaseURL>""")) { m ->
            val target = m.groupValues[1].trim()
            "<BaseURL>http://127.0.0.1:$port/seg?u=${URLEncoder.encode(target, "UTF-8")}</BaseURL>"
        }
        return out
    }

    private fun proxySegment(out: OutputStream, upstream: String, range: String?) {
        val conn = open(upstream)
        range?.let { conn.setRequestProperty("Range", it) }
        val code = runCatching { conn.responseCode }.getOrDefault(502)
        val body: InputStream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)

        val headers = StringBuilder()
        headers.append("HTTP/1.1 ").append(code).append(' ').append(if (code == 206) "Partial Content" else "OK").append("\r\n")
        conn.getHeaderField("Content-Type")?.let { headers.append("Content-Type: ").append(it).append("\r\n") }
        conn.getHeaderField("Content-Range")?.let { headers.append("Content-Range: ").append(it).append("\r\n") }
        conn.getHeaderField("Content-Length")?.let { headers.append("Content-Length: ").append(it).append("\r\n") }
        headers.append("Accept-Ranges: bytes\r\n")
        headers.append("Connection: close\r\n\r\n")
        out.write(headers.toString().toByteArray(Charsets.US_ASCII))
        out.flush()
        body.use { it.copyTo(out, 64 * 1024) }
        out.flush()
        conn.disconnect()
    }

    private fun open(upstream: String): HttpURLConnection {
        val conn = URL(upstream).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Referer", referer)
        conn.setRequestProperty("Origin", "https://goplay.su")
        cookie?.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("Cookie", it) }
        return conn
    }

    private fun fetch(upstream: String, range: String?): ByteArray? = runCatching {
        val conn = open(upstream)
        range?.let { conn.setRequestProperty("Range", it) }
        conn.inputStream.use { input ->
            val buf = ByteArrayOutputStream()
            input.copyTo(buf, 64 * 1024)
            buf.toByteArray()
        }.also { conn.disconnect() }
    }.getOrNull()

    private fun writeText(out: OutputStream, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 8192) return sb.toString()
        }
    }

    private const val UA = "Mozilla/5.0 (Linux; Android 16; SM-S911B Build/BP4A.251205.006) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.47 Mobile Safari/537.36"
}
