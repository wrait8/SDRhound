package com.example.rtlsdr

/** One decoded KEYUP event for the live log/packet list. */
class DmrEvent(
    val atMs: Long,
    val kind: String,          // "VOICE LC S1", "TERMINATOR S2", "VOICE S1", "CSBK TV_GRANT S1", ...
    val colorCode: Int,
    val flco: Int? = null,     // Full LC Opcode: 0 = group voice, 3 = unit-to-unit
    val dstId: Int? = null,    // talkgroup (group call) or destination radio ID
    val srcId: Int? = null,    // source radio ID
    val ambeSubframes: Array<IntArray>? = null, // 3x 72-bit raw AMBE subframes, for the vocoder (only for the monitored, unencrypted slot)
    val slot: Int = 0,         // 0 = timeslot 1, 1 = timeslot 2
    val encrypted: Boolean = false,
    val imbeFrames: Array<IntArray>? = null // P25 voice: 9x 144-bit raw IMBE frames per LDU, for the IMBE vocoder (colorCode = NAC)
)

/**
 * DMR frame decoder, structured after dsd-fme's dmrBS()/dmrMS()/dmr_data_sync()/dmr_dburst:
 *  - [DmrFrameSync] delivers 144-dibit frames; the CACH TACT (Hamming(7,4)) names the timeslot, so BOTH slots
 *    are decoded independently, each with its own voice-superframe counter (A..F), call state and embedded-LC buffer.
 *    Direct-mode (MS) frames have no CACH and are slot 0.
 *  - Voice sync = burst A (counter 1); bursts without a sync inside a superframe are B..F; B..E carry embedded LC.
 *  - Data sync: Slot Type (Golay 20,8) -> colour code + data type -> BPTC(196,96) -> per-type check:
 *    VLC/TLC Reed-Solomon(12,9) masks 0x96/0x99, PI header CRC16 mask 0x6969, CSBK 0xA5A5, MBC header 0xAAAA,
 *    data header 0xCCCC (masks from dsd-fme dmr_dburst.c). IDs are only shown for checks that passed.
 *  - Service-options privacy bit (0x40) or a PI header marks a call encrypted: its audio is NOT sent to the vocoder.
 *  - Only one slot is voiced at a time ([monitorSlot]: -1 = follow whichever slot is active first, 0/1 = fixed),
 *    since the single vocoder history can't be shared between two simultaneous calls.
 */
class DmrVerify {
    private val sync = DmrFrameSync()

    private class Slot {
        var vc = 7                 // 1..6 = voice burst A..F expected/in progress, 7 = not in a voice superframe
        var cc = 0
        val emb = IntArray(128); var embGot = 0
        var enc = false
        var lastEmbKey = -1L; var lastEmbMs = 0L
        var lastVoiceLogMs = 0L
        fun reset() { vc = 7; cc = 0; embGot = 0; enc = false; lastEmbKey = -1L; lastEmbMs = 0L; lastVoiceLogMs = 0L }
    }
    private val slots = arrayOf(Slot(), Slot())

    /** -1 = automatic (first active slot, released after 1 s of silence), 0 = timeslot 1, 1 = timeslot 2. */
    @Volatile var monitorSlot = -1
    private var autoSlot = -1; private var autoMs = 0L

    /** Wall-clock ms of the most recent frame that carried real activity (the scanner uses this to hold on an active channel). */
    @Volatile var lastBurstMs = 0L; private set

    fun reset() { sync.reset(); slots.forEach { it.reset() }; autoSlot = -1 }

    fun process(i: FloatArray, q: FloatArray, n: Int, rate: Int, now: Long): List<DmrEvent> {
        val frames = sync.process(i, q, n, rate, now)
        if (frames.isEmpty()) return emptyList()
        val events = ArrayList<DmrEvent>(frames.size + 1)
        for (f in frames) decodeFrame(f, events)
        return events
    }

    private fun tag(kind: String, slot: Int) = "$kind S${slot + 1}"

    private fun decodeFrame(f: DmrFrame, out: MutableList<DmrEvent>) {
        val slotIdx = if (f.ms) 0 else Dmr.decodeTact(f.cach).slot
        val st = slots[slotIdx]
        val s = f.sync
        val inVoice = st.vc in 1..6
        if (s != null || inVoice) lastBurstMs = f.atMs
        when {
            s != null && s.isVoice -> { st.vc = 1; voiceBurst(f, st, slotIdx, 1, out); st.vc = 2 }
            s != null -> { st.vc = 7; st.embGot = 0; dataBurst(f, st, slotIdx, out) }
            inVoice -> { val v = st.vc; voiceBurst(f, st, slotIdx, v, out); st.vc = if (v >= 6) 7 else v + 1 }
        }
    }

    // ---- voice (dmr_bs.c: three AMBE+2 frames per burst, EMB/sync in the middle) ----
    private fun voiceBurst(f: DmrFrame, st: Slot, slot: Int, vc: Int, out: MutableList<DmrEvent>) {
        val b = f.bits
        // embedded Link Control: fragments in bursts B..E (vc 2..5), 32 bits each after the 8 EMB bits
        if (vc in 2..5) {
            val frag = vc - 2
            if (frag == 0) st.embGot = 1 else if (frag == st.embGot) st.embGot++ else st.embGot = 0
            if (st.embGot >= 1) for (k in 0 until 32) st.emb[frag * 32 + k] = b[Dmr.HALF_BITS + 8 + k]
            if (frag == 3 && st.embGot == 4) { st.embGot = 0; embeddedLc(f, st, slot, out) }
        }

        val now = f.atMs
        if (autoSlot < 0 || (autoSlot != slot && now - autoMs > 1000)) autoSlot = slot
        if (autoSlot == slot) autoMs = now
        val mon = monitorSlot
        val play = !st.enc && (if (mon in 0..1) mon == slot else autoSlot == slot)

        if (play) {
            val v = IntArray(216)
            System.arraycopy(b, 0, v, 0, Dmr.HALF_BITS)
            System.arraycopy(b, Dmr.HALF_BITS + Dmr.SYNC_BITS, v, Dmr.HALF_BITS, Dmr.HALF_BITS)
            val subs = Array(3) { s -> IntArray(72) { k -> v[s * 72 + k] } }
            out.add(DmrEvent(now, tag(if (vc > 1) "VOICE (cont.)" else "VOICE", slot), st.cc, ambeSubframes = subs, slot = slot))
        } else if (now - st.lastVoiceLogMs > 2000) { // other slot / encrypted: one info row per 2 s, no audio
            st.lastVoiceLogMs = now
            out.add(DmrEvent(now, tag(if (st.enc) "VOICE ENC" else "VOICE", slot), st.cc, slot = slot, encrypted = st.enc))
        }
    }

    private fun embeddedLc(f: DmrFrame, st: Slot, slot: Int, out: MutableList<DmrEvent>) {
        val lc = DmrFec.embeddedLcDecode(st.emb) ?: return
        val flco = lc[0] and 0x3F
        if (flco != 0 && flco != 3) return
        if (lc[2] and 0x40 != 0) st.enc = true
        val dst = (lc[3] shl 16) or (lc[4] shl 8) or lc[5]
        val src = (lc[6] shl 16) or (lc[7] shl 8) or lc[8]
        val key = (dst.toLong() shl 24) or src.toLong()
        // the same call repeats the embedded LC every 360 ms: log it when it changes, else every 3 s
        if (key != st.lastEmbKey || f.atMs - st.lastEmbMs > 3000) {
            st.lastEmbKey = key; st.lastEmbMs = f.atMs
            out.add(DmrEvent(f.atMs, tag("VOICE LC (embedded)", slot), st.cc, flco, dst, src, slot = slot, encrypted = st.enc))
        }
    }

    // ---- data (dmr_data.c / dmr_dburst.c) ----
    private fun typeName(dt: Int) = when (dt) {
        0 -> "PI HEADER"; 3 -> "CSBK"; 4 -> "MBC HEADER"; 5 -> "MBC"; 6 -> "DATA HEADER"
        7 -> "RATE 1/2 DATA"; 8 -> "RATE 3/4 DATA"; 9 -> "IDLE"; 10 -> "RATE 1 DATA"; else -> "DATA ($dt)"
    }

    private fun bptc96(b: IntArray): IntArray? {
        val raw = IntArray(196)
        for (k in 0 until 98) { raw[k] = b[k]; raw[98 + k] = b[166 + k] }
        return DmrFec.bptc19696Decode(raw)
    }

    private fun dataBurst(f: DmrFrame, st: Slot, slot: Int, out: MutableList<DmrEvent>) {
        val b = f.bits
        var code = 0
        for (k in 98..107) code = (code shl 1) or b[k]
        for (k in 156..164) code = (code shl 1) or b[k]
        val info = DmrFec.golaySlotType(code)
        val cc = (info shr 4) and 0xF
        val dt = info and 0xF
        st.cc = cc
        val t = f.atMs
        fun ev(kind: String, flco: Int? = null, dst: Int? = null, src: Int? = null, enc: Boolean = false) =
            DmrEvent(t, tag(kind, slot), cc, flco, dst, src, slot = slot, encrypted = enc)

        when (dt) {
            1, 2 -> { // voice LC header / terminator with LC: Reed-Solomon(12,9)
                val bits = bptc96(b) ?: return
                val lc = DmrFec.bitsToBytes(bits)
                val name = if (dt == 1) "VOICE LC" else "TERMINATOR"
                if (!DmrFec.rs129Check(lc, if (dt == 1) 0x96 else 0x99)) { out.add(ev("$name (bad CRC)")); return }
                val flco = lc[0] and 0x3F
                val enc = (lc[2] and 0x40) != 0
                val dst = (lc[3] shl 16) or (lc[4] shl 8) or lc[5]
                val src = (lc[6] shl 16) or (lc[7] shl 8) or lc[8]
                if (dt == 1) st.enc = enc
                out.add(if (flco == 0 || flco == 3) ev(name, flco, dst, src, enc) else ev("$name FLCO ${"%02X".format(flco)}", flco, enc = enc))
                if (dt == 2) { st.enc = false; st.embGot = 0; st.lastEmbKey = -1L }
            }
            0 -> { // privacy indicator header: its presence means the call is encrypted
                val bits = bptc96(b) ?: return
                if (!Dmr.crc16Ok(bits, 0x6969)) { out.add(ev("PI HEADER (bad CRC)")); return }
                st.enc = true
                out.add(ev("PI HEADER", enc = true))
            }
            3 -> csbk(b, f, cc, slot, out)
            4, 6 -> { // MBC header / data header: CRC16
                val bits = bptc96(b) ?: return
                val ok = Dmr.crc16Ok(bits, if (dt == 4) 0xAAAA else 0xCCCC)
                out.add(ev(if (ok) typeName(dt) else typeName(dt) + " (bad CRC)"))
            }
            else -> out.add(ev(typeName(dt)))
        }
    }

    private fun csbk(b: IntArray, f: DmrFrame, cc: Int, slot: Int, out: MutableList<DmrEvent>) {
        val bits = bptc96(b) ?: return
        if (!Dmr.crc16Ok(bits, 0xA5A5)) { out.add(DmrEvent(f.atMs, tag("CSBK (bad CRC)", slot), cc, slot = slot)); return }
        val bytes = DmrFec.bitsToBytes(bits)
        val pf = (bytes[0] shr 6) and 1
        val op = bytes[0] and 0x3F
        val fid = bytes[1]
        if (pf == 1) { out.add(DmrEvent(f.atMs, tag("CSBK (protected)", slot), cc, slot = slot)); return }
        val target = (bytes[4] shl 16) or (bytes[5] shl 8) or bytes[6]   // bits 32..55
        val source = (bytes[7] shl 16) or (bytes[8] shl 8) or bytes[9]   // bits 56..79
        val name = if (fid != 0) null else when (op) { // opcodes and layout per dsd-fme dmr_csbk.c / ETSI 7.1.1.1.1
            48 -> "PV_GRANT"; 49 -> "TV_GRANT"; 50 -> "BTV_GRANT"; 51 -> "PD_GRANT"; 52 -> "TD_GRANT"
            53 -> "PV_GRANT_DX"; 54 -> "PD_GRANT_DX"; 55 -> "PD_GRANT_MULTI"
            4 -> "UU_V_REQ"; 5 -> "UU_ANS_RSP"
            else -> null
        }
        if (name != null) out.add(DmrEvent(f.atMs, tag("CSBK $name", slot), cc, dstId = target, srcId = source, slot = slot))
        else out.add(DmrEvent(f.atMs, tag("CSBK ${"%02X".format(op)}" + if (fid != 0) " FID ${"%02X".format(fid)}" else "", slot), cc, slot = slot))
    }
}
