package com.example.rtlsdr

import kotlin.math.abs
import kotlin.math.atan2

/**
 * DMR (ETSI TS 102 361-1) physical-layer burst sync and 4FSK demod -- the "KEYUP" mode's stage 1/2
 * (burst sync + framing), the DMR counterpart of [FcchDetector]/[GsmBurstVerifier] for GSM.
 *
 * DMR's air interface is 4-level FSK at 4800 symbols/s (9600 bit/s, 2 bits/symbol), and every burst is
 * exactly 264 bits: 108 payload bits, a 48-bit SYNC (or embedded-signalling) field, then 108 more
 * payload bits -- confirmed against ETSI TS 102 361-1 Table 9.2 and public reference decoders (e.g.
 * g4klx/DMRGateway, dsd-fme) rather than reconstructed from memory, since a wrong sync word here means
 * nothing downstream ever locks.
 */
object Dmr {
    const val SYMBOL_RATE = 4800.0
    const val BIT_RATE = 9600.0          // 2 bits/symbol
    const val BURST_BITS = 264            // 108 + 48 + 108
    const val SYNC_BITS = 48
    const val HALF_BITS = 108
    /** Bits between the starts of two consecutive bursts of the SAME timeslot (2 x (264 burst + 24 CACH)). */
    const val SLOT_STRIDE = 576

    /** The four deviations map to dibits per ETSI Table 9.2: +3=01, +1=00, -1=10, -3=11. */
    fun dibitForSymbol(sym: Int): Int = when (sym) { 3 -> 0b01; 1 -> 0b00; -1 -> 0b10; else -> 0b11 }

    /** 48-bit SYNC patterns (ETSI TS 102 361-1 Table 9.2). */
    enum class Sync(val bits48: Long) {
        BS_VOICE(0x755FD7DF75F7L),
        BS_DATA(0xDFF57D75DF5DL),
        MS_VOICE(0x7F7D5DD57DFDL),
        MS_DATA(0xD5D7F77FD757L);
        val isVoice: Boolean get() = this == BS_VOICE || this == MS_VOICE
    }

    /** One FULL 264-bit burst (payload_1 108 | sync/EMB 48 | payload_2 108). [follow] = true for the voice
        bursts B..F that follow a voice-sync burst A: they carry embedded signalling instead of a SYNC, so
        they are located by timing (576 bits after the previous one) rather than found by correlation. */
    class Burst(val sync: Sync, val bits: IntArray, val atMs: Long, val follow: Boolean = false, val index: Int = 0)
}

/**
 * Demodulates 4FSK IQ and finds bursts by correlating against [Dmr.Sync].
 *
 * Compared with the first version this:
 *  - tracks all 10 symbol-timing phases in parallel and keeps the best-matching one (no timing recovery
 *    before, so a bad sampling phase meant sampling on the symbol transitions),
 *  - slices with an ADAPTIVE centre/amplitude (the old fixed 0.35 rad threshold was ~1.4x the real outer
 *    deviation, so outer symbols never sliced correctly, and any tuning error shifted everything),
 *  - captures the full 264-bit burst (the old one only kept 108 bits, so voice frames were wrong),
 *  - after a voice SYNC, keeps going for the 5 sync-less voice bursts of the superframe, so you get
 *    continuous voice and not 1 burst in 6.
 */
class DmrBurstSync {
    companion object {
        const val RATE = 48_000             // 10 samples/symbol at 4800 baud
        private const val SPS = RATE / 4800
        private const val HIST = 320
        private const val PREFIX = Dmr.HALF_BITS + Dmr.SYNC_BITS // 156 bits already seen when the sync completes
        private const val MAX_FOLLOW = 5
        private const val FOLLOW_BITS = MAX_FOLLOW * Dmr.SLOT_STRIDE + Dmr.BURST_BITS
        private const val MASK48 = (1L shl 48) - 1
        private const val MAX_ERR = 4
        private val SYNCS = Dmr.Sync.values()
    }

    private lateinit var dec: FirDecim
    private lateinit var decQ: FirDecim
    private var curRate = 0
    private var lastI = 1f; private var lastQ = 0f
    private var bi = FloatArray(0); private var bq = FloatArray(0)

    // symbol slicer state
    private val box = FloatArray(SPS); private var boxPos = 0; private var boxSum = 0f
    private var dc = 0f; private var amp = 0.17f // 2 x the ideal +-1 deviation (0.085 rad/sample at 48 kS/s)
    private var sampleCtr = 0
    private var nAdapt = 0 // samples since (re)start: drives the fast-then-slow centre/amplitude tracking

    // one bit history + 48-bit shift register per timing phase
    private val hist = Array(SPS) { IntArray(HIST) }
    private val hpos = IntArray(SPS)
    private val hfill = IntArray(SPS)
    private val reg = LongArray(SPS)

    // burst currently being collected (after a sync match)
    private val pend = IntArray(FOLLOW_BITS)
    private var pendPhase = -1
    private var pendFill = 0
    private var pendNext = 0
    private var pendMaxK = 0
    private var pendErr = 99
    private var pendSync = Dmr.Sync.BS_VOICE
    private var pendMs = 0L
    private var windowLeft = 0

    private val out = ArrayList<Dmr.Burst>()

    fun reset() {
        curRate = 0; sampleCtr = 0; boxSum = 0f; boxPos = 0; java.util.Arrays.fill(box, 0f)
        dc = 0f; amp = 0.17f; nAdapt = 0
        for (p in 0 until SPS) { hpos[p] = 0; hfill[p] = 0; reg[p] = 0L }
        pendPhase = -1; pendFill = 0; pendNext = 0; pendErr = 99; windowLeft = 0
    }

    fun process(i: FloatArray, q: FloatArray, n: Int, rate: Int, now: Long): List<Dmr.Burst> {
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
            // 1-symbol boxcar = crude matched filter
            boxSum += f - box[boxPos]; box[boxPos] = f; boxPos = (boxPos + 1) % SPS
            val sm = boxSum / SPS
            // slow centre (tuning error / drift) and amplitude tracking; DMR is scrambled so symbols are balanced
            // running-mean style start-up (1/n) so a tuning offset of a few kHz is absorbed within a few ms,
            // settling to a slow 0.0006 tracker once enough symbols have been seen
            val alpha = maxOf(0.0006f, 1f / (nAdapt + 24f))
            if (nAdapt < 1_000_000) nAdapt++
            dc += alpha * (sm - dc)
            amp += alpha * (abs(sm - dc) - amp)
            amp = amp.coerceIn(0.04f, 0.5f)
            if (windowLeft > 0) windowLeft--
            val ph = sampleCtr % SPS
            sampleCtr++
            onSymbol(ph, slice(sm - dc), now)
        }
        return out
    }

    /** For balanced 4FSK, mean |x| = 2 units, and the inner/outer boundary sits at 2 units = [amp]. */
    private fun slice(x: Float): Int = when {
        x > amp -> 3
        x > 0f -> 1
        x > -amp -> -1
        else -> -3
    }

    private fun onSymbol(ph: Int, sym: Int, now: Long) {
        val d = Dmr.dibitForSymbol(sym)
        pushBit(ph, (d shr 1) and 1, now)
        pushBit(ph, d and 1, now)
    }

    private fun pushBit(ph: Int, bit: Int, now: Long) {
        val h = hist[ph]
        h[hpos[ph]] = bit; hpos[ph] = (hpos[ph] + 1) % HIST
        if (hfill[ph] < HIST) hfill[ph]++
        reg[ph] = ((reg[ph] shl 1) or bit.toLong()) and MASK48

        if (ph == pendPhase) appendPending(bit)

        if (hfill[ph] < PREFIX) return
        // while a burst is being collected, only allow a BETTER phase to take over within the timing window
        if (pendPhase >= 0 && windowLeft <= 0) return
        for (s in SYNCS) {
            val err = java.lang.Long.bitCount(reg[ph] xor s.bits48)
            if (err > MAX_ERR || (pendPhase >= 0 && err >= pendErr)) continue
            if (pendPhase < 0) windowLeft = SPS
            pendPhase = ph; pendErr = err; pendSync = s; pendMs = now
            pendMaxK = if (s.isVoice) MAX_FOLLOW else 0
            pendNext = 0
            // snapshot the 156 bits that ended with this sync (payload_1 + sync)
            var idx = hpos[ph] - PREFIX
            for (b in 0 until PREFIX) { pend[b] = h[((idx % HIST) + HIST) % HIST]; idx++ }
            pendFill = PREFIX
            return
        }
    }

    private fun appendPending(bit: Int) {
        if (pendFill < FOLLOW_BITS) pend[pendFill++] = bit
        while (pendNext <= pendMaxK && pendFill >= Dmr.SLOT_STRIDE * pendNext + Dmr.BURST_BITS) {
            val from = Dmr.SLOT_STRIDE * pendNext
            out.add(Dmr.Burst(pendSync, pend.copyOfRange(from, from + Dmr.BURST_BITS), pendMs + pendNext * 60L, pendNext > 0, pendNext))
            pendNext++
        }
        if (pendNext > pendMaxK) { pendPhase = -1; pendErr = 99; windowLeft = 0 }
    }
}
