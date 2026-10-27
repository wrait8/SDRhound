package com.example.rtlsdr

/** One printable row: close to what sms_evil/imsi catchers traditionally show. [mcc]/[mnc]/[lac]/[cell] are the
    SERVING cell's identity (from the most recent System Information Type 3 on this channel), not the IMSI's own
    home network -- the IMSI itself already carries its home MCC/MNC as its first five digits. */
class ImsiRow(val id: Int, val imsi: String, val tmsi: String, val mcc: String, val mnc: String, val lac: String, val cell: String, val atMs: Long)

/**
 * Ported from the imsi_catcher.py reference tool: same byte offsets (shifted by -16 since we work on the
 * 23-byte L3 block directly, without the GSMTAP header python indexes through), same TBCD nibble-swap
 * decode, same new-IMSI / TMSI-reassociation bookkeeping -- so a row shown here is a row that tool would
 * also have printed from the same air traffic. Runs directly on each Fire-check-verified CCCH/BCCH block
 * (see [CchResult.isCcch]), no network hop needed.
 */
class GsmImsiCatcher {
    private val imsis = ArrayList<String>()
    private val tmsis = HashMap<String, String>() // tmsi hex -> imsi digits
    private var nbImsi = 0
    private var mcc = ""; private var mnc = ""; private var lac = ""; private var cell = ""
    val rows = ArrayList<ImsiRow>() // newest first

    fun reset() { imsis.clear(); tmsis.clear(); nbImsi = 0; mcc = ""; mnc = ""; lac = ""; cell = ""; rows.clear() }

    private fun u(b: Byte) = b.toInt() and 0xFF

    private fun strTmsi(b: ByteArray?): String {
        if (b == null) return ""
        val sb = StringBuilder("0x")
        for (x in b) sb.append("%02x".format(u(x)))
        return sb.toString()
    }

    /** TBCD nibble swap: low nibble first, then high nibble, one hex char each -- matches hex(a) parsing in
        the python decode_imsi() exactly (including its single-digit edge case, which is just the high
        nibble being zero). */
    private fun decodeDigits(b: ByteArray): String {
        val sb = StringBuilder()
        for (x in b) { val v = u(x); sb.append((v and 0xF).toString(16)); sb.append(((v shr 4) and 0xF).toString(16)) }
        return sb.toString()
    }

    /** Drop the leading identity-type/odd-even nibble the swap exposes as the first "digit": what's left is
        the real IMSI (home MCC + MNC + subscriber number), same slicing python's str_imsi() ends up using. */
    private fun imsiNumber(b: ByteArray): String { val d = decodeDigits(b); return if (d.isNotEmpty()) d.substring(1) else d }

    /** Feed one Fire-check-verified, BCCH/CCCH-shaped 23-byte L3 block (see [CchResult.isCcch]). */
    fun feed(l3: ByteArray, atMs: Long) {
        if (l3.size < 23) return
        when (u(l3[2])) {
            0x1b -> { // System Information Type 3: updates the serving cell used for every row's MCC/MNC/LAC/CI
                mcc = decodeDigits(byteArrayOf(l3[5])) + (u(l3[6]) and 0xF).toString(16)
                mnc = decodeDigits(byteArrayOf(l3[7]))
                lac = (u(l3[8]) * 256 + u(l3[9])).toString()
                cell = (u(l3[3]) * 256 + u(l3[4])).toString()
            }
            0x21 -> { // Paging Request Type 1
                if (u(l3[4]) == 0x08 && (u(l3[5]) and 1) == 1) {
                    val imsi1 = l3.copyOfRange(5, 13)
                    var imsi2: ByteArray? = null; var tmsi1: ByteArray? = null
                    if (u(l3[0]) == 0x59 && u(l3[14]) == 0x08 && (u(l3[15]) and 1) == 1) {
                        imsi2 = l3.copyOfRange(15, 23)
                    } else if (u(l3[0]) == 0x4d && u(l3[14]) == 0x05 && u(l3[15]) == 0xf4) {
                        tmsi1 = l3.copyOfRange(16, 20)
                    }
                    register(imsi1, imsi2, tmsi1, null, atMs)
                } else if (u(l3[11]) == 0x08 && (u(l3[12]) and 1) == 1) {
                    register(null, l3.copyOfRange(12, 20), l3.copyOfRange(6, 10), null, atMs)
                } else if (u(l3[4]) == 0x05 && (u(l3[5]) and 0x07) == 4) {
                    val tmsi1 = l3.copyOfRange(6, 10)
                    val tmsi2 = if (u(l3[11]) == 0x05 && (u(l3[12]) and 0x07) == 4) l3.copyOfRange(13, 17) else null
                    register(null, null, tmsi1, tmsi2, atMs)
                }
            }
            0x22 -> { // Paging Request Type 2
                if (u(l3[13]) == 0x08 && (u(l3[14]) and 1) == 1) {
                    register(null, l3.copyOfRange(14, 22), l3.copyOfRange(4, 8), l3.copyOfRange(8, 12), atMs)
                }
            }
        }
    }

    private fun register(imsi1b: ByteArray?, imsi2b: ByteArray?, tmsi1b: ByteArray?, tmsi2b: ByteArray?, atMs: Long) {
        var doPrint = false; var n = 0
        val t1 = strTmsi(tmsi1b); val t2 = strTmsi(tmsi2b)
        var i1 = imsi1b?.let { imsiNumber(it) }
        val i2 = imsi2b?.let { imsiNumber(it) }
        if (i1 != null) {
            if (i1 !in imsis) { doPrint = true; imsis.add(i1); nbImsi++; n = nbImsi }
            if (t1.isNotEmpty() && tmsis[t1] != i1) { doPrint = true; tmsis[t1] = i1 }
            if (t2.isNotEmpty() && tmsis[t2] != i1) { doPrint = true; tmsis[t2] = i1 }
        }
        if (i2 != null) {
            if (i2 !in imsis) { doPrint = true; imsis.add(i2); nbImsi++; n = nbImsi }
            if (t1.isNotEmpty() && tmsis[t1] != i2) { doPrint = true; tmsis[t1] = i2 }
            if (t2.isNotEmpty() && tmsis[t2] != i2) { doPrint = true; tmsis[t2] = i2 }
        }
        // no IMSI in this message, just a TMSI pair: if tmsi2 is already known, the network is reassigning it to tmsi1
        if (i1 == null && i2 == null && t1.isNotEmpty() && t2.isNotEmpty()) {
            val known = tmsis[t2]
            if (known != null) { doPrint = true; i1 = known; tmsis[t1] = known; tmsis.remove(t2) }
        }
        if (doPrint) {
            if (i1 != null) rows.add(0, ImsiRow(n, i1, t1.ifEmpty { t2 }, mcc, mnc, lac, cell, atMs))
            if (i2 != null) rows.add(0, ImsiRow(n, i2, t2.ifEmpty { t1 }, mcc, mnc, lac, cell, atMs))
            while (rows.size > 200) rows.removeAt(rows.size - 1)
        }
    }
}
