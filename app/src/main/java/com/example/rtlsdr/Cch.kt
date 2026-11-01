package com.example.rtlsdr

/**
 * GSM control-channel (xCCH) FEC: rate-1/2 convolutional code, 40-bit Fire code, and the TS 05.03
 * block-diagonal interleaver, sourced from the Airprobe/Osmocom GSM toolchain's published source
 * (the generator polynomials, Fire-code polynomial and interleaver formula are reproduced verbatim).
 * Decodes a CCCH/BCCH idle "Fill Frame" (all 0x2b bytes) to confirm a channel is really live GSM.
 */
object Cch {
    // --- rate 1/2 convolutional code, K=5: G0=1+D^3+D^4, G1=1+D+D^3+D^4 ---
    private const val K = 5
    private const val STATES = 1 shl (K - 1) // 16

    private fun outputs(state: Int, bit: Int): Pair<Int, Int> {
        // shift register: [bit, state bits...] = u_k, u_k-1, u_k-2, u_k-3, u_k-4 (5 bits, MSB = newest)
        val reg = (bit shl 4) or state
        val g0 = (reg shr 4 xor (reg shr 1) xor reg) and 1          // u_k + u_k-3 + u_k-4
        val g1 = (reg shr 4 xor (reg shr 3) xor (reg shr 1) xor reg) and 1 // u_k+u_k-1+u_k-3+u_k-4
        return g0 to g1
    }
    // state bit 3 = u(k-1) (newest) ... bit 0 = u(k-4) (oldest), matching outputs(). The new input enters at the TOP
    // and everything else moves down. (The old version shifted the other way, so the taps landed on the wrong
    // delays: encode/decode still round-tripped with itself, but it was not the real GSM code.)
    private fun nextState(state: Int, bit: Int) = (bit shl 3) or (state shr 1)

    /** 228 bits (184 data + 40 parity + 4 flush) -> 456 coded bits. */
    fun convEncode(bits228: IntArray): IntArray {
        val out = IntArray(456)
        var state = 0
        for (k in bits228.indices) {
            val (g0, g1) = outputs(state, bits228[k])
            out[2 * k] = g0; out[2 * k + 1] = g1
            state = nextState(state, bits228[k])
        }
        return out
    }

    /** Hard-decision Viterbi decode of 456 coded bits -> 228 bits. */
    fun viterbiDecode(coded: IntArray): IntArray {
        val n = coded.size / 2
        val INF = Int.MAX_VALUE / 2
        var cost = IntArray(STATES) { INF }.also { it[0] = 0 }
        val back = Array(n) { IntArray(STATES) }
        val bit = Array(n) { IntArray(STATES) }
        for (k in 0 until n) {
            val next = IntArray(STATES) { INF }
            val r0 = coded[2 * k]; val r1 = coded[2 * k + 1]
            for (s in 0 until STATES) {
                if (cost[s] >= INF) continue
                for (b in 0..1) {
                    val (g0, g1) = outputs(s, b)
                    val d = (if (g0 != r0) 1 else 0) + (if (g1 != r1) 1 else 0)
                    val ns = nextState(s, b)
                    val c = cost[s] + d
                    if (c < next[ns]) { next[ns] = c; back[k][ns] = s; bit[k][ns] = b }
                }
            }
            cost = next
        }
        var state = 0 // the 4 flush bits force the encoder back to state 0
        val outBits = IntArray(n)
        for (k in n - 1 downTo 0) { outBits[k] = bit[k][state]; state = back[k][state] }
        return outBits
    }

    /**
     * Soft-decision Viterbi. [soft] = 456 values, positive = coded bit 1, negative = 0, magnitude = confidence
     * (clipped to +-1). Gains roughly 2-3 dB over hard decisions, which is the difference between decoding
     * and not decoding a weak beacon.
     */
    fun viterbiDecodeSoft(soft: FloatArray): IntArray {
        val n = soft.size / 2
        val INF = 1e30f
        var cost = FloatArray(STATES) { INF }.also { it[0] = 0f }
        val back = Array(n) { ByteArray(STATES) }
        val bit = Array(n) { ByteArray(STATES) }
        for (k in 0 until n) {
            val next = FloatArray(STATES) { INF }
            val s0 = soft[2 * k].coerceIn(-1f, 1f); val s1 = soft[2 * k + 1].coerceIn(-1f, 1f)
            for (s in 0 until STATES) {
                val cs = cost[s]
                if (cs >= INF) continue
                for (b in 0..1) {
                    val (g0, g1) = outputs(s, b)
                    val d = (if (g0 == 1) 1f - s0 else 1f + s0) + (if (g1 == 1) 1f - s1 else 1f + s1)
                    val ns = nextState(s, b)
                    val c = cs + d
                    if (c < next[ns]) { next[ns] = c; back[k][ns] = s.toByte(); bit[k][ns] = b.toByte() }
                }
            }
            cost = next
        }
        var state = 0
        val outBits = IntArray(n)
        for (k in n - 1 downTo 0) { outBits[k] = bit[k][state].toInt(); state = back[k][state].toInt() }
        return outBits
    }

    // --- Fire code, g(x) = x^40 + x^26 + x^23 + x^17 + x^3 + 1 (protects 184 data bits with 40 parity bits) ---
    private val PARITY_POLY = intArrayOf(
        1,0,0,0,0,0,0,0, 0,0,0,0,0,0,1,0, 0,1,0,0,0,0,0,1, 0,0,0,0,0,0,0,0, 0,0,0,0,0,1,0,0, 1
    )
    private val PARITY_REMAINDER = IntArray(40) { 1 }
    private const val DATA_LEN = 184
    private const val PARITY_LEN = 40

    // the divide loop always runs over exactly the 184 data positions, whether encoding (buf holds data+zeros)
    // or checking (buf holds data+received parity) -- matching the reference parity_encode/parity_check exactly
    private fun divide(buf: IntArray) {
        for (q in 0 until DATA_LEN) if (buf[q] != 0) for (i in PARITY_POLY.indices) buf[q + i] = buf[q + i] xor PARITY_POLY[i]
    }

    /** 184 payload bits -> 40 Fire-code parity bits (the raw division remainder, inverted -- see parity_encode). */
    fun fireEncode(data184: IntArray): IntArray {
        val buf = IntArray(DATA_LEN + PARITY_LEN)
        System.arraycopy(data184, 0, buf, 0, DATA_LEN)
        divide(buf)
        return IntArray(PARITY_LEN) { 1 - buf[DATA_LEN + it] }
    }

    /** True if the 224-bit word (184 data + 40 parity, as received) checks out. */
    fun fireCheckOk(word224: IntArray): Boolean {
        val buf = word224.copyOf()
        divide(buf)
        return (0 until PARITY_LEN).all { buf[DATA_LEN + it] == PARITY_REMAINDER[it] }
    }

    // --- TS 05.03 xCCH interleaver: 456 coded bits <-> 4 bursts of 114 bits ---
    /** [iB] = 4 bursts' 114 data bits each, concatenated in burst order (456 total) -> 456 bits in natural (coded) order. */
    fun deinterleave(iB: IntArray): IntArray {
        val cB = IntArray(456)
        for (k in 0 until 456) {
            val b = k and 3
            val j = 2 * ((49 * k) % 57) + ((k and 7) shr 2)
            cB[k] = iB[b * 114 + j]
        }
        return cB
    }

    fun interleave(cB: IntArray): IntArray {
        val iB = IntArray(456)
        for (k in 0 until 456) {
            val b = k and 3
            val j = 2 * ((49 * k) % 57) + ((k and 7) shr 2)
            iB[b * 114 + j] = cB[k]
        }
        return iB
    }

    /** Full chain: 184 payload bits -> 4x114-bit interleaved burst data (what's actually transmitted). */
    fun encodeFull(data184: IntArray): IntArray {
        val parity = fireEncode(data184)
        val bits228 = IntArray(228)
        System.arraycopy(data184, 0, bits228, 0, 184)
        System.arraycopy(parity, 0, bits228, 184, 40)
        // last 4 bits of the 228 stay 0 (flush bits for the convolutional encoder)
        val coded = convEncode(bits228)
        return interleave(coded)
    }

    /** Full chain: 4x114-bit interleaved burst data -> 184 payload bits, or null if the Fire check fails. */
    fun decodeFull(iB: IntArray): IntArray? {
        val r = decodeAttempt(iB)
        return if (r.second) r.first else null
    }

    /** Same decode, but always returns the 184 bits plus whether the Fire check passed, instead of
        discarding a failed attempt -- useful to show what a rejected group actually contained. */
    fun decodeAttempt(iB: IntArray): Pair<IntArray, Boolean> {
        val coded = deinterleave(iB)
        val bits228 = viterbiDecode(coded)
        val word224 = bits228.copyOfRange(0, 224)
        return bits228.copyOfRange(0, 184) to fireCheckOk(word224)
    }

    /** Soft version of [deinterleave]. */
    fun deinterleaveSoft(iB: FloatArray): FloatArray {
        val cB = FloatArray(456)
        for (k in 0 until 456) {
            val b = k and 3
            val j = 2 * ((49 * k) % 57) + ((k and 7) shr 2)
            cB[k] = iB[b * 114 + j]
        }
        return cB
    }

    /** [iBsoft] = 4 bursts x 114 soft values (positive = bit 1). Returns the 184 bits and whether the Fire check passed. */
    fun decodeAttemptSoft(iBsoft: FloatArray): Pair<IntArray, Boolean> {
        val bits228 = viterbiDecodeSoft(deinterleaveSoft(iBsoft))
        return bits228.copyOfRange(0, 184) to fireCheckOk(bits228.copyOfRange(0, 224))
    }

    // --- 8 GSM training sequences (26 bits each), ETSI 05.02 ---
    val TSC = arrayOf(
        intArrayOf(0,0,1,0,0,1,0,1,1,1,0,0,0,0,1,0,0,0,1,0,0,1,0,1,1,1),
        intArrayOf(0,0,1,0,1,1,0,1,1,1,0,1,1,1,1,0,0,0,1,0,0,1,0,1,1,1),
        intArrayOf(0,1,0,0,0,0,1,1,1,0,1,1,1,0,1,0,0,1,0,0,0,0,1,1,1,0),
        intArrayOf(0,1,0,0,0,1,1,1,1,0,1,1,0,1,0,0,0,1,0,0,0,1,1,1,1,0),
        intArrayOf(0,0,0,1,1,0,1,0,1,1,1,0,0,1,0,0,0,0,0,1,1,0,1,0,1,1),
        intArrayOf(0,1,0,0,1,1,1,0,1,0,1,1,0,0,0,0,0,1,0,0,1,1,1,0,1,0),
        intArrayOf(1,0,1,0,0,1,1,1,1,1,0,1,1,0,0,0,1,0,1,0,0,1,1,1,1,1),
        intArrayOf(1,1,1,0,1,1,1,1,0,0,0,1,0,0,1,0,1,1,1,0,1,1,1,1,0,0)
    )
}
