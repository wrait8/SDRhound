package com.example.rtlsdr

import kotlin.math.abs
import kotlin.math.atan2

/**
 * DMR (ETSI TS 102 361-1) physical layer, structured after the open-source DSD / dsd-fme decoder
 * (lwvmobile/dsd-fme: dsd_symbol.c, dsd_frame_sync.c, dmr_bs.c, dmr_ms.c, dmr_data.c). Constants, the CACH
 * de-interleave table and the TACT Hamming(7,4) matrix below are taken from that source, not from memory.
 * Note DSD+ itself is closed-source freeware, so this mirrors the open lineage it descends from.
 *
 * Air interface: 4FSK, 4800 symbols/s, 2 bits per symbol. A TDMA "frame" is 144 dibits = 12 CACH + 132 burst
 * (54 payload | 24 sync-or-EMB | 54 payload) = 30 ms, alternating timeslots.
 */
object Dmr {
    const val SYMBOL_RATE = 4800.0
    const val BIT_RATE = 9600.0
    const val BURST_BITS = 264
    const val SYNC_BITS = 48
    const val HALF_BITS = 108
    const val CACH_DIBITS = 12
    const val BURST_DIBITS = 132
    const val FRAME_DIBITS = 144
    /** Bits between bursts of the SAME timeslot (2 frames). */
    const val SLOT_STRIDE = 576

    /** ETSI Table 9.2: +3=01, +1=00, -1=10, -3=11. */
    fun dibitForSymbol(sym: Int): Int = when (sym) { 3 -> 0b01; 1 -> 0b00; -1 -> 0b10; else -> 0b11 }
    fun symbolForDibit(d: Int): Int = when (d and 3) { 1 -> 3; 0 -> 1; 2 -> -1; else -> -3 }

    /** 48-bit SYNC patterns. ms = mobile-station (direct-mode) source, no CACH. */
    enum class Sync(val bits48: Long, val ms: Boolean) {
        BS_VOICE(0x755FD7DF75F7L, false),
        BS_DATA(0xDFF57D75DF5DL, false),
        MS_VOICE(0x7F7D5DD57DFDL, true),
        MS_DATA(0xD5D7F77FD757L, true);
        val isVoice: Boolean get() = this == BS_VOICE || this == MS_VOICE
        /** dibit k (0..23, MSB first) of the pattern. */
        fun dibit(k: Int): Int = ((bits48 shr (46 - 2 * k)) and 3L).toInt()
    }

    /** dsd-fme cachInterleave: raw CACH bit k lands at deinterleaved position CACH_INTERLEAVE[k]. */
    val CACH_INTERLEAVE = intArrayOf(0, 7, 8, 9, 1, 10, 11, 12, 2, 13, 14, 15, 3, 16, 4, 17, 18, 19, 5, 20, 21, 22, 6, 23)

    /** TACT: access type, timeslot number (0/1), link-control start/stop. */
    class Tact(val at: Int, val slot: Int, val lcss: Int)

    private val H74 = arrayOf(intArrayOf(1, 1, 1, 0, 1, 0, 0), intArrayOf(0, 1, 1, 1, 0, 1, 0), intArrayOf(1, 1, 0, 1, 0, 0, 1))
    // syndrome (row0<<2 | row1<<1 | row2) -> bit position to flip (Hamming_7_4_m_corr in dsd-fme fec.c)
    private val H74_CORR = intArrayOf(-1, 6, 5, 3, 4, 0, 2, 1)

    /** Hamming(7,4) on the first 7 deinterleaved CACH bits. Always yields a word (the code is perfect), like dsd-fme. */
    fun decodeTact(cach: IntArray): Tact {
        val t = IntArray(7) { cach[it] }
        var syn = 0
        for (r in 0 until 3) {
            var x = 0
            for (c in 0 until 7) x = x xor (t[c] and H74[r][c])
            syn = (syn shl 1) or x
        }
        if (syn != 0) { val p = H74_CORR[syn]; if (p >= 0) t[p] = t[p] xor 1 }
        return Tact(t[0], t[1], (t[2] shl 1) or t[3])
    }

    /** CRC-CCITT as in dsd-fme ComputeCrcCCITT16d: poly 0x1021, init 0, over [len] bits, result inverted. */
    fun crc16(bits: IntArray, from: Int, len: Int): Int {
        var crc = 0
        for (i in 0 until len) {
            val fb = ((crc shr 15) and 1) xor (bits[from + i] and 1)
            crc = (crc shl 1) and 0xFFFF
            if (fb != 0) crc = crc xor 0x1021
        }
        return crc xor 0xFFFF
    }

    /** True if the 16 CRC bits at bits[80..95] of a 96-bit BPTC payload match the CRC over bits[0..79] after the data-type [mask]. */
    fun crc16Ok(bits96: IntArray, mask: Int): Boolean {
        var ext = 0
        for (i in 0 until 16) ext = (ext shl 1) or (bits96[80 + i] and 1)
        return (ext xor mask) == crc16(bits96, 0, 80)
    }
}

/** One TDMA frame handed from [DmrFrameSync] to the decoder: the 24 de-interleaved CACH bits and the 264 burst bits. */
class DmrFrame(
    val atMs: Long, val ms: Boolean, val cach: IntArray, val bits: IntArray,
    /** Sync pattern found in the burst centre, or null (voice burst B..F / noise). */
    val sync: Dmr.Sync?, val syncErr: Int
)

/**
 * Symbol + frame synchroniser (dsd_symbol.c / dsd_frame_sync.c). 4FSK discriminator -> symbol-spaced slicer ->
 * sync search over the last 24 dibits at all 10 timing phases; on a hit it LOCKS, calibrating the slicer centre and
 * deviation from the 24 known sync symbols, and from then on cuts the stream into 144-dibit frames at fixed spacing
 * (288 in direct mode, where the other timeslot is skipped, as dmr_ms.c does). The sampling point is nudged by +-1
 * sample whenever a sync word is re-found. Inverted polarity is detected automatically. Lock is dropped when no
 * sync word has been seen for [LOSS_SAMPLES].
 */
class DmrFrameSync {
    companion object {
        const val RATE = 48_000
        const val SPS = RATE / 4800
        private const val RING = 1 shl 14
        private const val MASK = RING - 1
        private const val MASK48 = (1L shl 48) - 1
        private const val INV48 = 0xAAAAAAAAAAAAL
        private const val MAX_ERR_SEARCH = 4
        private const val MAX_ERR_LOCK = 7
        private const val LOSS_SAMPLES = 38_400L // 0.8 s: longer than a voice superframe (360 ms) with margin
        private val SYNCS = Dmr.Sync.values()
    }

    private lateinit var dec: FirDecim
    private lateinit var decQ: FirDecim
    private var curRate = 0
    private var lastI = 1f; private var lastQ = 0f
    private var bi = FloatArray(0); private var bq = FloatArray(0)
    private val box = FloatArray(SPS); private var boxPos = 0; private var boxSum = 0f
    private val ring = FloatArray(RING)
    private var pos = -1L

    private var dc = 0f; private var amp = 0.17f; private var nAdapt = 0

    private val reg = LongArray(SPS); private val fill = IntArray(SPS)
    private var candFirst = -1L; private var candLast = 0L; private var candErr = 99
    private var candSync = Dmr.Sync.BS_VOICE; private var candInv = false; private var candDeadline = 0L

    @Volatile var locked = false; private set
    private var inv = false; private var ms = false
    private var nextEnd = 0L; private var lastGood = 0L
    private val out = ArrayList<DmrFrame>()

    fun reset() {
        curRate = 0; boxSum = 0f; boxPos = 0; java.util.Arrays.fill(box, 0f)
        dc = 0f; amp = 0.17f; nAdapt = 0; unlock()
    }

    private fun unlock() {
        locked = false; candFirst = -1L
        java.util.Arrays.fill(reg, 0L); java.util.Arrays.fill(fill, 0)
    }

    fun process(i: FloatArray, q: FloatArray, n: Int, rate: Int, now: Long): List<DmrFrame> {
        out.clear()
        if (rate != curRate) {
            val factor = (rate / RATE).coerceAtLeast(1)
            dec = FirDecim(factor, 9_000.0, rate.toDouble(), maxOf(factor + 1, 48))
            decQ = FirDecim(factor, 9_000.0, rate.toDouble(), maxOf(factor + 1, 48))
            reset(); curRate = rate
        }
        val cap = n / (rate / RATE) + 64
        if (bi.size < cap) { bi = FloatArray(cap); bq = FloatArray(cap) }
        val n1 = dec.process(i, n, bi)
        decQ.process(q, n, bq)
        for (k in 0 until n1) {
            val zi = bi[k]; val zq = bq[k]
            val re = zi * lastI + zq * lastQ
            val im = zq * lastI - zi * lastQ
            lastI = zi; lastQ = zq
            val f = atan2(im, re)
            boxSum += f - box[boxPos]; box[boxPos] = f; boxPos = (boxPos + 1) % SPS // 1-symbol boxcar
            val sm = boxSum / SPS
            pos++
            ring[(pos and MASK.toLong()).toInt()] = sm
            if (!locked) {
                val alpha = maxOf(0.0006f, 1f / (nAdapt + 24f)); if (nAdapt < 1_000_000) nAdapt++
                dc += alpha * (sm - dc)
                amp += alpha * (abs(sm - dc) - amp); amp = amp.coerceIn(0.04f, 0.5f)
                searchStep(sm)
            } else {
                if (pos == nextEnd) processFrame(now)
                if (locked && pos - lastGood > LOSS_SAMPLES) unlock()
            }
        }
        return out
    }

    private fun slice(x: Float): Int = when { x > amp -> 3; x > 0f -> 1; x > -amp -> -1; else -> -3 }
    private fun sliceAt(idx: Long): Int {
        val d = Dmr.dibitForSymbol(slice(ring[(idx and MASK.toLong()).toInt()] - dc))
        return if (inv) d xor 2 else d
    }

    private fun searchStep(sm: Float) {
        val ph = (pos % SPS).toInt()
        val d = Dmr.dibitForSymbol(slice(sm - dc))
        reg[ph] = ((reg[ph] shl 2) or d.toLong()) and MASK48
        if (fill[ph] < 24) fill[ph]++
        if (fill[ph] >= 24) {
            var bestE = 99; var bestS = Dmr.Sync.BS_VOICE; var bestInv = false
            for (s in SYNCS) {
                val e = java.lang.Long.bitCount(reg[ph] xor s.bits48)
                if (e < bestE) { bestE = e; bestS = s; bestInv = false }
                val e2 = java.lang.Long.bitCount(reg[ph] xor (s.bits48 xor INV48))
                if (e2 < bestE) { bestE = e2; bestS = s; bestInv = true }
            }
            if (bestE <= MAX_ERR_SEARCH) {
                if (candFirst < 0) {
                    candFirst = pos; candLast = pos; candErr = bestE; candSync = bestS; candInv = bestInv; candDeadline = pos + SPS
                } else if (bestE < candErr) {
                    candFirst = pos; candLast = pos; candErr = bestE; candSync = bestS; candInv = bestInv
                } else if (bestE == candErr && bestS == candSync && bestInv == candInv) candLast = pos
            }
        }
        // wait one symbol period for a better timing phase, then lock on the middle of the best run
        if (candFirst >= 0 && pos >= candDeadline) {
            val c = (candFirst + candLast) / 2
            inv = candInv; ms = candSync.ms
            calibrate(c, candSync)
            locked = true; lastGood = pos
            nextEnd = c + 54L * SPS // the sync word's last dibit is burst dibit 77; the burst's last dibit (131) is 54 symbols later
            candFirst = -1L
        }
    }

    /** Slicer centre/deviation from the 24 known sync symbols (DMR sync words use only the outer +-3 levels). */
    private fun calibrate(lastSyncDibitPos: Long, s: Dmr.Sync) {
        var sp = 0f; var np = 0; var sn = 0f; var nn = 0
        for (k in 0 until 24) {
            val v = ring[((lastSyncDibitPos - (23 - k) * SPS) and MASK.toLong()).toInt()]
            var sym = Dmr.symbolForDibit(s.dibit(k)); if (inv) sym = -sym
            if (sym > 0) { sp += v; np++ } else if (sym < 0) { sn += v; nn++ }
        }
        if (np >= 3 && nn >= 3) {
            val mp = sp / np; val mm = sn / nn
            val g = (mp - mm) / 6f
            if (g > 0.01f) { dc = (mp + mm) / 2f; amp = (2f * g).coerceIn(0.04f, 0.5f) }
        }
    }

    private fun readCenter(start: Long): Long {
        var v = 0L
        for (k in 0 until 24) v = (v shl 2) or sliceAt(start + (54 + k) * SPS).toLong()
        return v
    }

    private fun bestSync(w: Long): Pair<Dmr.Sync, Int> {
        var bs = Dmr.Sync.BS_VOICE; var be = 99
        for (s in SYNCS) { val e = java.lang.Long.bitCount(w xor s.bits48); if (e < be) { be = e; bs = s } }
        return bs to be
    }

    private fun processFrame(now: Long) {
        val end = nextEnd
        val start = end - (Dmr.BURST_DIBITS - 1) * SPS // sample of burst dibit 0
        val cach = IntArray(24)
        for (k in 0 until Dmr.CACH_DIBITS) {
            val d = sliceAt(start - (Dmr.CACH_DIBITS - k) * SPS)
            cach[Dmr.CACH_INTERLEAVE[2 * k]] = d shr 1; cach[Dmr.CACH_INTERLEAVE[2 * k + 1]] = d and 1
        }
        val bits = IntArray(Dmr.BURST_BITS)
        for (j in 0 until Dmr.BURST_DIBITS) { val d = sliceAt(start + j * SPS); bits[2 * j] = d shr 1; bits[2 * j + 1] = d and 1 }

        val (s0, e0) = bestSync(readCenter(start))
        var found: Dmr.Sync? = null; var err = 99; var adj = 0
        if (e0 <= MAX_ERR_LOCK) {
            found = s0; err = e0; lastGood = end
            if (e0 > 0) { // timing nudge: is the sync word a better match one sample early or late?
                var bestE = e0
                for (o in intArrayOf(-1, 1)) {
                    val e = java.lang.Long.bitCount(readCenter(start + o) xor s0.bits48)
                    if (e < bestE) { bestE = e; adj = o }
                }
            }
            if (e0 <= 2) calibrate(start + 77L * SPS, s0)
        }
        // a sync of the other family (BS vs MS) means the lock was on the wrong source type: follow it
        if (found != null && found.ms != ms) ms = found.ms
        out.add(DmrFrame(now, ms, cach, bits, found, err))
        nextEnd = end + (if (ms) 2 * Dmr.FRAME_DIBITS else Dmr.FRAME_DIBITS) * SPS.toLong() + adj
    }
}
