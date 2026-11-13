package com.watchuu.musicbox

import java.io.File
import java.io.RandomAccessFile
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * In-place decryptor for CENC (cenc-aes-ctr) protected ISO-BMFF audio, which is what Amazon's
 * resolver returns (the same job ffmpeg's `-decryption_key` does for the SpotiFLAC extension).
 *
 * Handles both progressive files (stbl + senc or saio/saiz) and fragmented files (moof/traf with
 * senc or saio/saiz). After decrypting, the `enca` sample entry is rewritten to its original format
 * (frma) and `sinf` is neutralised, so a normal player can open the file without any DRM setup.
 *
 * The algorithm was validated against ffmpeg-produced CENC files (bit-exact decode).
 */
object CencDecryptor {
    private class Box(val type: String, val off: Int, val hdr: Int, val size: Int)

    private class TrackInfo(val ivSize: Int, val constIv: ByteArray?)

    private class Aux(val iv: ByteArray, val subs: List<Pair<Int, Long>>?)

    private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF
    private fun u16(b: ByteArray, o: Int) = (u8(b, o) shl 8) or u8(b, o + 1)
    private fun u32(b: ByteArray, o: Int): Long =
        (u8(b, o).toLong() shl 24) or (u8(b, o + 1).toLong() shl 16) or (u8(b, o + 2).toLong() shl 8) or u8(b, o + 3).toLong()
    private fun u64(b: ByteArray, o: Int): Long = (u32(b, o) shl 32) or u32(b, o + 4)
    private fun s32(b: ByteArray, o: Int): Int = u32(b, o).toInt()
    private fun flags(b: ByteArray, o: Int) = (u8(b, o + 1) shl 16) or (u8(b, o + 2) shl 8) or u8(b, o + 3)
    private fun tag(b: ByteArray, o: Int) = String(b, o, 4, Charsets.ISO_8859_1)

    private fun boxes(b: ByteArray, s: Int, e: Int): List<Box> {
        val out = ArrayList<Box>()
        var o = s
        while (o + 8 <= e) {
            var size = u32(b, o)
            var h = 8
            if (size == 1L) { size = u64(b, o + 8); h = 16 } else if (size == 0L) size = (e - o).toLong()
            if (size < h || o + size > e) break
            out.add(Box(tag(b, o + 4), o, h, size.toInt()))
            o += size.toInt()
        }
        return out
    }

    private fun find(b: ByteArray, s: Int, e: Int, type: String): Box? = boxes(b, s, e).firstOrNull { it.type == type }
    private fun Box.end() = off + size
    private fun Box.body() = off + hdr

    /** Decrypts [file] in place with the 16-byte hex [keyHex]. Throws when the file cannot be handled. */
    fun decrypt(file: File, keyHex: String) {
        val key = keyHex.trim().chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        require(key.size == 16) { "CENC key must be 16 bytes" }
        RandomAccessFile(file, "rw").use { raf ->
            val tracks = HashMap<Int, TrackInfo>()
            val trexSize = HashMap<Int, Long>()
            var pos = 0L
            val len = raf.length()
            var decryptedSomething = false
            while (pos + 8 <= len) {
                val head = ByteArray(16)
                raf.seek(pos)
                raf.read(head, 0, 8)
                var size = u32(head, 0)
                val type = tag(head, 4)
                if (size == 1L) { raf.seek(pos + 8); raf.readFully(head, 8, 8); size = u64(head, 8) } else if (size == 0L) size = len - pos
                if (size < 8) break
                if (type == "moov") {
                    val m = ByteArray(size.toInt())
                    raf.seek(pos); raf.readFully(m)
                    if (processMoov(raf, m, pos, key, tracks, trexSize)) decryptedSomething = true
                    raf.seek(pos); raf.write(m)
                } else if (type == "moof") {
                    val f = ByteArray(size.toInt())
                    raf.seek(pos); raf.readFully(f)
                    if (processMoof(raf, f, pos, key, tracks, trexSize)) decryptedSomething = true
                }
                pos += size
            }
            require(decryptedSomething) { "no CENC protected samples found" }
        }
    }

    // ------------------------------------------------------------------ moov

    private fun processMoov(
        raf: RandomAccessFile, m: ByteArray, mpos: Long, key: ByteArray,
        tracks: MutableMap<Int, TrackInfo>, trexSize: MutableMap<Int, Long>,
    ): Boolean {
        val moov = Box("moov", 0, 8, m.size)
        find(m, moov.body(), moov.end(), "mvex")?.let { mvex ->
            for (t in boxes(m, mvex.body(), mvex.end())) if (t.type == "trex") {
                trexSize[u32(m, t.body() + 4).toInt()] = u32(m, t.body() + 16)
            }
        }
        var any = false
        for (trak in boxes(m, moov.body(), moov.end())) {
            if (trak.type != "trak") continue
            val tkhd = find(m, trak.body(), trak.end(), "tkhd") ?: continue
            val tver = u8(m, tkhd.body())
            val tid = u32(m, tkhd.body() + if (tver == 1) 20 else 12).toInt()
            val mdia = find(m, trak.body(), trak.end(), "mdia") ?: continue
            val minf = find(m, mdia.body(), mdia.end(), "minf") ?: continue
            val stbl = find(m, minf.body(), minf.end(), "stbl") ?: continue
            val stsd = find(m, stbl.body(), stbl.end(), "stsd") ?: continue
            val ent = stsd.body() + 8
            val entType = tag(m, ent + 4)
            if (entType != "enca" && entType != "encv") continue
            val entSize = u32(m, ent).toInt()
            val inner = ent + 8 + if (entType == "enca") 28 else 78
            val sinf = boxes(m, inner, ent + entSize).firstOrNull { it.type == "sinf" } ?: continue
            val frma = find(m, sinf.body(), sinf.end(), "frma") ?: continue
            val schi = find(m, sinf.body(), sinf.end(), "schi") ?: continue
            val tenc = find(m, schi.body(), schi.end(), "tenc") ?: continue
            var p = tenc.body() + 4
            val protectedFlag = u8(m, p + 2)
            val ivSize = u8(m, p + 3)
            p += 4 + 16
            var const: ByteArray? = null
            if (protectedFlag == 1 && ivSize == 0) {
                val n = u8(m, p)
                const = m.copyOfRange(p + 1, p + 1 + n)
            }
            // restore the clear sample-entry type, hide sinf
            System.arraycopy(m, frma.body(), m, ent + 4, 4)
            "free".toByteArray(Charsets.ISO_8859_1).copyInto(m, sinf.off + 4)
            val info = TrackInfo(ivSize, const)
            tracks[tid] = info
            any = true

            // progressive layout: samples described by stsz/stsc/stco
            val stsz = find(m, stbl.body(), stbl.end(), "stsz") ?: continue
            val stsc = find(m, stbl.body(), stbl.end(), "stsc") ?: continue
            val stco = find(m, stbl.body(), stbl.end(), "stco") ?: find(m, stbl.body(), stbl.end(), "co64") ?: continue
            val defSize = u32(m, stsz.body() + 4)
            val count = u32(m, stsz.body() + 8).toInt()
            if (count == 0) continue
            val sizes = LongArray(count) { if (defSize != 0L) defSize else u32(m, stsz.body() + 12 + 4 * it) }
            val scN = u32(m, stsc.body() + 4).toInt()
            val sc = Array(scN) { i ->
                val o = stsc.body() + 8 + 12 * i
                longArrayOf(u32(m, o), u32(m, o + 4))
            }
            val coN = u32(m, stco.body() + 4).toInt()
            val is64 = stco.type == "co64"
            val samples = ArrayList<Pair<Long, Int>>(count)
            var si = 0
            for (ci in 0 until coN) {
                var off = if (is64) u64(m, stco.body() + 8 + 8 * ci) else u32(m, stco.body() + 8 + 4 * ci)
                var spc = 0L
                for (e in sc) if (e[0] <= ci + 1) spc = e[1]
                for (k in 0 until spc) {
                    if (si >= count) break
                    samples.add(off to sizes[si].toInt())
                    off += sizes[si]; si++
                }
            }
            val aux: List<Aux> = find(m, stbl.body(), stbl.end(), "senc")?.let { parseSenc(m, it, info) }
                ?: run {
                    val r = parseSaio(m, stbl.body(), stbl.end())
                    if (r == null) emptyList() else {
                        val first = r.first.firstOrNull() ?: return@run emptyList()
                        parseAuxAt(raf, first, r.second.size, info.ivSize, r.second)
                    }
                }
            decryptSamples(raf, key, info, samples, aux)
        }
        return any
    }

    // ------------------------------------------------------------------ moof

    private fun processMoof(
        raf: RandomAccessFile, f: ByteArray, fpos: Long, key: ByteArray,
        tracks: Map<Int, TrackInfo>, trexSize: Map<Int, Long>,
    ): Boolean {
        var any = false
        for (traf in boxes(f, 8, f.size)) {
            if (traf.type != "traf") continue
            val tfhd = find(f, traf.body(), traf.end(), "tfhd") ?: continue
            val fl = flags(f, tfhd.body())
            val tid = u32(f, tfhd.body() + 4).toInt()
            val info = tracks[tid] ?: continue
            var p = tfhd.body() + 8
            var base = fpos
            if (fl and 0x1 != 0) { base = u64(f, p); p += 8 }
            if (fl and 0x2 != 0) p += 4
            if (fl and 0x8 != 0) p += 4
            var dsz = trexSize[tid] ?: 0L
            if (fl and 0x10 != 0) { dsz = u32(f, p); p += 4 }

            val samples = ArrayList<Pair<Long, Int>>()
            var cur = -1L
            for (trun in boxes(f, traf.body(), traf.end())) {
                if (trun.type != "trun") continue
                val tf = flags(f, trun.body())
                val n = u32(f, trun.body() + 4).toInt()
                var q = trun.body() + 8
                if (tf and 0x1 != 0) { cur = base + s32(f, q); q += 4 } else if (cur < 0) cur = base
                if (tf and 0x4 != 0) q += 4
                for (i in 0 until n) {
                    if (tf and 0x100 != 0) q += 4
                    var sz = dsz
                    if (tf and 0x200 != 0) { sz = u32(f, q); q += 4 }
                    if (tf and 0x400 != 0) q += 4
                    if (tf and 0x800 != 0) q += 4
                    samples.add(cur to sz.toInt())
                    cur += sz
                }
            }
            val aux: List<Aux> = find(f, traf.body(), traf.end(), "senc")?.let { parseSenc(f, it, info) }
                ?: run {
                    val r = parseSaio(f, traf.body(), traf.end())
                    if (r == null) emptyList() else {
                        val first = r.first.firstOrNull() ?: return@run emptyList()
                        parseAuxAt(raf, base + first, r.second.size, info.ivSize, r.second)
                    }
                }
            decryptSamples(raf, key, info, samples, aux)
            any = true
        }
        return any
    }

    // ------------------------------------------------------------------ aux info

    private fun parseSenc(b: ByteArray, box: Box, info: TrackInfo): List<Aux> {
        val fl = flags(b, box.body())
        val n = u32(b, box.body() + 4).toInt()
        var p = box.body() + 8
        val out = ArrayList<Aux>(n)
        for (i in 0 until n) {
            val iv = b.copyOfRange(p, p + info.ivSize); p += info.ivSize
            var subs: ArrayList<Pair<Int, Long>>? = null
            if (fl and 0x2 != 0) {
                val c = u16(b, p); p += 2
                subs = ArrayList(c)
                for (k in 0 until c) { subs.add(u16(b, p) to u32(b, p + 2)); p += 6 }
            }
            out.add(Aux(iv, subs))
        }
        return out
    }

    /** returns (offsets, per-sample aux sizes) */
    private fun parseSaio(b: ByteArray, s: Int, e: Int): Pair<List<Long>, List<Int>>? {
        val saio = find(b, s, e, "saio") ?: return null
        val saiz = find(b, s, e, "saiz") ?: return null
        val ver = u8(b, saio.body())
        var p = saio.body() + 4
        if (flags(b, saio.body()) and 1 != 0) p += 8
        val n = u32(b, p).toInt(); p += 4
        val offs = List(n) { i -> if (ver == 1) u64(b, p + 8 * i) else u32(b, p + 4 * i) }
        var q = saiz.body() + 4
        if (flags(b, saiz.body()) and 1 != 0) q += 8
        val def = u8(b, q)
        val cnt = u32(b, q + 1).toInt()
        val sizes = List(cnt) { i -> if (def != 0) def else u8(b, q + 5 + i) }
        return offs to sizes
    }

    private fun parseAuxAt(raf: RandomAccessFile, start: Long, count: Int, ivSize: Int, sizes: List<Int>): List<Aux> {
        val total = sizes.sum()
        val buf = ByteArray(total)
        raf.seek(start); raf.readFully(buf)
        val out = ArrayList<Aux>(count)
        var p = 0
        for (i in 0 until count) {
            val info = sizes[i]
            val iv = buf.copyOfRange(p, p + ivSize)
            var subs: ArrayList<Pair<Int, Long>>? = null
            if (info > ivSize) {
                var q = p + ivSize
                val c = u16(buf, q); q += 2
                subs = ArrayList(c)
                for (k in 0 until c) { subs.add(u16(buf, q) to u32(buf, q + 2)); q += 6 }
            }
            out.add(Aux(iv, subs))
            p += info
        }
        return out
    }

    // ------------------------------------------------------------------ AES-CTR

    private fun decryptSamples(raf: RandomAccessFile, key: ByteArray, info: TrackInfo, samples: List<Pair<Long, Int>>, aux: List<Aux>) {
        val spec = SecretKeySpec(key, "AES")
        for ((i, s) in samples.withIndex()) {
            val (off, size) = s
            if (size <= 0) continue
            val a = aux.getOrNull(i)
            var iv = a?.iv ?: ByteArray(0)
            if (iv.isEmpty() && info.constIv != null) iv = info.constIv
            if (iv.isEmpty()) continue
            val iv16 = ByteArray(16)
            System.arraycopy(iv, 0, iv16, 0, minOf(16, iv.size))
            val buf = ByteArray(size)
            raf.seek(off); raf.readFully(buf)
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, spec, IvParameterSpec(iv16))
            val subs = a?.subs
            if (subs == null || subs.isEmpty()) {
                raf.seek(off); raf.write(cipher.update(buf))
            } else {
                var p = 0
                for ((clear, enc) in subs) {
                    p += clear
                    val n = enc.toInt()
                    if (n > 0) cipher.update(buf, p, n, buf, p)
                    p += n
                }
                raf.seek(off); raf.write(buf)
            }
        }
    }
}
