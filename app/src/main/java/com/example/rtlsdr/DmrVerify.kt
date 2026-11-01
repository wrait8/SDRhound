package com.example.rtlsdr

/** One decoded KEYUP event for the live log/packet list. */
class DmrEvent(
    val atMs: Long,
    val kind: String,          // "VOICE LC", "TERMINATOR", "VOICE", "CSBK", ...
    val colorCode: Int,
    val flco: Int? = null,     // Full LC Opcode: 0 = group voice, 3 = unit-to-unit
    val dstId: Int? = null,    // talkgroup (group call) or destination radio ID
    val srcId: Int? = null,    // source radio ID
    val ambeSubframes: Array<IntArray>? = null // 3x 72-bit raw AMBE subframes, for the vocoder
)

/**
 * Stages 3-5 of the KEYUP chain, working on FULL 264-bit bursts from [DmrBurstSync]:
 *
 *   bits 0..107    payload_1
 *   bits 108..155  SYNC (burst A) / embedded signalling (B..F)
 *   bits 156..263  payload_2
 *
 *  - Voice: the 216 payload bits are 3 consecutive 72-bit AMBE+2 subframes (subframe 2 straddles the sync field).
 *  - Data: Slot Type = 10 bits before + 10 after the sync field, Golay(20,8) corrected -> colour code + data type.
 *  - Voice LC header / terminator: BPTC(196,96) over bursts bits 0..97 + 166..263, Reed-Solomon(12,9) checked.
 *    IDs are only shown when that check passes, so a talkgroup/radio ID on screen is a verified one.
 *  - Mid-call: voice bursts B..E each carry 32 bits of an embedded Link Control; once all four are in, it is
 *    Hamming/parity/checksum-verified and reported, so you get dst/src even if you tuned in after the call began.
 *
 * Link Control: PF+R+FLCO (8) | FID (8) | Service Options (8) | Dst (24) | Src (24) | RS check (24).
 * (The earlier version read dst/src 8 bits too early, used the wrong Hamming codes and the wrong matrix layout.)
 */
class DmrVerify {
    private val sync = DmrBurstSync()
    private var lastCc = 0
    private val embRaw = IntArray(128)
    private var embGot = 0
    private var lastEmbKey = -1L
    private var lastEmbMs = 0L

    /** Wall-clock ms of the most recent burst of any kind (the scanner uses this to hold on an active channel). */
    @Volatile var lastBurstMs = 0L; private set

    fun reset() { sync.reset(); lastCc = 0; embGot = 0; lastEmbKey = -1L }

    fun process(i: FloatArray, q: FloatArray, n: Int, rate: Int, now: Long): List<DmrEvent> {
        val bursts = sync.process(i, q, n, rate, now)
        if (bursts.isEmpty()) return emptyList()
        lastBurstMs = now
        val events = ArrayList<DmrEvent>(bursts.size + 1)
        for (b in bursts) decode(b, events)
        return events
    }

    private fun decode(b: Dmr.Burst, out: MutableList<DmrEvent>) {
        if (b.bits.size < Dmr.BURST_BITS) return
        if (b.sync.isVoice) { decodeVoice(b, out); return }
        var code = 0
        for (k in 98..107) code = (code shl 1) or b.bits[k]
        for (k in 156..164) code = (code shl 1) or b.bits[k]
        val info = DmrFec.golaySlotType(code)
        val cc = (info shr 4) and 0xF
        val dataType = info and 0xF
        lastCc = cc
        decodeDataBurst(b, cc, dataType)?.let { out.add(it) }
    }

    private fun decodeVoice(b: Dmr.Burst, out: MutableList<DmrEvent>) {
        val v = IntArray(216)
        System.arraycopy(b.bits, 0, v, 0, Dmr.HALF_BITS)
        System.arraycopy(b.bits, Dmr.HALF_BITS + Dmr.SYNC_BITS, v, Dmr.HALF_BITS, Dmr.HALF_BITS)
        val subs = Array(3) { s -> IntArray(72) { k -> v[s * 72 + k] } }
        out.add(DmrEvent(b.atMs, if (b.follow) "VOICE (cont.)" else "VOICE", lastCc, ambeSubframes = subs))

        // embedded Link Control: fragments in voice bursts B..E (index 1..4 after the sync burst A)
        if (b.follow && b.index in 1..4) {
            if (b.index == 1) embGot = 1 else if (b.index == embGot + 1) embGot = b.index else embGot = 0
            if (embGot >= 1) for (k in 0 until 32) embRaw[(b.index - 1) * 32 + k] = b.bits[116 + k]
            if (b.index == 4 && embGot == 4) {
                embGot = 0
                val lc = DmrFec.embeddedLcDecode(embRaw)
                if (lc != null) {
                    val flco = lc[0] and 0x3F
                    if (flco == 0 || flco == 3) {
                        val dst = (lc[3] shl 16) or (lc[4] shl 8) or lc[5]
                        val src = (lc[6] shl 16) or (lc[7] shl 8) or lc[8]
                        val key = (dst.toLong() shl 24) or src.toLong()
                        // same call repeats the embedded LC every 360 ms: log it when it changes, else every 3 s
                        if (key != lastEmbKey || b.atMs - lastEmbMs > 3000) {
                            lastEmbKey = key; lastEmbMs = b.atMs
                            out.add(DmrEvent(b.atMs, "VOICE LC (embedded)", lastCc, flco, dst, src))
                        }
                    }
                }
            }
        } else if (!b.follow) embGot = 0
    }

    private fun typeName(dt: Int) = when (dt) {
        0 -> "PI HEADER"; 3 -> "CSBK"; 4 -> "MBC HEADER"; 5 -> "MBC"; 6 -> "DATA HEADER"
        7 -> "RATE 1/2 DATA"; 8 -> "RATE 3/4 DATA"; 9 -> "IDLE"; 10 -> "RATE 1 DATA"; else -> "DATA ($dt)"
    }

    private fun decodeDataBurst(b: Dmr.Burst, cc: Int, dataType: Int): DmrEvent? {
        if (dataType != 1 && dataType != 2) return DmrEvent(b.atMs, typeName(dataType), cc)
        val raw196 = IntArray(196)
        for (k in 0 until 98) raw196[k] = b.bits[k]
        for (k in 0 until 98) raw196[98 + k] = b.bits[166 + k]
        val bits = DmrFec.bptc19696Decode(raw196) ?: return null
        val lc = DmrFec.bitsToBytes(bits)
        val mask = if (dataType == 1) 0x96 else 0x99
        val name = if (dataType == 1) "VOICE LC" else "TERMINATOR"
        if (!DmrFec.rs129Check(lc, mask)) return DmrEvent(b.atMs, "$name (bad CRC)", cc)
        val flco = lc[0] and 0x3F
        val dst = (lc[3] shl 16) or (lc[4] shl 8) or lc[5]
        val src = (lc[6] shl 16) or (lc[7] shl 8) or lc[8]
        return DmrEvent(b.atMs, name, cc, flco, dst, src)
    }
}
