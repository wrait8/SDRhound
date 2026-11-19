package com.example.rtlsdr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class CchResult(val hex: String, val bytes: ByteArray) {
    /** True for a BCCH/CCCH message (what grgsm_livemon shows): L2 pseudo-length octet (low bits 01), radio-resource
        protocol discriminator 0x06, or a pure fill frame. Valid blocks from OTHER timeslots that share the
        training sequence (SDCCH/SACCH...) also pass the Fire check but fail this, and would show up in
        Wireshark as malformed packets, so they are not forwarded. */
    val isCcch: Boolean = run {
        val b0 = bytes[0].toInt() and 0xFF; val b1 = bytes[1].toInt() and 0xFF
        bytes.all { it.toInt() and 0xFF == 0x2b } || ((b0 and 3) == 1 && b1 == 0x06 && (b0 shr 2) <= 22)
    }
}
/** One single burst that synced (training sequence found + coherent check passed): the raw 114 coded bits as hex.
    Only a heartbeat so the person can see the receiver is alive; rate limited. */
class RawBurst(val tsc: Int, val errBits: Int, val hex: String)

/** One line of the live verify feed. [kind]: 0 = raw single-burst sighting, 1 = a 4-burst group was tried
    but failed the Fire check, 2 = fully FEC-verified message. */
class VerifyLine(val kind: Int, val text: String, val atMs: Long)

/** 4 same-timeslot bursts were grouped but the Fire-code check rejected the result. */
class TriedGroup(val hex: String)

/** One row of the Wireshark-style packet list: a fully FEC-verified L3 message, named the way the
    BCCH/CCCH message-type byte identifies it (the same byte Wireshark's GSM CCCH dissector keys off). */
class GsmPacket(val no: Int, val atMs: Long, val arfcn: Int, val channel: String, val type: String, val length: Int, val hex: String)

/** Builds the 16-byte GSMTAP pseudo-header (version 2, type UM) used both for live UDP delivery to an
    external Wireshark and for the bytes written into a .pcap recording -- the two paths share this so a
    recorded packet looks exactly like what Wireshark would have seen live. */
object Gsmtap {
    /** BCCH (system information) vs CCCH (paging, assignments): decided from the L3 message type, since the
        burst position in the 51-multiframe is not tracked here. Wireshark dissects the two differently. */
    fun subType(l3: ByteArray): Int {
        if (l3.size < 3) return 0x02
        return when (l3[2].toInt() and 0xFF) {
            0x19, 0x1a, 0x1b, 0x1c, 0x02, 0x03, 0x07, 0x00 -> 0x01 // SI1,2,3,4, 2bis, 2ter, 2quater, 13 -> BCCH
            else -> 0x02                                           // CCCH
        }
    }

    /** Short "channel" label for the packet list, mirroring [subType]'s BCCH/CCCH split. */
    fun channelName(l3: ByteArray) = if (subType(l3) == 0x01) "BCCH" else "CCCH"

    /** Human message name from the L3 message-type byte (RR messages on BCCH/CCCH, TS 04.08 §10.4),
        the same names Wireshark shows in its Info column for these frames. */
    fun messageName(l3: ByteArray): String {
        if (l3.size < 3) return "Unknown"
        val mt = l3[2].toInt() and 0xFF
        return when (mt) {
            0x19 -> "System Information Type 1"
            0x1a -> "System Information Type 2"
            0x1b -> "System Information Type 3"
            0x1c -> "System Information Type 4"
            0x02 -> "System Information Type 2bis"
            0x03 -> "System Information Type 2ter"
            0x07 -> "System Information Type 2quater"
            0x00 -> "System Information Type 13"
            0x21 -> "Paging Request Type 1"
            0x22 -> "Paging Request Type 2"
            0x24 -> "Paging Request Type 3"
            0x3f -> "Immediate Assignment"
            0x39 -> "Immediate Assignment Extended"
            0x3a -> "Immediate Assignment Reject"
            else -> "RR message 0x%02x".format(mt)
        }
    }

    fun buildFrame(arfcn: Int, l3: ByteArray): ByteArray {
        val buf = java.nio.ByteBuffer.allocate(16 + l3.size).order(java.nio.ByteOrder.BIG_ENDIAN)
        buf.put(0x02)                 // version
        buf.put(4)                    // hdr_len, in 32-bit words
        buf.put(0x01)                 // type = GSMTAP_TYPE_UM
        buf.put(0)                    // timeslot (always 0 for BCCH/CCCH)
        buf.putShort(arfcn.coerceAtLeast(0).toShort()) // arfcn (0 if unknown)
        buf.put(0); buf.put(0)        // signal_dbm, snr_db (not measured)
        buf.putInt(0)                 // frame_number (not tracked)
        buf.put(subType(l3).toByte()) // sub_type
        buf.put(0); buf.put(0); buf.put(0) // antenna_nr, sub_slot, res
        buf.put(l3)
        return buf.array()
    }
}

/** Sends the GSMTAP frame over UDP to port 4729 -- the same format and port grgsm_livemon uses, so an
    external Wireshark pointed at this phone (or listening on its own loopback) decodes it live. */
class GsmtapSender(host: String = "127.0.0.1", port: Int = 4729) {
    private var socket: java.net.DatagramSocket? = null
    private var addr: java.net.InetAddress? = null

    /** Destination of the GSMTAP packets (e.g. the PC running Wireshark: "192.168.1.20"). Changing it
        reopens the socket on the next send. A LAN broadcast address such as 255.255.255.255 also works. */
    @Volatile var host: String = host
        set(v) { field = v; close() }
    @Volatile var port: Int = port
        set(v) { field = v; close() }

    fun send(arfcn: Int, l3: ByteArray) {
        try {
            if (socket == null) { socket = java.net.DatagramSocket().also { it.broadcast = true }; addr = java.net.InetAddress.getByName(host) }
            val frame = Gsmtap.buildFrame(arfcn, l3)
            val pkt = java.net.DatagramPacket(frame, frame.size, addr, port)
            socket?.send(pkt)
        } catch (_: Exception) {}
    }

    fun close() { try { socket?.close() } catch (_: Exception) {}; socket = null }
}

/**
 * GSM control-channel (BCCH/CCCH) receiver: IQ at 1.083333 MS/s (4 samples/symbol) -> hex L3 messages.
 *
 * Why it works the way it does (GSM 05.04): the GMSK frequency sign carries the DIFFERENTIALLY encoded bits,
 * d(k) xor d(k-1), not the bits themselves. Comparing the discriminator output straight to the training
 * sequence therefore never matches. Instead:
 *  1. find a burst cheaply on the hard frequency-sign stream (training sequence in its differential form,
 *     one popcount per training sequence per sample),
 *  2. confirm and measure it coherently: derotating the symbol-rate samples by (-j)^k turns GMSK into
 *     (almost) plain BPSK on the real axis, so Re() is the bit directly, with soft confidence and no
 *     error propagation; the odd-symbol ISI lands on the imaginary axis,
 *  3. chain 4 bursts 1250 symbols apart, soft deinterleave, soft Viterbi, Fire code.
 * Everything per sample is a handful of multiplies, so it keeps up with the radio in real time.
 */
class GsmCchVerifier {
    companion object {
        const val RATE = 1_083_333 // 4 samples/symbol (symbol rate 1625000/6 = 270833.33)
        private const val SYM = 270_833.333
        private const val TAPS = 41
        private const val RB = 4096                 // complex ring buffer (needs ~600 samples of history)
        private const val RMASK = RB - 1
        private const val TSC_LEN = 26
        private const val HARD_ERR = 3              // max hard-stage mismatches of the 25 differential bits
        private const val SLOT = 5000L              // samples between bursts of one timeslot (1250 symbols x 4)
        private const val SLOT_TOL = 6L
        private const val RIPE = 250L               // samples to wait after the last candidate so data2 is in the ring
        private const val Q_MIN = 0.75f             // coherent training-sequence quality needed to accept a burst (noise peaks ~0.80 only rarely)
        private const val Q_RAW = 0.82f             // stricter bar for the raw 'burst seen' heartbeat: pure noise essentially never reaches it
        private val SWEEP = floatArrayOf(0f, 12e3f, -12e3f, 24e3f, -24e3f, 36e3f, -36e3f)
    }

    /** Frequency error of the channel in Hz (positive = the signal sits above the tuned frequency).
        Seed it from the scan's FCCH measurement; it then keeps refining itself from every decoded burst. */
    @Volatile var freqOffsetHz = 0f
    /** Grey single-burst heartbeat lines and amber "Fire check failed" lines: on by default while debugging; set both false for a livemon-style feed that only prints decoded messages. Counters below still run. */
    @Volatile var showRaw = true
    @Volatile var showTried = true
    var burstsSynced = 0; private set
    var groupsTried = 0; private set
    var groupsOk = 0; private set

    // ---- front end ----
    private val h = FloatArray(TAPS)
    private var xi = FloatArray(0); private var xq = FloatArray(0)
    private var dcPI = 0f; private var dcPQ = 0f; private var dcOI = 0f; private var dcOQ = 0f
    private var oscI = 1f; private var oscQ = 0f
    private var rotI = 1f; private var rotQ = 0f
    private var appliedHz = Float.NaN
    private var afc = 0f; private var sweepIdx = 0
    private var sinceLock = 0L

    // ---- symbol-rate timeline ----
    private val zi = FloatArray(RB); private val zq = FloatArray(RB)
    private var n = 0L // absolute index of the next filtered sample
    private val sr = IntArray(4)
    private val pat = IntArray(8)
    private val tscSign = Array(8) { t -> FloatArray(TSC_LEN) { k -> 1f - 2f * Cch.TSC[t][k] } } // D = (-1)^bit
    private val tscAlpha = Array(8) { t -> FloatArray(TSC_LEN) { k -> if (k == 0) 0f else tscSign[t][k] * tscSign[t][k - 1] } }

    private class Cand(var lastN: Long, var bestN: Long, var bestErr: Int) { var mask = 0 }
    private val pends = ArrayList<Cand>() // bursts waiting for their data2 half to arrive, oldest first

    private class BurstRec(val e: Long, val tsc: Int, val soft: FloatArray, val w: Float, val q: Float) { var used = false }
    private val bursts = ArrayList<BurstRec>()

    private val result = ArrayList<CchResult>()
    private val raw = ArrayList<RawBurst>()
    private val tried = ArrayList<TriedGroup>()
    private var lastRawN = -1_000_000_000L
    private var lastTriedN = -1_000_000_000L

    // per-burst working values (avoid allocation)
    private val tmpSoft = FloatArray(114)
    private var mFreq = 0f; private var mQ = 0f; private var mTsc = 0

    init {
        val fc = 115_000.0 / RATE
        val m = (TAPS - 1) / 2.0
        var sum = 0.0
        val t = DoubleArray(TAPS)
        for (i in 0 until TAPS) {
            val x = i - m
            val s = if (abs(x) < 1e-9) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
            t[i] = s * (0.54 - 0.46 * cos(2 * PI * i / (TAPS - 1)))
            sum += t[i]
        }
        for (i in 0 until TAPS) h[i] = (t[i] / sum).toFloat()
        for (t2 in 0 until 8) { var v = 0; for (k in 1 until TSC_LEN) v = (v shl 1) or (if (tscAlpha[t2][k] > 0f) 1 else 0); pat[t2] = v }
    }

    fun reset() {
        n = 0; java.util.Arrays.fill(sr, 0); pends.clear(); bursts.clear()
        dcPI = 0f; dcPQ = 0f; dcOI = 0f; dcOQ = 0f
        java.util.Arrays.fill(zi, 0f); java.util.Arrays.fill(zq, 0f)
        xi = FloatArray(0); xq = FloatArray(0)
        appliedHz = Float.NaN; oscI = 1f; oscQ = 0f
        afc = 0f; sweepIdx = 0; sinceLock = 0
        lastRawN = -1_000_000_000L; lastTriedN = -1_000_000_000L
        burstsSynced = 0; groupsTried = 0; groupsOk = 0
    }

    /** Call when samples were dropped: spacing between bursts is no longer meaningful. */
    fun markGap() { pends.clear(); bursts.clear() }

    val rawBursts: List<RawBurst> get() = raw
    val triedGroups: List<TriedGroup> get() = tried

    @Suppress("UNUSED_PARAMETER")
    fun process(i: FloatArray, q: FloatArray, n0: Int, rate: Int): List<CchResult> {
        result.clear(); raw.clear(); tried.clear()
        val cnt = n0
        val hist = TAPS - 1
        if (xi.size < cnt + hist) { // keep the filter history when growing
            val ni = FloatArray(cnt + hist + 1024); val nq = FloatArray(cnt + hist + 1024)
            System.arraycopy(xi, 0, ni, 0, minOf(xi.size, hist)); System.arraycopy(xq, 0, nq, 0, minOf(xq.size, hist))
            xi = ni; xq = nq
        }
        // slide the last TAPS-1 inputs of the previous call to the front
        // (on the first call they are zero)
        val effHz = freqOffsetHz + afc + SWEEP[sweepIdx]
        if (effHz != appliedHz) {
            val w = -2.0 * PI * effHz / (RATE.toDouble())
            rotI = cos(w).toFloat(); rotQ = sin(w).toFloat(); appliedHz = effHz
        }
        // DC blocker + frequency shift into xi/xq[hist ...]
        var oI = oscI; var oQ = oscQ
        for (k in 0 until cnt) {
            dcOI = i[k] - dcPI + 0.9995f * dcOI; dcPI = i[k]
            dcOQ = q[k] - dcPQ + 0.9995f * dcOQ; dcPQ = q[k]
            xi[hist + k] = dcOI * oI - dcOQ * oQ
            xq[hist + k] = dcOI * oQ + dcOQ * oI
            val ni = oI * rotI - oQ * rotQ; oQ = oI * rotQ + oQ * rotI; oI = ni
        }
        val mag = sqrt(oI * oI + oQ * oQ); oscI = oI / mag; oscQ = oQ / mag

        val m = hist / 2
        val ctr = h[m]
        for (k in 0 until cnt) {
            var ai = ctr * xi[k + m]; var aq = ctr * xq[k + m]
            for (j in 0 until m) {
                val c = h[j]
                ai += c * (xi[k + j] + xi[k + hist - j]); aq += c * (xq[k + j] + xq[k + hist - j])
            }
            pushSample(ai, aq)
        }
        // keep the last TAPS-1 inputs as history for the next call
        System.arraycopy(xi, cnt, xi, 0, hist); System.arraycopy(xq, cnt, xq, 0, hist)

        sinceLock += cnt
        if (sinceLock > 1_600_000L) { sinceLock = 0; sweepIdx = (sweepIdx + 1) % SWEEP.size } // nothing syncing: try another offset
        return result
    }

    private fun pushSample(a: Float, b: Float) {
        val idx = (n and RMASK.toLong()).toInt()
        zi[idx] = a; zq[idx] = b
        val cur = n
        n++
        if (cur >= 4) {
            val p = ((cur - 4) and RMASK.toLong()).toInt()
            // sign of the phase change over one symbol (lag 4) = differential bit alpha: Im(z[n] * conj(z[n-4]))
            val im = b * zi[p] - a * zq[p]
            val ph = (cur and 3).toInt()
            sr[ph] = ((sr[ph] shl 1) or (if (im > 0f) 1 else 0)) and 0x1FFFFFF
            if (cur >= 120) {
                var bestErr = 99
                var mask = 0
                val reg = sr[ph]
                for (t in 0 until 8) { val e = Integer.bitCount(reg xor pat[t]); if (e < bestErr) bestErr = e; if (e <= HARD_ERR + 1) mask = mask or (1 shl t) }
                if (bestErr <= HARD_ERR) {
                    // merge with a candidate that is within a few samples (the 4 phases see the same burst),
                    // otherwise start a new one -- several can be waiting at once, so a noise hit can no longer
                    // shadow a real burst that arrives while it is still pending
                    var pc: Cand? = null
                    for (c in pends) if (cur - c.lastN <= 12) { pc = c; break }
                    if (pc != null) {
                        pc.lastN = cur; pc.mask = pc.mask or mask
                        if (bestErr < pc.bestErr) { pc.bestErr = bestErr; pc.bestN = cur }
                    } else if (pends.size < 24) {
                        pends.add(Cand(cur, cur, bestErr).also { it.mask = mask })
                    }
                }
            }
        }
        while (pends.isNotEmpty() && cur - pends[0].lastN >= RIPE) process(pends.removeAt(0))
    }

    private fun zRe(idx: Long) = zi[(idx and RMASK.toLong()).toInt()]
    private fun zIm(idx: Long) = zq[(idx and RMASK.toLong()).toInt()]

    /** Coherent measurement of the training sequence [t] for a burst whose last TSC symbol sits at sample [e].
        Fills mFreq (residual rad/symbol) and returns quality in 0..1 plus the complex correlation via out[0..1]. */
    private fun measure(e: Long, t: Int, out: FloatArray): Float {
        val s0 = e - 100 // sample of TSC symbol 0
        // 1. coarse frequency from the differential form (robust to large offsets): sum of u_k * (-j) * alpha_k = e^{j w}
        var di = 0f; var dq = 0f
        var pr = zRe(s0); var pq = zIm(s0)
        for (k in 1 until TSC_LEN) {
            val cr = zRe(s0 + 4L * k); val cq = zIm(s0 + 4L * k)
            val ur = cr * pr + cq * pq; val uq = cq * pr - cr * pq // z_k * conj(z_k-1)
            val al = tscAlpha[t][k]
            di += uq * al; dq -= ur * al                          // (ur + j uq) * (-j) * alpha
            pr = cr; pq = cq
        }
        val w0 = atan2(dq, di)
        // 2. derotate: y_k = z_k (-j)^k e^{-j w0 (k - 12.5)}; split correlation in two halves for the residual slope
        var c1r = 0f; var c1i = 0f; var c2r = 0f; var c2i = 0f; var energy = 0f
        for (k in 0 until TSC_LEN) {
            val yr: Float; val yi: Float
            val zr = zRe(s0 + 4L * k); val zim = zIm(s0 + 4L * k)
            when (k and 3) { 0 -> { yr = zr; yi = zim }; 1 -> { yr = zim; yi = -zr }; 2 -> { yr = -zr; yi = -zim }; else -> { yr = -zim; yi = zr } }
            val a = -w0 * (k - 12.5f)
            val ca = cos(a); val sa = sin(a)
            val rr = yr * ca - yi * sa; val ri = yr * sa + yi * ca
            val d = tscSign[t][k]
            if (k < 13) { c1r += rr * d; c1i += ri * d } else { c2r += rr * d; c2i += ri * d }
            energy += sqrt(yr * yr + yi * yi)
        }
        val w1 = atan2(c2i * c1r - c2r * c1i, c2r * c1r + c2i * c1i) / 13f
        // (c2 * conj(c1)): re = c2r*c1r + c2i*c1i ; im = c2i*c1r - c2r*c1i
        val wT = w0 + w1
        var cr2 = 0f; var ci2 = 0f
        for (k in 0 until TSC_LEN) {
            val zr = zRe(s0 + 4L * k); val zim = zIm(s0 + 4L * k)
            val yr: Float; val yi: Float
            when (k and 3) { 0 -> { yr = zr; yi = zim }; 1 -> { yr = zim; yi = -zr }; 2 -> { yr = -zr; yi = -zim }; else -> { yr = -zim; yi = zr } }
            val a = -wT * (k - 12.5f)
            val ca = cos(a); val sa = sin(a)
            val d = tscSign[t][k]
            cr2 += (yr * ca - yi * sa) * d; ci2 += (yr * sa + yi * ca) * d
        }
        mFreq = wT
        out[0] = cr2; out[1] = ci2
        return if (energy > 1e-9f) sqrt(cr2 * cr2 + ci2 * ci2) / energy else 0f
    }

    private val tmpC = FloatArray(2)

    private fun process(c: Cand) {
        // timing refinement over +-3 samples and training-sequence choice, all coherent
        var bestQ = -1f; var bestE = 0L; var bestT = 0; var bR = 0f; var bI = 0f; var bW = 0f
        for (dt in -3..3) {
            val e = c.bestN + dt
            for (t in 0 until 8) {
                if (c.mask and (1 shl t) == 0) continue
                val q = measure(e, t, tmpC)
                if (q > bestQ) { bestQ = q; bestE = e; bestT = t; bR = tmpC[0]; bI = tmpC[1]; bW = mFreq }
            }
        }
        if (bestQ < Q_MIN) return
        demod(bestE, bestT, bR, bI, bW)
        // register
        burstsSynced++

        // the same physical burst can reach here twice (two hit clusters a few symbols apart): keep one copy, the better one
        val twin = bursts.firstOrNull { abs(it.e - bestE) <= 40 }
        if (twin != null) { if (twin.used || twin.q >= bestQ) return; bursts.remove(twin) }
        val rec = BurstRec(bestE, bestT, tmpSoft.copyOf(), bW, bestQ)
        bursts.add(rec)
        while (bursts.size > 24 || (bursts.isNotEmpty() && bestE - bursts[0].e > 6 * SLOT)) bursts.removeAt(0)

        if (showRaw && bestQ >= Q_RAW && bestE - lastRawN > 4L * 300_000) { // at most ~3-4 raw heartbeat lines per second
            lastRawN = bestE
            val bytes = ByteArray(15)
            for (b in 0 until 15) {
                var v = 0
                for (k in 0 until 8) { val ix = b * 8 + k; v = (v shl 1) or (if (ix < 114 && tmpSoft[ix] > 0f) 1 else 0) }
                bytes[b] = v.toByte()
            }
            raw.add(RawBurst(bestT, 0, bytes.joinToString(" ") { "%02x".format(it) }))
        }
        tryGroup(rec)
    }

    /** Coherent demodulation of the two data halves with a small decision-directed phase tracker. */
    private fun demod(e: Long, t: Int, cr: Float, ci: Float, wT: Float) {
        val s0 = e - 100
        val amp = sqrt(cr * cr + ci * ci) / TSC_LEN
        val th = atan2(ci, cr)
        val ia = 1f / (amp + 1e-9f)
        val alpha = 0.08f; val beta = 0.004f
        // data2: symbols 27..83, flag at 26; tracker starts at the end of the TSC and runs forward
        var phi = th + wT * 12.5f; var w = wT
        phi += w // symbol 26 (flag)
        for (k in 27..83) {
            phi += w
            val v = derot(s0 + 4L * k, k, phi)
            val x = v[0] * ia; val y = v[1] * ia
            val sgn = if (x >= 0f) 1f else -1f
            val err = (y * sgn).coerceIn(-1f, 1f)
            phi += alpha * err; w += beta * err
            tmpSoft[57 + (k - 27)] = (-x).coerceIn(-1f, 1f) // bit 1 <-> D = -1 <-> negative real part
        }
        // data1: symbols -58..-2, flag at -1; tracker runs backwards from the start of the TSC
        phi = th - wT * 12.5f; w = wT
        phi -= w // symbol -1 (flag)
        for (k in -2 downTo -58) {
            phi -= w
            val v = derot(s0 + 4L * k, k, phi)
            val x = v[0] * ia; val y = v[1] * ia
            val sgn = if (x >= 0f) 1f else -1f
            val err = (y * sgn).coerceIn(-1f, 1f)
            phi -= alpha * err; w -= beta * err
            tmpSoft[k + 58] = (-x).coerceIn(-1f, 1f)
        }
    }

    private val dv = FloatArray(2)
    /** z at sample [s] multiplied by (-j)^k and e^{-j phi}. */
    private fun derot(s: Long, k: Int, phi: Float): FloatArray {
        val zr = zRe(s); val zim = zIm(s)
        val yr: Float; val yi: Float
        when (k and 3) { 0 -> { yr = zr; yi = zim }; 1 -> { yr = zim; yi = -zr }; 2 -> { yr = -zr; yi = -zim }; else -> { yr = -zim; yi = zr } }
        val ca = cos(phi); val sa = sin(phi)
        dv[0] = yr * ca + yi * sa   // multiply by e^{-j phi}
        dv[1] = yi * ca - yr * sa
        return dv
    }

    /** Chain with the three bursts 1250/2500/3750 symbols earlier (same training sequence, not yet consumed). */
    private fun tryGroup(last: BurstRec) {
        val run = ArrayList<BurstRec>(4)
        for (back in 3 downTo 1) {
            val target = last.e - SLOT * back
            val cand = bursts.filter { !it.used && it.tsc == last.tsc && it !== last && abs(it.e - target) <= SLOT_TOL }
                .minByOrNull { abs(it.e - target) } ?: return
            run.add(cand)
        }
        run.add(last)
        val iB = FloatArray(456)
        for (b in 0 until 4) System.arraycopy(run[b].soft, 0, iB, b * 114, 114)
        groupsTried++
        val (bits184, ok) = Cch.decodeAttemptSoft(iB)
        val bytes = ByteArray(23)
        // GSM sends the LSB of each octet first (04.06 / 05.03), so bit 0 of an octet is the first bit on the air
        for (bi in 0 until 23) { var v = 0; for (k in 0 until 8) v = v or (bits184[bi * 8 + k] shl k); bytes[bi] = v.toByte() }
        val hex = bytes.joinToString(" ") { "%02x".format(it) }
        if (!ok) {
            if (showTried && last.e - lastTriedN > 4L * 270_000L / 2) { lastTriedN = last.e; tried.add(TriedGroup(hex)) }
            return
        }
        groupsOk++
        sinceLock = 0
        // self-calibrate the frequency error, but only from a VERIFIED group (noise bursts must not move it):
        // mean residual rotation of the 4 bursts, rad/symbol -> Hz
        var wm = 0f; for (r in run) wm += r.w
        afc += 0.5f * (wm / 4f * SYM / (2 * PI)).toFloat()
        for (r in run) r.used = true
        result.add(CchResult(hex, bytes))
    }
}
