package com.example.rtlsdr

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * NOAA APT (Automatic Picture Transmission) weather-satellite image decoder.
 * NOAA 15/18/19 transmit on ~137 MHz: an NFM carrier carrying a 2400 Hz AM subcarrier,
 * 2 lines/second, 2080 pixels/line (sync + space + 909px image A + telemetry, repeated for B).
 * This finds the first line with a sync-pulse correlation, then free-runs the pixel clock;
 * a long pass can drift out of alignment since there is no continuous resync (a simplification).
 */
class AptDecoder {
    companion object {
        const val WORK_RATE = 11025        // FM-demod rate before the subcarrier/pixel stages
        const val SUBCARRIER_HZ = 2400.0
        const val PIXEL_RATE = 4160.0      // samples/sec = pixels/sec
        const val LINE_PX = 2080           // 0.5s per line
        private const val SYNC_A_HZ = 1040.0
        private const val SYNC_A_CYCLES = 7
    }

    var image: Array<IntArray> = emptyArray(); private set // rows of 2080 bytes (0-255), newest last
    var linesDecoded = 0; private set
    var locked = false; private set
    private var workRateActual = WORK_RATE.toDouble()

    private var synced = false
    private var lineBuf = FloatArray(LINE_PX)
    private var lineFill = 0
    private var resampleAcc = 0.0
    private var lastEnv = 0f
    private val envWindow = ArrayList<Float>()
    private var envLo = 0f; private var envHi = 0f // slow-tracking low/high envelope levels, for AGC-style normalisation
    private val rows = ArrayList<IntArray>()

    private var mixPhase = 0.0
    private lateinit var fmDec: FirDecim
    private lateinit var fmDecQ: FirDecim
    private var curRate = 0
    private var fmLastI = 1f; private var fmLastQ = 0f
    private lateinit var lpI: FirDecim
    private lateinit var lpQ: FirDecim
    private var bi = FloatArray(0); private var bq = FloatArray(0)

    fun reset() {
        synced = false; lineFill = 0; resampleAcc = 0.0; mixPhase = 0.0
        envWindow.clear(); rows.clear(); linesDecoded = 0; image = emptyArray(); locked = false
    }

    /** [i],[q] at the radio's sample rate. */
    fun process(i: FloatArray, q: FloatArray, n: Int, rate: Int) {
        if (rate != curRate) {
            val factor = (rate / WORK_RATE).coerceAtLeast(1)
            fmDec = FirDecim(factor, 17000.0, rate.toDouble(), maxOf(factor + 1, 64))
            fmDecQ = FirDecim(factor, 17000.0, rate.toDouble(), maxOf(factor + 1, 64))
            workRateActual = rate.toDouble() / factor
            lpI = FirDecim(1, 2200.0, workRateActual, 33)
            lpQ = FirDecim(1, 2200.0, workRateActual, 33)
            curRate = rate; fmLastI = 1f; fmLastQ = 0f
        }
        val cap = n / (rate / WORK_RATE) + 64
        if (bi.size < cap) { bi = FloatArray(cap); bq = FloatArray(cap) }
        val n1 = fmDec.process(i, n, bi)
        fmDecQ.process(q, n, bq)

        val audio = FloatArray(n1)
        for (k in 0 until n1) {
            val zi = bi[k]; val zq = bq[k]
            val re = zi * fmLastI + zq * fmLastQ
            val im = zq * fmLastI - zi * fmLastQ
            fmLastI = zi; fmLastQ = zq
            audio[k] = atan2(im, re)
        }

        val mixI = FloatArray(n1); val mixQ = FloatArray(n1)
        val inc = 2 * PI * SUBCARRIER_HZ / workRateActual
        for (k in 0 until n1) {
            mixI[k] = audio[k] * cos(mixPhase).toFloat()
            mixQ[k] = audio[k] * sin(mixPhase).toFloat()
            mixPhase += inc; if (mixPhase > 2 * PI) mixPhase -= 2 * PI
        }
        val fi = FloatArray(n1); val fq = FloatArray(n1)
        val n2 = lpI.process(mixI, n1, fi); lpQ.process(mixQ, n1, fq)
        for (k in 0 until n2) feedEnvelope(sqrt(fi[k] * fi[k] + fq[k] * fq[k]))
    }

    private fun feedEnvelope(env: Float) {
        // slow peak/trough trackers, used only to scale the sync correlation -- the actual gain/AFC/antenna
        // conditions set the absolute envelope level, and a fixed-number threshold on that raw level only
        // ever matches one specific signal strength (see trySync())
        envHi = maxOf(env, envHi * 0.9995f)
        envLo = minOf(env, envLo + (envHi - envLo) * 0.0005f)
        resampleAcc += PIXEL_RATE / workRateActual
        while (resampleAcc >= 1.0) {
            resampleAcc -= 1.0
            onPixelSample((lastEnv + env) / 2f)
        }
        lastEnv = env
    }

    private fun onPixelSample(v: Float) {
        if (!synced) {
            envWindow.add(v)
            if (envWindow.size > LINE_PX * 2) envWindow.removeAt(0)
            if (envWindow.size >= 300) trySync()
            return
        }
        lineBuf[lineFill++] = v
        if (lineFill >= LINE_PX) { lineFill = 0; commitLine() }
    }

    private fun trySync() {
        val cyclePx = PIXEL_RATE / SYNC_A_HZ
        val syncLen = (cyclePx * SYNC_A_CYCLES).toInt()
        if (envWindow.size < syncLen + 20) return
        var bestScore = Float.NEGATIVE_INFINITY; var bestStart = -1
        val searchFrom = maxOf(0, envWindow.size - LINE_PX - syncLen)
        for (start in searchFrom until envWindow.size - syncLen) {
            var score = 0f
            for (k in 0 until syncLen) {
                val phase = (k / cyclePx) % 1.0
                score += (if (phase < 0.5) 1f else -1f) * envWindow[start + k]
            }
            if (score > bestScore) { bestScore = score; bestStart = start }
        }
        val swing = (envHi - envLo).coerceAtLeast(1e-6f)
        if (bestStart >= 0 && bestScore / syncLen / swing > 0.15f) {
            synced = true; locked = true; lineFill = 0
            for (k in bestStart until envWindow.size) { lineBuf[lineFill++] = envWindow[k]; if (lineFill >= LINE_PX) { lineFill = 0; commitLine() } }
            envWindow.clear()
        }
    }

    private fun commitLine() {
        var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
        for (v in lineBuf) { if (v < mn) mn = v; if (v > mx) mx = v }
        val range = (mx - mn).coerceAtLeast(1e-6f)
        val row = IntArray(LINE_PX) { ((lineBuf[it] - mn) / range * 255f).toInt().coerceIn(0, 255) }
        rows.add(row)
        if (rows.size > 3000) rows.removeAt(0)
        image = rows.toTypedArray()
        linesDecoded++
    }
}
