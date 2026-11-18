package com.watchuu.nonu

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * CENC AES-128-CTR decryptor for fragmented MP4 audio segments.
 * Adapted from MusicBox's CencDecryptor for in-memory byte-array processing.
 *
 * Usage:
 * 1. [parseTrackInfo] on the raw init segment (before patching) to get ivSize/KID
 * 2. For each downloaded fragment, [decryptFragment] with the 16-byte content key
 */
object CencDecrypt {

    data class TrackInfo(
        val ivSize: Int,
        val constIv: ByteArray?,
        val kid: ByteArray,
        val originalCodec: String
    )

    private data class SampleAux(val iv: ByteArray, val subs: List<Pair<Int, Long>>?)

    // ------------------------------------------------------------------ MP4 helpers

    private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF
    private fun u16(b: ByteArray, o: Int) = (u8(b, o) shl 8) or u8(b, o + 1)
    private fun u32(b: ByteArray, o: Int): Long =
        (u8(b, o).toLong() shl 24) or (u8(b, o + 1).toLong() shl 16) or
        (u8(b, o + 2).toLong() shl 8) or u8(b, o + 3).toLong()
    private fun u64(b: ByteArray, o: Int): Long = (u32(b, o) shl 32) or u32(b, o + 4)
    private fun s32(b: ByteArray, o: Int): Int = u32(b, o).toInt()
    private fun flags(b: ByteArray, o: Int) =
        (u8(b, o + 1) shl 16) or (u8(b, o + 2) shl 8) or u8(b, o + 3)
    private fun tag(b: ByteArray, o: Int) = String(b, o, 4, Charsets.ISO_8859_1)

    private data class Box(val type: String, val off: Int, val hdr: Int, val size: Int) {
        fun body() = off + hdr
        fun end() = off + size
    }

    private fun boxes(b: ByteArray, s: Int, e: Int): List<Box> {
        val out = ArrayList<Box>()
        var o = s
        val limit = minOf(e, b.size)
        while (o + 8 <= limit) {
            var size = u32(b, o)
            var h = 8
            if (size == 1L) {
                if (o + 16 > limit) break
                size = u64(b, o + 8); h = 16
            } else if (size == 0L) size = (limit - o).toLong()
            if (size < h || o + size > limit) break
            out.add(Box(tag(b, o + 4), o, h, size.toInt()))
            o += size.toInt()
        }
        return out
    }

    private fun find(b: ByteArray, s: Int, e: Int, type: String): Box? =
        boxes(b, s, e).firstOrNull { it.type == type }

    // ------------------------------------------------------------------ init parsing

    /**
     * Extract CENC track info from the raw (unpatched) init segment.
     * Returns null if no encrypted audio track is found.
     */
    fun parseTrackInfo(init: ByteArray): TrackInfo? {
        val moov = find(init, 0, init.size, "moov") ?: return null
        for (trak in boxes(init, moov.body(), moov.end())) {
            if (trak.type != "trak") continue
            val mdia = find(init, trak.body(), trak.end(), "mdia") ?: continue
            val minf = find(init, mdia.body(), mdia.end(), "minf") ?: continue
            val stbl = find(init, minf.body(), minf.end(), "stbl") ?: continue
            val stsd = find(init, stbl.body(), stbl.end(), "stsd") ?: continue

            for (entry in boxes(init, stsd.body() + 8, stsd.end())) {
                if (entry.type != "enca") continue
                val inner = entry.body() + 28          // past AudioSampleEntry fixed fields
                val sinf = boxes(init, inner, entry.end()).firstOrNull { it.type == "sinf" } ?: continue
                val frma = find(init, sinf.body(), sinf.end(), "frma") ?: continue
                val schi = find(init, sinf.body(), sinf.end(), "schi") ?: continue
                val tenc = find(init, schi.body(), schi.end(), "tenc") ?: continue

                val originalCodec = tag(init, frma.body())
                var p = tenc.body() + 4                // past version + flags
                val protectedFlag = u8(init, p + 2)
                val ivSize = u8(init, p + 3)
                p += 4
                val kid = init.copyOfRange(p, p + 16)
                p += 16

                var constIv: ByteArray? = null
                if (protectedFlag == 1 && ivSize == 0 && p < tenc.end()) {
                    val n = u8(init, p)
                    if (p + 1 + n <= tenc.end()) constIv = init.copyOfRange(p + 1, p + 1 + n)
                }
                return TrackInfo(ivSize, constIv, kid, originalCodec)
            }
        }
        return null
    }

    // ------------------------------------------------------------------ senc parsing

    private fun parseSenc(b: ByteArray, box: Box, ivSize: Int): List<SampleAux> {
        val fl = flags(b, box.body())
        val n = u32(b, box.body() + 4).toInt()
        var p = box.body() + 8
        val out = ArrayList<SampleAux>(n)
        for (i in 0 until n) {
            if (p + ivSize > box.end()) break
            val iv = b.copyOfRange(p, p + ivSize); p += ivSize
            var subs: ArrayList<Pair<Int, Long>>? = null
            if (fl and 0x2 != 0) {
                val c = u16(b, p); p += 2
                subs = ArrayList(c)
                for (k in 0 until c) { subs.add(u16(b, p) to u32(b, p + 2)); p += 6 }
            }
            out.add(SampleAux(iv, subs))
        }
        return out
    }

    // ------------------------------------------------------------------ fragment decrypt

    /**
     * Decrypt a fragment (moof + mdat) **in place** using the given 16-byte content key.
     * Also neutralises senc/saiz/saio/pssh/sgpd/sbgp DRM boxes (renamed to "free").
     */
    fun decryptFragment(frag: ByteArray, key: ByteArray, trackInfo: TrackInfo) {
        require(key.size == 16) { "CENC key must be 16 bytes, got ${key.size}" }
        val spec = SecretKeySpec(key, "AES")

        for (moof in boxes(frag, 0, frag.size).filter { it.type == "moof" }) {
            for (traf in boxes(frag, moof.body(), moof.end())) {
                if (traf.type != "traf") continue

                // ---- tfhd: default sample size, base offset ----
                val tfhd = find(frag, traf.body(), traf.end(), "tfhd") ?: continue
                val fl = flags(frag, tfhd.body())
                var p = tfhd.body() + 8
                var base = moof.off.toLong()
                if (fl and 0x1 != 0) { base = u64(frag, p); p += 8 }
                if (fl and 0x2 != 0) p += 4
                if (fl and 0x8 != 0) p += 4
                var dsz = 0L
                if (fl and 0x10 != 0) { dsz = u32(frag, p); p += 4 }

                // ---- trun(s): sample offsets + sizes ----
                val samples = ArrayList<Pair<Long, Int>>()
                var cur = -1L
                for (trun in boxes(frag, traf.body(), traf.end())) {
                    if (trun.type != "trun") continue
                    val tf = flags(frag, trun.body())
                    val n = u32(frag, trun.body() + 4).toInt()
                    var q = trun.body() + 8
                    if (tf and 0x1 != 0) { cur = base + s32(frag, q); q += 4 } else if (cur < 0) cur = base
                    if (tf and 0x4 != 0) q += 4
                    for (i in 0 until n) {
                        if (tf and 0x100 != 0) q += 4
                        var sz = dsz
                        if (tf and 0x200 != 0) { sz = u32(frag, q); q += 4 }
                        if (tf and 0x400 != 0) q += 4
                        if (tf and 0x800 != 0) q += 4
                        samples.add(cur to sz.toInt())
                        cur += sz
                    }
                }

                // ---- senc: per-sample IVs ----
                val senc = find(frag, traf.body(), traf.end(), "senc")
                val aux: List<SampleAux> = if (senc != null) parseSenc(frag, senc, trackInfo.ivSize) else emptyList()

                // ---- AES-CTR decrypt each sample ----
                for ((i, s) in samples.withIndex()) {
                    val (off, size) = s
                    if (size <= 0) continue
                    val offInt = off.toInt()
                    if (offInt < 0 || offInt + size > frag.size) continue

                    val a = aux.getOrNull(i)
                    var iv = a?.iv ?: ByteArray(0)
                    if (iv.isEmpty() && trackInfo.constIv != null) iv = trackInfo.constIv
                    if (iv.isEmpty()) continue

                    val iv16 = ByteArray(16)
                    System.arraycopy(iv, 0, iv16, 0, minOf(16, iv.size))

                    val cipher = Cipher.getInstance("AES/CTR/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, spec, IvParameterSpec(iv16))

                    val subs = a?.subs
                    if (subs == null || subs.isEmpty()) {
                        cipher.update(frag, offInt, size, frag, offInt)
                    } else {
                        var pos = offInt
                        for ((clear, enc) in subs) {
                            pos += clear
                            val n = enc.toInt()
                            if (n > 0 && pos + n <= frag.size) cipher.update(frag, pos, n, frag, pos)
                            pos += n
                        }
                    }
                }

                // ---- neutralise DRM boxes ----
                for (box in boxes(frag, traf.body(), traf.end())) {
                    when (box.type) {
                        "senc", "saiz", "saio" ->
                            "free".toByteArray(Charsets.ISO_8859_1).copyInto(frag, box.off + 4)
                        "sgpd", "sbgp" -> {
                            if (box.body() + 8 <= box.end()) {
                                val gt = tag(frag, box.body() + 4)
                                if (gt == "seig" || gt == "seam")
                                    "free".toByteArray(Charsets.ISO_8859_1).copyInto(frag, box.off + 4)
                            }
                        }
                    }
                }
            }

            // pssh inside moof
            for (box in boxes(frag, moof.body(), moof.end())) {
                if (box.type == "pssh") "free".toByteArray(Charsets.ISO_8859_1).copyInto(frag, box.off + 4)
            }
        }
    }
}
