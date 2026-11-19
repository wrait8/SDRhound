package com.example.rtlsdr

/**
 * P25 Phase 1 frame decoder, structured after dsd-fme's processLDU1()/processLDU2():
 *  - an LDU carries 9 IMBE voice frames (144 bits each) interleaved with 6 groups of 4 hex words (Hamming(10,6,3)
 *    each) that spell, across the frame, a Reed-Solomon codeword: LDU1 = Link Control (24,12,13: who is calling whom),
 *    LDU2 = Encryption Sync (24,16,9: algorithm id, key id, message indicator), plus 16 bits of low-speed data.
 *  - IDs are reported only when the Reed-Solomon check passes, so a talkgroup/source shown is a verified one.
 *  - A call is treated as encrypted when the LC service options set the privacy bit (0x40) or the ES algorithm id is not
 *    0x80 ("unencrypted"); encrypted audio is NOT sent to the vocoder.
 * Events reuse [DmrEvent]: colorCode = NAC, flco = link-control format byte, dstId = talkgroup / target, srcId = source.
 * Not decoded (reported by name only): HDU, TSDU (trunking signalling), PDU (packet data), TDULC payload.
 */
class P25Verify {
    private val sync = P25FrameSync()

    @Volatile var lastBurstMs = 0L; private set

    private var enc = false
    private var lastLcKey = -1L; private var lastLcMs = 0L
    private var lastInfoMs = 0L

    fun reset() { sync.reset(); enc = false; lastLcKey = -1L; lastLcMs = 0L; lastInfoMs = 0L }

    fun process(i: FloatArray, q: FloatArray, n: Int, rate: Int, now: Long): List<DmrEvent> {
        val frames = sync.process(i, q, n, rate, now)
        if (frames.isEmpty()) return emptyList()
        val out = ArrayList<DmrEvent>(frames.size * 2)
        for (f in frames) { lastBurstMs = now; decode(f, out) }
        return out
    }

    private companion object {
        // offsets of the payload pieces, in data dibits counted from the first dibit after the NID
        val IMBE_AT = intArrayOf(0, 72, 164, 256, 348, 440, 532, 624, 712)
        val HEX_AT = intArrayOf(144, 236, 328, 420, 512, 604) // 4 hex words (5 dibits each) after IMBE 2..7
    }

    private fun decode(f: P25Frame, out: MutableList<DmrEvent>) {
        val sym = f.sym
        if (sym == null) {
            when (f.duid) {
                P25.DUID_TDU, P25.DUID_TDULC -> { enc = false; lastLcKey = -1L }
            }
            out.add(DmrEvent(f.atMs, "P25 " + P25.duidName(f.duid), f.nac))
            return
        }
        // drop the status symbols (every 36th stream symbol)
        val dd = IntArray(840); var k = 0
        for (s in sym.indices) if ((s + 1) % 36 != 0) dd[k++] = sym[s]
        val base = 56 // 24 sync + 32 NID

        // ---- hex words -> RS codeword
        val isLdu1 = f.duid == P25.DUID_LDU1
        val nData = if (isLdu1) 12 else 16
        val nPar = if (isLdu1) 12 else 8
        val data = IntArray(nData); val par = IntArray(nPar)
        var r = 0
        for (seg in HEX_AT) for (w in 0 until 4) {
            val at = base + seg + 5 * w
            val hex = (dd[at] shl 4) or (dd[at + 1] shl 2) or dd[at + 2]
            val p4 = (dd[at + 3] shl 2) or dd[at + 4]
            val fixed = P25Fec.hamming1063(hex, p4)
            val v = if (fixed >= 0) fixed else hex
            if (r < nData) data[nData - 1 - r] = v else par[nPar - 1 - (r - nData)] = v
            r++
        }

        var lcOk = false; var lcf = -1; var svc = 0; var tg = 0; var src = 0; var unit = false
        if (isLdu1) {
            val fixed = P25Fec.rs24_12(data, par)
            if (fixed != null) {
                lcOk = true
                val bits = IntArray(72)
                for (w in 0 until 12) for (b in 0 until 6) bits[w * 6 + b] = (fixed[11 - w] shr (5 - b)) and 1
                fun u(from: Int, len: Int): Int { var v = 0; for (x in 0 until len) v = (v shl 1) or bits[from + x]; return v }
                val pf = bits[0]; lcf = u(0, 8); val mfid = u(8, 8); svc = u(16, 8)
                if (pf == 0 && (mfid == 0 || mfid == 1)) {
                    if (lcf == 0x00) { tg = u(32, 16); src = u(48, 24) }
                    else if (lcf == 0x03) { tg = u(24, 24); src = u(48, 24); unit = true }
                    if (lcf == 0x00 || lcf == 0x03) enc = (svc and 0x40) != 0
                }
            }
        } else {
            val fixed = P25Fec.rs24_16(data, par)
            if (fixed != null) {
                val bits = IntArray(96)
                for (w in 0 until 16) for (b in 0 until 6) bits[w * 6 + b] = (fixed[15 - w] shr (5 - b)) and 1
                var algid = 0; for (x in 0 until 8) algid = (algid shl 1) or bits[72 + x]
                enc = algid != 0x80
            }
        }

        // ---- IMBE voice (9 frames = 180 ms)
        val now = f.atMs
        if (!enc) {
            val frames = Array(9) { n ->
                val at = base + IMBE_AT[n]
                IntArray(144) { b -> val d = dd[at + (b shr 1)]; if (b and 1 == 0) d shr 1 else d and 1 }
            }
            out.add(DmrEvent(now, "P25 VOICE", f.nac, imbeFrames = frames))
        } else if (now - lastInfoMs > 2000) {
            lastInfoMs = now
            out.add(DmrEvent(now, "P25 VOICE ENC", f.nac, encrypted = true))
        }

        // ---- verified link control
        if (lcOk) {
            val shown = lcf == 0x00 || lcf == 0x03
            val key = (tg.toLong() shl 24) or src.toLong()
            if (key != lastLcKey || now - lastLcMs > 3000) {
                lastLcKey = key; lastLcMs = now
                if (shown) out.add(DmrEvent(now, if (unit) "P25 UNIT-UNIT" else "P25 GROUP", f.nac, lcf, tg, src, encrypted = (svc and 0x40) != 0))
                else out.add(DmrEvent(now, "P25 LC ${"%02X".format(lcf)}", f.nac, lcf))
            }
        }
    }
}
