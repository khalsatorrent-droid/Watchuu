package com.watchuu.musicbox

import java.io.File
import java.io.RandomAccessFile

/**
 * Turns a (decrypted) FLAC-in-MP4 file into a native `.flac` stream. Players are far more reliable with
 * native FLAC than with `fLaC` sample entries inside ISO-BMFF, which is the likely reason a correctly
 * decrypted Amazon file stayed silent. Returns false (leaving the file untouched) when the file is not
 * FLAC-in-MP4, so other codecs keep the old path.
 */
object FlacRemux {
    private class Box(val type: String, val off: Int, val hdr: Int, val size: Int) {
        val body get() = off + hdr
        val end get() = off + size
    }

    private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF
    private fun u16(b: ByteArray, o: Int) = (u8(b, o) shl 8) or u8(b, o + 1)
    private fun u32(b: ByteArray, o: Int): Long =
        (u8(b, o).toLong() shl 24) or (u8(b, o + 1).toLong() shl 16) or (u8(b, o + 2).toLong() shl 8) or u8(b, o + 3).toLong()
    private fun u64(b: ByteArray, o: Int): Long = (u32(b, o) shl 32) or u32(b, o + 4)
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

    private fun find(b: ByteArray, s: Int, e: Int, type: String) = boxes(b, s, e).firstOrNull { it.type == type }

    /** Replaces [file] with a native FLAC stream; true on success. */
    fun convert(file: File): Boolean {
        val tmp = File(file.path + ".flac")
        try {
            val ok = RandomAccessFile(file, "r").use { raf -> remux(raf, tmp) }
            if (!ok) { tmp.delete(); return false }
            if (!tmp.renameTo(file)) { tmp.copyTo(file, true); tmp.delete() }
            return true
        } catch (e: Exception) {
            tmp.delete()
            return false
        }
    }

    private fun remux(raf: RandomAccessFile, out: File): Boolean {
        val len = raf.length()
        var info: ByteArray? = null
        var tid = -1
        val trexSize = HashMap<Int, Long>()
        val samples = ArrayList<Pair<Long, Int>>()
        var pos = 0L
        while (pos + 8 <= len) {
            val head = ByteArray(16)
            raf.seek(pos); raf.read(head, 0, 8)
            var size = u32(head, 0)
            val type = tag(head, 4)
            if (size == 1L) { raf.seek(pos + 8); raf.readFully(head, 8, 8); size = u64(head, 8) } else if (size == 0L) size = len - pos
            if (size < 8) break
            if (type == "moov" || type == "moof") {
                val m = ByteArray(size.toInt())
                raf.seek(pos); raf.readFully(m)
                if (type == "moov") {
                    val moov = Box("moov", 0, 8, m.size)
                    find(m, moov.body, moov.end, "mvex")?.let { mvex ->
                        for (t in boxes(m, mvex.body, mvex.end)) if (t.type == "trex") trexSize[u32(m, t.body + 4).toInt()] = u32(m, t.body + 16)
                    }
                    for (trak in boxes(m, moov.body, moov.end)) {
                        if (trak.type != "trak") continue
                        val tkhd = find(m, trak.body, trak.end, "tkhd") ?: continue
                        val mdia = find(m, trak.body, trak.end, "mdia") ?: continue
                        val minf = find(m, mdia.body, mdia.end, "minf") ?: continue
                        val stbl = find(m, minf.body, minf.end, "stbl") ?: continue
                        val stsd = find(m, stbl.body, stbl.end, "stsd") ?: continue
                        val ent = stsd.body + 8
                        if (tag(m, ent + 4) != "fLaC") continue
                        val dfla = boxes(m, ent + 36, ent + u32(m, ent).toInt()).firstOrNull { it.type == "dfLa" } ?: continue
                        info = m.copyOfRange(dfla.body + 4, dfla.end)
                        tid = u32(m, tkhd.body + if (u8(m, tkhd.body) == 1) 20 else 12).toInt()
                        val stsz = find(m, stbl.body, stbl.end, "stsz")
                        val stsc = find(m, stbl.body, stbl.end, "stsc")
                        val stco = find(m, stbl.body, stbl.end, "stco") ?: find(m, stbl.body, stbl.end, "co64")
                        if (stsz != null && stsc != null && stco != null) {
                            val defSize = u32(m, stsz.body + 4)
                            val count = u32(m, stsz.body + 8).toInt()
                            if (count > 0) {
                                val sizes = LongArray(count) { if (defSize != 0L) defSize else u32(m, stsz.body + 12 + 4 * it) }
                                val scN = u32(m, stsc.body + 4).toInt()
                                val sc = Array(scN) { i -> val o = stsc.body + 8 + 12 * i; longArrayOf(u32(m, o), u32(m, o + 4)) }
                                val coN = u32(m, stco.body + 4).toInt()
                                val is64 = stco.type == "co64"
                                var si = 0
                                for (ci in 0 until coN) {
                                    var off = if (is64) u64(m, stco.body + 8 + 8 * ci) else u32(m, stco.body + 8 + 4 * ci)
                                    var spc = 0L
                                    for (e in sc) if (e[0] <= ci + 1) spc = e[1]
                                    for (k in 0 until spc) {
                                        if (si >= count) break
                                        samples.add(off to sizes[si].toInt()); off += sizes[si]; si++
                                    }
                                }
                            }
                        }
                    }
                } else if (tid >= 0) {
                    for (traf in boxes(m, 8, m.size)) {
                        if (traf.type != "traf") continue
                        val tfhd = find(m, traf.body, traf.end, "tfhd") ?: continue
                        if (u32(m, tfhd.body + 4).toInt() != tid) continue
                        val fl = flags(m, tfhd.body)
                        var p = tfhd.body + 8
                        var base = pos
                        if (fl and 0x1 != 0) { base = u64(m, p); p += 8 }
                        if (fl and 0x2 != 0) p += 4
                        if (fl and 0x8 != 0) p += 4
                        var dsz = trexSize[tid] ?: 0L
                        if (fl and 0x10 != 0) { dsz = u32(m, p); p += 4 }
                        var cur = -1L
                        for (trun in boxes(m, traf.body, traf.end)) {
                            if (trun.type != "trun") continue
                            val tf = flags(m, trun.body)
                            val n = u32(m, trun.body + 4).toInt()
                            var q = trun.body + 8
                            if (tf and 0x1 != 0) { cur = base + u32(m, q).toInt(); q += 4 } else if (cur < 0) cur = base
                            if (tf and 0x4 != 0) q += 4
                            for (i in 0 until n) {
                                if (tf and 0x100 != 0) q += 4
                                var sz = dsz
                                if (tf and 0x200 != 0) { sz = u32(m, q); q += 4 }
                                if (tf and 0x400 != 0) q += 4
                                if (tf and 0x800 != 0) q += 4
                                samples.add(cur to sz.toInt()); cur += sz
                            }
                        }
                    }
                }
            }
            pos += size
        }
        val meta = info ?: return false
        if (samples.isEmpty() || meta.size < 4 + 34) return false

        // re-flag the metadata blocks so exactly the last one is marked "last"
        val blocks = ArrayList<ByteArray>()
        var p = 0
        while (p + 4 <= meta.size) {
            val n = (u8(meta, p + 1) shl 16) or (u8(meta, p + 2) shl 8) or u8(meta, p + 3)
            if (p + 4 + n > meta.size) break
            blocks.add(meta.copyOfRange(p, p + 4 + n))
            p += 4 + n
        }
        if (blocks.isEmpty()) return false

        // sanity: a correctly decrypted FLAC frame starts with the 14-bit sync code 0xFFF8
        val probe = ByteArray(2)
        raf.seek(samples[0].first); raf.readFully(probe)
        if (u8(probe, 0) != 0xFF || (u8(probe, 1) and 0xFE) != 0xF8) {
            MusicBoxLog.w("FlacRemux", "first sample is not a FLAC frame (${"%02x%02x".format(u8(probe, 0), u8(probe, 1))}) - key or layout wrong")
            return false
        }
        out.outputStream().buffered(256 * 1024).use { o ->
            o.write("fLaC".toByteArray(Charsets.ISO_8859_1))
            for ((i, bl) in blocks.withIndex()) {
                val b = bl.copyOf()
                b[0] = if (i == blocks.size - 1) (b[0].toInt() or 0x80).toByte() else (b[0].toInt() and 0x7F).toByte()
                o.write(b)
            }
            val buf = ByteArray(64 * 1024)
            for ((off, size) in samples) {
                if (size <= 0) continue
                raf.seek(off)
                var left = size
                while (left > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size, left))
                    if (n < 0) return false
                    o.write(buf, 0, n); left -= n
                }
            }
        }
        return true
    }
}
