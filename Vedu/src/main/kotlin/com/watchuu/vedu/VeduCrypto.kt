package com.watchuu.vedu

import android.util.Base64
import java.math.BigInteger
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Ports of the small public player ciphers used by the Vedu app's "Orbit" (67Movies) resolver.
 * Each function mirrors the JavaScript in assets/resolvers/67movies.js (v1.0.50).
 */
object VeduCrypto {

    // ------------------------------------------------------------------ base64

    fun b64UrlDecode(value: String): ByteArray {
        val normalized = value.trim().replace('-', '+').replace('_', '/')
        val padded = normalized.padEnd(4 * ((normalized.length + 3) / 4), '=')
        return Base64.decode(padded, Base64.DEFAULT)
    }

    fun b64UrlEncode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)

    private fun hexBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    // ------------------------------------------------------------------ VidRock

    private const val VIDROCK_KEY = "7f3e9c2a8b5d1f4e6a9c3b7d2e5f8a1c4b6d9e2f5a8c1b4d7e9f2a5c8b1d4e7f"

    /** AES-256-GCM, iv = first 12 bytes, rest = ciphertext||tag. */
    fun vidrockDecrypt(encrypted: String): String? = try {
        val bytes = b64UrlDecode(encrypted)
        if (bytes.size <= 28) null else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(hexBytes(VIDROCK_KEY), "AES"),
                GCMParameterSpec(128, bytes.copyOfRange(0, 12))
            )
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        }
    } catch (_: Exception) {
        null
    }

    // ------------------------------------------------------------------ Vidnest

    private const val STD_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="
    private const val VIDNEST_ALPHABET = "RB0fpH8ZEyVLkv7c2i6MAJ5u3IKFDxlS1NTsnGaqmXYdUrtzjwObCgQP94hoeW+/="

    fun vidnestDecode(data: String): String {
        val mapped = buildString(data.length) {
            for (c in data) {
                val i = VIDNEST_ALPHABET.indexOf(c)
                if (i < 0) throw IllegalArgumentException("vidnest_alphabet")
                append(STD_ALPHABET[i])
            }
        }
        return String(Base64.decode(mapped, Base64.DEFAULT), Charsets.UTF_8)
    }

    // ------------------------------------------------------------------ Videasy

    private val VIDEASY_WORDS = intArrayOf(
        1116352408, 1899447441, -1245643825, -373957723,
        961987163, 1508970993, -1841331548, -1424204075,
        -670586216, 310598401, 607225278, 1426881987,
        1925078388, -2132889090, -1680079193, -1046744716,
    )
    private const val GOLDEN = -1640531527 // 2654435769

    private fun mixWord(value: Int): Int {
        var w = value
        w = w xor (w ushr 16)
        w *= -2048144789 // 2246822507
        w = w xor (w ushr 13)
        w *= -1028477387 // 3266489909
        return w xor (w ushr 16)
    }

    private fun rotl(value: Int, count: Int): Int = Integer.rotateLeft(value, count and 31)

    private fun fnvWord(value: String): Int {
        var w = -2128831035 // 2166136261
        for (ch in value) w = (w xor ch.code) * 16777619
        return mixWord(w)
    }

    private fun mod61(value: Int): Int = ((value.toLong() and 0xffffffffL) % 61L).toInt()

    private class VideasyState(val state: Array<Int?>, var acc: Int)

    private fun videasyState(key: String, seed: Int): VideasyState {
        val state = arrayOfNulls<Int>(61)
        var acc = mixWord(fnvWord(key) xor mixWord(seed xor GOLDEN))
        for (index in 0 until 8) {
            if ((index * (index + 1)) and 1 == 0) {
                val slot = mod61(acc)
                acc = rotl(acc + GOLDEN, 7 + (7 and index))
                state[slot] = acc xor mixWord(acc)
                acc = mixWord(acc + slot)
            } else {
                state[index] = VIDEASY_WORDS[index and 15]
            }
        }
        return VideasyState(state, mixWord(-1515870811 xor acc)) // 2779096485
    }

    private fun videasyNext(v: VideasyState, index: Int): Int {
        val slot = mod61(v.acc)
        val existsMask = if (v.state[slot] != null) -1 else 0
        val existing = v.state[slot] ?: 0
        val mixed = existing xor (GOLDEN * (index + 1))
        var word = (v.acc xor mixed) or (v.acc and mixed and existsMask)
        word = rotl(word + v.acc, slot and 31) xor rotl(v.acc, (slot * 7) and 31)
        v.acc = mixWord(word + GOLDEN)
        v.state[slot] = v.acc
        return v.acc
    }

    /** decryptVideasyPayload(body, seedString, Number(tmdbId)) */
    fun videasyDecrypt(encoded: String, key: String, seed: Int): String? {
        val encrypted = try { b64UrlDecode(encoded) } catch (_: Exception) { return null }
        val st = videasyState(key, seed)
        val out = ByteArray(encrypted.size)
        var i = 0
        var wordIndex = 0
        while (i < encrypted.size) {
            val word = videasyNext(st, wordIndex++)
            for (shift in intArrayOf(0, 8, 16, 24)) {
                if (i >= encrypted.size) break
                out[i] = (encrypted[i].toInt() xor (word ushr shift)).toByte()
                i++
            }
        }
        val magic = byteArrayOf(109, 118, 109, 49) // "mvm1"
        if (out.size < 4 || (0 until 4).any { out[it] != magic[it] }) return null
        return String(out, 4, out.size - 4, Charsets.UTF_8)
    }

    // ------------------------------------------------------------------ VidLink

    private val VIDLINK_KEY = intArrayOf(
        199, 81, 54, 197, 102, 139, 191, 230, 90, 126, 202, 212, 49, 167, 69, 219,
        104, 181, 243, 129, 85, 91, 56, 216, 246, 198, 153, 68, 156, 241, 31, 205,
    ).map { it.toByte() }.toByteArray()

    private fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 255) or ((b[off + 1].toInt() and 255) shl 8) or
            ((b[off + 2].toInt() and 255) shl 16) or ((b[off + 3].toInt() and 255) shl 24)

    private fun putLeInt(b: ByteArray, off: Int, v: Int) {
        b[off] = v.toByte(); b[off + 1] = (v ushr 8).toByte()
        b[off + 2] = (v ushr 16).toByte(); b[off + 3] = (v ushr 24).toByte()
    }

    private fun qr(w: IntArray, a: Int, b: Int, c: Int, d: Int) {
        w[b] = w[b] xor rotl(w[a] + w[d], 7)
        w[c] = w[c] xor rotl(w[b] + w[a], 9)
        w[d] = w[d] xor rotl(w[c] + w[b], 13)
        w[a] = w[a] xor rotl(w[d] + w[c], 18)
    }

    private fun salsaRounds(w: IntArray) {
        repeat(10) {
            qr(w, 0, 4, 8, 12); qr(w, 5, 9, 13, 1); qr(w, 10, 14, 2, 6); qr(w, 15, 3, 7, 11)
            qr(w, 0, 1, 2, 3); qr(w, 5, 6, 7, 4); qr(w, 10, 11, 8, 9); qr(w, 15, 12, 13, 14)
        }
    }

    private fun salsaState(key: ByteArray): IntArray {
        val w = IntArray(16)
        w[0] = 0x61707865; w[5] = 0x3320646e; w[10] = 0x79622d32; w[15] = 0x6b206574
        for (i in 0 until 8) w[if (i < 4) i + 1 else i + 7] = leInt(key, i * 4)
        return w
    }

    private fun vidlinkKeyStream(length: Int): ByteArray {
        val derived = salsaState(VIDLINK_KEY)
        salsaRounds(derived)
        val derivedKey = ByteArray(32)
        intArrayOf(0, 5, 10, 15, 6, 7, 8, 9).forEachIndexed { i, p -> putLeInt(derivedKey, i * 4, derived[p]) }
        val initial = salsaState(derivedKey)
        val out = ByteArray(length)
        var offset = 0
        while (offset < length) {
            initial[8] = offset / 64
            val w = initial.copyOf()
            salsaRounds(w)
            val block = ByteArray(64)
            for (i in 0 until 16) putLeInt(block, i * 4, w[i] + initial[i])
            System.arraycopy(block, 0, out, offset, minOf(64, length - offset))
            offset += 64
        }
        return out
    }

    private fun leBig(b: ByteArray, from: Int, to: Int): BigInteger {
        var r = BigInteger.ZERO
        for (i in to - 1 downTo from) r = r.shiftLeft(8).or(BigInteger.valueOf((b[i].toInt() and 255).toLong()))
        return r
    }

    private fun poly1305(message: ByteArray, key: ByteArray): ByteArray {
        val r = leBig(key, 0, 16).and(BigInteger("0ffffffc0ffffffc0ffffffc0fffffff", 16))
        val p = BigInteger.ONE.shiftLeft(130).subtract(BigInteger.valueOf(5))
        var acc = BigInteger.ZERO
        var off = 0
        while (off < message.size) {
            val end = minOf(off + 16, message.size)
            val n = leBig(message, off, end).add(BigInteger.ONE.shiftLeft((end - off) * 8))
            acc = acc.add(n).multiply(r).mod(p)
            off += 16
        }
        var tag = acc.add(leBig(key, 16, 32)).and(BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE))
        val out = ByteArray(16)
        val mask = BigInteger.valueOf(255)
        for (i in 0 until 16) {
            out[i] = tag.and(mask).toInt().toByte()
            tag = tag.shiftRight(8)
        }
        return out
    }

    fun vidlinkToken(identifier: String, timestampSeconds: Long = System.currentTimeMillis() / 1000 + 120): String {
        val idBytes = identifier.toByteArray(Charsets.UTF_8)
        val message = ByteArray(idBytes.size + 8)
        System.arraycopy(idBytes, 0, message, 0, idBytes.size)
        var ts = timestampSeconds
        for (i in message.size - 1 downTo idBytes.size) {
            message[i] = (ts and 255).toByte()
            ts = ts shr 8
        }
        val ks = vidlinkKeyStream(message.size + 32)
        val encrypted = ByteArray(message.size) { i -> (message[i].toInt() xor ks[i + 32].toInt()).toByte() }
        val tag = poly1305(encrypted, ks.copyOfRange(0, 32))
        val payload = ByteArray(24 + 16 + encrypted.size)
        System.arraycopy(tag, 0, payload, 24, 16)
        System.arraycopy(encrypted, 0, payload, 40, encrypted.size)
        return b64UrlEncode(payload)
    }
}
