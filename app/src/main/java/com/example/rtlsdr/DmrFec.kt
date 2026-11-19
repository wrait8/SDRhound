package com.example.rtlsdr

/**
 * DMR block codes (ETSI TS 102 361-1), ported from the g4klx/MMDVMHost reference decoder (GPL-2.0+ source,
 * Copyright (C) Jonathan Naylor G4KLX) and checked against it bit-for-bit on a PC:
 *  - BPTC(196,96): the 196 info bits are de-interleaved ((a*181) mod 196) into a 13x15 matrix (first bit =
 *    reserved R(3)); columns are Hamming(13,9,3), rows are Hamming(15,11,3); corrected iteratively (up to 5 passes).
 *  - Reed-Solomon(12,9) over GF(2^8) (poly 0x11D): the 3 check bytes of a Link Control, after removing the
 *    per-data-type CRC mask. This is what tells a real Link Control from line noise.
 *  - Slot Type Golay(20,8,7): colour code + data type, corrected (shortened-Golay, up to 3 bit errors).
 * The earlier version of this file used power-of-two Hamming parity positions and a row-major layout, neither of
 * which is what DMR uses, so no Link Control (talkgroup/radio IDs) could ever decode.
 */
object DmrFec {

    // ---- Hamming codes (parity layout as in the DMR spec / MMDVMHost) ----
    /** Hamming(15,11,3) on d[o..o+14]; flips one bit if the syndrome points at one. @return true if a bit was fixed. */
    private fun hamming15113(d: IntArray, o: Int): Boolean {
        val c0 = d[o] xor d[o + 1] xor d[o + 2] xor d[o + 3] xor d[o + 5] xor d[o + 7] xor d[o + 8]
        val c1 = d[o + 1] xor d[o + 2] xor d[o + 3] xor d[o + 4] xor d[o + 6] xor d[o + 8] xor d[o + 9]
        val c2 = d[o + 2] xor d[o + 3] xor d[o + 4] xor d[o + 5] xor d[o + 7] xor d[o + 9] xor d[o + 10]
        val c3 = d[o] xor d[o + 1] xor d[o + 2] xor d[o + 4] xor d[o + 6] xor d[o + 7] xor d[o + 10]
        var n = 0
        if (c0 != d[o + 11]) n = n or 1
        if (c1 != d[o + 12]) n = n or 2
        if (c2 != d[o + 13]) n = n or 4
        if (c3 != d[o + 14]) n = n or 8
        val pos = when (n) {
            0x01 -> 11; 0x02 -> 12; 0x04 -> 13; 0x08 -> 14
            0x09 -> 0; 0x0B -> 1; 0x0F -> 2; 0x07 -> 3; 0x0E -> 4; 0x05 -> 5
            0x0A -> 6; 0x0D -> 7; 0x03 -> 8; 0x06 -> 9; 0x0C -> 10
            else -> return false
        }
        d[o + pos] = d[o + pos] xor 1
        return true
    }

    /** Hamming(13,9,3) on a 13-element column. @return true if a bit was fixed. */
    private fun hamming1393(d: IntArray): Boolean {
        val c0 = d[0] xor d[1] xor d[3] xor d[5] xor d[6]
        val c1 = d[0] xor d[1] xor d[2] xor d[4] xor d[6] xor d[7]
        val c2 = d[0] xor d[1] xor d[2] xor d[3] xor d[5] xor d[7] xor d[8]
        val c3 = d[0] xor d[2] xor d[4] xor d[5] xor d[8]
        var n = 0
        if (c0 != d[9]) n = n or 1
        if (c1 != d[10]) n = n or 2
        if (c2 != d[11]) n = n or 4
        if (c3 != d[12]) n = n or 8
        val pos = when (n) {
            0x01 -> 9; 0x02 -> 10; 0x04 -> 11; 0x08 -> 12
            0x0F -> 0; 0x07 -> 1; 0x0E -> 2; 0x05 -> 3; 0x0A -> 4
            0x0D -> 5; 0x03 -> 6; 0x06 -> 7; 0x0C -> 8
            else -> return false
        }
        d[pos] = d[pos] xor 1
        return true
    }

    /**
     * BPTC(196,96) decode. [raw196] = the 196 info bits of a data burst in air order: burst bits 0..97 followed by
     * burst bits 166..263 (the slot-type/sync field in the middle is NOT part of it). Returns the 96 payload bits
     * (9 rows of 11 data bits; the first 3 of row 0 are reserved and skipped). Always returns bits -- decide whether
     * to trust them with [rs129Check].
     */
    fun bptc19696Decode(raw196: IntArray): IntArray? {
        if (raw196.size != 196) return null
        val d = IntArray(196)
        for (a in 0 until 196) d[a] = raw196[(a * 181) % 196]
        var count = 0
        var fixing: Boolean
        val col = IntArray(13)
        do {
            fixing = false
            for (c in 0 until 15) {
                var pos = c + 1
                for (a in 0 until 13) { col[a] = d[pos]; pos += 15 }
                if (hamming1393(col)) {
                    pos = c + 1
                    for (a in 0 until 13) { d[pos] = col[a]; pos += 15 }
                    fixing = true
                }
            }
            for (r in 0 until 9) if (hamming15113(d, r * 15 + 1)) fixing = true
            count++
        } while (fixing && count < 5)

        val out = IntArray(96)
        var p = 0
        for (a in 4..11) out[p++] = d[a]
        for (start in intArrayOf(16, 31, 46, 61, 76, 91, 106, 121)) for (a in start..start + 10) out[p++] = d[a]
        return out
    }

    // ---- Reed-Solomon(12,9), GF(2^8) with primitive polynomial 0x11D ----
    private val EXP = IntArray(512)
    private val LOG = IntArray(256)
    private val POLY = intArrayOf(64, 56, 14, 1)
    init {
        var x = 1
        for (i in 0 until 255) { EXP[i] = x; LOG[x] = i; x = x shl 1; if (x and 0x100 != 0) x = x xor 0x11D }
        for (i in 255 until 512) EXP[i] = EXP[i - 255]
    }
    private fun gmult(a: Int, b: Int) = if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]

    private fun rsParity(msg: IntArray, nbytes: Int): IntArray {
        val parity = IntArray(4)
        for (i in 0 until nbytes) {
            val dbyte = msg[i] xor parity[2]
            for (j in 2 downTo 1) parity[j] = parity[j - 1] xor gmult(POLY[j], dbyte)
            parity[0] = gmult(POLY[0], dbyte)
        }
        return parity
    }

    /** Converts 96 payload bits to 12 bytes. */
    fun bitsToBytes(bits: IntArray): IntArray = IntArray(bits.size / 8) { i ->
        var v = 0; for (k in 0 until 8) v = (v shl 1) or bits[i * 8 + k]; v
    }

    /**
     * True if [lc12] (12 bytes) is a valid Link Control once the data-type CRC [mask] (0x96 voice LC header, 0x99
     * terminator with LC) is removed from its 3 check bytes. Modifies nothing.
     */
    fun rs129Check(lc12: IntArray, mask: Int): Boolean {
        val m = lc12.copyOf()
        m[9] = m[9] xor mask; m[10] = m[10] xor mask; m[11] = m[11] xor mask
        val p = rsParity(m, 9)
        return m[9] == p[2] && m[10] == p[1] && m[11] == p[0]
    }

    // ---- Slot Type Golay(20,8,7) ----
    private fun syndrome1987(p0: Int): Int {
        var pattern = p0
        var aux = 0x40000
        if (pattern >= 0x800) {
            while ((pattern and -2048) != 0) {
                while ((aux and pattern) == 0) aux = aux shr 1
                pattern = pattern xor ((aux / 0x800) * 0xC75)
            }
        }
        return pattern
    }
    private val golayErr: IntArray by lazy {
        val t = IntArray(2048)
        for (i in 0 until 19) {
            t[syndrome1987(1 shl i)] = 1 shl i
            for (j in i + 1 until 19) {
                t[syndrome1987((1 shl i) or (1 shl j))] = (1 shl i) or (1 shl j)
                for (k in j + 1 until 19) {
                    val e = (1 shl i) or (1 shl j) or (1 shl k)
                    t[syndrome1987(e)] = e
                }
            }
        }
        t
    }

    /** [code19] = the first 19 bits of the 20-bit slot-type codeword (MSB first). @return the 8 info bits: colour code << 4 | data type. */
    fun golaySlotType(code19: Int): Int {
        val e = golayErr[syndrome1987(code19)]
        return (code19 xor e) shr 11
    }

    fun bitsToLong(bits: IntArray, from: Int, len: Int): Long {
        var v = 0L
        for (i in 0 until len) v = (v shl 1) or bits[from + i].toLong()
        return v
    }

    // ---- Embedded Link Control (voice bursts B..E): Hamming(16,11,4) rows + column parity + 5-bit checksum ----
    private fun hamming16114(d: IntArray, o: Int): Boolean {
        val c0 = d[o] xor d[o + 1] xor d[o + 2] xor d[o + 3] xor d[o + 5] xor d[o + 7] xor d[o + 8]
        val c1 = d[o + 1] xor d[o + 2] xor d[o + 3] xor d[o + 4] xor d[o + 6] xor d[o + 8] xor d[o + 9]
        val c2 = d[o + 2] xor d[o + 3] xor d[o + 4] xor d[o + 5] xor d[o + 7] xor d[o + 9] xor d[o + 10]
        val c3 = d[o] xor d[o + 1] xor d[o + 2] xor d[o + 4] xor d[o + 6] xor d[o + 7] xor d[o + 10]
        val c4 = d[o] xor d[o + 2] xor d[o + 5] xor d[o + 6] xor d[o + 8] xor d[o + 9] xor d[o + 10]
        var n = 0
        if (c0 != d[o + 11]) n = n or 0x01
        if (c1 != d[o + 12]) n = n or 0x02
        if (c2 != d[o + 13]) n = n or 0x04
        if (c3 != d[o + 14]) n = n or 0x08
        if (c4 != d[o + 15]) n = n or 0x10
        val pos = when (n) {
            0x00 -> return true
            0x01 -> 11; 0x02 -> 12; 0x04 -> 13; 0x08 -> 14; 0x10 -> 15
            0x19 -> 0; 0x0B -> 1; 0x1F -> 2; 0x07 -> 3; 0x0E -> 4; 0x15 -> 5
            0x1A -> 6; 0x0D -> 7; 0x13 -> 8; 0x16 -> 9; 0x1C -> 10
            else -> return false // unrecoverable
        }
        d[o + pos] = d[o + pos] xor 1
        return true
    }

    /**
     * Decodes a complete embedded LC from its 4 fragments ([raw128] = fragments B,C,D,E of 32 bits each, in order).
     * @return the 9 Link Control bytes (FLCO, FID, options, dst x3, src x3) or null if the FEC/checksum fails.
     */
    fun embeddedLcDecode(raw128: IntArray): IntArray? {
        val data = IntArray(128)
        var b = 0
        for (a in 0 until 128) { data[b] = raw128[a]; b += 16; if (b > 127) b -= 127 }
        var a = 0
        while (a < 112) { if (!hamming16114(data, a)) return null; a += 16 }
        for (c in 0 until 16) {
            val parity = data[c] xor data[c + 16] xor data[c + 32] xor data[c + 48] xor data[c + 64] xor data[c + 80] xor data[c + 96] xor data[c + 112]
            if (parity != 0) return null
        }
        val m = IntArray(72)
        var p = 0
        for (x in 0 until 11) m[p++] = data[x]
        for (x in 16 until 27) m[p++] = data[x]
        for (x in 32 until 42) m[p++] = data[x]
        for (x in 48 until 58) m[p++] = data[x]
        for (x in 64 until 74) m[p++] = data[x]
        for (x in 80 until 90) m[p++] = data[x]
        for (x in 96 until 106) m[p++] = data[x]
        var crc = 0
        if (data[42] != 0) crc += 16
        if (data[58] != 0) crc += 8
        if (data[74] != 0) crc += 4
        if (data[90] != 0) crc += 2
        if (data[106] != 0) crc += 1
        val bytes = bitsToBytes(m)
        var total = 0
        for (v in bytes) total += v
        if (total % 31 != crc) return null
        return bytes
    }
}
