package com.example.rtlsdr

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Bandwidths are the full channel width in Hz. */
enum class Mode(val label: String, val minBw: Float, val maxBw: Float, val defBw: Float) {
    WFM("WFM", 100_000f, 220_000f, 200_000f),
    NFM("NFM", 5_000f, 25_000f, 12_500f),
    AM("AM", 3_000f, 20_000f, 10_000f),
    USB("USB", 1_500f, 4_000f, 2_700f),
    LSB("LSB", 1_500f, 4_000f, 2_700f)
}

/** Windowed-sinc low-pass FIR with decimation, one channel, keeps state between calls. */
class FirDecim(private val factor: Int, cutoffHz: Double, fs: Double, private val taps: Int) {
    private val h = FloatArray(taps)
    private var work = FloatArray(0)
    private var carried = 0

    init {
        val m = (taps - 1) / 2.0
        var sum = 0.0
        val t = DoubleArray(taps)
        for (i in 0 until taps) {
            val x = i - m
            val sinc = if (abs(x) < 1e-9) 2 * cutoffHz / fs else sin(2 * PI * cutoffHz / fs * x) / (PI * x)
            t[i] = sinc * (0.54 - 0.46 * cos(2 * PI * i / (taps - 1)))
            sum += t[i]
        }
        for (i in 0 until taps) h[i] = (t[i] / sum).toFloat()
    }

    fun process(inp: FloatArray, n: Int, out: FloatArray): Int {
        val total = carried + n
        if (work.size < total) work = work.copyOf(total + 1024)
        System.arraycopy(inp, 0, work, carried, n)
        var p = 0; var o = 0
        while (p + taps <= total) {
            var acc = 0f
            for (k in 0 until taps) acc += work[p + k] * h[k]
            out[o++] = acc
            p += factor
        }
        carried = total - p
        System.arraycopy(work, p, work, 0, carried)
        return o
    }
}

/** 48 kHz mono float audio output (non-blocking so the radio thread never stalls). */
class AudioOut {
    private val rate = 48000
    private var track: AudioTrack? = null

    fun start() {
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(max(min, rate * 4 / 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().also { it.play() }
    }

    fun write(a: FloatArray, n: Int) { try { track?.write(a, 0, n, AudioTrack.WRITE_NON_BLOCKING) } catch (_: Exception) {} }

    fun stop() { val t = track; track = null; try { t?.stop(); t?.release() } catch (_: Exception) {} }
}

/** IQ (any multiple of 240 kS/s) -> 48 kHz audio, with adjustable bandwidth and squelch. */
class Demodulator {
    companion object { const val SQL_OFF = -70f }

    @Volatile var mode = Mode.WFM
    @Volatile var bandwidth = Mode.WFM.defBw
    /** Input IQ rate; must be a multiple of 240 kS/s. */
    @Volatile var sampleRate = SdrEngine.DEFAULT_RATE
    @Volatile var volume = 0.8f
    @Volatile var muted = false
    @Volatile var squelchDb = SQL_OFF
    /** In-channel signal level in dB (full scale = 0), for the squelch slider. */
    @Volatile var level = -100f; private set
    /** Receives the (squelched, pre-volume) audio, used for recording. */
    @Volatile var tap: ((FloatArray, Int) -> Unit)? = null

    private val audio = AudioOut()
    private var cur: Mode? = null
    private var curBw = 0f
    private var curRate = 0
    private var d1 = 4
    private var ssbI = FloatArray(0); private var ssbQ = FloatArray(0)
    private lateinit var s1i: FirDecim
    private lateinit var s1q: FirDecim
    private lateinit var s2i: FirDecim
    private lateinit var s2q: FirDecim

    private var b1i = FloatArray(0); private var b1q = FloatArray(0)
    private var b2i = FloatArray(0); private var b2q = FloatArray(0)
    private var aud = FloatArray(0)

    private var lastI = 0f; private var lastQ = 0f
    private var deemph = 0f
    private var dc = 0.1f
    private var peak = 0.01f
    private var preP = 0.0; private var postP = 0.0
    private var sqlOpen = true

    fun start() = audio.start()
    fun stop() = audio.stop()

    private fun setup(m: Mode, bw: Float, rate: Int) {
        val fs = rate.toDouble()
        d1 = (rate / 240_000).coerceAtLeast(1)
        val fs2 = 240_000.0
        val half = bw / 2.0
        val c1 = if (m == Mode.WFM) half else 100_000.0
        val taps1 = maxOf(64, d1 * 8)
        s1i = FirDecim(d1, c1, fs, taps1); s1q = FirDecim(d1, c1, fs, taps1)
        when (m) {
            Mode.WFM -> { s2i = FirDecim(5, 15_000.0, fs2, 97); s2q = s2i }
            Mode.NFM, Mode.AM -> { s2i = FirDecim(5, half, fs2, 129); s2q = FirDecim(5, half, fs2, 129) }
            Mode.USB, Mode.LSB -> { s2i = FirDecim(5, half, fs2, 257); s2q = FirDecim(5, half, fs2, 257) }
        }
        lastI = 0f; lastQ = 0f; deemph = 0f; peak = 0.01f; dc = 0.1f
        cur = m; curBw = bw; curRate = rate
    }

    fun process(i: FloatArray, q: FloatArray, n: Int) {
        val bw = bandwidth
        val rate = sampleRate
        if (cur != mode || curBw != bw || curRate != rate) setup(mode, bw, rate)
        val m = cur!!
        val cap = n / d1 + 256
        if (b1i.size < cap) { b1i = FloatArray(cap); b1q = FloatArray(cap); b2i = FloatArray(cap); b2q = FloatArray(cap); aud = FloatArray(cap) }

        // SSB: shift the wanted sideband around 0 Hz before filtering (shift = half the bandwidth)
        val sgn = if (m == Mode.USB) 1.0 else -1.0
        val shift = bw / 2.0
        var srcI = i; var srcQ = q
        if (m == Mode.USB || m == Mode.LSB) {
            if (ssbI.size < n) { ssbI = FloatArray(n); ssbQ = FloatArray(n) }
            val ti = ssbI; val tq = ssbQ
            val inc = 2 * PI * shift / rate
            for (k in 0 until n) {
                val a = -sgn * preP
                val c = cos(a).toFloat(); val s = sin(a).toFloat()
                ti[k] = i[k] * c - q[k] * s
                tq[k] = i[k] * s + q[k] * c
                preP += inc; if (preP > 2 * PI) preP -= 2 * PI
            }
            srcI = ti; srcQ = tq
        }
        val n1 = s1i.process(srcI, n, b1i)
        s1q.process(srcQ, n, b1q)

        val outN: Int
        var li = b1i; var lq = b1q; var ln = n1
        when (m) {
            Mode.WFM -> {
                val alpha = 1f - exp(-1.0 / (240_000.0 * 75e-6)).toFloat()
                for (k in 0 until n1) {
                    val re = b1i[k] * lastI + b1q[k] * lastQ
                    val im = b1q[k] * lastI - b1i[k] * lastQ
                    lastI = b1i[k]; lastQ = b1q[k]
                    val d = atan2(im, re) * 0.509f
                    deemph += alpha * (d - deemph)
                    b2i[k] = deemph
                }
                outN = s2i.process(b2i, n1, aud)
            }
            else -> {
                val n2 = s2i.process(b1i, n1, b2i)
                s2q.process(b1q, n1, b2q)
                outN = n2
                li = b2i; lq = b2q; ln = n2
                when (m) {
                    Mode.NFM -> for (k in 0 until n2) {
                        val re = b2i[k] * lastI + b2q[k] * lastQ
                        val im = b2q[k] * lastI - b2i[k] * lastQ
                        lastI = b2i[k]; lastQ = b2q[k]
                        aud[k] = atan2(im, re) * 1.5f
                    }
                    Mode.AM -> for (k in 0 until n2) {
                        val mag = sqrt(b2i[k] * b2i[k] + b2q[k] * b2q[k])
                        dc += 0.001f * (mag - dc)
                        aud[k] = (mag - dc) / (dc + 1e-4f)
                    }
                    else -> {
                        val inc = 2 * PI * shift / 48000.0
                        for (k in 0 until n2) {
                            val b = sgn * postP
                            val x = (b2i[k] * cos(b) - b2q[k] * sin(b)).toFloat()
                            postP += inc; if (postP > 2 * PI) postP -= 2 * PI
                            peak = max(abs(x), peak * 0.9995f)
                            aud[k] = x / (peak + 1e-3f) * 0.5f
                        }
                    }
                }
            }
        }

        // Squelch: gate on the in-channel level (with 2 dB hysteresis)
        if (ln > 0) {
            var p = 0.0
            for (k in 0 until ln) p += (li[k] * li[k] + lq[k] * lq[k]).toDouble()
            val db = (10.0 * log10(p / ln + 1e-12)).toFloat()
            level = if (level <= -99f) db else level + 0.15f * (db - level)
        }
        val thr = squelchDb
        if (thr <= Demodulator.SQL_OFF) sqlOpen = true
        else if (level > thr) sqlOpen = true
        else if (level < thr - 2f) sqlOpen = false
        if (!sqlOpen) java.util.Arrays.fill(aud, 0, outN, 0f)

        tap?.invoke(aud, outN)

        val v = if (muted) 0f else volume
        for (k in 0 until outN) aud[k] = (aud[k] * v).coerceIn(-1f, 1f)
        audio.write(aud, outN)
    }
}