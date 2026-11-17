package com.watchuu.nonu

/**
 * Pure Kotlin port of am-alac/src/lib.rs + am-mp4/src/alac.rs —
 * ALAC end-tag repair for decrypted Apple Music fragments.
 *
 * FairPlay SAMPLE-AES strips the 3-bit END tag from uncompressed ALAC
 * packets during encryption. After decryption the samples are correct
 * but the missing END tag causes silence in many players. This module
 * detects the missing tag and writes it back without changing packet size.
 */
object AlacRepair {

    // ------------------------------------------------------------------ ALAC Config

    data class AlacConfig(
        val channels: Int,  // 1 (mono) or 2 (stereo)
        val bits: Int,      // 16, 20, 24 or 32
        val maxBlock: Int   // 1..65535
    ) {
        fun isValid(): Boolean =
            channels in 1..2 &&
            bits in intArrayOf(16, 20, 24, 32) &&
            maxBlock in 1..65535

        companion object {
            /**
             * Parse a 24-byte ALAC cookie (the body of the 'alac' box minus the
             * 4-byte FullBox header) into a Config, or null if invalid.
             */
            fun fromCookie(cookie: ByteArray): AlacConfig? {
                if (cookie.size != 24 || cookie[4] != 0.toByte()) return null
                val config = AlacConfig(
                    maxBlock = beU32(cookie, 0).toInt(),
                    bits = cookie[5].toInt() and 0xFF,
                    channels = cookie[9].toInt() and 0xFF
                )
                return if (config.isValid()) config else null
            }
        }

        /**
         * Calculate the bit position of the END tag for an ALAC packet.
         * Returns null if the packet is compressed, partial-but-too-short, or invalid.
         */
        private fun endBit(packet: ByteArray): Int? {
            if (!isValid() || packet.size < 3) return null

            val header = ((packet[0].toInt() and 0xFF) shl 16) or
                         ((packet[1].toInt() and 0xFF) shl 8) or
                          (packet[2].toInt() and 0xFF)

            // Check channel config: (header >> 21) must be 0 or 3 for mono, 1 for stereo
            val chanCfg = header ushr 21
            when {
                channels == 1 && chanCfg != 0 && chanCfg != 3 -> return null
                channels == 2 && chanCfg != 1 -> return null
            }
            // Reserved bits must be zero
            if ((header ushr 5) and 0xFFF != 0) return null
            if ((header ushr 2) and 3 != 0) return null
            if ((header ushr 1) and 1 != 1) return null

            val partial = (header ushr 4) and 1 != 0
            val samples: Int
            if (partial) {
                if (packet.size < 7) return null
                // 32-bit sample count encoded across bytes 2..6
                val word = ((packet[2].toLong() and 0xFF) shl 24) or
                           ((packet[3].toLong() and 0xFF) shl 16) or
                           ((packet[4].toLong() and 0xFF) shl 8) or
                           (packet[5].toLong() and 0xFF)
                samples = ((word shl 7) or ((packet[6].toInt() and 0xFF) ushr 1).toLong()).toInt()
            } else {
                samples = maxBlock
            }

            if (samples == 0 || samples > maxBlock) return null

            val end = 23 +
                (if (partial) 32 else 0) +
                samples * channels * bits

            val totalBits = packet.size * 8
            val remaining = totalBits - end
            if (remaining < 0 || remaining > 10) return null
            return end
        }

        /**
         * Length-preserving in-place repair. If the 3-bit END tag doesn't fit
         * or is already present, returns false and leaves the packet unchanged.
         */
        fun repairInPlace(packet: ByteArray): Boolean {
            val end = endBit(packet) ?: return false
            if (end + 3 > packet.size * 8 || hasEnd(packet, end)) return false
            writeEnd(packet, end)
            return true
        }
    }

    private fun hasEnd(packet: ByteArray, end: Int): Boolean {
        for (i in end until end + 3) {
            if (packet[i / 8].toInt() and (0x80 ushr (i % 8)) == 0) return false
        }
        return true
    }

    private fun writeEnd(packet: ByteArray, end: Int) {
        // Set 3 bits at positions end, end+1, end+2 to 1
        for (i in end until end + 3) {
            packet[i / 8] = (packet[i / 8].toInt() or (0x80 ushr (i % 8))).toByte()
        }
        // Clear all bits from end+3 to the end of the packet
        for (i in (end + 3) until (packet.size * 8)) {
            packet[i / 8] = (packet[i / 8].toInt() and (0x80 ushr (i % 8)).inv()).toByte()
        }
    }

    // ------------------------------------------------------------------ ALAC Track

    data class AlacTrack(
        val trackId: Int,
        val defaultDescription: Int,
        val configs: List<AlacConfig?>
    )

    // ------------------------------------------------------------------ box helpers (duplicated from Mp4Patcher for self-containment)

    private fun beU32(buf: ByteArray, pos: Int): Long {
        return ((buf[pos].toLong() and 0xFF) shl 24) or
               ((buf[pos + 1].toLong() and 0xFF) shl 16) or
               ((buf[pos + 2].toLong() and 0xFF) shl 8) or
               (buf[pos + 3].toLong() and 0xFF)
    }

    private data class BoxHeader(val typ: String, val start: Int, val body: Int, val end: Int)

    private fun children(buf: ByteArray, rangeStart: Int, rangeEnd: Int): List<BoxHeader> {
        val out = mutableListOf<BoxHeader>()
        var pos = rangeStart
        val end = minOf(rangeEnd, buf.size)
        while (pos + 8 <= end) {
            val size = beU32(buf, pos)
            val typ = String(buf, pos + 4, 4, Charsets.US_ASCII)
            val len: Long
            val header: Int
            when {
                size == 0L -> { len = (end - pos).toLong(); header = 8 }
                size == 1L -> {
                    if (pos + 16 > end) break
                    len = ((buf[pos + 8].toLong() and 0xFF) shl 56) or
                          ((buf[pos + 9].toLong() and 0xFF) shl 48) or
                          ((buf[pos + 10].toLong() and 0xFF) shl 40) or
                          ((buf[pos + 11].toLong() and 0xFF) shl 32) or
                          ((buf[pos + 12].toLong() and 0xFF) shl 24) or
                          ((buf[pos + 13].toLong() and 0xFF) shl 16) or
                          ((buf[pos + 14].toLong() and 0xFF) shl 8) or
                          (buf[pos + 15].toLong() and 0xFF)
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

    // ------------------------------------------------------------------ parse init segment for ALAC track

    /**
     * Parse the init segment (ftyp + moov) to find a single-track ALAC configuration.
     * Works both before and after encryption-box sanitizing.
     */
    fun parseAlacTrack(initData: ByteArray): AlacTrack? {
        val moov = find(initData, 0, initData.size, "moov") ?: return null
        val tracks = children(initData, moov.body, moov.end).filter { it.typ == "trak" }
        if (tracks.size != 1) return null

        val trak = tracks[0]

        // Read track_id from tkhd
        val tkhd = find(initData, trak.body, trak.end, "tkhd") ?: return null
        val version = initData.getOrNull(tkhd.body)?.toInt()?.and(0xFF) ?: return null
        val offset = when (version) {
            0 -> 12
            1 -> 20
            else -> return null
        }
        if (tkhd.body + offset + 4 > tkhd.end) return null
        val trackId = beU32(initData, tkhd.body + offset).toInt()

        // Navigate to stsd
        val mdia = find(initData, trak.body, trak.end, "mdia") ?: return null
        val minf = find(initData, mdia.body, mdia.end, "minf") ?: return null
        val stbl = find(initData, minf.body, minf.end, "stbl") ?: return null
        val stsd = find(initData, stbl.body, stbl.end, "stsd") ?: return null
        if (stsd.body + 8 > stsd.end) return null

        val entries = children(initData, stsd.body + 8, stsd.end)
        val entryCount = beU32(initData, stsd.body + 4).toInt()
        if (entries.size != entryCount) return null

        val configs = entries.map { entry -> entryConfig(initData, entry) }
        if (configs.none { it != null }) return null

        // Default description index
        var defaultDescription = if (configs.size == 1) 1 else 0
        find(initData, moov.body, moov.end, "mvex")?.let { mvex ->
            for (trex in children(initData, mvex.body, mvex.end).filter { it.typ == "trex" }) {
                if (trex.body + 12 <= trex.end && beU32(initData, trex.body + 4).toInt() == trackId) {
                    defaultDescription = beU32(initData, trex.body + 8).toInt()
                }
            }
        }

        return AlacTrack(trackId, defaultDescription, configs)
    }

    /**
     * Check if a sample entry is ALAC (directly or via enca+sinf+frma) and
     * parse the ALAC cookie if so.
     */
    private fun entryConfig(init: ByteArray, entry: BoxHeader): AlacConfig? {
        // SampleEntry(8) + AudioSampleEntry(20) = 28 bytes of fields before child boxes
        if (entry.body + 28 > entry.end) return null
        // data_reference_index check: bytes 8..10 of the body
        if (init[entry.body + 8] != 0.toByte() || init[entry.body + 9] != 0.toByte()) return null

        val isAlac = entry.typ == "alac"
        if (!isAlac) {
            if (entry.typ != "enca") return null
            // For encrypted entries, check sinf → frma for original codec
            val sinf = find(init, entry.body + 28, entry.end, "sinf") ?: return null
            val frma = find(init, sinf.body, sinf.end, "frma") ?: return null
            if (frma.body + 4 > frma.end) return null
            val original = String(init, frma.body, 4, Charsets.US_ASCII)
            if (original != "alac") return null
        }

        // Find the alac cookie box
        val cookie = find(init, entry.body + 28, entry.end, "alac") ?: return null
        if (cookie.end - cookie.body != 28) return null  // FullBox(4) + 24-byte cookie
        return AlacConfig.fromCookie(init.copyOfRange(cookie.body + 4, cookie.end))
    }

    // ------------------------------------------------------------------ repair fragment

    /**
     * Repair ALAC samples in a decrypted fragment. The fragment must have already
     * been decrypted and its DRM boxes renamed.
     * Returns the number of samples actually repaired.
     */
    fun repairAlacFragment(track: AlacTrack, fragData: ByteArray): Int {
        val top = children(fragData, 0, fragData.size)

        // Collect (sampleRange, config) pairs
        data class SampleInfo(val start: Int, val end: Int, val config: AlacConfig)
        val samples = mutableListOf<SampleInfo>()

        for (moof in top.filter { it.typ == "moof" }) {
            for (traf in children(fragData, moof.body, moof.end).filter { it.typ == "traf" }) {
                val tfhd = find(fragData, traf.body, traf.end, "tfhd") ?: continue
                val r = TfhdReader(fragData, tfhd)
                if (r.trackId != track.trackId) continue
                if (r.hasBaseDataOffset) {
                    throw IllegalStateException("tfhd base-data-offset is not supported")
                }

                val description = r.sampleDescriptionIndex ?: track.defaultDescription
                val configIdx = description - 1
                if (configIdx < 0 || configIdx >= track.configs.size) continue
                val config = track.configs[configIdx] ?: continue

                // Parse traf to get sample ranges
                val ranges = parseTrafRanges(fragData, moof, traf)
                for (range in ranges) {
                    samples.add(SampleInfo(range.first, range.last + 1, config))
                }
            }
        }

        // Validate: samples must be in mdat and non-overlapping
        var previous = 0
        for (s in samples) {
            val inMdat = top.any { it.typ == "mdat" && s.start >= it.body && s.end <= it.end }
            if (s.start < previous || !inMdat) {
                throw IllegalStateException(
                    "ALAC sample outside mdat or overlapping another sample"
                )
            }
            previous = s.end
        }

        var repaired = 0
        for (s in samples) {
            val packet = fragData.copyOfRange(s.start, s.end)
            if (s.config.repairInPlace(packet)) {
                packet.copyInto(fragData, s.start)
                repaired++
            }
        }
        return repaired
    }

    /**
     * Parse trun boxes in a traf to extract sample byte ranges.
     * Simplified version of parseTraf that only collects ranges.
     */
    private fun parseTrafRanges(src: ByteArray, moof: BoxHeader, traf: BoxHeader): List<IntRange> {
        val samples = mutableListOf<IntRange>()
        var defaultSize: Int? = null
        var nextData = moof.start

        for (b in children(src, traf.body, traf.end)) {
            when (b.typ) {
                "tfhd" -> {
                    val flags = readFullBoxFlags(src, b)
                    var pos = b.body + 4 + 4 // skip version/flags + track_id
                    if (flags and 0x01 != 0) return emptyList() // base-data-offset not supported
                    if (flags and 0x02 != 0) pos += 4 // sample_description_index
                    if (flags and 0x08 != 0) pos += 4 // default_sample_duration
                    if (flags and 0x10 != 0 && pos + 4 <= b.end) {
                        defaultSize = beU32(src, pos).toInt()
                    }
                }
                "trun" -> {
                    val flags = readFullBoxFlags(src, b)
                    var rp = b.body + 4 // skip version/flags
                    if (rp + 4 > b.end) continue
                    val count = beU32(src, rp).toInt(); rp += 4

                    var pos = nextData
                    if (flags and 0x01 != 0) {
                        if (rp + 4 > b.end) continue
                        val off = beU32(src, rp).toInt(); rp += 4
                        pos = moof.start + off
                    }
                    if (flags and 0x04 != 0) rp += 4 // first_sample_flags
                    if (count > src.size) continue

                    for (i in 0 until count) {
                        if (flags and 0x100 != 0) rp += 4
                        val size = if (flags and 0x200 != 0) {
                            if (rp + 4 > b.end) break
                            val s = beU32(src, rp).toInt(); rp += 4; s
                        } else {
                            defaultSize ?: break
                        }
                        if (flags and 0x400 != 0) rp += 4
                        if (flags and 0x800 != 0) rp += 4
                        samples.add(pos until (pos + size))
                        pos += size
                    }
                    nextData = pos
                }
            }
        }
        return samples
    }

    private fun readFullBoxFlags(buf: ByteArray, b: BoxHeader): Int {
        if (b.body + 4 > b.end) return 0
        return beU32(buf, b.body).toInt() and 0x00FFFFFF
    }

    /** Minimal reader for tfhd to extract track_id, flags, and sample_description_index. */
    private class TfhdReader(buf: ByteArray, tfhd: BoxHeader) {
        val flags: Int
        val trackId: Int
        val hasBaseDataOffset: Boolean
        val sampleDescriptionIndex: Int?

        init {
            var pos = tfhd.body
            val vf = beU32(buf, pos).toInt(); pos += 4
            flags = vf and 0x00FFFFFF
            trackId = beU32(buf, pos).toInt(); pos += 4
            hasBaseDataOffset = flags and 0x01 != 0
            sampleDescriptionIndex = if (flags and 0x02 != 0 && pos + 4 <= tfhd.end) {
                beU32(buf, pos).toInt()
            } else null
        }
    }
}
