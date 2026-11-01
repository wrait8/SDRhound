package com.example.rtlsdr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.log10

/** Bands an R820T dongle can reach (DCS1800/PCS1900 are above its ~1.77 GHz limit). */
enum class GsmBand(val label: String) { GSM850("GSM850"), GSM900("GSM900") }

/** The three live-feed views in [GsmPanel]: raw HEX console, IMSI catcher table, Wireshark-style packet list. */
enum class GsmView(val label: String) { HEX("HEX"), IMSI("IMSI"), PKT("PKT") }

class GsmChannel(val arfcn: Int, val khz: Long)

/** One measured channel. [hits] = number of FCCH bursts seen, i.e. the channel is a base-station beacon (BCCH). */
class GsmResult(val arfcn: Int, val khz: Long, val powerDb: Float, val hits: Int, val offsetHz: Float)

object GsmBands {
    /** ARFCN of the GSM850/GSM900 downlink channel nearest to [khz] (within 100 kHz), or -1 if it isn't one. */
    fun arfcnFor(khz: Long): Int {
        val best = GsmBand.values().flatMap { channels(it) }.minByOrNull { abs(it.khz - khz) } ?: return -1
        return if (abs(best.khz - khz) <= 100) best.arfcn else -1
    }

    /** Downlink channels of the band, lowest frequency first. */
    fun channels(b: GsmBand): List<GsmChannel> = when (b) {
        GsmBand.GSM850 -> (128..251).map { GsmChannel(it, 869_200L + (it - 128) * 200L) }
        // P-GSM 1..124 plus E-GSM 975..1023
        GsmBand.GSM900 -> (1..124).map { GsmChannel(it, 935_000L + it * 200L) } +
            (975..1023).map { GsmChannel(it, 925_200L + (it - 975) * 200L) }
    }.sortedBy { it.khz }
}

/**
 * Finds GSM frequency-correction bursts (FCCH).  An FCCH is 148 symbols of pure tone at +67.7 kHz
 * (a quarter of the 270.833 kS/s symbol rate), sent about every 50 ms on a base station's beacon channel.
 * Normal GMSK data has no such steady tone, and noise does not either, so false alarms are very rare.
 *
 * Input: IQ at [RATE] S/s centred on the channel.  Only measures passive broadcast beacons.
 */
class FcchDetector {
    companion object {
        const val RATE = 1_083_333           // 4 x the GSM symbol rate
        const val SYM = 270_833.0
        private const val L = 96             // detection window in symbols
    }

    private val fi = FirDecim(4, 90_000.0, RATE.toDouble(), 48)
    private val fq = FirDecim(4, 90_000.0, RATE.toDouble(), 48)
    private var bi = FloatArray(0)
    private var bq = FloatArray(0)
    private var lastI = 0f
    private var lastQ = 0f
    private val win = FloatArray(L)
    private var pos = 0
    private var filled = 0
    private var sumE = 0.0
    private var sumE2 = 0.0
    private var cooldown = 0
    private var offAcc = 0.0
    private var powerSum = 0.0
    private var powerN = 0L

    var hits = 0; private set
    val offsetHz: Float get() = if (hits == 0) 0f else (offAcc / hits).toFloat()
    /** In-channel power, dB relative to full scale. */
    val powerDb: Float get() = if (powerN == 0L) -120f else (10.0 * log10(powerSum / powerN + 1e-12)).toFloat()

    fun reset() {
        java.util.Arrays.fill(win, 0f)
        pos = 0; filled = 0; sumE = 0.0; sumE2 = 0.0; cooldown = 0
        offAcc = 0.0; powerSum = 0.0; powerN = 0; hits = 0
    }

    fun process(i: FloatArray, q: FloatArray, n: Int) {
        val cap = n / 4 + 64
        if (bi.size < cap) { bi = FloatArray(cap); bq = FloatArray(cap) }
        val n1 = fi.process(i, n, bi)
        fq.process(q, n, bq)
        for (k in 0 until n1) {
            val zi = bi[k]; val zq = bq[k]
            powerSum += (zi * zi + zq * zq).toDouble(); powerN++
            val re = zi * lastI + zq * lastQ
            val im = zq * lastI - zi * lastQ
            lastI = zi; lastQ = zq
            // phase step per symbol, minus the expected +90 degrees of the FCCH tone
            var e = atan2(im, re) - (PI / 2).toFloat()
            if (e < -PI.toFloat()) e += (2 * PI).toFloat()
            val old = win[pos]
            win[pos] = e
            pos = (pos + 1) % L
            if (filled < L) { filled++; sumE += e; sumE2 += (e * e).toDouble() }
            else { sumE += (e - old); sumE2 += (e * e - old * old).toDouble() }
            if (cooldown > 0) { cooldown--; continue }
            if (filled == L) {
                val mean = sumE / L
                val ms = sumE2 / L
                if (abs(mean) < 0.2 && ms < 0.25) {
                    hits++
                    // average only the clean symbols: a couple of data symbols at the edge would bias the mean
                    var sIn = 0.0; var cIn = 0
                    for (v in win) if (abs(v) < 0.4f) { sIn += v; cIn++ }
                    offAcc += (if (cIn > 0) sIn / cIn else mean) * SYM / (2 * PI)
                    cooldown = 800 // one burst is only 148 symbols long
                }
            }
        }
    }
}
