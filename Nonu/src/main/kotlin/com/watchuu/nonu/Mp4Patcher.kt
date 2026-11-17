package com.watchuu.nonu

/**
 * Pure Kotlin port of am-mp4/src/lib.rs — ISOBMFF box walking, DRM-box patching,
 * fragment scanning and decryption coordination.
 *
 * All modifications are length-preserving so HTTP Range offsets stay valid.
 */
object Mp4Patcher {

    // ------------------------------------------------------------------ box header

    private data class BoxHeader(
        val typ: String,   // 4-char ASCII box type
        val start: Int,    // offset of the size field
        val body: Int,     // offset of the payload (after size/type/largesize)
        val end: Int       // first byte past this box
    )

    // ------------------------------------------------------------------ helpers

    private fun beU32(buf: ByteArray, pos: Int): Long {
        return ((buf[pos].toLong() and 0xFF) shl 24) or
               ((buf[pos + 1].toLong() and 0xFF) shl 16) or
               ((buf[pos + 2].toLong() and 0xFF) shl 8) or
               (buf[pos + 3].toLong() and 0xFF)
    }

    private fun beU64(buf: ByteArray, pos: Int): Long {
        return ((buf[pos].toLong() and 0xFF) shl 56) or
               ((buf[pos + 1].toLong() and 0xFF) shl 48) or
               ((buf[pos + 2].toLong() and 0xFF) shl 40) or
               ((buf[pos + 3].toLong() and 0xFF) shl 32) or
               ((buf[pos + 4].toLong() and 0xFF) shl 24) or
               ((buf[pos + 5].toLong() and 0xFF) shl 16) or
               ((buf[pos + 6].toLong() and 0xFF) shl 8) or
               (buf[pos + 7].toLong() and 0xFF)
    }

    private fun typeAt(buf: ByteArray, off: Int): String =
        String(buf, off, 4, Charsets.US_ASCII)

    private fun writeType(buf: ByteArray, off: Int, typ: String) {
        typ.toByteArray(Charsets.US_ASCII).copyInto(buf, off)
    }

    // ------------------------------------------------------------------ box walking

    /** Enumerate sibling boxes in buf[rangeStart..rangeEnd). Stops on malformed data. */
    private fun children(buf: ByteArray, rangeStart: Int, rangeEnd: Int): List<BoxHeader> {
        val out = mutableListOf<BoxHeader>()
        var pos = rangeStart
        val end = minOf(rangeEnd, buf.size)
        while (pos + 8 <= end) {
            val size = beU32(buf, pos)
            val typ = typeAt(buf, pos + 4)
            val len: Long
            val header: Int
            when {
                size == 0L -> { len = (end - pos).toLong(); header = 8 }
                size == 1L -> {
                    if (pos + 16 > end) break
                    len = beU64(buf, pos + 8)
                    header = 16
                }
                else -> { len = size; header = 8 }
            }
            if (len < header || pos + len.toInt() > end) break
            out.add(BoxHeader(typ, pos, pos + header, pos + len.toInt()))
            pos += len.toInt()
        }
        return out
    }

    private fun find(buf: ByteArray, start: Int, end: Int, typ: String): BoxHeader? =
        children(buf, start, end).firstOrNull { it.typ == typ }

    // ------------------------------------------------------------------ renames

    private data class Rename(val start: Int, val newType: String)

    private fun applyRenames(buf: ByteArray, renames: List<Rename>) {
        for ((start, typ) in renames) {
            writeType(buf, start + 4, typ)
        }
    }

    // ------------------------------------------------------------------ patch init

    /**
     * Rewrite init segment in place:
     * - enca/encv → original codec from frma
     * - sinf → free (body zeroed)
     * - pssh → free
     */
    fun patchInitInPlace(init: ByteArray) {
        val renames = mutableListOf<Rename>()
        val wipes = mutableListOf<IntRange>()

        for (moov in children(init, 0, init.size).filter { it.typ == "moov" }) {
            for (b in children(init, moov.body, moov.end)) {
                when (b.typ) {
                    "pssh" -> renames.add(Rename(b.start, "free"))
                    "trak" -> patchTrak(init, b, renames, wipes)
                }
            }
        }

        applyRenames(init, renames)
        for (r in wipes) {
            for (i in r) init[i] = 0
        }
    }

    private fun patchTrak(
        src: ByteArray,
        trak: BoxHeader,
        renames: MutableList<Rename>,
        wipes: MutableList<IntRange>
    ) {
        val stsd = find(src, trak.body, trak.end, "mdia")?.let { mdia ->
            find(src, mdia.body, mdia.end, "minf")?.let { minf ->
                find(src, minf.body, minf.end, "stbl")?.let { stbl ->
                    find(src, stbl.body, stbl.end, "stsd")
                }
            }
        } ?: return

        // stsd: FullBox(4) + entry_count(4), then sample entries
        for (entry in children(src, stsd.body + 8, stsd.end)) {
            val fields = when (entry.typ) {
                "enca" -> 28
                "encv" -> 78
                else -> continue
            }
            val sinf = find(src, entry.body + fields, entry.end, "sinf") ?: continue
            val frma = find(src, sinf.body, sinf.end, "frma") ?: continue
            if (frma.body + 4 > frma.end) continue

            val original = typeAt(src, frma.body)
            renames.add(Rename(entry.start, original))
            renames.add(Rename(sinf.start, "free"))
            wipes.add(sinf.body until sinf.end)
        }
    }

    // ------------------------------------------------------------------ scan fragment

    private data class ScanResult(
        val samples: List<IntRange>,
        val renames: List<Rename>
    )

    private fun scanFragment(frag: ByteArray): ScanResult {
        val samples = mutableListOf<IntRange>()
        val renames = mutableListOf<Rename>()
        var foundMoof = false

        for (moof in children(frag, 0, frag.size).filter { it.typ == "moof" }) {
            foundMoof = true
            for (b in children(frag, moof.body, moof.end)) {
                when (b.typ) {
                    "pssh" -> renames.add(Rename(b.start, "free"))
                    "traf" -> parseTraf(frag, moof, b, samples, renames)
                }
            }
        }

        if (!foundMoof) throw IllegalStateException("No moof box found in fragment")

        // Remove empty ranges and validate ordering
        val filtered = samples.filter { !it.isEmpty() }
        var prevEnd = 0
        for (r in filtered) {
            if (r.first < prevEnd || r.last + 1 > frag.size) {
                throw IllegalStateException(
                    "Sample range ${r.first}..${r.last + 1} is out of order or past fragment end ${frag.size}"
                )
            }
            prevEnd = r.last + 1
        }

        return ScanResult(filtered, renames)
    }

    private fun parseTraf(
        src: ByteArray,
        moof: BoxHeader,
        traf: BoxHeader,
        samples: MutableList<IntRange>,
        renames: MutableList<Rename>
    ) {
        var defaultSize: Int? = null
        var nextData = moof.start

        for (b in children(src, traf.body, traf.end)) {
            when (b.typ) {
                "tfhd" -> {
                    val r = FullBoxReader(src, b)
                    r.u32() // track_ID
                    if (r.flags and 0x01 != 0) {
                        throw IllegalStateException("tfhd base-data-offset is not supported")
                    }
                    if (r.flags and 0x02 != 0) r.u32() // sample_description_index
                    if (r.flags and 0x08 != 0) r.u32() // default_sample_duration
                    if (r.flags and 0x10 != 0) defaultSize = r.u32().toInt()
                }
                "trun" -> {
                    val r = FullBoxReader(src, b)
                    val count = r.u32().toInt()
                    var pos = nextData
                    if (r.flags and 0x01 != 0) {
                        val off = r.u32().toInt() // signed i32
                        pos = moof.start + off
                        if (pos < 0) throw IllegalStateException("trun data_offset points before fragment start")
                    }
                    if (r.flags and 0x04 != 0) r.u32() // first_sample_flags
                    if (count > src.size) {
                        throw IllegalStateException("Implausible trun sample_count $count")
                    }
                    for (i in 0 until count) {
                        if (r.flags and 0x100 != 0) r.u32() // sample_duration
                        val size = if (r.flags and 0x200 != 0) {
                            r.u32().toInt()
                        } else {
                            defaultSize ?: throw IllegalStateException(
                                "Sample size missing from both trun and tfhd"
                            )
                        }
                        if (r.flags and 0x400 != 0) r.u32() // sample_flags
                        if (r.flags and 0x800 != 0) r.u32() // sample_composition_time_offset
                        samples.add(pos until (pos + size))
                        pos += size
                    }
                    nextData = pos
                }
                "senc", "saiz", "saio" -> renames.add(Rename(b.start, "free"))
                "sgpd", "sbgp" -> {
                    // FullBox(4) then grouping_type(4); only clear encryption-related types
                    if (b.body + 8 <= b.end) {
                        val groupType = typeAt(src, b.body + 4)
                        if (groupType == "seig" || groupType == "seam") {
                            renames.add(Rename(b.start, "free"))
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ decrypt fragment

    /**
     * Decrypt a media fragment (moof + mdat) in place:
     * 1. Scan for sample byte ranges and DRM boxes
     * 2. Rename DRM boxes to 'free'
     * 3. Decrypt sample data with the temari cipher
     */
    fun decryptFragmentInPlace(tpl: TemariCipher.Template, data: ByteArray) {
        val (samples, renames) = scanFragment(data)
        applyRenames(data, renames)
        // Convert IntRange to (offset, length) pairs for the cipher
        val ranges = samples.map { it.first to (it.last - it.first + 1) }
        TemariCipher.decryptRangesInPlace(tpl, data, ranges)
    }

    // ------------------------------------------------------------------ FullBox reader

    /** Sequential reader for FullBox payloads with bounds checking. */
    private class FullBoxReader(val buf: ByteArray, b: BoxHeader) {
        var pos: Int = b.body
        val end: Int = b.end
        val flags: Int

        init {
            val vf = u32().toInt()
            flags = vf and 0x00FFFFFF
        }

        fun u32(): Long {
            if (pos + 4 > end) throw IllegalStateException("Truncated box")
            val v = beU32(buf, pos)
            pos += 4
            return v
        }
    }
}
