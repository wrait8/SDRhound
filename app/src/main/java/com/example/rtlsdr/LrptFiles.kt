package com.example.rtlsdr

import java.io.InputStream
import java.util.Locale

/**
 * Offline LRPT decoding from recorded files, in the formats SatDump and common SDR tools write:
 *  - baseband IQ: WAV (PCM 8/16-bit or 32-bit float, 2 channels = I/Q, the sample rate is read from the header), or raw
 *    .cu8 / .cs8 / .cs16 / .cf32 (little endian; the sample rate must be given or is guessed from the file name);
 *  - demodulated soft symbols: .soft / .s (signed 8-bit I,Q pairs, as written by meteor_demod / MeteorDemod / SatDump);
 *  - .cadu: already error-corrected frames (1024 bytes each).
 */
object LrptFiles {
    enum class Kind(val label: String, val needsRate: Boolean) {
        WAV("WAV IQ", false), CU8("raw IQ u8", true), CS8("raw IQ s8", true), CS16("raw IQ s16", true), CF32("raw IQ f32", true),
        SOFT("soft symbols", false), CADU("CADU frames", false)
    }

    /** Guess the format from the file name (and the first bytes for WAV). */
    fun detect(name: String, head: ByteArray): Kind? {
        val n = name.lowercase(Locale.ROOT)
        if (head.size >= 12 && String(head, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(head, 8, 4, Charsets.ISO_8859_1) == "WAVE") return Kind.WAV
        return when {
            n.endsWith(".wav") -> Kind.WAV
            n.endsWith(".cu8") || n.endsWith(".u8") || n.endsWith(".iq") || n.endsWith(".bin") || n.endsWith(".raw") -> Kind.CU8
            n.endsWith(".cs8") || n.endsWith(".s8") -> Kind.CS8
            n.endsWith(".cs16") || n.endsWith(".s16") -> Kind.CS16
            n.endsWith(".cf32") || n.endsWith(".f32") || n.endsWith(".fc32") -> Kind.CF32
            n.endsWith(".soft") || n.endsWith(".s") -> Kind.SOFT
            n.endsWith(".cadu") -> Kind.CADU
            else -> null
        }
    }

    /** Sample rate hidden in a file name such as "baseband_137900000Hz_12-34-56_1024000SPS.cu8" or "iq_2.4msps.bin". */
    fun guessRate(name: String): Int? {
        val n = name.lowercase(Locale.ROOT)
        Regex("(\\d{5,8})\\s*(sps|sa/s|hz|sr)").findAll(n).forEach { m -> val v = m.groupValues[1].toInt(); if (v in 200_000..20_000_000 && v != 137_900_000) return v }
        Regex("(\\d+(?:\\.\\d+)?)\\s*(msps|ms/s|msamples)").find(n)?.let { return (it.groupValues[1].toDouble() * 1e6).toInt() }
        Regex("(\\d+(?:\\.\\d+)?)\\s*(ksps|ks/s)").find(n)?.let { return (it.groupValues[1].toDouble() * 1e3).toInt() }
        return null
    }

    class WavInfo(val rate: Int, val bits: Int, val float: Boolean, val channels: Int, val dataStart: Int)

    /** Parses a WAV header from [head] (needs the first few hundred bytes), or null. */
    fun parseWav(head: ByteArray): WavInfo? {
        if (head.size < 44 || String(head, 0, 4, Charsets.ISO_8859_1) != "RIFF") return null
        fun u16(o: Int) = (head[o].toInt() and 255) or ((head[o + 1].toInt() and 255) shl 8)
        fun u32(o: Int) = u16(o) or (u16(o + 2) shl 16)
        var pos = 12; var rate = 0; var bits = 0; var fl = false; var ch = 0
        while (pos + 8 <= head.size) {
            val id = String(head, pos, 4, Charsets.ISO_8859_1); val len = u32(pos + 4)
            if (id == "fmt ") { val tag = u16(pos + 8); ch = u16(pos + 10); rate = u32(pos + 12); bits = u16(pos + 22); fl = tag == 3 || (tag == 0xFFFE && bits == 32) }
            else if (id == "data") return if (rate > 0) WavInfo(rate, bits, fl, ch, pos + 8) else null
            pos += 8 + len + (len and 1)
        }
        return null
    }

    /**
     * Reads [input] to the end, feeding [rx]. [rate] is used for raw IQ (WAV carries its own). [progress] gets 0..1 (when [totalBytes] is
     * known); [cancel] returning true stops early. Returns a short text describing what was read.
     */
    fun decode(input: InputStream, totalBytes: Long, name: String, kindIn: Kind?, rateIn: Int, rx: LrptReceiver, progress: (Float) -> Unit, cancel: () -> Boolean): String {
        val bis = java.io.BufferedInputStream(input, 1 shl 16)
        bis.mark(4096)
        val head = ByteArray(4096); val hn = bis.read(head); bis.reset()
        // A real WAV's own magic bytes always win over any forced/guessed kind: no genuine raw-IQ recording starts with
        // "RIFF"/"WAVE" by chance, so if we see it, a caller's wrong guess (e.g. from a file name with no ".wav" extension)
        // must not be allowed to decode the header and 16-bit PCM audio as if they were raw 8-bit IQ samples.
        val realKind = if (hn >= 12 && String(head, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(head, 8, 4, Charsets.ISO_8859_1) == "WAVE") Kind.WAV else null
        val kind = realKind ?: kindIn ?: detect(name, head.copyOf(maxOf(0, hn))) ?: return "unknown file type (use .wav, .cu8, .cs16, .cf32, .soft or .cadu)"
        var rate = rateIn
        var bytesPerSample = 2; var fmt = kind
        var read = 0L
        if (kind == Kind.WAV) {
            val w = parseWav(head.copyOf(maxOf(0, hn))) ?: return "not a readable WAV file"
            if (w.channels != 2) return "WAV must have 2 channels (I and Q), this one has ${w.channels}"
            rate = w.rate
            fmt = when { w.float && w.bits == 32 -> Kind.CF32; w.bits == 16 -> Kind.CS16; w.bits == 8 -> Kind.CU8; else -> return "unsupported WAV format (${w.bits}-bit)" }
            var skip = w.dataStart.toLong(); while (skip > 0) { val s = bis.skip(skip); if (s <= 0) break; skip -= s }
            read = w.dataStart.toLong()
        }
        val soft = kind == Kind.SOFT; val cadu = kind == Kind.CADU
        if (!soft && !cadu) {
            // the OQPSK signal itself is only ~115 kHz wide; 140 kHz leaves enough margin for a few kHz of tuning/Doppler error.
            // Below ~200 kHz there is less headroom for frequency offset, so a mistuned recording is more likely to fail --
            // that shows up as "frames ok 0" in the diagnostic line below, not as a rejection here.
            if (rate < 140_000) return "sample rate ${rate} Hz is too low for LRPT (the signal itself needs at least ~115 kHz; 140 kHz is the practical floor)"
            bytesPerSample = when (fmt) { Kind.CU8, Kind.CS8 -> 1; Kind.CS16 -> 2; else -> 4 }
        }
        val frameBytes = if (soft || cadu) 65536 else 2 * bytesPerSample * 32768
        val buf = ByteArray(frameBytes)
        rx.offline = true; var maxQ = 0.0
        var carry = 0
        val fi = FloatArray(32768); val fq = FloatArray(32768)
        var total = 0L
        try { while (!cancel()) {
            val n = bis.read(buf, carry, buf.size - carry)
            if (n <= 0) break
            read += n; total += n
            val avail = carry + n
            if (soft) { rx.feedSoft(buf, avail and 1.inv()); carry = avail and 1; if (carry == 1) buf[0] = buf[avail - 1] }
            else if (cadu) { val use = avail - avail % Lrpt.CADU_BYTES; rx.feedCadu(buf, use); carry = avail - use; if (carry > 0) System.arraycopy(buf, use, buf, 0, carry) }
            else {
                val step = 2 * bytesPerSample; val count = avail / step
                for (k in 0 until count) {
                    val o = k * step
                    when (fmt) {
                        Kind.CU8 -> { fi[k] = ((buf[o].toInt() and 255) - 127.5f) / 128f; fq[k] = ((buf[o + 1].toInt() and 255) - 127.5f) / 128f }
                        Kind.CS8 -> { fi[k] = buf[o] / 128f; fq[k] = buf[o + 1] / 128f }
                        Kind.CS16 -> { fi[k] = ((buf[o].toInt() and 255) or (buf[o + 1].toInt() shl 8)).toShort() / 32768f; fq[k] = ((buf[o + 2].toInt() and 255) or (buf[o + 3].toInt() shl 8)).toShort() / 32768f }
                        else -> { fi[k] = java.lang.Float.intBitsToFloat((buf[o].toInt() and 255) or ((buf[o + 1].toInt() and 255) shl 8) or ((buf[o + 2].toInt() and 255) shl 16) or (buf[o + 3].toInt() shl 24))
                                  fq[k] = java.lang.Float.intBitsToFloat((buf[o + 4].toInt() and 255) or ((buf[o + 5].toInt() and 255) shl 8) or ((buf[o + 6].toInt() and 255) shl 16) or (buf[o + 7].toInt() shl 24)) }
                    }
                }
                rx.process(fi, fq, count, rate)
                maxQ = maxOf(maxQ, rx.signalQuality)
                carry = avail - count * step
                if (carry > 0) System.arraycopy(buf, count * step, buf, 0, carry)
            }
            if (totalBytes > 0) progress((read.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f))
        }
        } finally { rx.offline = false }
        rx.flush()
        progress(1f)
        val diag = "  |  frames ok ${rx.rsOk} bad ${rx.rsFail}, peak signal %.1f (noise ~3, signal >5), carrier %+.0f Hz".format(Locale.US, maxQ, rx.carrierHz)
        return if (soft || cadu) "${kind.label}: ${total / 1024} KB$diag" else "${kind.label} at ${"%.3f".format(Locale.US, rate / 1e6)} MS/s: ${total / (2L * bytesPerSample * rate).coerceAtLeast(1)} s of signal$diag"
    }
}
