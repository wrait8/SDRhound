package com.example.rtlsdr

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 8 kHz -> 48 kHz (x6) polyphase interpolator with a windowed-sinc low-pass at 3.7 kHz. The vocoder's 8 kHz
 * output has to be upsampled for the fixed 48 kHz audio track; plain linear interpolation (what this replaces)
 * leaves strong spectral images above 4 kHz that make speech sound buzzy and harsh.
 */
class Upsampler6 {
    private companion object {
        const val L = 6
        const val PER = 12                 // input samples spanned per output sample
        const val TAPS = L * PER
    }
    private val h = FloatArray(TAPS)
    private val hist = FloatArray(PER)    // hist[0] = newest input sample

    init {
        val fc = 3700.0 / 48000.0           // cutoff, cycles per output sample
        val m = (TAPS - 1) / 2.0
        var sum = 0.0
        val t = DoubleArray(TAPS)
        for (i in 0 until TAPS) {
            val x = i - m
            val sinc = if (Math.abs(x) < 1e-9) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
            val w = 0.42 - 0.5 * cos(2 * PI * i / (TAPS - 1)) + 0.08 * cos(4 * PI * i / (TAPS - 1)) // Blackman
            t[i] = sinc * w
            sum += t[i]
        }
        for (i in 0 until TAPS) h[i] = (t[i] / sum * L).toFloat() // unity gain after zero-stuffing
    }

    fun reset() { java.util.Arrays.fill(hist, 0f) }

    /** [pcm] = 16-bit samples at 8 kHz; returns float samples at 48 kHz scaled by [gain]. */
    fun process(pcm: ShortArray, gain: Float): FloatArray {
        val out = FloatArray(pcm.size * L)
        for (i in pcm.indices) {
            System.arraycopy(hist, 0, hist, 1, PER - 1)
            hist[0] = pcm[i] / 32768f * gain
            for (p in 0 until L) {
                var acc = 0f
                var k = 0
                var idx = p
                while (idx < TAPS) { acc += h[idx] * hist[k]; idx += L; k++ }
                out[i * L + p] = acc
            }
        }
        return out
    }
}
