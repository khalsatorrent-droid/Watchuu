package com.watchuu.nonu

/**
 * Pure-Kotlin port of temari/rounds.rs — template struct + CBC decrypt loop.
 * All u32 arithmetic is done on JVM Int (wrapping by default on 32-bit).
 * Unsigned right-shift uses `ushr`.
 */
object TemariCipher {
    const val CTX_SIZE = 0x8000   // 32768 bytes
    const val ST_SIZE  = 2048

    data class R1Entry(
        val rdx: Int,
        val rcx: Int,
        val rax: Int,
        val r9:  Int,
        val rbp: Int
    )

    class Template(
        val ctx: ByteArray,
        val stInit: IntArray,   // [ST_SIZE]
        val r1Entry: R1Entry
    )

    // ------------------------------------------------------------------ helpers

    /** 4-byte little-endian read from ctx at `off + (idx & 0xFF) * 4`. */
    @JvmStatic
    fun t4(ctx: ByteArray, off: Int, idx: Int): Int {
        val base = off + (idx and 0xFF) * 4
        return (ctx[base].toInt() and 0xFF) or
               ((ctx[base + 1].toInt() and 0xFF) shl 8) or
               ((ctx[base + 2].toInt() and 0xFF) shl 16) or
               ((ctx[base + 3].toInt() and 0xFF) shl 24)
    }

    /** Single byte read from ctx. */
    @JvmStatic
    fun ctxb(ctx: ByteArray, off: Int): Int {
        return ctx[off].toInt() and 0xFF
    }

    // ------------------------------------------------------------------ reconstruct

    /**
     * Build a 16-byte plaintext block from (offset, byte) pairs produced by round3.
     */
    private fun reconstructBlock(pt: Array<Pair<Int, Int>>, blk: ByteArray, blkOff: Int) {
        var base = pt[0].first
        for ((o, _) in pt) {
            if (o < base) base = o
        }
        for ((o, b) in pt) {
            val idx = o - base
            if (idx in 0..15) {
                blk[blkOff + idx] = b.toByte()
            }
        }
    }

    // ------------------------------------------------------------------ process_block

    /**
     * Process one 16-byte block: R1 → R2 → R3 → CBC, write plaintext into out[outOff..outOff+16].
     */
    private fun processBlock(
        tpl: Template,
        st: IntArray,
        ct: ByteArray,
        bi: Int,
        out: ByteArray,
        outOff: Int
    ) {
        val rdi = 0x1EB2C6B4.toInt() xor (bi shl 4)
        val rsi = 0x8 + (bi shl 4)
        val regs = TemariRoundsGen.Round1Regs(
            rdi = rdi,
            rsi = rsi,
            rdx = tpl.r1Entry.rdx,
            rcx = tpl.r1Entry.rcx,
            r8  = 0,
            r9  = tpl.r1Entry.r9,
            rax = tpl.r1Entry.rax,
            rbx = 0,
            r10 = 0,
            r11 = 0,
            r12 = 0,
            r13 = 0,
            r14 = 0,
            r15 = 0,
            rbp = tpl.r1Entry.rbp
        )
        val ctx = tpl.ctx

        val mid = TemariRoundsGen.round1Mid(ctx, st, ct, regs)
        val r2 = TemariRoundsGen.round1Tail(
            ctx, st,
            mid.rax,
            mid.r13 and 0xFF,
            mid.r15 and 0xFF,
            mid.r8 and 0xFF,
            mid.r14 and 0xFF
        )
        val r2v = TemariRoundsGen.round2Sub6400(
            ctx, st,
            r2.rdi, r2.rsi, r2.rdx, r2.rcx, r2.r8, r2.r9,
            r2.rax, r2.rbx, r2.r10, r2.r11, r2.r13, r2.r14, r2.r15,
            0
        )

        // R3 boundary parameters
        val cp12 = r2v.cp12
        val r8p = cp12[2] xor st[0x5B8] xor cp12[1]
        val v6 = (t4(ctx, 0x46a0, st[0x390] xor 0x2B)
                xor st[0x5F0]
                xor t4(ctx, 0x4ac0, ((r8p ushr 24) and 0xFF) xor 0x29)
                xor t4(ctx, 0x2ff0, ((st[0x298] ushr 16) and 0xFF) xor 0xD6))
        val v9 = (t4(ctx, 0x4ac0, cp12[0])
                xor st[0x540]
                xor t4(ctx, 0x2ff0, ((r2v.v171 ushr 16) and 0xFF) xor 0x69))
        val v11 = (t4(ctx, 0x3950, (st[0x298] and 0xFF) xor 0x57)
                xor st[0x538]
                xor t4(ctx, 0x46a0, ((r8p ushr 8) and 0xFF) xor 0x2F))

        val a2 = st[0x270]
        val a6 = st[0x280]
        val r3v = TemariRoundsGen.round3Sub8000(
            ctx, st,
            a2,
            r2v.v189,
            r8p and 0xFF,
            (r8p ushr 16) and 0xFFFF,
            a6,
            v6,
            (r8p ushr 16) and 0xFF,
            v9,
            r2v.v187 and 0xFF,
            v11,
            r2v.v189
        )

        reconstructBlock(r3v.pt, out, outOff)

        // CBC state pass-through
        st[0x108] = st[0x180]
        st[0x220] = st[0x220] + 0x10
    }

    // ------------------------------------------------------------------ decrypt_blocks_into

    /**
     * Decrypt the 16-byte-aligned prefix of ciphertext into out[].
     * `ct` is the full ciphertext buffer (round1_mid indexes into it).
     */
    private fun decryptBlocksInto(tpl: Template, ct: ByteArray, ctLen: Int, out: ByteArray, outOff: Int) {
        val nblocks = ctLen / 16
        val st = tpl.stInit.copyOf()
        for (bi in 0 until nblocks) {
            processBlock(tpl, st, ct, bi, out, outOff + bi * 16)
        }
    }

    // ------------------------------------------------------------------ public API

    /**
     * Decrypt one sample in-place. The 16-byte-aligned prefix is decrypted;
     * a non-16-aligned tail passes through unchanged.
     * State resets per call (SAMPLE-AES: each sample is independently decrypted).
     */
    fun decryptSampleInPlace(tpl: Template, buf: ByteArray, off: Int, len: Int) {
        val head = (len / 16) * 16
        if (head > 0) {
            // scratch copy for ciphertext (round1_mid reads ct by index,
            // while processBlock writes decrypted output back to the same array)
            val scratch = ByteArray(head)
            System.arraycopy(buf, off, scratch, 0, head)
            decryptBlocksInto(tpl, scratch, head, buf, off)
        }
        // tail (len - head) bytes pass through unchanged
    }

    /**
     * Decrypt the samples at given ranges of buf in-place (single-threaded).
     * Each range is a Pair(offset, length). State resets per sample (SAMPLE-AES).
     */
    fun decryptRangesInPlace(tpl: Template, buf: ByteArray, ranges: List<Pair<Int, Int>>) {
        for ((off, len) in ranges) {
            decryptSampleInPlace(tpl, buf, off, len)
        }
    }
}
