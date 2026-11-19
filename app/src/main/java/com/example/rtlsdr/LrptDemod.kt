package com.example.rtlsdr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Meteor-M LRPT demodulator: complex baseband at any sample rate -> signed 8-bit soft symbols (I, Q interleaved) for [LrptDecoder].
 *
 * Chain (design follows the structure of the open Meteor demodulators): low-pass + decimate, fractional resample to 4 samples/symbol,
 * AGC, root-raised-cosine matched filter (alpha 0.6), coarse frequency estimate from the 4th-power spectrum (so tuning error and
 * Doppler of several kHz are pulled in), a per-sample Costas loop whose OQPSK mode delays Q by half a symbol, and a Gardner
 * timing loop with cubic interpolation. The I/Q orientation and sign are NOT resolved here: the decoder's marker search does that.
 */
class LrptDemod(private val symbolRate: Double = 72_000.0, private val oqpsk: Boolean = true) {
    companion object {
        private const val SPS = 4
        private const val RRC_SPAN = 8
        private const val FFT_N = 2048
        private const val AFC_AVG = 24 // FFT frames averaged per estimate
        private const val AFC_Q_MIN = 5.0 // peak / background needed to call it a signal (measured: noise alone reaches ~3.1, a signal at 4 dB Es/N0 gives ~8)
    }
    private val fw = symbolRate * SPS

    // ---- stage A/B: decimate + fractional resample to fw
    private var curRate = 0
    private var dec = 1
    private var firI: FirDecim? = null; private var firQ: FirDecim? = null
    private var tmpI = FloatArray(0); private var tmpQ = FloatArray(0)
    private var rm = 0.0
    private var dcI = 0f; private var dcQ = 0f
    private var rsPhase = 0.0
    private val hist = Array(2) { FloatArray(4) } // last 4 samples of I and Q at the intermediate rate

    // ---- AGC
    private var gain = 1.0f
    private var magAvg = 0f

    // ---- RRC
    private val rrc = FloatArray(SPS * RRC_SPAN + 1)
    private val bufI = FloatArray(rrc.size); private val bufQ = FloatArray(rrc.size); private var bufPos = 0

    // ---- carrier loop
    private var phase = 0.0; private var freq = 0.0
    private var alpha = 0.0; private var beta = 0.0
    private val qDelay = FloatArray(SPS / 2); private var qDelayPos = 0
    @Volatile var carrierHz = 0.0; private set
    @Volatile var lockMetric = 1.0f; private set // smoothed |phase error| (small = locked)

    // ---- AFC
    private val afcRe = FloatArray(FFT_N); private val afcIm = FloatArray(FFT_N); private var afcFill = 0
    private val afcPow = DoubleArray(FFT_N); private var afcFrames = 0
    private val twRe = FloatArray(FFT_N / 2) { cos(-2 * PI * it / FFT_N).toFloat() }
    private val twIm = FloatArray(FFT_N / 2) { sin(-2 * PI * it / FFT_N).toFloat() }
    private val win = FloatArray(FFT_N) { (0.5 - 0.5 * cos(2 * PI * it / (FFT_N - 1))).toFloat() }
    private var sampleCount = 0L
    private var lastValidSample = -1L
    private var validRun = 0
    /** Ratio of the 4th-power spectrum peak to its background at the last estimate (2-3 for noise, 8 at 4 dB Es/N0, 80 at 12 dB). */
    @Volatile var afcQ = 0.0; private set
    /** True while a QPSK/OQPSK carrier has been seen recently (estimates valid in the last 1.5 s). */
    val signalPresent: Boolean get() = validRun >= 2 && lastValidSample >= 0 && sampleCount - lastValidSample < (1.5 * fw).toLong()

    // ---- timing recovery (Gardner) on the aligned stream
    private val ringI = FloatArray(64); private val ringQ = FloatArray(64); private var ringPos = 0L // sample count
    private var nextSym = 0.0
    private var ts = SPS.toDouble()
    private var tsMin = SPS * 0.995; private var tsMax = SPS * 1.005
    private var prevI = 0f; private var prevQ = 0f
    private var tedI = 0.0
    private var started = false

    init {
        // RRC taps, alpha 0.6, unit energy
        val a = 0.6; val n = rrc.size; var e = 0.0
        for (k in 0 until n) {
            val t = (k - (n - 1) / 2.0) / SPS
            val v = if (abs(t) < 1e-9) 1.0 - a + 4 * a / PI
            else if (abs(abs(4 * a * t) - 1.0) < 1e-6) (a / sqrt(2.0)) * ((1 + 2 / PI) * sin(PI / (4 * a)) + (1 - 2 / PI) * cos(PI / (4 * a)))
            else (sin(PI * t * (1 - a)) + 4 * a * t * cos(PI * t * (1 + a))) / (PI * t * (1 - (4 * a * t) * (4 * a * t)))
            rrc[k] = v.toFloat(); e += v * v
        }
        val s = (1.0 / sqrt(e)).toFloat(); for (k in 0 until n) rrc[k] *= s
        setLoopBandwidth(0.0015)
    }

    /** Second-order loop, damping 0.707; [bn] = loop noise bandwidth as a fraction of the working sample rate. */
    private fun setLoopBandwidth(bn: Double) {
        val z = 0.707; val th = bn / (z + 1 / (4 * z)); val d = 1 + 2 * z * th + th * th
        alpha = 4 * z * th / d; beta = 4 * th * th / d
    }

    fun reset() { dcI = 0f; dcQ = 0f; curRate = 0; phase = 0.0; freq = 0.0; started = false; afcFill = 0; afcFrames = 0; sampleCount = 0L; lastValidSample = -1L; validRun = 0; afcQ = 0.0; gain = 1f; magAvg = 0f; java.util.Arrays.fill(afcPow, 0.0) }

    /** Soft symbols produced by the last [process] call are appended to [out] (I, Q interleaved); returns how many values were written. */
    fun process(i: FloatArray, q: FloatArray, n: Int, rate: Int, out: ByteArray): Int {
        if (rate != curRate) {
            curRate = rate
            dec = max(1, rate / 480_000)
            rm = rate.toDouble() / dec
            firI = FirDecim(dec, 100_000.0, rate.toDouble(), max(dec * 8 + 1, 33))
            firQ = FirDecim(dec, 100_000.0, rate.toDouble(), max(dec * 8 + 1, 33))
            rsPhase = 0.0
            for (h in hist) java.util.Arrays.fill(h, 0f)
        }
        val cap = n / dec + 8
        if (tmpI.size < cap) { tmpI = FloatArray(cap); tmpQ = FloatArray(cap) }
        val m = firI!!.process(i, n, tmpI)
        firQ!!.process(q, n, tmpQ)
        // remove the dongle's DC spike (a slow average subtracted: QPSK itself carries no DC)
        for (k in 0 until m) { dcI += 2e-4f * (tmpI[k] - dcI); dcQ += 2e-4f * (tmpQ[k] - dcQ); tmpI[k] -= dcI; tmpQ[k] -= dcQ }
        var o = 0
        val step = rm / fw // intermediate samples per working sample
        for (k in 0 until m) {
            // push into the 4-sample history, then emit working-rate samples whose time falls before the newest sample
            val hI = hist[0]; val hQ = hist[1]
            hI[0] = hI[1]; hI[1] = hI[2]; hI[2] = hI[3]; hI[3] = tmpI[k]
            hQ[0] = hQ[1]; hQ[1] = hQ[2]; hQ[2] = hQ[3]; hQ[3] = tmpQ[k]
            // interpolation between hist[1] and hist[2], valid for rsPhase in [0,1)
            while (rsPhase < 1.0) {
                val mu = rsPhase.toFloat()
                val yi = cubic(hI, mu); val yq = cubic(hQ, mu)
                o = workSample(yi, yq, out, o)
                rsPhase += step
            }
            rsPhase -= 1.0
        }
        return o
    }

    private fun cubic(h: FloatArray, mu: Float): Float { // Catmull-Rom between h[1] and h[2]
        val a = -0.5f * h[0] + 1.5f * h[1] - 1.5f * h[2] + 0.5f * h[3]
        val b = h[0] - 2.5f * h[1] + 2f * h[2] - 0.5f * h[3]
        val c = -0.5f * h[0] + 0.5f * h[2]
        return ((a * mu + b) * mu + c) * mu + h[1]
    }

    /** One sample at the working rate (4 sps). */
    private fun workSample(xi: Float, xq: Float, out: ByteArray, oIn: Int): Int {
        var o = oIn
        // AGC: slow, drives |z| towards 1
        val mag = sqrt(xi * xi + xq * xq)
        var zi = xi * gain; var zq = xq * gain
        // (old additive loop needed ~15 s to climb from gain 1 to 100, which is what a quiet 8-bit recording needs; soft symbols
        //  were rounding to ~0 meanwhile). Track the mean input magnitude instead and scale it to ~1 right away.
        magAvg = if (magAvg == 0f) mag else magAvg + 1e-4f * (mag - magAvg)
        gain = 1f / max(magAvg, 1e-7f)
        // RRC matched filter
        bufI[bufPos] = zi; bufQ[bufPos] = zq
        var fi = 0f; var fq = 0f
        var idx = bufPos
        for (t in rrc.indices) { fi += rrc[t] * bufI[idx]; fq += rrc[t] * bufQ[idx]; idx--; if (idx < 0) idx = rrc.size - 1 }
        bufPos++; if (bufPos == rrc.size) bufPos = 0
        // coarse frequency estimate on the 4th power (after the half-symbol alignment so OQPSK looks like QPSK)
        afcStep(fi, fq)
        // carrier loop
        val c = cos(phase).toFloat(); val s = sin(phase).toFloat()
        val ri = fi * c + fq * s; val rq = fq * c - fi * s
        val e = ((if (ri > 0) 1f else -1f) * rq - (if (rq > 0) 1f else -1f) * ri).coerceIn(-1f, 1f)
        lockMetric += 1e-4f * (abs(e) - lockMetric)
        freq += beta * e
        val maxF = 2 * PI * 12_000 / fw
        if (freq > maxF) freq = maxF else if (freq < -maxF) freq = -maxF
        phase += freq + alpha * e
        if (phase > PI) phase -= 2 * PI else if (phase < -PI) phase += 2 * PI
        carrierHz = freq * fw / (2 * PI)
        // OQPSK: delay Q by half a symbol so the constellation points of I and Q coincide
        val dq = if (oqpsk) { val d = qDelay[qDelayPos]; qDelay[qDelayPos] = rq; qDelayPos++; if (qDelayPos == qDelay.size) qDelayPos = 0; d } else rq
        // keep a short history of the aligned stream for interpolation
        val ix = (ringPos and 63L).toInt(); ringI[ix] = ri; ringQ[ix] = dq; ringPos++
        // timing recovery: symbol instants at nextSym (in sample counts)
        if (!started) { if (ringPos > 16) { started = true; nextSym = ringPos - 6.0 } else return o }
        while (nextSym + 2.0 <= ringPos - 1 && nextSym - ts - 2 >= ringPos - 60) {
            val yI = interp(ringI, nextSym); val yQ = interp(ringQ, nextSym)
            val mI = interp(ringI, nextSym - ts / 2); val mQ = interp(ringQ, nextSym - ts / 2)
            // Gardner: e = Re{ (y_k - y_{k-1}) * conj(y_mid) }
            val te = ((yI - prevI) * mI + (yQ - prevQ) * mQ).toDouble().coerceIn(-1.0, 1.0)
            prevI = yI; prevQ = yQ
            ts -= 0.0006 * te; if (ts < tsMin) ts = tsMin else if (ts > tsMax) ts = tsMax
            nextSym += ts - 0.02 * te
            tedI += 0.01 * (abs(te) - tedI)
            // soft symbols: |value| about 0.7 -> about 90 counts
            o = putSoft(out, o, yI); o = putSoft(out, o, yQ)
        }
        return o
    }

    private fun putSoft(out: ByteArray, o: Int, v: Float): Int {
        if (o >= out.size) return o
        out[o] = max(-127f, min(127f, v * 45f)).toInt().toByte()  // matched-filter output is ~1.9 at the symbol points
        return o + 1
    }

    private fun interp(r: FloatArray, t: Double): Float { // cubic interpolation at fractional sample time t in the 64-sample ring
        val base = kotlin.math.floor(t).toLong(); val mu = (t - base).toFloat()
        fun g(k: Long) = r[(k and 63L).toInt()]
        val h0 = g(base - 1); val h1 = g(base); val h2 = g(base + 1); val h3 = g(base + 2)
        val a = -0.5f * h0 + 1.5f * h1 - 1.5f * h2 + 0.5f * h3
        val b = h0 - 2.5f * h1 + 2f * h2 - 0.5f * h3
        val cc = -0.5f * h0 + 0.5f * h2
        return ((a * mu + b) * mu + cc) * mu + h1
    }

    // ---------------------------------------------------------------------------------------------- coarse AFC
    private val alignQ = FloatArray(SPS / 2); private var alignPos = 0
    private fun afcStep(fi: Float, fq: Float) {
        sampleCount++
        val dq = alignQ[alignPos]; alignQ[alignPos] = fq; alignPos++; if (alignPos == alignQ.size) alignPos = 0
        val ai = fi; val aq = if (oqpsk) dq else fq
        // z^4
        val r2 = ai * ai - aq * aq; val i2 = 2 * ai * aq
        val r4 = r2 * r2 - i2 * i2; val i4 = 2 * r2 * i2
        val nrm = 1f / max(1e-12f, (ai * ai + aq * aq) * (ai * ai + aq * aq))
        afcRe[afcFill] = r4 * nrm * win[afcFill]; afcIm[afcFill] = i4 * nrm * win[afcFill]; afcFill++
        if (afcFill == FFT_N) {
            fft(afcRe, afcIm)
            for (k in 0 until FFT_N) afcPow[k] += (afcRe[k] * afcRe[k] + afcIm[k] * afcIm[k]).toDouble()
            afcFill = 0; afcFrames++
            if (afcFrames >= AFC_AVG) {
                var best = 0; var bp = -1.0
                val lim = (FFT_N * 36_000 / fw).toInt() // search +-36 kHz of 4th-power frequency = +-9 kHz of carrier
                for (k in -lim..lim) { val p = afcPow[(k + FFT_N) % FFT_N]; if (p > bp) { bp = p; best = k } }
                var sum = 0.0; var cnt = 0
                for (k in 0 until FFT_N) { val d = min(abs(k - ((best + FFT_N) % FFT_N)), FFT_N - abs(k - ((best + FFT_N) % FFT_N))); if (d > 4) { sum += afcPow[k]; cnt++ } }
                val q = bp / (sum / max(1, cnt))
                afcQ = q
                if (q >= AFC_Q_MIN) {
                    val pm = afcPow[(best - 1 + FFT_N) % FFT_N]; val p0 = afcPow[(best + FFT_N) % FFT_N]; val pp = afcPow[(best + 1 + FFT_N) % FFT_N]
                    val den = pm - 2 * p0 + pp; val frac = if (den != 0.0) 0.5 * (pm - pp) / den else 0.0
                    val fOff = (best + frac) * fw / FFT_N / 4.0
                    // retune the loop only if it isn't already there (a good lock must not be disturbed by a noisy estimate)
                    if (validRun < 2 || abs(fOff - carrierHz) > 250.0) { freq = 2 * PI * fOff / fw }
                    validRun++; lastValidSample = sampleCount
                } else if (lastValidSample >= 0 && sampleCount - lastValidSample > (1.5 * fw).toLong()) validRun = 0
                afcFrames = 0; java.util.Arrays.fill(afcPow, 0.0)
            }
        }
    }

    private fun fft(re: FloatArray, im: FloatArray) {
        val n = FFT_N
        var j = 0
        for (i in 1 until n) { var bit = n shr 1; while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }; j = j xor bit
            if (i < j) { val tr = re[i]; re[i] = re[j]; re[j] = tr; val ti = im[i]; im[i] = im[j]; im[j] = ti } }
        var len = 2
        while (len <= n) {
            val half = len / 2; val stride = n / len
            var i = 0
            while (i < n) {
                for (k in 0 until half) {
                    val wr = twRe[k * stride]; val wi = twIm[k * stride]
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + half] * wr - im[i + k + half] * wi; val vi = re[i + k + half] * wi + im[i + k + half] * wr
                    re[i + k] = ur + vr; im[i + k] = ui + vi; re[i + k + half] = ur - vr; im[i + k + half] = ui - vi
                }
                i += len
            }
            len = len shl 1
        }
    }
}
