package com.example.rtlsdr

/**
 * Meteor-M LRPT back end: soft symbols -> frame sync -> Viterbi -> descramble -> Reed-Solomon -> CCSDS packets -> JPEG-style
 * MCU strips -> per-channel images. Frame layout, constants, tables and the JPEG / Huffman / packet logic are a port of
 * dbdexter-dev/meteor_decode (MIT licence, (c) 2021 dbdexter-dev), and this port is differential-tested against it.
 *
 * Input convention (same as meteor_decode / medet / MeteorDemod ".s" files): signed 8-bit soft symbols, I then Q for each
 * QPSK symbol, negative = bit 1. Supports the standard 72 kbaud mode (no interleaving, no differential coding).
 */
object Lrpt {
    const val CADU_BYTES = 1024
    const val CADU_SOFT = 2 * 8 * CADU_BYTES           // 16384 soft values per frame
    const val VCDU_BYTES = 1020
    const val SYNCWORD = 0x1ACFFC1D
    const val G1 = 0x79
    const val G2 = 0x5B
    const val MCU_PER_MPDU = 14
    const val MPDU_PER_LINE = 14
    const val MCU_PER_LINE = MCU_PER_MPDU * MPDU_PER_LINE      // 196
    const val MPDU_PER_PERIOD = 3 * MPDU_PER_LINE + 1
    const val MPDU_MAX_SEQ = 16384
    const val MPDU_US_PER_LINE = 1220 * 1000
    const val US_PER_DAY = 1000L * 1000L * 86400L
    /** Image width in pixels. */
    const val WIDTH = MCU_PER_LINE * 8                          // 1568
    const val PIXELS_PER_STRIP = MCU_PER_LINE * 8 * 8

    fun parity(x: Int) = Integer.bitCount(x) and 1
}

// ======================================================================================================== Viterbi
/** Rate-1/2, K=7 (G1 = 0x79, G2 = 0x5B) soft-decision Viterbi decoder for one whole frame. */
internal object LrptViterbi {
    private const val NS = 64
    /** Encoder output (g1 << 1 | g2) for each 7-bit window (new bit in bit 6). */
    private val OUT = IntArray(128) { (Lrpt.parity(it and Lrpt.G1) shl 1) or Lrpt.parity(it and Lrpt.G2) }

    /** Encodes [data] bits (MSB first of a 32-bit word) from encoder state 0, returning the 64 stream-order hard bits (g2, g1 per data bit). */
    fun encodeWord32(data: Int): IntArray {
        val out = IntArray(64)
        var w = 0
        for (k in 0 until 32) {
            val b = (data ushr (31 - k)) and 1
            w = ((w shr 1) or (b shl 6)) and 127
            val o = OUT[w]
            out[2 * k] = o and 1          // g2 first in the soft stream
            out[2 * k + 1] = (o shr 1) and 1
        }
        return out
    }

    /**
     * Decodes [pairs] code pairs starting at soft[off] (stream order: g2 soft, g1 soft; negative = 1). Returns the decoded bits (length
     * [outBits]) packed into [outBytes]. Starts with all states equally likely and traces back from the best final state.
     */
    fun decode(soft: ByteArray, off: Int, pairs: Int, outBits: Int, outBytes: ByteArray): Int {
        var m = IntArray(NS); var nm = IntArray(NS)
        val dec = ByteArray(pairs * NS)
        val bm = IntArray(4)
        for (t in 0 until pairs) {
            val s0 = soft[off + 2 * t].toInt(); val s1 = soft[off + 2 * t + 1].toInt() // s0 <-> g2, s1 <-> g1
            for (o in 0 until 4) {
                val g2 = o and 1; val g1 = o shr 1
                bm[o] = (if (g2 == 1) -s0 else s0) + (if (g1 == 1) -s1 else s1)
            }
            val base = t * NS
            for (ns in 0 until NS) {
                val b = ns shr 5
                val p0 = (ns shl 1) and 63
                val c0 = m[p0] + bm[OUT[p0 or (b shl 6)]]
                val c1 = m[p0 or 1] + bm[OUT[(p0 or 1) or (b shl 6)]]
                if (c1 > c0) { nm[ns] = c1; dec[base + ns] = 1 } else { nm[ns] = c0; dec[base + ns] = 0 }
            }
            val tmp = m; m = nm; nm = tmp
            if ((t and 63) == 63) { var mx = m[0]; for (i in 1 until NS) if (m[i] > mx) mx = m[i]; for (i in 0 until NS) m[i] -= mx }
        }
        var best = 0
        for (i in 1 until NS) if (m[i] > m[best]) best = i
        var state = best
        val bits = ByteArray(pairs)
        for (t in pairs - 1 downTo 0) {
            bits[t] = (state shr 5).toByte()
            state = ((state shl 1) and 63) or dec[t * NS + state].toInt()
        }
        java.util.Arrays.fill(outBytes, 0, (outBits + 7) / 8, 0)
        for (i in 0 until outBits) if (bits[i].toInt() != 0) outBytes[i shr 3] = (outBytes[i shr 3].toInt() or (0x80 shr (i and 7))).toByte()
        return m[best]
    }
}

// ======================================================================================================== Reed-Solomon
/** CCSDS RS(255,223), interleave depth 4, field polynomial 0x187, first root 112, root skip 11 (as in meteor_decode). */
internal object LrptRs {
    private const val N = 255
    private const val T = 32
    private const val T2 = 16
    private const val GEN_POLY = 0x187
    private const val FIRST_ROOT = 112
    private const val ROOT_SKIP = 11
    const val INTERLEAVING = 4
    private val alpha = IntArray(N + 1)
    private val logt = IntArray(N + 1)
    private val gaproot = IntArray(N + 1)
    private val zeroes = IntArray(T)
    init {
        alpha[0] = 1; logt[1] = 0; logt[0] = 183
        for (i in 1 until N) {
            var tmp = alpha[i - 1] shl 1
            tmp = if (tmp > N) tmp xor GEN_POLY else tmp
            alpha[i] = tmp; logt[tmp] = i
        }
        logt[255] = 183; alpha[255] = 0
        for (i in 0 until N) gaproot[gfpow(i, ROOT_SKIP)] = i
        for (i in 0 until T) zeroes[i] = alpha[((i + FIRST_ROOT) * ROOT_SKIP) % N]
    }
    private fun gfmul(x: Int, y: Int) = if (x == 0 || y == 0) 0 else alpha[(logt[x] + logt[y]) % N]
    private fun gfdiv(x: Int, y: Int) = if (x == 0 || y == 0) 0 else alpha[(logt[x] - logt[y] + N) % N]
    private fun gfpow(x: Int, e: Int) = if (x == 0) 0 else alpha[(logt[x] * e) % N]
    private fun polyEval(p: IntArray, x: Int, len: Int): Int { var r = 0; for (i in len - 1 downTo 0) r = gfmul(r, x) xor p[i]; return r }

    /** Fixes the 4 interleaved codewords of [c] (1020 bytes) in place. @return corrected byte count, or -1 if any codeword is uncorrectable. */
    fun fix(c: ByteArray): Int {
        var errors = 0
        val block = IntArray(N)
        for (i in 0 until INTERLEAVING) {
            for (j in 0 until N) block[j] = c[j * INTERLEAVING + i].toInt() and 0xFF
            val d = fixBlock(block)
            errors = if (d < 0 || errors < 0) -1 else errors + d
            for (j in 0 until N) c[j * INTERLEAVING + i] = block[j].toByte()
        }
        return errors
    }

    private fun fixBlock(data: IntArray): Int {
        val syndrome = IntArray(T)
        var has = 0
        for (i in 0 until T) { syndrome[i] = polyEval(data, zeroes[i], N); has = has or syndrome[i] }
        if (has == 0) return 0
        val lambda = IntArray(T2 + 1); val prev = IntArray(T2 + 1); val tmp = IntArray(T2 + 1)
        var deg = 0; var prevDelta = 1
        lambda[0] = 1; prev[0] = 1
        var m = 1
        for (n in 0 until T) {
            var delta = syndrome[n]
            for (i in 1..deg) delta = delta xor gfmul(syndrome[n - i], lambda[i])
            if (delta == 0) { m++ }
            else if (2 * deg <= n) {
                for (i in 0..T2) tmp[i] = lambda[i]
                for (i in m..T2) lambda[i] = lambda[i] xor gfmul(gfdiv(delta, prevDelta), prev[i - m])
                for (i in 0..T2) prev[i] = tmp[i]
                prevDelta = delta; deg = n + 1 - deg; m = 1
            } else {
                for (i in m..T2) lambda[i] = lambda[i] xor gfmul(gfdiv(delta, prevDelta), prev[i - m])
                m++
            }
        }
        val root = IntArray(T2); val pos = IntArray(T2)
        var cnt = 0
        var i = 1
        while (i <= N && cnt < deg) {
            if (polyEval(lambda, i, T2 + 1) == 0) { root[cnt] = i; pos[cnt] = logt[gaproot[gfdiv(1, i)]]; cnt++ }
            i++
        }
        if (cnt != deg) return -1
        val omega = IntArray(T)
        for (j in 0..T2) for (k in 0 until T) if (k + j < T) omega[k + j] = omega[k + j] xor gfmul(syndrome[k], lambda[j])
        val lp = IntArray(T2)
        for (k in 1..T2) { var v = 0; for (j in 0 until k) v = v xor lambda[k]; lp[k - 1] = v }
        for (k in 0 until cnt) {
            val fcr = gfpow(root[k], FIRST_ROOT - 1)
            val num = polyEval(omega, root[k], T)
            val den = polyEval(lp, root[k], T2)
            data[pos[k]] = data[pos[k]] xor gfdiv(gfmul(num, fcr), den)
        }
        return cnt
    }
}

// ======================================================================================================== JPEG-style image decode
internal object LrptJpeg {
    private const val Q_SHIFT = 14
    private const val Q_1SQRT2 = 0x2d41
    private val QUANT = intArrayOf(
        16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55, 14, 13, 16, 24, 40, 57, 69, 56, 14, 17, 22, 29, 51, 87, 80, 62,
        18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92, 49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99)
    private val ZIGZAG = intArrayOf(
        0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5, 12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
        35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51, 58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63)
    private val COS = intArrayOf(
        0x4000, 0x3ec5, 0x3b21, 0x3537, 0x2d41, 0x238e, 0x187e, 0x0c7c,
        0x4000, 0x3537, 0x187e, 0xf384.toShort().toInt(), 0xd2bf.toShort().toInt(), 0xc13b.toShort().toInt(), 0xc4df.toShort().toInt(), 0xdc72.toShort().toInt(),
        0x4000, 0x238e, 0xe782.toShort().toInt(), 0xc13b.toShort().toInt(), 0xd2bf.toShort().toInt(), 0x0c7c, 0x3b21, 0x3537,
        0x4000, 0x0c7c, 0xc4df.toShort().toInt(), 0xdc72.toShort().toInt(), 0x2d41, 0x3537, 0xe782.toShort().toInt(), 0xc13b.toShort().toInt(),
        0x4000, 0xf384.toShort().toInt(), 0xc4df.toShort().toInt(), 0x238e, 0x2d41, 0xcac9.toShort().toInt(), 0xe782.toShort().toInt(), 0x3ec5,
        0x4000, 0xdc72.toShort().toInt(), 0xe782.toShort().toInt(), 0x3ec5, 0xd2bf.toShort().toInt(), 0xf384.toShort().toInt(), 0x3b21, 0xcac9.toShort().toInt(),
        0x4000, 0xcac9.toShort().toInt(), 0x187e, 0x0c7c, 0xd2bf.toShort().toInt(), 0x3ec5, 0xc4df.toShort().toInt(), 0x238e,
        0x4000, 0xc13b.toShort().toInt(), 0x3b21, 0xcac9.toShort().toInt(), 0x2d41, 0xdc72.toShort().toInt(), 0x187e, 0xf384.toShort().toInt())

    private fun qmul(x: Int, y: Int): Int = ((x * y) shr Q_SHIFT).toShort().toInt()

    private fun quantization(quality: Int, x: Int, y: Int): Int {
        val ratio = if (quality < 50) 5000 / quality else 200 - 2 * quality
        return maxOf(1, ((QUANT[x * 8 + y] * ratio / 50) + 1) / 2)
    }

    /** One 8x8 block: [src] = 64 Huffman-decoded values in zigzag order, result written to [dst] (64 pixels, row-major). */
    fun decodeBlock(dst: ByteArray, dstOff: Int, src: IntArray, srcOff: Int, q: Int) {
        val blk = IntArray(64)
        for (k in 0 until 64) blk[ZIGZAG[k]] = src[srcOff + k]
        for (i in 0 until 8) for (j in 0 until 8) blk[i * 8 + j] = (blk[i * 8 + j] * quantization(q, i, j)).toShort().toInt()
        val work = IntArray(64)
        for (i in 0 until 8) {
            val alpha = if (i != 0) 0x4000 else Q_1SQRT2
            for (j in 0 until 8) for (u in 0 until 8) work[j * 8 + u] += qmul(alpha, COS[u * 8 + i]) * blk[j * 8 + i]
        }
        val row = IntArray(8)
        for (j in 0 until 8) {
            java.util.Arrays.fill(row, 0)
            for (i in 0 until 8) {
                val alpha = if (i != 0) 0x4000 else Q_1SQRT2
                for (v in 0 until 8) row[v] += ((work[i * 8 + j].toLong() * qmul(alpha, COS[v * 8 + i])) shr Q_SHIFT).toInt()
            }
            for (i in 0 until 8) dst[dstOff + i * 8 + j] = maxOf(0, minOf(255, ((row[i] / 4) shr Q_SHIFT) + 128)).toByte()
        }
    }

    // ---- Huffman
    private val DC_PREFIX = intArrayOf(2, 3, 3, 3, 3, 3, 4, 5, 6, 7, 8, 9)
    private val AC_SIZE = intArrayOf(0, 0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 125)
    private val AC_TABLE = intArrayOf(
        1, 2, 3, 0, 4, 17, 5, 18, 33, 49, 65, 6, 19, 81, 97, 7, 34, 113, 20, 50, 129, 145, 161, 8, 35, 66, 177, 193, 21, 82, 209, 240,
        36, 51, 98, 114, 130, 9, 10, 22, 23, 24, 25, 26, 37, 38, 39, 40, 41, 42, 52, 53, 54, 55, 56, 57,
        58, 67, 68, 69, 70, 71, 72, 73, 74, 83, 84, 85, 86, 87, 88, 89, 90, 99, 100,
        101, 102, 103, 104, 105, 106, 115, 116, 117, 118, 119, 120, 121, 122, 131,
        132, 133, 134, 135, 136, 137, 138, 146, 147, 148, 149, 150, 151, 152, 153,
        154, 162, 163, 164, 165, 166, 167, 168, 169, 170, 178, 179, 180, 181, 182,
        183, 184, 185, 186, 194, 195, 196, 197, 198, 199, 200, 201, 202, 210, 211,
        212, 213, 214, 215, 216, 217, 218, 225, 226, 227, 228, 229, 230, 231, 232,
        233, 234, 241, 242, 243, 244, 245, 246, 247, 248, 249, 250)

    /** utils.c read_bits: [bitcount] bits starting [offsetBits] bits into [src] (bytes past the end read as 0). */
    private fun readBits(src: ByteArray, base: Int, offsetBits: Int, bitcountIn: Int): Long {
        var p = base + offsetBits / 8
        val off = offsetBits % 8
        fun b(i: Int) = if (i < src.size) (src[i].toInt() and 0xFF).toLong() else 0L
        var ret = b(p++) and ((1L shl (8 - off)) - 1)
        var bitcount = bitcountIn - (8 - off)
        while (bitcount > 0) { ret = (ret shl 8) or b(p++); bitcount -= 8 }
        return if (bitcount < 0) ret shr -bitcount else ret
    }

    private fun dcCat(cw: Int): Int {
        if (cw shr 14 == 0) return 0
        if (cw shr 13 < 7) return (cw shr 13) - 1
        if (cw shr 12 < 0xF) return 6
        if (cw shr 11 < 0x1F) return 7
        if (cw shr 10 < 0x3F) return 8
        if (cw shr 9 < 0x7F) return 9
        if (cw shr 8 < 0xFF) return 10
        if (cw shr 7 < 0x1FF) return 11
        return -1
    }

    /**
     * Decodes [count] blocks of Huffman data from src[start..] into [dst] (count * 64 values, zigzag order), stopping after [maxlen]
     * bytes. @return false if a DC code is invalid (the packet is then unusable).
     */
    fun huffman(dst: IntArray, src: ByteArray, start: Int, count: Int, maxlen: Int): Boolean {
        java.util.Arrays.fill(dst, 0)
        var bitIdx = 0; var bytecount = 0; var dcCoeff = 0
        var base = start
        for (i in 0 until count) {
            val dcInfo = readBits(src, base, bitIdx, 32)
            val cat = dcCat(((dcInfo shr 16) and 0xFFFF).toInt())
            if (cat < 0) return false
            val sign = ((dcInfo shr (31 - DC_PREFIX[cat])) and 1L).toInt()
            if (cat > 0) {
                val extra = ((dcInfo shr (31 - DC_PREFIX[cat] - cat + 1)) and ((1L shl (cat - 1)) - 1)).toInt()
                dcCoeff += extra + (if (sign != 0) (1 shl (cat - 1)) else 1 - (1 shl cat))
            }
            dst[i * 64] = dcCoeff.toShort().toInt()
            bitIdx += DC_PREFIX[cat] + cat
            base += bitIdx / 8; bytecount += bitIdx / 8; bitIdx %= 8
            if (bytecount >= maxlen) break
            var r = 1
            while (r < 64) {
                val acBuf = readBits(src, base, bitIdx, 32) and 0xFFFFFFFFL
                var first = 0L; var idx = 0
                var j = 2
                var acInfo = 0L
                while (j < AC_SIZE.size) {
                    acInfo = acBuf shr (32 - j)
                    if (((acInfo - first) and 0xFFFFFFFFL) < AC_SIZE[j]) { acInfo = AC_TABLE.getOrElse(idx + (acInfo - first).toInt()) { 0 }.toLong(); break }
                    first = ((first + AC_SIZE[j]) shl 1) and 0xFFFFFFFFL
                    idx += AC_SIZE[j]
                    j++
                }
                bitIdx += j
                if (acInfo == 0L) {
                    while (r < 64) { dst[i * 64 + r] = 0; r++ }
                } else {
                    var run = ((acInfo shr 4) and 0xF).toInt()
                    val cat2 = (acInfo and 0xF).toInt()
                    val asign = readBits(src, base, bitIdx, 1).toInt()
                    val extra = if (cat2 > 1) readBits(src, base, bitIdx + 1, cat2 - 1).toInt() else 0
                    val coeff = if (cat2 > 0) extra + (if (asign != 0) (1 shl (cat2 - 1)) else 1 - (1 shl cat2)) else 0
                    while (run > 0 && r < 63) { dst[i * 64 + r] = 0; run--; r++ }
                    dst[i * 64 + r] = coeff.toShort().toInt()
                    bitIdx += cat2
                }
                base += bitIdx / 8; bytecount += bitIdx / 8; bitIdx %= 8
                if (bytecount >= maxlen) break
                r++
            }
            if (bytecount >= maxlen) break
        }
        return true
    }
}

// ======================================================================================================== image channels
/** One APID's image: [WIDTH] pixels wide, 8-pixel-high strips appended as packets arrive; lost packets become black. */
class LrptChannel(val apid: Int) {
    var mcuSeq = 0; var mpduSeq = -1
    var pixels = ByteArray(Lrpt.PIXELS_PER_STRIP * 32)
    var offset = 0
    /** Height in pixels of what has been received so far (a partly filled strip counts). */
    val height: Int get() = offset / Lrpt.WIDTH + (if (mcuSeq > 0) 8 else 0)
    @Volatile var version = 0

    fun appendStrip(strip: ByteArray?, mcuSeqIn: Int, mpduSeqIn: Int) {
        val mcu = mcuSeqIn - (mcuSeqIn % Lrpt.MCU_PER_MPDU)
        val mpduDelta = (mpduSeqIn - mpduSeq - 1 + Lrpt.MPDU_MAX_SEQ) % Lrpt.MPDU_MAX_SEQ
        // A packet "behind" the current position is a duplicate or arrived out of order (two decoders can both deliver a frame):
        // ignore it, instead of reading it as a jump of nearly the whole 14-bit sequence space.
        if (mpduSeq >= 0 && mpduDelta >= Lrpt.MPDU_MAX_SEQ / 2) return
        val mcuDelta = (mcu - mcuSeq + Lrpt.MCU_PER_LINE) % Lrpt.MCU_PER_LINE
        // gaps are filled with black so lines stay aligned, but a wild jump (corrupt header) must not allocate megabytes
        val linesLost = if (mpduSeq < 0) 0 else minOf(mpduDelta / Lrpt.MPDU_PER_PERIOD, 600)
        val stripsLost = mcuDelta / Lrpt.MCU_PER_MPDU + linesLost * Lrpt.MPDU_PER_LINE
        for (i in stripsLost downTo 1) cache(null)
        mpduSeq = mpduSeqIn; mcuSeq = mcu
        cache(strip)
        version++
    }

    /** [strip] = 14 blocks x 64 pixels, or null for a black strip. */
    private fun cache(strip: ByteArray?) {
        if (offset + Lrpt.PIXELS_PER_STRIP > pixels.size) pixels = pixels.copyOf(pixels.size + Lrpt.PIXELS_PER_STRIP * 32)
        if (strip != null) for (row in 0 until 8) for (block in 0 until Lrpt.MCU_PER_MPDU)
            System.arraycopy(strip, block * 64 + row * 8, pixels, offset + row * Lrpt.WIDTH + (mcuSeq + block) * 8, 8)
        else for (row in 0 until 8) java.util.Arrays.fill(pixels, offset + row * Lrpt.WIDTH + mcuSeq * 8, offset + row * Lrpt.WIDTH + (mcuSeq + Lrpt.MCU_PER_MPDU) * 8, 0)
        mcuSeq += Lrpt.MCU_PER_MPDU
        if (mcuSeq >= Lrpt.MCU_PER_LINE) { mcuSeq = 0; mpduSeq += Lrpt.MPDU_PER_PERIOD - Lrpt.MPDU_PER_LINE; offset += Lrpt.PIXELS_PER_STRIP }
    }
}

// ======================================================================================================== decoder
/** Decoder statistics for a status line. */
class LrptStats(
    @Volatile var locked: Boolean = false, @Volatile var frames: Int = 0, @Volatile var rsOk: Int = 0, @Volatile var rsFail: Int = 0,
    @Volatile var packets: Int = 0, @Volatile var syncCorr: Int = 0, @Volatile var lastTimeUs: Long = 0L
)

/**
 * Streaming LRPT decoder. Feed it soft symbols with [feed]; decoded strips accumulate in [channels] (keyed by APID 64..69).
 * Sync: the convolution-coded frame marker is searched at 8 orientations of the I/Q pair (4 rotations x mirrored), a lock needs
 * the marker to repeat one frame later; the orientation found is undone before Viterbi decoding.
 */
class LrptDecoder(
    /** Image channels by APID; pass a shared map so several decoders (pairing hypotheses) fill the same pictures. */
    val channels: MutableMap<Int, LrptChannel> = LinkedHashMap(),
    /**
     * OQPSK carrier recovery has four lock states; in two of them I and Q swap roles and the half-symbol alignment pairs them one
     * symbol apart. With [delayFirst] the first value of every pair is delayed by one symbol, which repairs exactly those states.
     */
    private val delayFirst: Boolean = false
) {
    val stats = LrptStats()
    private var prevFirst = 0
    /** Called (on the feeding thread) whenever a channel gained data. */
    @Volatile var onUpdate: (() -> Unit)? = null
    /** Optional per-frame hook (frame index, RS corrected-byte count or -1, sync correlation of 64). */
    @Volatile var onFrame: ((Int, Int, Int) -> Unit)? = null

    // ---- sync patterns: stream-order hard sync word, as seen through each of the 8 orientations
    private val patt = LongArray(8)
    private val inv = IntArray(8)
    private companion object {
        const val CORR_FIND = 50   // of 64 bits, first sighting
        const val CORR_VERIFY = 40 // of 64 bits, one frame later
        const val CORR_HOLD = 34   // of 64 bits while locked
        const val TAIL_PAIRS = 64  // extra code pairs after the frame, so the last bytes are decoded with future context

        /** What orientation [t] does to a (first, second) soft pair; negation = bit flip. */
        fun fwd(t: Int, a: Int, b: Int): Pair<Int, Int> = when (t) {
            0 -> a to b; 1 -> -b to a; 2 -> -a to -b; 3 -> b to -a
            4 -> b to a; 5 -> a to -b; 6 -> -b to -a; else -> -a to b
        }
    }
    init {
        val word = LrptViterbi.encodeWord32(Lrpt.SYNCWORD)
        for (t in 0 until 8) {
            var w = 0L
            for (k in 0 until 32) {
                val a = if (word[2 * k] == 1) -1 else 1; val b = if (word[2 * k + 1] == 1) -1 else 1
                val (x, y) = fwd(t, a, b)
                w = (w shl 1) or (if (x < 0) 1L else 0L)
                w = (w shl 1) or (if (y < 0) 1L else 0L)
            }
            patt[t] = w
        }
        for (t in 0 until 8) for (u in 0 until 8) {
            val (x, y) = fwd(t, 1, 3); val (p, q) = fwd(u, x, y)
            if (p == 1 && q == 3) inv[t] = u
        }
    }

    private var buf = ByteArray(1 shl 20)
    private var n = 0                 // valid bytes in buf
    private var scanPos = 0           // next index to push into the sliding window while searching
    private var window = 0L
    private var locked = false
    private var lockT = 0
    private var framePos = 0          // buffer index of the next frame's first soft value
    private var misses = 0
    private var rsFailRun = 0

    private val frameSoft = ByteArray(Lrpt.CADU_SOFT + 2 * TAIL_PAIRS)
    private val cadu = ByteArray(Lrpt.CADU_BYTES)
    private val vcdu = ByteArray(Lrpt.VCDU_BYTES)
    private val noise = ByteArray(255)
    private val mpdu = ByteArray(6 + 8 + 2048)
    private var mState = 0; private var mOffset = 0; private var mFrag = 0
    private var firstTime = -1L
    private var lastTime = 0L

    init {
        var state = 0xFF
        for (i in 0 until 255) {
            var v = 0
            for (j in 0 until 8) {
                val nb = ((state shr 7) and 1) xor ((state shr 5) and 1) xor ((state shr 3) and 1) xor (state and 1)
                v = (v shl 1) or (state and 1)
                state = (state shr 1) or (nb shl 7)
            }
            noise[i] = v.toByte()
        }
    }

    fun reset() {
        channels.clear(); prevFirst = 0; n = 0; scanPos = 0; window = 0L; primeLeft = 63; pkP = -1; pkC = 0; pkAge = 0; locked = false; misses = 0; rsFailRun = 0; candP = -1
        mState = 0; mOffset = 0; mFrag = 0; firstTime = -1L
        stats.locked = false; stats.frames = 0; stats.rsOk = 0; stats.rsFail = 0; stats.packets = 0
    }

    /** Appends [count] soft values (I, Q, I, Q, ...) taken from [soft] and decodes whatever is complete. */
    fun feed(soft: ByteArray, count: Int) {
        if (n + count > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, n + count))
        if (delayFirst) {
            var i = 0
            while (i + 1 < count) {
                val a = prevFirst; prevFirst = soft[i].toInt()
                buf[n + i] = (if (a < -127) -127 else a).toByte(); val b = soft[i + 1].toInt(); buf[n + i + 1] = (if (b < -127) -127 else b).toByte()
                i += 2
            }
            n += count - (count and 1)
        } else {
            for (i in 0 until count) { val v = soft[i].toInt(); buf[n + i] = (if (v < -127) -127 else v).toByte() } // keep -128 out
            n += count
        }
        process(false)
    }

    /** Feeds frames that are already error-corrected (SatDump ".cadu": 1024 bytes each, sync marker + RS-corrected VCDU). */
    fun feedCadu(data: ByteArray, count: Int) {
        var off = 0
        while (off + Lrpt.CADU_BYTES <= count) {
            System.arraycopy(data, off + 4, vcdu, 0, Lrpt.VCDU_BYTES)
            stats.frames++; stats.rsOk++
            parseVcdu()
            off += Lrpt.CADU_BYTES
        }
    }

    /** End of input: decode the last frame even though no tail context follows it. */
    fun flush() = process(true)

    private fun hardAt(i: Int) = if (buf[i] < 0) 1L else 0L

    private fun corr(pos: Int, t: Int): Int {
        var w = 0L
        for (i in 0 until 64) w = (w shl 1) or hardAt(pos + i)
        return 64 - java.lang.Long.bitCount(w xor patt[t])
    }

    private fun process(flush: Boolean) {
        var guard = 0
        while (guard++ < 100000) {
            if (!locked) { if (!search()) break } else { if (!decodeFrame(flush)) break }
        }
        // drop what is no longer needed
        val keepFrom = if (locked) framePos else if (candP >= 0) candP else if (pkP >= 0) pkP else maxOf(0, scanPos - 64)
        if (keepFrom > 1 shl 18) {
            System.arraycopy(buf, keepFrom, buf, 0, n - keepFrom)
            n -= keepFrom; scanPos -= keepFrom; framePos -= keepFrom; if (candP >= 0) candP -= keepFrom; if (pkP >= 0) pkP -= keepFrom
        }
    }

    private var primeLeft = 63      // bits still needed before the sliding window is full
    private var pkP = -1; private var pkC = 0; private var pkT = 0; private var pkAge = 0 // running peak of the marker search
    private var candP = -1       // a sync candidate waiting for the next frame's marker to be verified
    private var candT = 0

    private fun lockAt(p: Int, t: Int, c: Int) {
        locked = true; lockT = t; framePos = p; misses = 0; rsFailRun = 0; candP = -1
        stats.locked = true; stats.syncCorr = c
    }

    /** @return true if state changed and processing should continue. */
    private fun search(): Boolean {
        if (candP >= 0) { // verify the pending candidate once a whole frame later has arrived
            if (candP + Lrpt.CADU_SOFT + 64 > n) return false
            val c = corr(candP + Lrpt.CADU_SOFT, candT)
            if (c >= CORR_VERIFY) { lockAt(candP, candT, c); return true }
            candP = -1
        }
        while (scanPos < n) {
            window = (window shl 1) or hardAt(scanPos)
            scanPos++
            if (primeLeft > 0) { primeLeft--; continue } // the 64-bit window isn't full yet
            val p = scanPos - 64                          // buffer index of the window's first bit
            var bestT = -1; var best = 0
            for (t in 0 until 8) { val c = 64 - java.lang.Long.bitCount(window xor patt[t]); if (c > best) { best = c; bestT = t } }
            // The coded marker has long periodic runs, so copies of it shifted by a few symbols also score high: follow the
            // correlation to its PEAK (40 symbols past the last improvement) instead of taking the first position over the threshold.
            if (best >= CORR_FIND && best > pkC) { pkP = p; pkC = best; pkT = bestT; pkAge = 0 }
            else if (pkP >= 0) pkAge++
            if (pkP >= 0 && pkAge >= 40) {
                val pp = pkP; val tt = pkT; val cc = pkC
                pkP = -1; pkC = 0; pkAge = 0
                if (pp + Lrpt.CADU_SOFT + 64 > n) { candP = pp; candT = tt; return false } // wait for the data that verifies it
                val v = corr(pp + Lrpt.CADU_SOFT, tt)
                if (v >= CORR_VERIFY) { lockAt(pp, tt, cc); return true }
            }
        }
        return false
    }

    /** @return true if a frame was consumed (or lock dropped), false if more data is needed. */
    private fun decodeFrame(flush: Boolean): Boolean {
        val need = framePos + Lrpt.CADU_SOFT + (if (flush) 0 else 2 * TAIL_PAIRS)
        if (framePos + 64 + 3 > n && !flush) return false
        if (need > n && !flush) return false
        if (framePos + Lrpt.CADU_SOFT > n) { // flushed with an incomplete frame
            locked = false; stats.locked = false; return false
        }
        // check / re-find the marker (the demodulator can slip a symbol)
        var c = corr(framePos, lockT)
        if (c < 56) { // not a clean marker: look for a small slip (the demodulator can gain or lose a symbol)
            var bc = c; var bd = 0
            for (d in -2..2) { if (d == 0 || framePos + d < 0 || framePos + d + 64 > n) continue; val cc = corr(framePos + d, lockT); if (cc > bc + 4) { bc = cc; bd = d } }
            if (bd != 0) { framePos += bd; c = bc }
        }
        if (c < CORR_HOLD) {
            var bestC = c; var bestD = 0
            for (d in -3..3) {
                if (d == 0 || framePos + d < 0 || framePos + d + 64 > n) continue
                val cc = corr(framePos + d, lockT)
                if (cc > bestC) { bestC = cc; bestD = d }
            }
            if (bestC >= CORR_HOLD) { framePos += bestD; c = bestC } else {
                misses++
                if (misses >= 3) { locked = false; stats.locked = false; scanPos = framePos + 1; window = 0L; scanPos = framePos + 1; return true.also { prepareRescan() } }
            }
        } else misses = 0
        stats.syncCorr = c
        val avail = minOf(Lrpt.CADU_SOFT + 2 * TAIL_PAIRS, n - framePos)
        // undo the orientation (derotate) into a working copy
        val u = inv[lockT]
        var i = 0
        while (i + 1 < avail) {
            val a = buf[framePos + i].toInt(); val b = buf[framePos + i + 1].toInt()
            val (x, y) = fwd(u, a, b)
            frameSoft[i] = maxOf(-127, minOf(127, x)).toByte(); frameSoft[i + 1] = maxOf(-127, minOf(127, y)).toByte()
            i += 2
        }
        val pairs = avail / 2
        LrptViterbi.decode(frameSoft, 0, pairs, 8 * Lrpt.CADU_BYTES, cadu)
        stats.frames++
        System.arraycopy(cadu, 4, vcdu, 0, Lrpt.VCDU_BYTES)
        for (k in 0 until Lrpt.VCDU_BYTES) vcdu[k] = (vcdu[k].toInt() xor noise[k % 255].toInt()).toByte()
        val errs = LrptRs.fix(vcdu)
        onFrame?.invoke(stats.frames - 1, errs, c)
        if (errs < 0) {
            stats.rsFail++; rsFailRun++
            mState = 0; mOffset = 0; mFrag = 0
            if (rsFailRun >= 6) { locked = false; stats.locked = false; scanPos = framePos + Lrpt.CADU_SOFT; window = 0L; prepareRescan() }
        } else {
            stats.rsOk++; rsFailRun = 0
            parseVcdu()
        }
        framePos += Lrpt.CADU_SOFT
        return true
    }

    private fun prepareRescan() { window = 0L; primeLeft = 63; pkP = -1; pkC = 0; pkAge = 0; if (scanPos < 0) scanPos = 0 }

    // ---- VCDU -> MPDU reassembly (meteor_decode parser/mpdu_parser.c)
    private fun u8(i: Int) = vcdu[i].toInt() and 0xFF
    private fun parseVcdu() {
        val version = u8(0) shr 6
        val type = u8(1) and 0x3F
        val spare = u8(8) shr 3
        val ptr = ((u8(8) and 7) shl 8) or u8(9)
        val headerPresent = spare == 0 && ptr != 0x7FF
        if (version == 0 || type == 0) return
        val data = 10 // mpdu_data offset inside the VCDU (6 primary + 2 encryption + 2 mpdu header)
        val dataLen = 882
        var guardLoop = 0
        // run the state machine until this VCDU's data is consumed
        var proceed = false
        while (!proceed && guardLoop++ < 64) {
            val jmpIdle = headerPresent && mOffset == 0
            if (mFrag >= mpdu.size) { mState = 0; mOffset = 0; mFrag = 0; return }
            when (mState) {
                0 -> { // IDLE
                    if (headerPresent) {
                        mOffset = ptr
                        if (mOffset > dataLen) return
                        mFrag = 0; mState = 1
                    } else return
                }
                1 -> { // HEADER (6 bytes)
                    val left = 6 - mFrag
                    if (mOffset + left < dataLen) {
                        System.arraycopy(vcdu, data + mOffset, mpdu, mFrag, left)
                        mFrag = 0; mOffset += left; mState = 2
                    } else {
                        val c = dataLen - mOffset
                        System.arraycopy(vcdu, data + mOffset, mpdu, mFrag, c)
                        mFrag += c; mOffset = 0
                        proceed = true
                    }
                }
                else -> { // DATA
                    val plen = (((mpdu[4].toInt() and 0xFF) shl 8) or (mpdu[5].toInt() and 0xFF)) + 1
                    val left = plen - mFrag
                    if (mFrag + minOf(left, dataLen) > mpdu.size - 6) { mState = 0; mOffset = 0; mFrag = 0; return }
                    if (mOffset + left < dataLen) {
                        System.arraycopy(vcdu, data + mOffset, mpdu, 6 + mFrag, left)
                        mFrag = 0; mOffset += left
                        mState = if (jmpIdle) 0 else 1
                        handleMpdu()
                    } else {
                        val c = dataLen - mOffset
                        System.arraycopy(vcdu, data + mOffset, mpdu, 6 + mFrag, c)
                        mFrag += c; mOffset = 0
                        mState = if (jmpIdle) 0 else 2
                        if (!jmpIdle) proceed = true
                    }
                }
            }
        }
    }

    // ---- one complete packet (meteor_decode main.c process_mpdu)
    private fun m8(i: Int) = mpdu[i].toInt() and 0xFF
    private val strip = ByteArray(Lrpt.MCU_PER_MPDU * 64)
    private val coef = IntArray(Lrpt.MCU_PER_MPDU * 64)

    private fun handleMpdu() {
        val apid = ((m8(0) and 7) shl 8) or m8(1)
        val seq = ((m8(2) and 0x3F) shl 8) or m8(3)
        val plen = ((m8(4) shl 8) or m8(5)) + 1
        val day = (m8(6) shl 8) or m8(7)
        val ms = (m8(8).toLong() shl 24) or (m8(9).toLong() shl 16) or (m8(10).toLong() shl 8) or m8(11).toLong()
        val us = (m8(12) shl 8) or m8(13)
        val time = day.toLong() * 86400L * 1000L * 1000L + ms * 1000L + us
        stats.packets++
        if (firstTime < 0) firstTime = time
        if (time < firstTime && firstTime - time < Lrpt.US_PER_DAY / 2) return // satellite clock glitch
        lastTime = time; stats.lastTimeUs = time
        if (apid !in 64..69) return
        val ch = channels.getOrPut(apid) { LrptChannel(apid) }
        // AVHRR/MSU-MR segment header: seq, scan hdr (2), segment hdr (3), data ...
        val mcuSeq = m8(14)
        val q = m8(19)
        val ok = q != 0 && LrptJpeg.huffman(coef, mpdu, 20, Lrpt.MCU_PER_MPDU, plen)
        if (ok) for (b in 0 until Lrpt.MCU_PER_MPDU) LrptJpeg.decodeBlock(strip, b * 64, coef, b * 64, q)
        if (ch.mpduSeq < 0) {
            val linesLost = ((lastTime - firstTime) / Lrpt.MPDU_US_PER_LINE).toInt()
            ch.mpduSeq = (seq - Lrpt.MPDU_PER_PERIOD * linesLost - 1 + Lrpt.MPDU_MAX_SEQ) % Lrpt.MPDU_MAX_SEQ
        }
        ch.appendStrip(if (ok) strip else null, mcuSeq, seq)
        onUpdate?.invoke()
    }
}

/**
 * Complete LRPT receiver: IQ in, pictures out. Runs [LrptDemod] and two [LrptDecoder]s on its output (the two carrier-lock pairing
 * hypotheses, see [LrptDecoder]) that share the same image channels, so whichever matches the current lock state delivers.
 */
class LrptReceiver(symbolRate: Double = 72_000.0) {
    val channels = LinkedHashMap<Int, LrptChannel>()
    private val demod = LrptDemod(symbolRate, true)
    internal val decA = LrptDecoder(channels, false)
    internal val decB = LrptDecoder(channels, true)
    private var soft = ByteArray(1 shl 17)
    @Volatile var onUpdate: (() -> Unit)? = null
        set(v) { field = v; decA.onUpdate = v; decB.onUpdate = v }
    /** Offline files: feed every symbol to the decoders (the sync marker + Reed-Solomon do the validation), not only while the AFC detector is happy. */
    @Volatile var offline = false

    /** The pairing hypothesis that is delivering good frames (the other one fails Reed-Solomon by design). */
    private val owner: LrptDecoder get() = if (decA.stats.rsOk >= decB.stats.rsOk) decA else decB
    /** Status: locked if either hypothesis is locked; frame counters are those of the delivering decoder. */
    val locked: Boolean get() = decA.stats.locked || decB.stats.locked
    val rsOk: Int get() = owner.stats.rsOk
    val rsFail: Int get() = owner.stats.rsFail
    val frames: Int get() = rsOk + rsFail
    val packets: Int get() = owner.stats.packets
    val carrierHz: Double get() = demod.carrierHz
    /** A QPSK/OQPSK carrier is currently detected. */
    val signalPresent: Boolean get() = demod.signalPresent
    /** Signal-detection ratio (2-3 = noise only, >5 = signal). */
    val signalQuality: Double get() = demod.afcQ
    val lastTimeUs: Long get() = owner.stats.lastTimeUs

    fun reset() { demod.reset(); decA.reset(); decB.reset(); channels.clear() }

    fun process(i: FloatArray, q: FloatArray, n: Int, rate: Int) {
        val need = n / maxOf(1, rate / 288_000) * 2 + 4096
        if (soft.size < need) soft = ByteArray(need)
        val c = demod.process(i, q, n, rate, soft)
        // decoders only see symbols while a carrier is really present (a decoder fed noise would lock onto chance sync matches)
        if (c > 0 && (offline || demod.signalPresent)) { decA.feed(soft, c); decB.feed(soft, c) }
    }

    /** Offline use: decode soft symbols (I,Q signed bytes) straight from a file. */
    fun feedSoft(data: ByteArray, count: Int) { decA.feed(data, count); decB.feed(data, count) }
    fun feedCadu(data: ByteArray, count: Int) { decA.feedCadu(data, count) }
    fun flush() { decA.flush(); decB.flush() }
}
