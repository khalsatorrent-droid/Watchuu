package com.watchuu.musicbox.core

import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.security.MessageDigest

/**
 * Deezer's CDN scrambling, ported from Mornye-Extension sources/deezer (decryptDownloadedFile ->
 * file.transformPatternedBlocks in Mornye's rust_backend file_transform.rs):
 *
 *  - the stream is cut into 2048-byte segments
 *  - every third segment (index % 3 == 0) that is a full 2048 bytes is Blowfish-CBC decrypted,
 *    the IV restarting at 00 01 02 03 04 05 06 07 for each segment
 *  - the key is generateBlowfishKeyHex(trackId): md5hex(id)[i] ^ md5hex(id)[i + 16] ^ secret[i]
 *
 * Blowfish is not guaranteed on every Android build, so a small implementation is included; its
 * tables are the hexadecimal digits of pi, computed once instead of being pasted in.
 */
object DeezerCrypt {
    private const val SECRET = "g4el58wc0zvf9na1"
    private const val SEGMENT = 2048
    private val IV = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)

    fun keyFor(trackId: String): ByteArray {
        val md5 = MessageDigest.getInstance("MD5").digest(trackId.trim().toByteArray(Charsets.UTF_8))
        val hex = md5.joinToString("") { "%02x".format(it) }
        return ByteArray(16) { i -> (hex[i].code xor hex[i + 16].code xor SECRET[i].code).toByte() }
    }

    /** The song id the media file is keyed with (mediaSongIDFromURL). */
    fun mediaSongId(url: String): String? =
        Regex("""/media/(?:\d+/){1,6}(\d{3,})/[0-9a-f]{16,}\.[a-z0-9]+(?:[?#]|$)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)

    fun transform(input: InputStream, output: OutputStream, key: ByteArray) {
        val cipher = Blowfish(key)
        val segment = ByteArray(SEGMENT)
        var index = 0L
        while (true) {
            var n = 0
            while (n < SEGMENT) {
                val r = input.read(segment, n, SEGMENT - n)
                if (r < 0) break
                n += r
            }
            if (n == 0) break
            if (index % 3 == 0L && n == SEGMENT) cipher.decryptCbc(segment, 0, SEGMENT, IV)
            output.write(segment, 0, n)
            index++
            if (n < SEGMENT) break
        }
        output.flush()
    }

    // ------------------------------------------------------------------ Blowfish

    class Blowfish(key: ByteArray) {
        private val p = IntArray(18)
        private val s = Array(4) { IntArray(256) }

        init {
            val digits = piWords()
            for (i in 0 until 18) p[i] = digits[i]
            for (box in 0 until 4) for (i in 0 until 256) s[box][i] = digits[18 + box * 256 + i]
            var k = 0
            for (i in 0 until 18) {
                var word = 0
                repeat(4) { word = (word shl 8) or (key[k].toInt() and 0xff); k = (k + 1) % key.size }
                p[i] = p[i] xor word
            }
            val lr = IntArray(2)
            var i = 0
            while (i < 18) { encrypt(lr); p[i] = lr[0]; p[i + 1] = lr[1]; i += 2 }
            for (box in 0 until 4) {
                var j = 0
                while (j < 256) { encrypt(lr); s[box][j] = lr[0]; s[box][j + 1] = lr[1]; j += 2 }
            }
        }

        private fun f(x: Int): Int {
            val a = s[0][x ushr 24]
            val b = s[1][(x ushr 16) and 0xff]
            val c = s[2][(x ushr 8) and 0xff]
            val d = s[3][x and 0xff]
            return ((a + b) xor c) + d
        }

        private fun encrypt(lr: IntArray) {
            var l = lr[0]
            var r = lr[1]
            for (i in 0 until 16) {
                l = l xor p[i]
                r = r xor f(l)
                val t = l; l = r; r = t
            }
            val t = l; l = r; r = t
            r = r xor p[16]
            l = l xor p[17]
            lr[0] = l; lr[1] = r
        }

        private fun decrypt(lr: IntArray) {
            var l = lr[0]
            var r = lr[1]
            for (i in 17 downTo 2) {
                l = l xor p[i]
                r = r xor f(l)
                val t = l; l = r; r = t
            }
            val t = l; l = r; r = t
            r = r xor p[1]
            l = l xor p[0]
            lr[0] = l; lr[1] = r
        }

        fun decryptCbc(data: ByteArray, off: Int, len: Int, iv: ByteArray) {
            var prevL = word(iv, 0)
            var prevR = word(iv, 4)
            val lr = IntArray(2)
            var i = off
            while (i + 8 <= off + len) {
                val cl = word(data, i)
                val cr = word(data, i + 4)
                lr[0] = cl; lr[1] = cr
                decrypt(lr)
                put(data, i, lr[0] xor prevL)
                put(data, i + 4, lr[1] xor prevR)
                prevL = cl; prevR = cr
                i += 8
            }
        }

        private fun word(b: ByteArray, o: Int): Int =
            ((b[o].toInt() and 0xff) shl 24) or ((b[o + 1].toInt() and 0xff) shl 16) or
                ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff)

        private fun put(b: ByteArray, o: Int, v: Int) {
            b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte()
            b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte()
        }
    }

    @Volatile private var piCache: IntArray? = null

    /** The first 1042 32-bit words of pi's fractional part: Blowfish's P-array and S-boxes. */
    private fun piWords(): IntArray {
        piCache?.let { return it }
        val words = 18 + 4 * 256
        val bits = words * 32 + 64
        // Machin: pi = 16 atan(1/5) - 4 atan(1/239), in fixed point scaled by 2^bits.
        fun atanInv(x: Int): BigInteger {
            val one = BigInteger.ONE.shiftLeft(bits)
            val x2 = BigInteger.valueOf(x.toLong() * x)
            var term = one.divide(BigInteger.valueOf(x.toLong()))
            var sum = term
            var n = 1L
            var sign = -1
            while (term.signum() != 0) {
                term = term.divide(x2)
                val add = term.divide(BigInteger.valueOf(2 * n + 1))
                sum = if (sign < 0) sum.subtract(add) else sum.add(add)
                sign = -sign
                n++
            }
            return sum
        }
        val pi = atanInv(5).shiftLeft(4).subtract(atanInv(239).shiftLeft(2))
        val fraction = pi.subtract(BigInteger.valueOf(3).shiftLeft(bits))
        val out = IntArray(words)
        for (i in 0 until words) {
            out[i] = fraction.shiftRight(bits - (i + 1) * 32).toInt()
        }
        piCache = out
        return out
    }
}
