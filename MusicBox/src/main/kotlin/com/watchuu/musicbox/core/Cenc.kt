package com.watchuu.musicbox.core

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Streaming decryption of a Common Encryption (ISO/IEC 23001-7) fragmented MP4, the format Amazon's
 * download gateway serves. Mornye downloads the file and runs `ffmpeg -decryption_key`; a player
 * cannot do that, so this rewrites the stream as it passes through:
 *
 *  - samples are decrypted in place ("cenc" AES-CTR, and "cbcs" AES-CBC with a pattern)
 *  - every box that marks the file as protected is neutralised without changing any size:
 *    the `enca` sample entry gets its original format back (from `frma`), and `sinf`, `pssh`,
 *    `senc`, `saiz`, `saio` and PIFF sample-encryption boxes are renamed `free`
 *
 * Because no size changes, every offset in the file stays valid and the output has exactly the
 * input's length, which lets the local server pass the upstream Content-Length through.
 */
class Cenc(private val key: ByteArray) {

    class Unsupported(message: String) : Exception(message)

    private class TrackInfo(
        val scheme: String,
        val ivSize: Int,
        val constantIv: ByteArray?,
        val cryptBlocks: Int,
        val skipBlocks: Int,
    )

    private class Sample(val trackId: Int, val offset: Long, val size: Int, val iv: ByteArray, val subsamples: IntArray?)

    private val tracks = HashMap<Int, TrackInfo>()
    private val trexDefaultSize = HashMap<Int, Int>()

    /** Number of samples decrypted, for diagnostics. */
    var decryptedSamples = 0
        private set

    // ------------------------------------------------------------------ public entry

    /** Where a file whose index comes after its audio is buffered; null uses java.io.tmpdir. */
    var spoolDir: java.io.File? = null

    fun transform(input: InputStream, output: OutputStream) {
        var position = 0L
        var sawMoov = false
        val pending = ArrayList<Sample>()
        while (true) {
            val header = ByteArray(8)
            val first = readUpTo(input, header, 0, 8)
            if (first == 0) break
            if (first < 8) {
                output.write(header, 0, first)
                break
            }
            var size = u32(header, 0)
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            var headerLen = 8
            var headerBytes = header
            if (size == 1L) {
                val large = readFully(input, 8)
                size = u64(large, 0)
                headerLen = 16
                headerBytes = header + large
            }
            val boxStart = position
            if (size == 0L) {
                // Box runs to the end of the stream (only sensible for a final mdat).
                output.write(headerBytes)
                position += headerLen
                position = streamPayload(input, output, position, Long.MAX_VALUE, pending)
                break
            }
            if (size < headerLen) throw Unsupported("bad box size $size for $type at $boxStart")
            val payloadLen = size - headerLen
            when (type) {
                "moov", "moof" -> {
                    if (payloadLen > 64L * 1024 * 1024) throw Unsupported("$type box too large ($payloadLen)")
                    val box = ByteArray(size.toInt())
                    System.arraycopy(headerBytes, 0, box, 0, headerLen)
                    readFully(input, box, headerLen, payloadLen.toInt())
                    if (type == "moov") { sawMoov = true; pending.addAll(parseMoov(box, headerLen)) }
                    else pending.addAll(parseMoof(box, headerLen, boxStart))
                    output.write(box)
                    position += size
                }
                "mdat" -> if (!sawMoov) {
                    // Audio before its index: nothing can be decrypted until the moov has been read,
                    // so the rest of the file is buffered and processed once it is complete.
                    spoolAndFinish(headerBytes, input, output, boxStart)
                    return
                } else {
                    output.write(headerBytes)
                    position += headerLen
                    position = streamPayload(input, output, position, position + payloadLen, pending)
                }
                else -> {
                    output.write(headerBytes)
                    position += headerLen
                    copy(input, output, payloadLen)
                    position += payloadLen
                }
            }
        }
        output.flush()
    }

    private fun spoolAndFinish(firstHeader: ByteArray, input: InputStream, output: OutputStream, startPosition: Long) {
        val file = java.io.File.createTempFile("musicbox", ".mp4", spoolDir)
        try {
            java.io.FileOutputStream(file).use { out ->
                out.write(firstHeader)
                copy(input, out, Long.MAX_VALUE)
            }
            // Find and parse the moov first, then replay the buffered boxes in their original order.
            val pending = ArrayList<Sample>()
            var moovBytes: ByteArray? = null
            java.io.RandomAccessFile(file, "r").use { raf ->
                var p = 0L
                val len = raf.length()
                val head = ByteArray(16)
                while (p + 8 <= len) {
                    raf.seek(p); raf.readFully(head, 0, 8)
                    var size = u32(head, 0)
                    val type = String(head, 4, 4, Charsets.ISO_8859_1)
                    if (size == 1L) { raf.readFully(head, 8, 8); size = u64(head, 8) }
                    if (size == 0L) size = len - p
                    if (size < 8) break
                    if (type == "moov") {
                        if (size > 64L * 1024 * 1024) throw Unsupported("moov too large")
                        val box = ByteArray(size.toInt())
                        raf.seek(p); raf.readFully(box)
                        pending.addAll(parseMoov(box, if (u32(box, 0) == 1L) 16 else 8))
                        moovBytes = box
                    }
                    p += size
                }
            }
            val moov = moovBytes ?: throw Unsupported("no moov in file")
            java.io.FileInputStream(file).buffered().use { inp ->
                var position = startPosition
                while (true) {
                    val header = ByteArray(8)
                    val n = readUpTo(inp, header, 0, 8)
                    if (n < 8) { if (n > 0) output.write(header, 0, n); break }
                    var size = u32(header, 0)
                    val type = String(header, 4, 4, Charsets.ISO_8859_1)
                    var hb = header
                    if (size == 1L) { val l = readFully(inp, 8); size = u64(l, 0); hb = header + l }
                    if (size == 0L) size = Long.MAX_VALUE
                    val payload = if (size == Long.MAX_VALUE) Long.MAX_VALUE else size - hb.size
                    when (type) {
                        "moov" -> { skip(inp, payload); output.write(moov) }
                        "mdat" -> {
                            output.write(hb)
                            val from = position + hb.size
                            streamPayload(inp, output, from, if (payload == Long.MAX_VALUE) Long.MAX_VALUE else from + payload, pending)
                        }
                        else -> { output.write(hb); copy(inp, output, payload) }
                    }
                    if (size == Long.MAX_VALUE) break
                    position += size
                }
            }
            output.flush()
        } finally {
            file.delete()
        }
    }

    // ------------------------------------------------------------------ moov

    /** Rewrites the moov in place; returns the samples of a non-fragmented file's sample tables. */
    private fun parseMoov(box: ByteArray, start: Int): List<Sample> {
        val samples = ArrayList<Sample>()
        walk(box, start, box.size) { type, at, end, hdr ->
            when (type) {
                "trak" -> samples.addAll(parseTrak(box, at + hdr, end))
                "mvex" -> walk(box, at + hdr, end) { t, a, e, h ->
                    if (t == "trex" && e - a >= h + 24) {
                        val p = a + h + 4
                        trexDefaultSize[u32(box, p).toInt()] = u32(box, p + 12).toInt()
                    }
                }
                "pssh" -> rename(box, at, "free")
            }
        }
        return samples
    }

    private fun parseTrak(box: ByteArray, start: Int, end: Int): List<Sample> {
        var trackId = -1
        var samples: List<Sample> = emptyList()
        walk(box, start, end) { type, at, e, hdr ->
            when (type) {
                "tkhd" -> {
                    val version = box[at + hdr].toInt() and 0xff
                    trackId = u32(box, at + hdr + 4 + if (version == 1) 16 else 8).toInt()
                }
                "mdia" -> findStbl(box, at + hdr, e) { stbl, stblEnd, stblHdr ->
                    samples = parseStbl(box, stbl + stblHdr, stblEnd, trackId)
                }
            }
        }
        return samples
    }

    private fun findStbl(box: ByteArray, start: Int, end: Int, found: (Int, Int, Int) -> Unit) {
        walk(box, start, end) { type, at, e, hdr ->
            when (type) {
                "minf" -> findStbl(box, at + hdr, e, found)
                "stbl" -> found(at, e, hdr)
            }
        }
    }

    /**
     * Sample description, and for a non-fragmented file the sample table: sizes (stsz), chunk
     * offsets (stco/co64), samples per chunk (stsc) and the per-sample IVs (senc in the stbl).
     */
    private fun parseStbl(box: ByteArray, start: Int, end: Int, trackId: Int): List<Sample> {
        var sizes: IntArray? = null
        var chunkOffsets: LongArray? = null
        var stsc: IntArray? = null
        var sencAt = -1
        walk(box, start, end) { type, at, e, hdr ->
            val p = at + hdr
            when (type) {
                "stsd" -> parseStsd(box, p, e)?.let { info -> if (trackId >= 0) tracks[trackId] = info }
                "stsz" -> {
                    val fixed = u32(box, p + 4).toInt()
                    val count = u32(box, p + 8).toInt()
                    sizes = IntArray(count) { i -> if (fixed != 0) fixed else u32(box, p + 12 + i * 4).toInt() }
                }
                "stco" -> { val n = u32(box, p + 4).toInt(); chunkOffsets = LongArray(n) { i -> u32(box, p + 8 + i * 4) } }
                "co64" -> { val n = u32(box, p + 4).toInt(); chunkOffsets = LongArray(n) { i -> u64(box, p + 8 + i * 8) } }
                "stsc" -> { val n = u32(box, p + 4).toInt(); stsc = IntArray(n * 3) { i -> u32(box, p + 8 + i * 4).toInt() } }
                "senc" -> sencAt = at
                "saiz", "saio" -> rename(box, at, "free")
            }
        }
        if (sencAt < 0 || tracks[trackId] == null) return emptyList()
        val hdr = if (u32(box, sencAt) == 1L) 16 else 8
        val ivs = parseSenc(box, sencAt + hdr, trackId)
        rename(box, sencAt, "free")
        val sz = sizes ?: return emptyList()
        val offsets = chunkOffsets ?: return emptyList()
        val runs = stsc ?: return emptyList()
        val out = ArrayList<Sample>(sz.size)
        var sample = 0
        for (chunk in offsets.indices) {
            // stsc: (first_chunk, samples_per_chunk, description) runs, chunks numbered from 1.
            var perChunk = 0
            var r = 0
            while (r < runs.size) { if (runs[r] <= chunk + 1) perChunk = runs[r + 1]; r += 3 }
            var offset = offsets[chunk]
            repeat(perChunk) {
                if (sample >= sz.size) return@repeat
                val entry = ivs.getOrNull(sample)
                val iv = entry?.first?.takeIf { it.isNotEmpty() } ?: tracks[trackId]?.constantIv ?: ByteArray(16)
                out.add(Sample(trackId, offset, sz[sample], iv, entry?.second))
                offset += sz[sample]
                sample++
            }
        }
        return out
    }

    /** stsd payload: fullbox(4) + entry_count(4) + sample entries. */
    private fun parseStsd(box: ByteArray, start: Int, end: Int): TrackInfo? {
        var info: TrackInfo? = null
        walk(box, start + 8, end) { type, at, e, hdr ->
            if (type != "enca" && type != "encv") return@walk
            // SampleEntry (8) + AudioSampleEntry (20); QuickTime v1/v2 sound descriptions are longer.
            val qtVersion = ((box[at + hdr + 8].toInt() and 0xff) shl 8) or (box[at + hdr + 9].toInt() and 0xff)
            val childStart = at + hdr + when {
                type == "encv" -> 78
                qtVersion == 1 -> 28 + 16
                qtVersion == 2 -> 28 + 36
                else -> 28
            }
            walk(box, childStart, e) { t, a, se, h ->
                if (t != "sinf") return@walk
                var format: String? = null
                var scheme = "cenc"
                var tenc: TrackInfo? = null
                walk(box, a + h, se) { st, sa, sEnd, sh ->
                    when (st) {
                        "frma" -> format = String(box, sa + sh, 4, Charsets.ISO_8859_1)
                        "schm" -> scheme = String(box, sa + sh + 4, 4, Charsets.ISO_8859_1)
                        "schi" -> walk(box, sa + sh, sEnd) { tt, ta, _, th ->
                            if (tt == "tenc") tenc = parseTenc(box, ta + th)
                        }
                    }
                }
                val t0 = tenc ?: throw Unsupported("protected track without tenc")
                info = TrackInfo(scheme, t0.ivSize, t0.constantIv, t0.cryptBlocks, t0.skipBlocks)
                format?.let { rename(box, at, it) }
                rename(box, a, "free")
            }
        }
        return info
    }

    private fun parseTenc(box: ByteArray, p: Int): TrackInfo {
        val version = box[p].toInt() and 0xff
        var q = p + 4 + 1 // fullbox header, reserved
        var crypt = 0
        var skip = 0
        if (version > 0) {
            val pattern = box[q].toInt() and 0xff
            crypt = pattern shr 4
            skip = pattern and 0x0f
        }
        q += 1
        val isProtected = box[q].toInt() and 0xff
        val ivSize = box[q + 1].toInt() and 0xff
        q += 2 + 16 // KID
        var constantIv: ByteArray? = null
        if (isProtected == 1 && ivSize == 0) {
            val n = box[q].toInt() and 0xff
            constantIv = box.copyOfRange(q + 1, q + 1 + n)
        }
        return TrackInfo("", ivSize, constantIv, crypt, skip)
    }

    // ------------------------------------------------------------------ moof

    private val piffSampleEncryption = byteArrayOf(
        0xa2.toByte(), 0x39, 0x4f, 0x52, 0x5a, 0x9b.toByte(), 0x4f, 0x14,
        0xa2.toByte(), 0x44, 0x6c, 0x42, 0x7c, 0x64, 0x8d.toByte(), 0xf4.toByte(),
    )

    private fun parseMoof(box: ByteArray, start: Int, moofStart: Long): List<Sample> {
        val out = ArrayList<Sample>()
        walk(box, start, box.size) { type, at, end, hdr ->
            if (type == "traf") out.addAll(parseTraf(box, at + hdr, end, moofStart))
        }
        return out
    }

    private fun parseTraf(box: ByteArray, start: Int, end: Int, moofStart: Long): List<Sample> {
        var trackId = -1
        var base = moofStart
        var defaultSize = 0
        val runs = ArrayList<Pair<Long?, IntArray>>() // data offset (relative to base) and sample sizes
        var ivs: List<Pair<ByteArray, IntArray?>>? = null
        walk(box, start, end) { type, at, e, hdr ->
            val p = at + hdr
            when (type) {
                "tfhd" -> {
                    val flags = u24(box, p + 1)
                    trackId = u32(box, p + 4).toInt()
                    defaultSize = trexDefaultSize[trackId] ?: 0
                    var q = p + 8
                    if (flags and 0x1 != 0) { base = u64(box, q); q += 8 }
                    if (flags and 0x2 != 0) q += 4
                    if (flags and 0x8 != 0) q += 4
                    if (flags and 0x10 != 0) { defaultSize = u32(box, q).toInt(); q += 4 }
                }
                "trun" -> {
                    val flags = u24(box, p + 1)
                    val count = u32(box, p + 4).toInt()
                    var q = p + 8
                    var dataOffset: Long? = null
                    if (flags and 0x1 != 0) { dataOffset = s32(box, q).toLong(); q += 4 }
                    if (flags and 0x4 != 0) q += 4
                    val sizes = IntArray(count)
                    for (i in 0 until count) {
                        if (flags and 0x100 != 0) q += 4
                        sizes[i] = if (flags and 0x200 != 0) u32(box, q).toInt().also { q += 4 } else defaultSize
                        if (flags and 0x400 != 0) q += 4
                        if (flags and 0x800 != 0) q += 4
                    }
                    runs.add(dataOffset to sizes)
                }
                "senc" -> { ivs = parseSenc(box, p, trackId); rename(box, at, "free") }
                "uuid" -> if (e - p >= 16 && box.copyOfRange(p, p + 16).contentEquals(piffSampleEncryption)) {
                    ivs = parseSenc(box, p + 16, trackId)
                    rename(box, at, "free")
                }
                "saiz", "saio" -> rename(box, at, "free")
            }
        }
        val info = tracks[trackId] ?: return emptyList()
        val sampleIvs = ivs ?: if (info.constantIv != null) null else throw Unsupported("encrypted fragment without senc")
        val out = ArrayList<Sample>()
        var cursor = base
        var index = 0
        for ((offset, sizes) in runs) {
            if (offset != null) cursor = base + offset
            for (size in sizes) {
                val entry = sampleIvs?.getOrNull(index)
                val iv = entry?.first?.takeIf { it.isNotEmpty() } ?: info.constantIv ?: ByteArray(16)
                out.add(Sample(trackId, cursor, size, iv, entry?.second))
                cursor += size
                index++
            }
        }
        return out
    }

    private fun parseSenc(box: ByteArray, p: Int, trackId: Int): List<Pair<ByteArray, IntArray?>> {
        val info = tracks[trackId]
        val flags = u24(box, p + 1)
        val count = u32(box, p + 4).toInt()
        var q = p + 8
        val ivSize = info?.ivSize ?: 8
        val out = ArrayList<Pair<ByteArray, IntArray?>>(count)
        for (i in 0 until count) {
            val iv = box.copyOfRange(q, q + ivSize)
            q += ivSize
            var subs: IntArray? = null
            if (flags and 0x2 != 0) {
                val n = u16(box, q); q += 2
                subs = IntArray(n * 2)
                for (j in 0 until n) {
                    subs[j * 2] = u16(box, q)
                    subs[j * 2 + 1] = u32(box, q + 2).toInt()
                    q += 6
                }
            }
            out.add(iv to subs)
        }
        return out
    }

    // ------------------------------------------------------------------ mdat

    /** Copies mdat bytes from [from] to [until], decrypting the samples that fall inside. */
    private fun streamPayload(input: InputStream, output: OutputStream, from: Long, until: Long, pending: MutableList<Sample>): Long {
        var position = from
        pending.sortBy { it.offset }
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val sample = iterator.next()
            if (sample.offset >= until) break
            if (sample.offset < position) { iterator.remove(); continue }
            val gap = sample.offset - position
            if (gap > 0) { copy(input, output, gap); position += gap }
            val data = ByteArray(sample.size)
            val got = readUpTo(input, data, 0, sample.size)
            decrypt(sample, data, got)
            output.write(data, 0, got)
            position += got
            iterator.remove()
            if (got < sample.size) return position
        }
        if (until == Long.MAX_VALUE) {
            position += copy(input, output, Long.MAX_VALUE)
        } else if (until > position) {
            copy(input, output, until - position)
            position = until
        }
        return position
    }

    private fun decrypt(sample: Sample, data: ByteArray, length: Int) {
        val info = tracks[sample.trackId] ?: return
        val ranges = protectedRanges(sample, length)
        when (info.scheme) {
            "cenc" -> {
                val iv = ByteArray(16)
                System.arraycopy(sample.iv, 0, iv, 0, minOf(16, sample.iv.size))
                val cipher = Cipher.getInstance("AES/CTR/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                // One keystream runs across all protected ranges of the sample.
                for ((at, len) in ranges) {
                    if (len <= 0) continue
                    val plain = cipher.update(data, at, len)
                    if (plain != null) System.arraycopy(plain, 0, data, at, plain.size)
                }
            }
            "cbcs" -> {
                val iv = ByteArray(16)
                System.arraycopy(sample.iv, 0, iv, 0, minOf(16, sample.iv.size))
                val crypt = info.cryptBlocks
                val skip = info.skipBlocks
                for ((at, len) in ranges) {
                    // The CBC chain restarts at every protected range with the constant IV.
                    val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                    var p = at
                    val stop = at + len
                    while (stop - p >= 16) {
                        val blocks = if (crypt == 0 && skip == 0) (stop - p) / 16 else minOf(crypt, (stop - p) / 16)
                        if (blocks > 0) {
                            val plain = cipher.update(data, p, blocks * 16)
                            if (plain != null) System.arraycopy(plain, 0, data, p, plain.size)
                            p += blocks * 16
                        }
                        if (crypt == 0 && skip == 0) break
                        p += skip * 16
                    }
                }
            }
            else -> throw Unsupported("encryption scheme ${info.scheme} is not supported")
        }
        decryptedSamples++
    }

    /** (start, length) of the encrypted parts of a sample. */
    private fun protectedRanges(sample: Sample, length: Int): List<Pair<Int, Int>> {
        val subs = sample.subsamples ?: return listOf(0 to length)
        val out = ArrayList<Pair<Int, Int>>()
        var p = 0
        var i = 0
        while (i < subs.size) {
            p += subs[i]
            val enc = subs[i + 1]
            if (p >= length) break
            out.add(p to minOf(enc, length - p))
            p += enc
            i += 2
        }
        return out
    }

    // ------------------------------------------------------------------ helpers

    private fun walk(box: ByteArray, start: Int, end: Int, visit: (type: String, at: Int, end: Int, headerLen: Int) -> Unit) {
        var p = start
        while (p + 8 <= end) {
            var size = u32(box, p)
            var hdr = 8
            if (size == 1L) { size = u64(box, p + 8); hdr = 16 }
            if (size == 0L) size = (end - p).toLong()
            if (size < hdr || p + size > end) return
            val type = String(box, p + 4, 4, Charsets.ISO_8859_1)
            visit(type, p, (p + size).toInt(), hdr)
            p += size.toInt()
        }
    }

    private fun rename(box: ByteArray, at: Int, type: String) {
        val bytes = type.toByteArray(Charsets.ISO_8859_1)
        System.arraycopy(bytes, 0, box, at + 4, 4)
    }

    private fun readUpTo(input: InputStream, buf: ByteArray, off: Int, len: Int): Int {
        var n = 0
        while (n < len) {
            val r = input.read(buf, off + n, len - n)
            if (r < 0) break
            n += r
        }
        return n
    }

    private fun readFully(input: InputStream, len: Int): ByteArray = ByteArray(len).also { readFully(input, it, 0, len) }

    private fun readFully(input: InputStream, buf: ByteArray, off: Int, len: Int) {
        if (readUpTo(input, buf, off, len) < len) throw EOFException("stream ended inside a box")
    }

    private fun skip(input: InputStream, count: Long) {
        val buf = ByteArray(64 * 1024)
        var left = count
        while (left > 0) {
            val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) break
            left -= r
        }
    }

    private fun copy(input: InputStream, output: OutputStream, count: Long): Long {
        val buf = ByteArray(64 * 1024)
        var left = count
        var total = 0L
        while (left > 0) {
            val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) break
            output.write(buf, 0, r)
            left -= r
            total += r
        }
        return total
    }

    companion object {
        fun u16(b: ByteArray, p: Int): Int = ((b[p].toInt() and 0xff) shl 8) or (b[p + 1].toInt() and 0xff)
        fun u24(b: ByteArray, p: Int): Int = ((b[p].toInt() and 0xff) shl 16) or u16(b, p + 1)
        fun s32(b: ByteArray, p: Int): Int = (u16(b, p) shl 16) or u16(b, p + 2)
        fun u32(b: ByteArray, p: Int): Long = s32(b, p).toLong() and 0xffffffffL
        fun u64(b: ByteArray, p: Int): Long = (u32(b, p) shl 32) or u32(b, p + 4)

        /** _decryption_key_candidates(): hex (optionally 0x-prefixed or separated) or base64, 16 bytes. */
        fun parseKey(raw: String): ByteArray? {
            val trimmed = raw.trim().removePrefix("0x").removePrefix("0X")
            val hex = trimmed.filter { it.isLetterOrDigit() }
            if (hex.length == 32 && hex.all { it in "0123456789abcdefABCDEF" }) {
                return ByteArray(16) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
            }
            return base64(trimmed)?.takeIf { it.size == 16 }
        }

        /** Standard or URL-safe base64, padding optional (java.util.Base64 needs Android 8). */
        private fun base64(text: String): ByteArray? {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            val clean = text.filterNot { it.isWhitespace() || it == '=' }.replace('-', '+').replace('_', '/')
            if (clean.isEmpty() || clean.any { alphabet.indexOf(it) < 0 } || clean.length % 4 == 1) return null
            val out = java.io.ByteArrayOutputStream()
            var buffer = 0
            var bits = 0
            for (c in clean) {
                buffer = (buffer shl 6) or alphabet.indexOf(c)
                bits += 6
                if (bits >= 8) {
                    bits -= 8
                    out.write((buffer shr bits) and 0xff)
                }
            }
            return out.toByteArray()
        }
    }
}
