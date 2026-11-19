package com.example.rtlsdr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * FM Radio Data System (RDS, EN 50067 / IEC 62106) decoder: station name (PS), programme type (PTY), radio text
 * (RT), traffic flags and the PI code, from the FM discriminator output ("MPX") of a broadcast FM station.
 *
 * Input: the demodulated composite signal at 240 kS/s, taken BEFORE de-emphasis (de-emphasis would attenuate the
 * 57 kHz subcarrier). Chain:
 *   mix with 57 kHz -> low-pass + decimate by 10 (24 kS/s) -> Costas loop (BPSK carrier recovery; the loop's
 *   frequency also gives the exact bit clock, 57 kHz / 48 = 1187.5 Hz, which absorbs the dongle's clock error)
 *   -> bi-phase (Manchester) integrate-and-dump at 8 candidate bit phases, keeping the strongest
 *   -> differential decode -> 26-bit block sync using the five offset words A, B, C, C', D (10-bit CRC)
 *   -> group decoding (0A/0B programme service name, 2A/2B radio text).
 * The CRC/offset scheme was cross-checked against the redsea reference decoder.
 */
class RdsDecoder {
    companion object {
        const val FS_IN = 240_000
        private const val DEC = 10
        private const val FS = FS_IN / DEC                 // 24 kS/s after decimation
        private const val NH = 8                           // bit-phase hypotheses
        private val OFFSETS = intArrayOf(0x0FC, 0x198, 0x168, 0x350, 0x1B4) // A, B, C, C', D
        // 57 kHz / 240 kHz = 19/80, so the mixer repeats every 80 samples
        private val MIX_C = FloatArray(80) { cos(2.0 * PI * 19.0 * it / 80.0).toFloat() }
        private val MIX_S = FloatArray(80) { sin(2.0 * PI * 19.0 * it / 80.0).toFloat() }

        val PTY_NAMES = arrayOf("None", "News", "Current affairs", "Information", "Sport", "Education", "Drama", "Culture",
            "Science", "Varied", "Pop music", "Rock music", "Easy listening", "Light classical", "Serious classical",
            "Other music", "Weather", "Finance", "Children's", "Social affairs", "Religion", "Phone-in", "Travel",
            "Leisure", "Jazz", "Country", "National music", "Oldies", "Folk music", "Documentary", "Alarm test", "Alarm")

        /** 10-bit check: remainder of data * x^10 divided by g(x) = x^10+x^8+x^7+x^5+x^4+x^3+1. */
        fun crc10(data16: Int): Int {
            var reg = data16 shl 10
            for (i in 25 downTo 10) if ((reg shr i) and 1 != 0) reg = reg xor (0x5B9 shl (i - 10))
            return reg and 0x3FF
        }
    }

    // ---- decoded results (written on the DSP thread, read by the engine) ----
    @Volatile var pi = -1; private set
    @Volatile var ps = ""; private set
    @Volatile var rt = ""; private set
    @Volatile var pty = -1; private set
    @Volatile var tp = false; private set
    @Volatile var ta = false; private set
    @Volatile var synced = false; private set
    @Volatile var groups = 0; private set
    val ptyName: String get() = if (pty in PTY_NAMES.indices) PTY_NAMES[pty] else ""

    // ---- DSP state ----
    private val firI = FirDecim(DEC, 2800.0, FS_IN.toDouble(), 241)
    private val firQ = FirDecim(DEC, 2800.0, FS_IN.toDouble(), 241)
    private var mixPos = 0
    private var mi = FloatArray(0); private var mq = FloatArray(0)
    private var bi = FloatArray(0); private var bq = FloatArray(0)
    private var cPhase = 0.0; private var cFreq = 0.0; private var cFreqSm = 0.0
    private var agc = 1e-6
    private val ph = DoubleArray(NH) { it.toDouble() / NH }
    private val s1 = DoubleArray(NH); private val s2 = DoubleArray(NH); private val en = DoubleArray(NH)
    private var best = 0; private var sampleCtr = 0
    private var lastB = 0

    // ---- block / group state ----
    private var reg = 0; private var bitCount = 0L
    private var lastT = -1; private var lastPos = 0L
    private var expect = 0; private var sinceBlock = 0; private var badRun = 0
    private val blk = IntArray(4); private val blkOk = BooleanArray(4)
    private val psChars = CharArray(8) { ' ' }
    private val rtChars = CharArray(64) { ' ' }
    private var rtAb = -1

    @Volatile private var pendingReset = false
    /** Safe to call from any thread (e.g. the UI when the frequency changes): the reset happens on the DSP thread. */
    fun requestReset() { pendingReset = true }

    fun reset() {
        java.util.Arrays.fill(ph, 0.0); for (h in 0 until NH) ph[h] = h.toDouble() / NH
        java.util.Arrays.fill(s1, 0.0); java.util.Arrays.fill(s2, 0.0); java.util.Arrays.fill(en, 0.0)
        cPhase = 0.0; cFreq = 0.0; cFreqSm = 0.0; agc = 1e-6; best = 0; sampleCtr = 0; lastB = 0
        resetSync()
        clearText(); pi = -1; pty = -1; tp = false; ta = false; groups = 0
    }

    private fun resetSync() { synced = false; reg = 0; bitCount = 0; lastT = -1; expect = 0; sinceBlock = 0; badRun = 0 }
    private fun clearText() { java.util.Arrays.fill(psChars, ' '); java.util.Arrays.fill(rtChars, ' '); ps = ""; rt = ""; rtAb = -1 }

    /** [mpx] = [n] samples of FM discriminator output at 240 kS/s (any scale). */
    fun process(mpx: FloatArray, n: Int) {
        if (pendingReset) { pendingReset = false; reset() }
        if (mi.size < n) { mi = FloatArray(n); mq = FloatArray(n) }
        val cap = n / DEC + 32
        if (bi.size < cap) { bi = FloatArray(cap); bq = FloatArray(cap) }
        for (k in 0 until n) {
            val x = mpx[k]
            mi[k] = x * MIX_C[mixPos]; mq[k] = -x * MIX_S[mixPos]
            mixPos++; if (mixPos == 80) mixPos = 0
        }
        val n1 = firI.process(mi, n, bi)
        firQ.process(mq, n, bq)
        for (k in 0 until n1) costasStep(bi[k].toDouble(), bq[k].toDouble())
    }

    // Costas loop ~30 Hz wide (zeta 0.707) at 24 kS/s
    private val alpha = 0.0111; private val beta = 6.2e-5

    private fun costasStep(i: Double, q: Double) {
        val c = cos(cPhase); val s = sin(cPhase)
        val zi = i * c + q * s
        val zq = q * c - i * s
        agc += 0.0005 * ((zi * zi + zq * zq) - agc)
        val g = 1.0 / sqrt(agc + 1e-18)
        val ni = zi * g; val nq = zq * g
        val err = (ni * nq).coerceIn(-1.0, 1.0)
        cFreq = (cFreq + beta * err).coerceIn(-0.03, 0.03)       // +-115 Hz
        cPhase += cFreq + alpha * err
        if (cPhase > PI) cPhase -= 2 * PI else if (cPhase < -PI) cPhase += 2 * PI
        cFreqSm += 0.002 * (cFreq - cFreqSm)
        symbolStep(ni)
    }

    private fun symbolStep(x: Double) {
        // exact bit clock from the recovered carrier: bit rate = f57 / 48
        val f57 = 57000.0 + cFreqSm * FS / (2 * PI)
        val inc = f57 / 48.0 / FS
        for (h in 0 until NH) {
            val p = ph[h]
            if (p < 0.5) s1[h] += x else s2[h] += x
            val np = p + inc
            if (np >= 1.0) {
                ph[h] = np - 1.0
                val m = s1[h] - s2[h]
                en[h] += 0.02 * (abs(m) - en[h])
                s1[h] = 0.0; s2[h] = 0.0
                if (h == best) bit(if (m > 0) 1 else 0)
            } else ph[h] = np
        }
        if (++sampleCtr >= 4096) {
            sampleCtr = 0
            var b = best
            for (h in 0 until NH) if (en[h] > en[b] * 1.2) b = h
            if (b != best) { best = b; lastB = 0; resetSync() }
        }
    }

    // ---- bits -> blocks ----
    private fun bit(b: Int) {
        val d = b xor lastB; lastB = b
        reg = ((reg shl 1) or d) and 0x3FFFFFF
        bitCount++
        if (!synced) {
            val t = blockType(reg)
            if (t >= 0) {
                if (lastT >= 0 && bitCount - lastPos == 26L && follows(lastT, t)) {
                    synced = true; badRun = 0; sinceBlock = 0
                    expect = when (t) { 0 -> 1; 1 -> 2; 2, 3 -> 3; else -> 0 }
                }
                lastT = t; lastPos = bitCount
            }
        } else if (++sinceBlock == 26) {
            sinceBlock = 0
            val t = blockType(reg)
            val ok = when (expect) { 0 -> t == 0; 1 -> t == 1; 2 -> t == 2 || t == 3; else -> t == 4 }
            blkOk[expect] = ok
            if (ok) { blk[expect] = reg ushr 10; badRun = 0 } else if (++badRun > 12) { resetSync(); return }
            if (expect == 3) groupDone()
            expect = (expect + 1) and 3
            if (expect == 0) { java.util.Arrays.fill(blkOk, false) }
        }
    }

    private fun follows(a: Int, b: Int) = (a == 0 && b == 1) || (a == 1 && (b == 2 || b == 3)) ||
        ((a == 2 || a == 3) && b == 4) || (a == 4 && b == 0)

    /** 0..4 = offset word A,B,C,C',D if the 26-bit word is a valid block of that type, else -1. */
    private fun blockType(w: Int): Int {
        val syn = crc10(w ushr 10) xor (w and 0x3FF)
        for (t in 0 until 5) if (OFFSETS[t] == syn) return t
        return -1
    }

    // ---- groups ----
    private fun groupDone() {
        if (blkOk[0] && blk[0] != pi) { pi = blk[0]; clearText(); pty = -1 }   // new station
        if (!blkOk[1]) return
        val b = blk[1]
        val type = b ushr 12; val versionB = (b shr 11) and 1 == 1
        tp = (b shr 10) and 1 == 1; pty = (b shr 5) and 0x1F
        groups++
        when (type) {
            0 -> {
                ta = (b shr 4) and 1 == 1
                if (blkOk[3]) {
                    val addr = b and 3
                    psChars[addr * 2] = ch(blk[3] shr 8); psChars[addr * 2 + 1] = ch(blk[3] and 0xFF)
                    ps = String(psChars).trimEnd()
                }
            }
            2 -> {
                val addr = b and 0xF
                val ab = (b shr 4) and 1
                if (rtAb != -1 && ab != rtAb) java.util.Arrays.fill(rtChars, ' ')   // text A/B flag toggled: new message
                rtAb = ab
                if (!versionB) {
                    if (blkOk[2] && blkOk[3]) {
                        val p = addr * 4
                        rtChars[p] = ch(blk[2] shr 8); rtChars[p + 1] = ch(blk[2] and 0xFF)
                        rtChars[p + 2] = ch(blk[3] shr 8); rtChars[p + 3] = ch(blk[3] and 0xFF)
                    }
                } else if (blkOk[3]) {
                    val p = addr * 2
                    rtChars[p] = ch(blk[3] shr 8); rtChars[p + 1] = ch(blk[3] and 0xFF)
                }
                val end = String(rtChars).indexOf('\r')
                rt = (if (end >= 0) String(rtChars, 0, end) else String(rtChars)).trim()
            }
        }
    }

    private fun ch(v: Int): Char = when {
        v == 0x0D -> '\r'
        v in 0x20..0x7E -> v.toChar()
        else -> ' '
    }
}
