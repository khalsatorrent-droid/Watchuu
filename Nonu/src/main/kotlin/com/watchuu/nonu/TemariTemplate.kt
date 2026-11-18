package com.watchuu.nonu

/**
 * Pure-Kotlin port of temari/template.rs — parse JSON template into a
 * [TemariCipher.Template], plus binary prefetch-template loader.
 */
object TemariTemplate {

    private const val CTX_SIZE = TemariCipher.CTX_SIZE    // 0x8000
    private const val ST_SIZE  = TemariCipher.ST_SIZE     // 2048

    // ---------------------------------------------------------------------------
    // ST_SLOTS — the 263 state-slot offsets (u16 hex in the Rust source)
    // ---------------------------------------------------------------------------
    private val ST_SLOTS = intArrayOf(
        0x30, 0x40, 0x48, 0x50, 0x58, 0x5a, 0x60, 0x68, 0x70, 0x72, 0x78, 0x80, 0x88, 0x90, 0x96,
        0x98, 0xa0, 0xb0, 0xc0, 0xc8, 0xd0, 0xd8, 0xe0, 0xe8, 0xf0, 0xf8, 0x100, 0x104, 0x108,
        0x112, 0x118, 0x120, 0x130, 0x144, 0x150, 0x152, 0x158, 0x160, 0x168, 0x170, 0x178,
        0x180, 0x188, 0x190, 0x192, 0x198, 0x1a0, 0x1a8, 0x1b0, 0x1b8, 0x1c0, 0x200, 0x208,
        0x210, 0x216, 0x218, 0x220, 0x224, 0x228, 0x230, 0x232, 0x238, 0x240, 0x248, 0x250,
        0x256, 0x258, 0x260, 0x264, 0x268, 0x270, 0x280, 0x288, 0x290, 0x298, 0x2a0, 0x2a8,
        0x2b0, 0x2b8, 0x2c0, 0x2c8, 0x2d0, 0x2d8, 0x2e0, 0x2e8, 0x2f0, 0x2f8, 0x300, 0x304,
        0x308, 0x310, 0x318, 0x320, 0x328, 0x330, 0x336, 0x338, 0x340, 0x344, 0x348, 0x350,
        0x352, 0x358, 0x360, 0x368, 0x370, 0x376, 0x378, 0x380, 0x384, 0x388, 0x390, 0x392,
        0x398, 0x3b0, 0x3b8, 0x3c0, 0x3c8, 0x3d0, 0x3e0, 0x3e8, 0x3f8, 0x400, 0x408, 0x410,
        0x416, 0x418, 0x420, 0x424, 0x428, 0x430, 0x432, 0x440, 0x448, 0x450, 0x460, 0x468,
        0x470, 0x478, 0x480, 0x488, 0x490, 0x4d8, 0x4e0, 0x4e8, 0x4f0, 0x4f8, 0x500, 0x508,
        0x510, 0x512, 0x518, 0x520, 0x528, 0x530, 0x536, 0x538, 0x540, 0x548, 0x550, 0x552,
        0x558, 0x560, 0x568, 0x570, 0x576, 0x578, 0x580, 0x584, 0x588, 0x590, 0x592, 0x598,
        0x5a0, 0x5a8, 0x5b0, 0x5b8, 0x5c0, 0x5c8, 0x5d8, 0x5e8, 0x5f0, 0x5f8, 0x600, 0x608,
        0x610, 0x616, 0x618, 0x620, 0x624, 0x628, 0x638, 0x640, 0x648, 0x656, 0x658, 0x660,
        0x664, 0x668, 0x670, 0x672, 0x678, 0x680, 0x688, 0x690, 0x696, 0x698, 0x6a8, 0x6b0,
        0x6b8, 0x6c0, 0x6c8, 0x6d8, 0x6e0, 0x6e8, 0x6f0, 0x6f8, 0x700, 0x708, 0x720, 0x728,
        0x736, 0x740, 0x752, 0x760, 0x768, 0x770, 0x778, 0x780, 0x792, 0x816, 0x824, 0x872,
        0x912, 0x944, 0x952, 0x960, 0x992, 0x1040, 0x1088, 0x1328, 0x1360, 0x1368, 0x1384,
        0x1408, 0x1416, 0x1424, 0x1432, 0x1440, 0x1448, 0x1456, 0x1480, 0x1512, 0x1528,
        0x1536, 0x1552, 0x1560, 0x1568, 0x1576, 0x1632, 0x1704, 0x1720, 0x1760
    )

    // ---------------------------------------------------------------------------
    // helpers: LE u32
    // ---------------------------------------------------------------------------
    private fun readU32LE(data: ByteArray, off: Int): Int {
        return (data[off].toInt() and 0xFF) or
               ((data[off + 1].toInt() and 0xFF) shl 8) or
               ((data[off + 2].toInt() and 0xFF) shl 16) or
               ((data[off + 3].toInt() and 0xFF) shl 24)
    }

    // ---------------------------------------------------------------------------
    // base64 decode — delegates to Android's built-in decoder
    // ---------------------------------------------------------------------------
    private fun base64Decode(s: String): ByteArray {
        return android.util.Base64.decode(s, android.util.Base64.DEFAULT)
    }

    // ---------------------------------------------------------------------------
    // minimal flat JSON parser
    // ---------------------------------------------------------------------------
    /**
     * Parse a flat JSON object `{ "key": "value", ... }`. Returns a list of
     * (key, value) pairs with both sides unquoted.
     */
    private fun parseFlatJson(input: String): List<Pair<String, String>> {
        val b = input.toByteArray(Charsets.UTF_8)
        var i = 0
        val n = b.size
        val result = mutableListOf<Pair<String, String>>()

        // skip to first '{'
        while (i < n && b[i] != '{'.code.toByte()) i++
        i++ // consume '{'

        while (i < n) {
            // skip whitespace, commas, braces
            while (i < n) {
                val c = b[i]
                if (c == ' '.code.toByte() || c == '\t'.code.toByte() || c == '\n'.code.toByte() ||
                    c == '\r'.code.toByte() || c == ','.code.toByte() || c == '{'.code.toByte() ||
                    c == '}'.code.toByte()) {
                    i++
                } else break
            }
            if (i >= n) break

            // read key (must start with '"')
            if (b[i] != '"'.code.toByte()) break
            i++
            val key = StringBuilder()
            while (i < n && b[i] != '"'.code.toByte()) {
                if (b[i] == '\\'.code.toByte() && i + 1 < n) {
                    i++
                    when (b[i].toInt().toChar()) {
                        '"' -> key.append('"')
                        '\\' -> key.append('\\')
                        '/' -> key.append('/')
                        'n' -> key.append('\n')
                        't' -> key.append('\t')
                        'u' -> {
                            if (i + 4 < n) {
                                val hex = String(b, i + 1, 4, Charsets.UTF_8)
                                val cp = hex.toIntOrNull(16)
                                if (cp != null) {
                                    key.append(cp.toChar())
                                }
                                i += 4
                            }
                        }
                        else -> {}
                    }
                } else {
                    key.append(b[i].toInt().toChar())
                }
                i++
            }
            i++ // consume closing '"'

            // skip to ':'
            while (i < n && b[i] != ':'.code.toByte()) i++
            i++ // consume ':'

            // skip whitespace
            while (i < n && (b[i] == ' '.code.toByte() || b[i] == '\t'.code.toByte() ||
                             b[i] == '\n'.code.toByte() || b[i] == '\r'.code.toByte())) i++

            // read value
            val value: String
            if (i < n && b[i] == '"'.code.toByte()) {
                i++
                val v = StringBuilder()
                while (i < n && b[i] != '"'.code.toByte()) {
                    if (b[i] == '\\'.code.toByte() && i + 1 < n) {
                        i++
                        when (b[i].toInt().toChar()) {
                            '"' -> v.append('"')
                            '\\' -> v.append('\\')
                            '/' -> v.append('/')
                            'n' -> v.append('\n')
                            't' -> v.append('\t')
                            'u' -> {
                                if (i + 4 < n) {
                                    val hex = String(b, i + 1, 4, Charsets.UTF_8)
                                    val cp = hex.toIntOrNull(16)
                                    if (cp != null) {
                                        v.append(cp.toChar())
                                    }
                                    i += 4
                                }
                            }
                            else -> {}
                        }
                    } else {
                        v.append(b[i].toInt().toChar())
                    }
                    i++
                }
                i++ // consume closing '"'
                value = v.toString()
            } else {
                // bare value (number / hex / null / true)
                val start = i
                while (i < n) {
                    val c = b[i]
                    if (c == ' '.code.toByte() || c == '\t'.code.toByte() || c == '\n'.code.toByte() ||
                        c == '\r'.code.toByte() || c == ','.code.toByte() || c == '}'.code.toByte()) break
                    i++
                }
                value = String(b, start, i - start, Charsets.UTF_8)
            }
            result.add(key.toString() to value)
        }
        return result
    }

    // ---------------------------------------------------------------------------
    // hex parser
    // ---------------------------------------------------------------------------
    private fun parseHex(s: String): Int {
        val trimmed = s.trim().removePrefix("0x").removePrefix("0X")
        // Accept up to u64, truncate to low 32 bits
        val v = trimmed.toLongOrNull(16) ?: 0L
        return v.toInt()
    }

    // ---------------------------------------------------------------------------
    // template_from_json — build Template from key-server JSON
    // ---------------------------------------------------------------------------
    /**
     * Parse a JSON template response (from the key server) into a [TemariCipher.Template].
     *
     * The server returns one of two formats:
     *
     * 1. **contentKey format** (current 1412 proxy):
     * ```json
     * { "adamId": "...", "keyUri": "...", "contentKey": "<base64 of binary template>" }
     * ```
     * where `contentKey` decodes to 40 980 bytes in the same layout as prefetch_template.bin:
     * ctx[0x8000] | st[2048×4 LE] | rdx u32 | rcx u32 | rax u32 | r9 u32 | rbp u32
     *
     * 2. **Split-field format** (am-hook legacy):
     * ```json
     * { "ctx": "<b64>", "state": "<b64>",
     *   "rcx": "0x..", "rax": "0x..", "rdx": "0x..", "r9": "0x..", "rbp": "0x.." }
     * ```
     */
    fun templateFromJson(json: String): TemariCipher.Template {
        val fields = parseFlatJson(json)
        val map = HashMap<String, String>()
        for ((k, v) in fields) map[k] = v

        android.util.Log.d("TemariTemplate", "parsed keys: ${map.keys}")

        // Format 1: contentKey — a single base64 blob identical to prefetch_template.bin
        val contentKey = map["contentKey"]
        if (contentKey != null && contentKey.length > 100) {
            android.util.Log.d("TemariTemplate", "contentKey found, length=${contentKey.length}")
            val binary = base64Decode(contentKey)
            android.util.Log.d("TemariTemplate", "decoded binary size=${binary.size}, required=${CTX_SIZE + ST_SIZE * 4 + 20}")
            return prefetchTemplate(binary)
        }

        // Format 2: separate ctx / state / register fields
        val ctxB64   = map["ctx"]   ?: throw IllegalArgumentException("no ctx field (and no contentKey)")
        val stateB64 = map["state"] ?: throw IllegalArgumentException("no state field")
        val rcx = parseHex(map["rcx"] ?: "0x0")
        val rax = parseHex(map["rax"] ?: "0x0")
        val rdx = parseHex(map["rdx"] ?: "0x0")
        val r9  = parseHex(map["r9"]  ?: "0x0")
        val rbp = parseHex(map["rbp"] ?: "0x0")

        val ctx = base64Decode(ctxB64)
        if (ctx.size < CTX_SIZE) throw IllegalArgumentException("ctx too short: ${ctx.size}")

        val stRaw = base64Decode(stateB64)
        if (stRaw.size < 0x2000) throw IllegalArgumentException("state too short: ${stRaw.size}")

        val st = IntArray(ST_SIZE)
        for (off in ST_SLOTS) {
            if (off >= ST_SIZE) continue   // high slots exist but are never read by the round chain
            val pos = 0x2000 - off
            if (pos + 4 <= stRaw.size) {
                st[off] = readU32LE(stRaw, pos)
            }
        }

        return TemariCipher.Template(
            ctx,
            st,
            TemariCipher.R1Entry(rdx = rdx, rcx = rcx, rax = rax, r9 = r9, rbp = rbp)
        )
    }

    // ---------------------------------------------------------------------------
    // prefetch template — load from embedded binary blob
    // ---------------------------------------------------------------------------
    /**
     * Load a [TemariCipher.Template] from the embedded `prefetch_template.bin`
     * binary blob (40 980 bytes = CTX_SIZE + ST_SIZE*4 + 20).
     *
     * Layout:  ctx[0x8000] | st[2048 * 4 LE] | rdx u32 | rcx u32 | rax u32 | r9 u32 | rbp u32
     */
    fun prefetchTemplate(data: ByteArray): TemariCipher.Template {
        require(data.size >= CTX_SIZE + ST_SIZE * 4 + 20) { "prefetch template too short: ${data.size}" }

        val ctx = data.copyOfRange(0, CTX_SIZE)
        val stOff = CTX_SIZE
        val st = IntArray(ST_SIZE) { i -> readU32LE(data, stOff + i * 4) }

        val regOff = CTX_SIZE + ST_SIZE * 4
        val rdx = readU32LE(data, regOff)
        val rcx = readU32LE(data, regOff + 4)
        val rax = readU32LE(data, regOff + 8)
        val r9  = readU32LE(data, regOff + 12)
        val rbp = readU32LE(data, regOff + 16)

        return TemariCipher.Template(
            ctx,
            st,
            TemariCipher.R1Entry(rdx = rdx, rcx = rcx, rax = rax, r9 = r9, rbp = rbp)
        )
    }
}
