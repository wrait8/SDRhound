package com.example.rtlsdr

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Pure-Kotlin AMBE+2 half-rate (3600x2450) speech decoder for DMR voice -- no native code, no JNI, no
 * external library. A direct port of the decoding algorithm in mbelib (ISC licence, Copyright (C) 2010
 * mbelib Author): Golay(23,12) error correction, the PRNG demodulation of the second codeword, the
 * 49-bit parameter decode (pitch, voiced/unvoiced bands, gain, PRBA + HOC spectral amplitudes), spectral
 * amplitude enhancement, and the sinusoidal + multi-sine unvoiced synthesis with overlap-add.
 * C's float/double mixing is kept as it is in the original so results match mbelib's.
 *
 * One [AmbeDecoder] instance carries the previous-frame state (synthesis smoothing and error
 * concealment depend on it), so keep using the same instance for a whole call.
 *
 * Input: one 72-bit subframe exactly as received in a DMR voice burst (0/1 per element).
 * Output: 160 samples of 8 kHz signed 16-bit PCM (20 ms).
 */
class AmbeDecoder(seed: Int = 1) {
    private class Parms {
        var w0 = 0f; var L = 0; var K = 0
        val Vl = IntArray(64); val Ml = FloatArray(64); val log2Ml = FloatArray(64)
        val PHIl = FloatArray(64); val PSIl = FloatArray(64)
        var gamma = 0f; var repeat = 0
    }

    private val cur = Parms()
    private val prev = Parms()
    private val prevEnh = Parms()
    private var rng = seed.toLong() and 0x7fffffffL
    private val fr = Array(4) { IntArray(24) }
    private val d = IntArray(49)

    init { initParms() }

    /** Forget all history (call between unrelated transmissions). */
    fun reset() { initParms() }

    // ---------------------------------------------------------------- random numbers
    private fun rand(): Int { rng = (rng * 1103515245L + 12345L) and 0x7fffffffL; return rng.toInt() }
    private fun mbeRand(): Float = rand().toFloat() / 2147483647f
    private fun mbeRandPhase(): Float = mbeRand() * (Math.PI.toFloat() * 2.0f) - Math.PI.toFloat()

    // ---------------------------------------------------------------- public entry point
    fun decode(bits72: IntArray): ShortArray? {
        if (bits72.size != 72) return null
        for (r in fr) java.util.Arrays.fill(r, 0)
        // DMR interleave: 36 dibits scattered into the 4x24 frame matrix (schedule from the ETSI/DSD tables)
        for (i in 0 until 36) {
            fr[RW[i]][RX[i]] = bits72[2 * i] and 1
            fr[RY[i]][RZ[i]] = bits72[2 * i + 1] and 1
        }
        var errs = eccC0()
        demodulate()
        var errs2 = errs
        errs2 += eccData()
        val out = FloatArray(160)
        processData(out, errs2)
        val pcm = ShortArray(160)
        for (i in 0 until 160) {
            var a = 7f * out[i]
            if (a > 32760f) a = 32760f else if (a < -32760f) a = -32760f
            pcm[i] = a.toInt().toShort()
        }
        return pcm
    }

    // ---------------------------------------------------------------- error correction
    private fun golay2312(inp: IntArray, out: IntArray): Int {
        var block = 0L
        for (i in 22 downTo 0) block = (block shl 1) + inp[i]
        var mask = 0x400000L
        var eccExpected = 0
        for (i in 0 until 12) {
            if ((block and mask) != 0L) eccExpected = eccExpected xor AmbeTables.golayGenerator[i]
            mask = mask shr 1
        }
        val eccBits = (block and 0x7ffL).toInt()
        val syndrome = eccExpected xor eccBits
        var b = ((block shr 11).toInt() xor AmbeTables.golayMatrix[syndrome]).toLong()
        for (i in 22 downTo 11) { out[i] = ((b and 2048L) shr 11).toInt(); b = b shl 1 }
        for (i in 10 downTo 0) out[i] = inp[i]
        var errs = 0
        for (i in 22 downTo 11) if (out[i] != inp[i]) errs++
        return errs
    }

    private fun eccC0(): Int {
        val inp = IntArray(23); val out = IntArray(23)
        for (j in 0 until 23) inp[j] = fr[0][j + 1]
        val errs = golay2312(inp, out)
        for (j in 0 until 23) fr[0][j + 1] = out[j]
        return errs
    }

    private fun eccData(): Int {
        var p = 0
        for (j in 23 downTo 12) d[p++] = fr[0][j]
        val gin = IntArray(23); val gout = IntArray(23)
        for (j in 0 until 23) gin[j] = fr[1][j]
        val errs = golay2312(gin, gout)
        for (j in 22 downTo 11) d[p++] = gout[j]
        for (j in 10 downTo 0) d[p++] = fr[2][j]
        for (j in 13 downTo 0) d[p++] = fr[3][j]
        return errs
    }

    private fun demodulate() {
        val pr = IntArray(24)
        var foo = 0
        for (i in 23 downTo 12) { foo = (foo shl 1) or fr[0][i] }
        pr[0] = (16 * foo) and 0xFFFF
        for (i in 1 until 24) pr[i] = (173 * pr[i - 1] + 13849) and 0xFFFF
        for (i in 1 until 24) pr[i] = pr[i] / 32768
        var k = 1
        for (j in 22 downTo 0) { fr[1][j] = fr[1][j] xor pr[k]; k++ }
    }

    // ---------------------------------------------------------------- parameter state helpers
    private fun move(from: Parms, to: Parms) {
        to.w0 = from.w0; to.L = from.L; to.K = from.K
        to.gamma = from.gamma; to.repeat = from.repeat
        for (l in 0..56) {
            to.Ml[l] = from.Ml[l]; to.Vl[l] = from.Vl[l]; to.log2Ml[l] = from.log2Ml[l]
            to.PHIl[l] = from.PHIl[l]; to.PSIl[l] = from.PSIl[l]
        }
    }

    private fun initParms() {
        prev.w0 = 0.09378f; prev.L = 30; prev.K = 10; prev.gamma = 0f
        for (l in 0..56) {
            prev.Ml[l] = 0f; prev.Vl[l] = 0; prev.log2Ml[l] = 0f; prev.PHIl[l] = 0f
            prev.PSIl[l] = (Math.PI / 2.0).toFloat()
        }
        prev.repeat = 0
        move(prev, cur)
        move(prev, prevEnh)
    }

    // ---------------------------------------------------------------- 49-bit parameter decode
    /** @return 0 = ok, 2 = erasure frame, 3 = tone frame */
    private fun decodeParms(): Int {
        val a = d
        var silence = false
        cur.repeat = prev.repeat

        val b0 = (a[0] shl 6) or (a[1] shl 5) or (a[2] shl 4) or (a[3] shl 3) or (a[37] shl 2) or (a[38] shl 1) or a[39]
        var f0 = 0f
        var L = 0
        if (b0 in 120..123) return 2
        else if (b0 == 124 || b0 == 125) {
            silence = true
            cur.w0 = (2.0 * Math.PI / 32.0).toFloat()
            f0 = 1f / 32f
            L = 14; cur.L = 14
            for (l in 1..L) cur.Vl[l] = 0
        } else if (b0 == 126 || b0 == 127) return 3

        if (!silence) {
            f0 = AmbeTables.ambeW0table[b0]
            cur.w0 = ((f0 * 2f).toDouble() * Math.PI).toFloat()
        }
        val unvc = 0.2046f / sqrt(cur.w0)

        if (!silence) { L = AmbeTables.ambeLtable[b0].toInt(); cur.L = L }

        val b1 = (a[4] shl 4) or (a[5] shl 3) or (a[6] shl 2) or (a[7] shl 1) or a[35]
        for (l in 1..L) {
            val jl = (l.toFloat() * 16.0f * f0).toInt()
            if (!silence) cur.Vl[l] = AmbeTables.ambeVuv[b1 * 8 + jl]
        }

        val b2 = (a[8] shl 4) or (a[9] shl 3) or (a[10] shl 2) or (a[11] shl 1) or a[36]
        val deltaGamma = AmbeTables.ambeDg[b2]
        cur.gamma = deltaGamma + (0.5f * prev.gamma)

        val Gm = FloatArray(9)
        val b3 = (a[12] shl 8) or (a[13] shl 7) or (a[14] shl 6) or (a[15] shl 5) or (a[16] shl 4) or
                (a[17] shl 3) or (a[18] shl 2) or (a[19] shl 1) or a[40]
        Gm[2] = AmbeTables.ambePRBA24[b3 * 3]; Gm[3] = AmbeTables.ambePRBA24[b3 * 3 + 1]; Gm[4] = AmbeTables.ambePRBA24[b3 * 3 + 2]
        val b4 = (a[20] shl 6) or (a[21] shl 5) or (a[22] shl 4) or (a[23] shl 3) or (a[41] shl 2) or (a[42] shl 1) or a[43]
        for (k in 0..3) Gm[5 + k] = AmbeTables.ambePRBA58[b4 * 4 + k]

        val Ri = FloatArray(9)
        for (i in 1..8) {
            var sum = 0f
            for (m in 1..8) {
                val am = if (m == 1) 1 else 2
                val arg = ((Math.PI * (m - 1).toFloat() * (i.toFloat() - 0.5f)) / 8f.toDouble()).toFloat()
                sum += am.toFloat() * Gm[m] * cos(arg)
            }
            Ri[i] = sum
        }

        val Cik = Array(5) { FloatArray(24) }
        val rconst = (1.0 / (2.0 * Math.sqrt(2.0))).toFloat()
        Cik[1][1] = 0.5f * (Ri[1] + Ri[2]); Cik[1][2] = rconst * (Ri[1] - Ri[2])
        Cik[2][1] = 0.5f * (Ri[3] + Ri[4]); Cik[2][2] = rconst * (Ri[3] - Ri[4])
        Cik[3][1] = 0.5f * (Ri[5] + Ri[6]); Cik[3][2] = rconst * (Ri[5] - Ri[6])
        Cik[4][1] = 0.5f * (Ri[7] + Ri[8]); Cik[4][2] = rconst * (Ri[7] - Ri[8])

        val b5 = (a[24] shl 4) or (a[25] shl 3) or (a[26] shl 2) or (a[27] shl 1) or a[44]
        val b6 = (a[28] shl 3) or (a[29] shl 2) or (a[30] shl 1) or a[45]
        val b7 = (a[31] shl 3) or (a[32] shl 2) or (a[33] shl 1) or a[46]
        val b8 = (a[34] shl 2) or (a[47] shl 1) or a[48]

        val Ji = IntArray(5)
        for (k in 0..3) Ji[k + 1] = AmbeTables.ambeLmprbl[L * 4 + k]

        for (k in 3..Ji[1]) Cik[1][k] = if (k > 6) 0f else AmbeTables.ambeHOCb5[b5 * 4 + k - 3]
        for (k in 3..Ji[2]) Cik[2][k] = if (k > 6) 0f else AmbeTables.ambeHOCb6[b6 * 4 + k - 3]
        for (k in 3..Ji[3]) Cik[3][k] = if (k > 6) 0f else AmbeTables.ambeHOCb7[b7 * 4 + k - 3]
        for (k in 3..Ji[4]) Cik[4][k] = if (k > 6) 0f else AmbeTables.ambeHOCb8[b8 * 4 + k - 3]

        val Tl = FloatArray(64)
        var l = 1
        for (i in 1..4) {
            val ji = Ji[i]
            for (j in 1..ji) {
                var sum = 0f
                for (k in 1..ji) {
                    val ak = if (k == 1) 1 else 2
                    val arg = ((Math.PI * (k - 1).toFloat() * (j.toFloat() - 0.5f)) / ji.toFloat().toDouble()).toFloat()
                    sum += ak.toFloat() * Cik[i][k] * cos(arg)
                }
                Tl[l] = sum
                l++
            }
        }

        if (cur.L > prev.L) {
            for (ll in prev.L + 1..cur.L) {
                prev.Ml[ll] = prev.Ml[prev.L]
                prev.log2Ml[ll] = prev.log2Ml[prev.L]
            }
        }
        prev.log2Ml[0] = prev.log2Ml[1]
        prev.Ml[0] = prev.Ml[1]

        val flokl = FloatArray(64); val intkl = IntArray(64); val deltal = FloatArray(64)
        var Sum43 = 0f
        for (ll in 1..cur.L) {
            flokl[ll] = (prev.L.toFloat() / cur.L.toFloat()) * ll.toFloat()
            intkl[ll] = flokl[ll].toInt()
            deltal[ll] = flokl[ll] - intkl[ll].toFloat()
            Sum43 += ((1f - deltal[ll]) * prev.log2Ml[intkl[ll]]) + (deltal[ll] * prev.log2Ml[intkl[ll] + 1])
        }
        Sum43 = (0.65f / cur.L.toFloat()) * Sum43

        var Sum42 = 0f
        for (ll in 1..cur.L) Sum42 += Tl[ll]
        Sum42 /= cur.L.toFloat()
        val bigGamma = (cur.gamma.toDouble() - (0.5 * (ln(cur.L.toFloat().toDouble()) / ln(2.0))) - Sum42.toDouble()).toFloat()

        for (ll in 1..cur.L) {
            val c1 = 0.65f * (1f - deltal[ll]) * prev.log2Ml[intkl[ll]]
            val c2 = 0.65f * deltal[ll] * prev.log2Ml[intkl[ll] + 1]
            cur.log2Ml[ll] = Tl[ll] + c1 + c2 - Sum43 + bigGamma
            val e = exp((0.693f * cur.log2Ml[ll]).toDouble())
            cur.Ml[ll] = if (cur.Vl[ll] == 1) e.toFloat() else (unvc.toDouble() * e).toFloat()
        }
        return 0
    }

    // ---------------------------------------------------------------- frame handling (concealment)
    private fun processData(out: FloatArray, errs2: Int) {
        val bad = decodeParms()
        if (bad == 2) {
            cur.repeat = 0
        } else if (bad == 3) {
            cur.repeat = 0
        } else if (errs2 > 3) {
            // too many FEC errors: repeat the previous frame's parameters
            cur.w0 = prev.w0; cur.L = prev.L; cur.K = prev.K; cur.gamma = prev.gamma; cur.repeat = prev.repeat
            for (l in 0..56) {
                cur.Ml[l] = prev.Ml[l]; cur.Vl[l] = prev.Vl[l]; cur.log2Ml[l] = prev.log2Ml[l]
                cur.PHIl[l] = prev.PHIl[l]; cur.PSIl[l] = prev.PSIl[l]
            }
            cur.repeat++
        } else {
            cur.repeat = 0
        }

        if (bad == 0) {
            if (cur.repeat <= 3) {
                move(cur, prev)
                spectralAmpEnhance()
                synthesize(out, prevEnh)
                move(cur, prevEnh)
            } else {
                java.util.Arrays.fill(out, 0f)
                initParms()
            }
        } else {
            java.util.Arrays.fill(out, 0f)
            initParms()
        }
    }

    // ---------------------------------------------------------------- spectral amplitude enhancement
    private fun spectralAmpEnhance() {
        val Wl = FloatArray(64)
        var Rm0 = 0f; var Rm1 = 0f
        for (l in 1..cur.L) {
            Rm0 += cur.Ml[l] * cur.Ml[l]
            Rm1 += (cur.Ml[l] * cur.Ml[l]) * cos(cur.w0 * l.toFloat())
        }
        val R2m0 = Rm0 * Rm0
        val R2m1 = Rm1 * Rm1
        for (l in 1..cur.L) {
            if (cur.Ml[l] != 0f) {
                val num = (0.96f.toDouble() * Math.PI) * ((R2m0 + R2m1) - (2f * Rm0 * Rm1 * cos(cur.w0 * l.toFloat()))).toDouble()
                val den = (cur.w0 * Rm0 * (R2m0 - R2m1)).toDouble()
                Wl[l] = sqrt(cur.Ml[l]) * (num / den).toFloat().pow(0.25f)
                if ((8 * l) <= cur.L) {
                } else if (Wl[l].toDouble() > 1.2) {
                    cur.Ml[l] = (1.2 * cur.Ml[l].toDouble()).toFloat()
                } else if (Wl[l] < 0.5f) {
                    cur.Ml[l] = 0.5f * cur.Ml[l]
                } else {
                    cur.Ml[l] = Wl[l] * cur.Ml[l]
                }
            }
        }
        var sum = 0f
        for (l in 1..cur.L) {
            var m = cur.Ml[l]
            if (m < 0f) m = -m
            sum += m * m
        }
        val gamma = if (sum == 0f) 1.0f else sqrt(Rm0 / sum)
        for (l in 1..cur.L) cur.Ml[l] = gamma * cur.Ml[l]
    }

    // ---------------------------------------------------------------- speech synthesis
    private fun synthesize(out: FloatArray, prev: Parms) { // 'prev' here is the ENHANCED previous frame, as in mbelib
        val uvquality = 3
        val N = 160
        val Ws = AmbeTables.ws
        val uvthreshold = ((2700f.toDouble() * Math.PI) / 4000f.toDouble()).toFloat()
        val uvsine = (1.3591409f.toDouble() * Math.E).toFloat()
        val uvrand = 2.0f
        val loguvquality = (ln(uvquality.toFloat().toDouble()) / uvquality.toFloat().toDouble()).toFloat()
        val uvstep = 1.0f / uvquality.toFloat()
        val qfactor = loguvquality
        val uvoffset = (uvstep * (uvquality - 1).toFloat()) / 2f

        var numUv = 0
        for (l in 1..cur.L) if (cur.Vl[l] == 0) numUv++

        val cw0 = cur.w0
        val pw0 = prev.w0
        java.util.Arrays.fill(out, 0, N, 0f)

        val maxl: Int
        if (cur.L > prev.L) {
            maxl = cur.L
            for (l in prev.L + 1..maxl) { prev.Ml[l] = 0f; prev.Vl[l] = 1 }
        } else {
            maxl = prev.L
            for (l in cur.L + 1..maxl) { cur.Ml[l] = 0f; cur.Vl[l] = 1 }
        }

        for (l in 1..56) {
            cur.PSIl[l] = prev.PSIl[l] + ((pw0 + cw0) * ((l * N).toFloat() / 2f))
            if (l <= (cur.L / 4)) cur.PHIl[l] = cur.PSIl[l]
            else cur.PHIl[l] = cur.PSIl[l] + ((numUv.toFloat() * mbeRandPhase()) / cur.L.toFloat())
        }

        val rphase = FloatArray(64); val rphase2 = FloatArray(64)
        for (l in 1..maxl) {
            val cw0l = cw0 * l.toFloat()
            val pw0l = pw0 * l.toFloat()
            if (cur.Vl[l] == 0 && prev.Vl[l] == 1) {
                for (i in 0 until uvquality) rphase[i] = mbeRandPhase()
                for (n in 0 until N) {
                    val C1 = Ws[n + N] * prev.Ml[l] * cos((pw0l * n.toFloat()) + prev.PHIl[l])
                    var C3 = 0f
                    for (i in 0 until uvquality) {
                        C3 += cos((cw0 * n.toFloat() * (l.toFloat() + (i.toFloat() * uvstep) - uvoffset)) + rphase[i])
                        if (cw0l > uvthreshold) C3 += (cw0l - uvthreshold) * uvrand * mbeRand()
                    }
                    C3 = C3 * uvsine * Ws[n] * cur.Ml[l] * qfactor
                    out[n] = out[n] + C1 + C3
                }
            } else if (cur.Vl[l] == 1 && prev.Vl[l] == 0) {
                for (i in 0 until uvquality) rphase[i] = mbeRandPhase()
                for (n in 0 until N) {
                    val C1 = Ws[n] * cur.Ml[l] * cos((cw0l * (n - N).toFloat()) + cur.PHIl[l])
                    var C3 = 0f
                    for (i in 0 until uvquality) {
                        C3 += cos((pw0 * n.toFloat() * (l.toFloat() + (i.toFloat() * uvstep) - uvoffset)) + rphase[i])
                        if (pw0l > uvthreshold) C3 += (pw0l - uvthreshold) * uvrand * mbeRand()
                    }
                    C3 = C3 * uvsine * Ws[n + N] * prev.Ml[l] * qfactor
                    out[n] = out[n] + C1 + C3
                }
            } else if (cur.Vl[l] == 1 || prev.Vl[l] == 1) {
                for (n in 0 until N) {
                    val C1 = Ws[n + N] * prev.Ml[l] * cos((pw0l * n.toFloat()) + prev.PHIl[l])
                    val C2 = Ws[n] * cur.Ml[l] * cos((cw0l * (n - N).toFloat()) + cur.PHIl[l])
                    out[n] = out[n] + C1 + C2
                }
            } else {
                for (i in 0 until uvquality) rphase[i] = mbeRandPhase()
                for (i in 0 until uvquality) rphase2[i] = mbeRandPhase()
                for (n in 0 until N) {
                    var C3 = 0f
                    for (i in 0 until uvquality) {
                        C3 += cos((pw0 * n.toFloat() * (l.toFloat() + (i.toFloat() * uvstep) - uvoffset)) + rphase[i])
                        if (pw0l > uvthreshold) C3 += (pw0l - uvthreshold) * uvrand * mbeRand()
                    }
                    C3 = C3 * uvsine * Ws[n + N] * prev.Ml[l] * qfactor
                    var C4 = 0f
                    for (i in 0 until uvquality) {
                        C4 += cos((cw0 * n.toFloat() * (l.toFloat() + (i.toFloat() * uvstep) - uvoffset)) + rphase2[i])
                        if (cw0l > uvthreshold) C4 += (cw0l - uvthreshold) * uvrand * mbeRand()
                    }
                    C4 = C4 * uvsine * Ws[n] * cur.Ml[l] * qfactor
                    out[n] = out[n] + C3 + C4
                }
            }
        }
    }

    private companion object {
        // DMR AMBE interleave schedule (from the DSD project's dmr_const.h)
        val RW = intArrayOf(0,1,0,1,0,1, 0,1,0,1,0,1, 0,1,0,1,0,1, 0,1,0,1,0,2, 0,2,0,2,0,2, 0,2,0,2,0,2)
        val RX = intArrayOf(23,10,22,9,21,8, 20,7,19,6,18,5, 17,4,16,3,15,2, 14,1,13,0,12,10, 11,9,10,8,9,7, 8,6,7,5,6,4)
        val RY = intArrayOf(0,2,0,2,0,2, 0,2,0,3,0,3, 1,3,1,3,1,3, 1,3,1,3,1,3, 1,3,1,3,1,3, 1,3,1,3,1,3)
        val RZ = intArrayOf(5,3,4,2,3,1, 2,0,1,13,0,12, 22,11,21,10,20,9, 19,8,18,7,17,6, 16,5,15,4,14,3, 13,2,12,1,11,0)
    }
}
