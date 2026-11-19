package com.example.rtlsdr

import kotlin.math.abs
import kotlin.math.atan2

/**
 * APCO P25 Phase 1 (C4FM, 4800 symbols/s) physical layer, structured after dsd-fme (dsd_symbol.c, dsd_frame_sync.c,
 * p25p1_ldu*.c). Frame = 48-bit sync, 64-bit NID (NAC + DUID, BCH), payload; a status symbol is inserted after every 35
 * data symbols. Constants verified against dsd-fme: sync 0x5575F5FF77FF, LDU = 864 symbols including status symbols.
 */
object P25 {
    const val SYNC48 = 0x5575F5FF77FFL
    const val LDU_SYMBOLS = 864
    const val SYNC_SYMBOLS = 24
    const val NID_SYMBOLS = 32

    const val DUID_HDU = 0x0; const val DUID_TDU = 0x3; const val DUID_LDU1 = 0x5; const val DUID_TSDU = 0x7
    const val DUID_LDU2 = 0xA; const val DUID_PDU = 0xC; const val DUID_TDULC = 0xF

    fun duidName(d: Int) = when (d) {
        DUID_HDU -> "HDU"; DUID_TDU -> "TDU"; DUID_LDU1 -> "LDU1"; DUID_TSDU -> "TSDU"
        DUID_LDU2 -> "LDU2"; DUID_PDU -> "PDU"; DUID_TDULC -> "TDULC"; else -> "DUID $d"
    }
}

/**
 * One received P25 frame. [sym] holds the frame's stream symbols (dibits, status symbols INCLUDED, index 0 = first sync
 * symbol) for LDU1/LDU2, null for other frame types whose payload isn't decoded.
 */
class P25Frame(val atMs: Long, val nac: Int, val duid: Int, val nidBitErrors: Int, val sym: IntArray?)

/**
 * Symbol + frame synchroniser: 4FSK discriminator -> symbol slicer -> sync search at all 10 timing phases (inverted
 * polarity too) -> slicer calibrated from the known sync symbols -> NID decoded (BCH, nearest valid NAC/DUID) ->
 * for voice frames the whole 864-symbol frame is cut out of the sample ring and handed on.
 */
class P25FrameSync {
    companion object {
        const val RATE = 48_000
        const val SPS = RATE / 4800
        private const val RING = 1 shl 15
        private const val MASK = (RING - 1).toLong()
        private const val MASK48 = (1L shl 48) - 1
        private const val INV48 = 0xAAAAAAAAAAAAL
        private const val MAX_ERR = 4
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
    private var candFirst = -1L; private var candLast = 0L; private var candErr = 99; private var candInv = false; private var candDeadline = 0L

    // a locked frame waiting for more samples
    private var pending = false; private var pc = 0L; private var pInv = false
    private var nidDone = false; private var pNac = 0; private var pDuid = 0; private var pErr = 0
    private var skipUntil = 0L
    private var lastNac = -1
    private val out = ArrayList<P25Frame>()

    /** True from a sync hit until the frame has been delivered. */
    val busy: Boolean get() = pending

    fun reset() {
        curRate = 0; boxSum = 0f; boxPos = 0; java.util.Arrays.fill(box, 0f)
        dc = 0f; amp = 0.17f; nAdapt = 0; candFirst = -1L; pending = false; skipUntil = 0L; lastNac = -1
        java.util.Arrays.fill(reg, 0L); java.util.Arrays.fill(fill, 0)
    }

    fun process(i: FloatArray, q: FloatArray, n: Int, rate: Int, now: Long): List<P25Frame> {
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
            boxSum += f - box[boxPos]; box[boxPos] = f; boxPos = (boxPos + 1) % SPS
            val sm = boxSum / SPS
            pos++
            ring[(pos and MASK).toInt()] = sm
            if (!pending) {
                val alpha = maxOf(0.0006f, 1f / (nAdapt + 24f)); if (nAdapt < 1_000_000) nAdapt++
                dc += alpha * (sm - dc)
                amp += alpha * (abs(sm - dc) - amp); amp = amp.coerceIn(0.04f, 0.5f)
                searchStep(sm)
            } else pendingStep(now)
        }
        return out
    }

    private fun slice(x: Float): Int = when { x > amp -> 3; x > 0f -> 1; x > -amp -> -1; else -> -3 }
    private fun dibitAt(idx: Long): Int {
        val d = Dmr.dibitForSymbol(slice(ring[(idx and MASK).toInt()] - dc))
        return if (pInv) d xor 2 else d
    }
    /** Sample position of stream symbol [s] (sync's last symbol, s = 23, is at [pc]). */
    private fun symPos(s: Int) = pc + (s - 23).toLong() * SPS

    private fun searchStep(sm: Float) {
        val ph = (pos % SPS).toInt()
        val d = Dmr.dibitForSymbol(slice(sm - dc))
        reg[ph] = ((reg[ph] shl 2) or d.toLong()) and MASK48
        if (fill[ph] < 24) fill[ph]++
        if (pos >= skipUntil && fill[ph] >= 24) {
            val e1 = java.lang.Long.bitCount(reg[ph] xor P25.SYNC48)
            val e2 = java.lang.Long.bitCount(reg[ph] xor (P25.SYNC48 xor INV48))
            val e = minOf(e1, e2)
            if (e <= MAX_ERR) {
                val inv = e2 < e1
                if (candFirst < 0) { candFirst = pos; candLast = pos; candErr = e; candInv = inv; candDeadline = pos + SPS }
                else if (e < candErr) { candFirst = pos; candLast = pos; candErr = e; candInv = inv }
                else if (e == candErr && inv == candInv) candLast = pos
            }
        }
        if (candFirst >= 0 && pos >= candDeadline) {
            pc = (candFirst + candLast) / 2; pInv = candInv; candFirst = -1L
            calibrate()
            pending = true; nidDone = false
        }
    }

    /** Slicer centre / deviation from the 24 known sync symbols (only the outer +-3 levels occur in the P25 sync word). */
    private fun calibrate() {
        var sp = 0f; var np = 0; var sn = 0f; var nn = 0
        for (k in 0 until 24) {
            val v = ring[((pc - (23 - k) * SPS) and MASK).toInt()]
            var sym = Dmr.symbolForDibit(((P25.SYNC48 shr (46 - 2 * k)) and 3L).toInt()); if (pInv) sym = -sym
            if (sym > 0) { sp += v; np++ } else if (sym < 0) { sn += v; nn++ }
        }
        if (np >= 3 && nn >= 3) {
            val mp = sp / np; val mm = sn / nn; val g = (mp - mm) / 6f
            if (g > 0.01f) { dc = (mp + mm) / 2f; amp = (2f * g).coerceIn(0.04f, 0.5f) }
        }
    }

    private fun pendingStep(now: Long) {
        if (!nidDone) {
            // NID = stream symbols 24..56 minus the status symbol at 35: complete once symbol 56 is in the ring
            if (pos < symPos(56)) return
            var w = 0L
            for (s in 24..56) {
                if (s == 35) continue // status symbol
                w = (w shl 2) or dibitAt(symPos(s)).toLong()
            }
            // 32 dibits = 64 bits; the 64th bit (overall parity) is dropped, keeping the first 63
            val nid = P25Fec.decodeNid(w ushr 1, lastNac)
            if (nid == null) { pending = false; skipUntil = symPos(40); return }
            pNac = nid.nac; pDuid = nid.duid; pErr = nid.bitErrors; lastNac = nid.nac; nidDone = true
            if (pDuid != P25.DUID_LDU1 && pDuid != P25.DUID_LDU2) {
                out.add(P25Frame(now, pNac, pDuid, pErr, null))
                pending = false; skipUntil = symPos(56)
                return
            }
        }
        if (pos < symPos(P25.LDU_SYMBOLS - 1)) return
        val sym = IntArray(P25.LDU_SYMBOLS) { dibitAt(symPos(it)) }
        out.add(P25Frame(now, pNac, pDuid, pErr, sym))
        pending = false; skipUntil = symPos(P25.LDU_SYMBOLS - 1)
    }
}

/** P25 Phase 1 IMBE (7200x4400) vocoder: pure Kotlin ([AmbeDecoder.decodeImbe], a port of mbelib verified against it). */
object P25Vocoder {
    private val decoder = AmbeDecoder()
    /** Parses the lookup tables now so the first voice frame doesn't stall the radio thread. */
    fun warmUp() { try { AmbeDecoder().decodeImbe(IntArray(144)) } catch (_: Exception) {} }
    @Synchronized fun reset() = decoder.reset()
    /** [bits144] = one raw 144-bit IMBE frame. Returns 160 samples of 8 kHz PCM (20 ms), or null if malformed. */
    @Synchronized fun decode(bits144: IntArray): ShortArray? = try { decoder.decodeImbe(bits144) } catch (_: Exception) { null }
}
