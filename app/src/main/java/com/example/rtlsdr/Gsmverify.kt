package com.example.rtlsdr

import kotlin.math.PI
import kotlin.math.atan2

/**
 * ETSI 05.02 Dummy Burst fixed bits (142 bits), sourced from the Airprobe/gr-gsm receiver's verified
 * constants table. Base stations transmit this exact, unencrypted pattern to fill otherwise-idle slots,
 * so finding it is strong confirmation that a channel is really carrying live GSM traffic.
 */
object GsmDummyBurst {
    val BITS = intArrayOf(
        1,1,1,1,1,0,1,1,0,1, 1,1,0,1,1,0,0,0,0,0, 1,0,1,0,0,1,0,0,1,1,
        1,0,0,0,0,0,1,0,0,1, 0,0,0,1,0,0,0,0,0,0, 0,1,1,1,1,1,0,0,
        0,1,1,1,0,0,0,1,0,1, 1,1,0,0,0,1,0,1,1,1, 0,0,0,1,0,1,
        0,1,1,1,0,1,0,0,1,0, 1,0,0,0,1,1,0,0,1,1, 0,0,1,1,1,0,0,1,1,1,
        1,0,1,0,0,1,1,1,1,1, 0,0,0,1,0,0,1,0,1,1, 1,1,1,0,1,0,1,0
    )
}

/** Binary GMSK-ish differential detector that looks for the dummy-burst fixed pattern on the tuned channel. */
class GsmBurstVerifier {
    companion object {
        const val RATE = 1_083_333 // 4 samples/symbol at the GSM symbol rate (1625000/6 ~= 270833.3)
        private const val PH = 4
    }
    class Hit(val errBits: Int, val bytes: ByteArray)

    private lateinit var dec: FirDecim
    private lateinit var decQ: FirDecim
    private var curRate = 0
    private var lastI = 1f; private var lastQ = 0f
    private var bi = FloatArray(0); private var bq = FloatArray(0)

    private val ringLen = GsmDummyBurst.BITS.size + 8
    private val rings = Array(PH) { IntArray(ringLen) }
    private val fill = IntArray(PH)
    private val pos = IntArray(PH)
    private var phCounter = 0
    private val holdoff = IntArray(PH)
    private val hits = ArrayList<Hit>()

    fun reset() {
        curRate = 0; phCounter = 0
        for (p in 0 until PH) { fill[p] = 0; pos[p] = 0; holdoff[p] = 0 }
    }

    fun process(i: FloatArray, q: FloatArray, n: Int, rate: Int): List<Hit> {
        hits.clear()
        if (rate != curRate) {
            val factor = (rate / RATE).coerceAtLeast(1)
            dec = FirDecim(factor, 300000.0, rate.toDouble(), 7)
            decQ = FirDecim(factor, 300000.0, rate.toDouble(), 7)
            curRate = rate; lastI = 1f; lastQ = 0f
            for (p in 0 until PH) { fill[p] = 0; pos[p] = 0; holdoff[p] = 0 }
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
            val bit = if (atan2(im, re) >= 0f) 1 else 0
            val ph = phCounter % PH
            phCounter++
            pushBit(ph, bit)
        }
        return hits
    }

    private fun pushBit(ph: Int, bit: Int) {
        val r = rings[ph]
        r[pos[ph]] = bit
        pos[ph] = (pos[ph] + 1) % ringLen
        if (fill[ph] < ringLen) fill[ph]++
        if (holdoff[ph] > 0) { holdoff[ph]--; return }
        val need = GsmDummyBurst.BITS.size
        if (fill[ph] < need) return
        var err = 0
        var idx = pos[ph] - need
        val bits = IntArray(need)
        for (k in 0 until need) {
            val v = r[((idx % ringLen) + ringLen) % ringLen]
            bits[k] = v
            if (v != GsmDummyBurst.BITS[k]) err++
            idx++
        }
        if (err <= 14) { // ~10% tolerance, mirrors the margin used for the DMR sync correlation
            hits.add(Hit(err, bitsToBytes(bits)))
            // the other phases are very likely locked onto this same physical burst: stop them so it's only reported once
            for (p2 in 0 until PH) holdoff[p2] = need
        }
    }

    private fun bitsToBytes(bits: IntArray): ByteArray {
        val out = ByteArray((bits.size + 7) / 8)
        for (i in bits.indices) if (bits[i] == 1) out[i / 8] = (out[i / 8].toInt() or (1 shl (7 - i % 8))).toByte()
        return out
    }
}
