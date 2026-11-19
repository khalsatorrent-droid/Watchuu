package com.watchuu.atlantic

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Aphrodite, the one Atlantic source that does not use the native library (plugin v11 onwards).
 * It talks to cdn.hls.lol directly: a proof-of-work token, an HMAC-sealed handshake that yields a
 * short-lived session, then requests signed with that session. Ported from the plugin's own code.
 */
object AtlanticAphrodite {
    private const val TAG = "AtlanticAphrodite"
    private const val ROOT = "https://cdn.hls.lol"
    private const val ORIGIN = "https://atlantic.bz"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
    private val MASKED = intArrayOf(
        23, 132, 176, 2, 135, 122, 178, 136, 173, 90, 181, 196, 37, 19, 127, 140,
        193, 217, 176, 43, 115, 149, 56, 171, 64, 245, 122, 128, 105, 227, 118, 146
    )

    private class Session(val sid: String, val key: ByteArray, val exp: Long)
    private class Reply(val code: Int, val body: String)

    private val rnd = SecureRandom()
    private val lock = Any()
    @Volatile private var session: Session? = null
    @Volatile private var clockOffset = 0L

    /** last thing that went wrong, for the settings screen */
    @Volatile var lastError: String = ""

    private fun bundleKey() = ByteArray(MASKED.size) { (MASKED[it] xor (((it * 11) + 55) and 0xff)).toByte() }
    private fun now() = System.currentTimeMillis() / 1000 + clockOffset
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun unhex(s: String) = ByteArray(s.length / 2) {
        ((Character.digit(s[it * 2], 16) shl 4) + Character.digit(s[it * 2 + 1], 16)).toByte()
    }

    private fun hmac(key: ByteArray, msg: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
            .doFinal(msg.toByteArray(Charsets.UTF_8))

    private fun request(method: String, url: String, body: String?, timeout: Int, extra: Map<String, String> = emptyMap()): Reply {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = timeout
            c.readTimeout = timeout
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Origin", ORIGIN)
            c.setRequestProperty("Referer", "$ORIGIN/")
            c.setRequestProperty("Accept", "application/json")
            extra.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            return Reply(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            c.disconnect()
        }
    }

    /** proof of work: find a nonce whose sha256(seed || nonce_le64) has `diff` leading zero bits */
    private fun solveWork(seedHex: String, diff: Int): Pair<Long, Long>? {
        val seed = unhex(seedHex)
        if (seed.size != 16) return null
        val buf = ByteArray(24)
        System.arraycopy(seed, 0, buf, 0, 16)
        val md = MessageDigest.getInstance("SHA-256")
        val start = System.currentTimeMillis()
        var n = 0L
        while (true) {
            var v = n
            for (i in 0 until 8) { buf[16 + i] = (v and 255).toByte(); v = v ushr 8 }
            val d = md.digest(buf)
            var bits = 0
            for (b in d) {
                val x = b.toInt() and 0xff
                if (x != 0) { bits += Integer.numberOfLeadingZeros(x) - 24; break }
                bits += 8
            }
            if (bits >= diff) return n to (System.currentTimeMillis() - start)
            n++
            if ((n and 0xffff) == 0L && System.currentTimeMillis() - start > 25_000) return null
        }
    }

    private fun solveToken(): String {
        val r = request("POST", "$ROOT/content/athenaxo/challenge", JSONObject().put("c", "a").toString(), 10_000)
        if (r.code != 200) return ""
        val j = JSONObject(r.body)
        val seed = j.optString("seed")
        val diff = j.optInt("diff", 20)
        val (nonce, ms) = solveWork(seed, diff) ?: return ""
        return JSONObject().put("seed", seed).put("diff", diff).put("mode", j.optInt("mode", 0))
            .put("exp", j.optLong("exp")).put("tok", j.optString("tok"))
            .put("nonce", nonce.toString()).put("ms", ms).toString()
    }

    private fun handshake(attempt: Int): Session? {
        val key = bundleKey()
        val token = try { solveToken() } catch (e: Exception) { Log.w(TAG, "challenge failed: ${e.message}"); "" }
        val ts = now()
        val nb = ByteArray(8).also { rnd.nextBytes(it) }
        val nonce = hex(nb)
        val body = JSONObject().put("c", "a").put("ts", ts).put("n", nonce)
            .put("s", hex(hmac(key, "v2:a:$ts:$nonce"))).put("t", token).put("e", "").toString()
        val r = request("POST", "$ROOT/content/index", body, 10_000)
        if (r.code == 403) {
            val j = runCatching { JSONObject(r.body) }.getOrNull()
            val code = j?.optString("code").orEmpty()
            val srv = j?.optLong("now", 0L) ?: 0L
            if (code == "ts" && srv > 0 && attempt < 1) {
                clockOffset = srv - System.currentTimeMillis() / 1000
                return handshake(attempt + 1)
            }
            lastError = "handshake refused: ${code.ifBlank { r.body.take(80) }}"
            return null
        }
        if (r.code != 200) { lastError = "handshake http ${r.code}"; return null }
        val d = JSONObject(r.body).optString("d")
        if (d.isEmpty()) { lastError = "handshake empty"; return null }
        val raw = unhex(d)
        if (raw.size < 13) { lastError = "handshake short"; return null }
        val k = hmac(key, "aphrodite-seal-v2|$ts|$nonce")
        val plain = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, raw, 0, 12))
        }.doFinal(raw, 12, raw.size - 12)
        val j = JSONObject(String(plain, Charsets.UTF_8))
        val srv = j.optLong("now", 0L)
        if (srv > 0) clockOffset = srv - System.currentTimeMillis() / 1000
        return Session(j.getString("sid"), unhex(j.getString("skey")), j.getLong("exp"))
    }

    private fun ensure(force: Boolean): Session? = synchronized(lock) {
        val s = session
        if (!force && s != null && s.exp - now() > 30) return s
        val n = try { handshake(0) } catch (e: Exception) {
            lastError = "handshake failed: ${e.javaClass.simpleName} ${e.message}"
            Log.w(TAG, lastError); null
        }
        session = n
        n
    }

    private fun signed(s: Session, path: String): Map<String, String> {
        val ts = now()
        val nonce = hex(ByteArray(8).also { rnd.nextBytes(it) })
        return mapOf(
            "X-Az-Id" to s.sid, "X-Az-Ts" to ts.toString(), "X-Az-Nonce" to nonce,
            "X-Az-Tag" to hex(hmac(s.key, "v2:${s.sid}:$path:$ts:$nonce")),
        )
    }

    private fun fetch(path: String): JSONObject? {
        for (i in 0 until 2) {
            val s = ensure(i > 0) ?: return null
            val r = request("GET", ROOT + path, null, 8_000, signed(s, path))
            val j = runCatching { JSONObject(r.body) }.getOrNull() ?: run {
                lastError = "content http ${r.code}"; return null
            }
            if (j.optBoolean("renew", false)) { synchronized(lock) { session = null }; continue }
            if (r.code != 200) { lastError = "content http ${r.code}"; return null }
            return j
        }
        return null
    }

    /** returns the same JSON shape the native sources do, or null when nothing was found */
    fun resolve(r: AtlanticNative.Request): String? {
        return try {
            val path = if (r.isShow) {
                "/content/tv/${r.tmdbId}/${r.season.ifBlank { "1" }}/${r.ep.ifBlank { "1" }}"
            } else "/content/movie/${r.tmdbId}"
            val j = fetch(path) ?: return null
            if (!j.optBoolean("found", false)) return null
            var url = j.optString("hls")
            if (url.isBlank() && j.optString("type") == "hls") url = j.optString("url")
            if (url.isBlank()) return null
            val caps = JSONArray()
            j.optJSONArray("subtitles")?.let { a ->
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val u = o.optString("url"); val l = o.optString("language")
                    if (u.isNotBlank() && l.isNotBlank()) caps.put(JSONObject().put("url", u).put("language", l.lowercase()))
                }
            }
            JSONObject().put("streamUrl", url).put("streamType", "hls").put("captions", caps)
                .put("headers", JSONObject().put("Origin", ORIGIN).put("Referer", "$ORIGIN/").put("User-Agent", UA))
                .toString()
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            Log.w(TAG, "resolve failed: $lastError")
            null
        }
    }
}
