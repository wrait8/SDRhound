package com.example.rtlsdr

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

enum class AppMode { RADIO, ADSB, GSM, SATELLITE }

/** One snapshot of a track's state at the time a message was processed, for the detail panel's history list. */
class HistPoint(val ms: Long, val alt: Int?, val speed: Int?, val heading: Int?, val squawk: String?)

data class Aircraft(
    val icao: Int, val callsign: String, val alt: Int?, val speed: Int?, val heading: Int?,
    val lat: Double?, val lon: Double?, val lastMs: Long, val msgs: Int,
    /** Recent positions (lat, lon), oldest first, for the map trail. */
    val trail: List<Pair<Double, Double>> = emptyList(),
    /** Mode A squawk code (4 octal digits), from a DF5/DF21 identity reply matched to this aircraft -- null until one is seen. */
    val squawk: String? = null,
    /** Recent (alt, speed, heading, squawk) snapshots, oldest first, for the detail panel. */
    val history: List<HistPoint> = emptyList()
)

/**
 * Mode S / ADS-B decoder for 2.0 MS/s unsigned 8-bit IQ (2 samples per 1 us bit). Handles:
 *  - DF17/18 extended squitter (112 bit): position, callsign, velocity -- CRC must be exactly 0.
 *  - DF5/DF21 identity (surveillance squawk reply, 56/112 bit): these have no ICAO field of their own: the
 *    last 24 bits (AP) are the ICAO XOR'd with the CRC of the rest, so the ICAO is recovered by computing
 *    that CRC and XOR-ing it with the received AP, then only KEEPING the message if the result matches an
 *    aircraft already tracked from DF17/18 (the standard dump1090 "brute force AP" technique) -- so a
 *    squawk only ever attaches to an aircraft already seen via extended squitter, never a bare DF5/21 alone.
 */
class AdsbDecoder {
    private class Track(val icao: Int) {
        var callsign = ""; var alt: Int? = null; var speed: Int? = null; var hdg: Int? = null
        var squawk: String? = null
        var lat: Double? = null; var lon: Double? = null
        var lastMs = 0L; var msgs = 0
        val trail = ArrayList<Pair<Double, Double>>()
        val hist = ArrayList<HistPoint>()
        var evenLat = 0; var evenLon = 0; var evenT = 0L
        var oddLat = 0; var oddLon = 0; var oddT = 0L
    }

    private val tracks = HashMap<Int, Track>()
    var total = 0; private set
    private var pw = IntArray(16384)
    private var carry = 0
    private val msg = ByteArray(14)

    /** [raw] holds [nIq] interleaved unsigned 8-bit I/Q pairs. */
    @Synchronized
    fun process(raw: ByteArray, nIq: Int) {
        val need = carry + nIq
        if (pw.size < need) pw = pw.copyOf(need + 4096)
        for (k in 0 until nIq) {
            val i = (raw[2 * k].toInt() and 0xFF) * 2 - 255
            val q = (raw[2 * k + 1].toInt() and 0xFF) * 2 - 255
            pw[carry + k] = i * i + q * q
        }
        val last = need - 240 - 1 // 16 preamble samples + up to 224 data samples (112 bit, the longest message)
        val now = System.currentTimeMillis()
        var j = 0
        while (j < last) {
            val p0 = pw[j]; val p1 = pw[j + 1]; val p2 = pw[j + 2]; val p3 = pw[j + 3]; val p4 = pw[j + 4]
            val p5 = pw[j + 5]; val p6 = pw[j + 6]; val p7 = pw[j + 7]; val p8 = pw[j + 8]; val p9 = pw[j + 9]
            if (!(p0 > p1 && p1 < p2 && p2 > p3 && p3 < p0 && p4 < p0 && p5 < p0 && p6 < p0 && p7 > p8 && p8 < p9 && p9 > p6)) { j++; continue }
            val high = (p0 + p2 + p7 + p9) / 9
            if (p4 >= high || p5 >= high) { j++; continue }
            if (pw[j + 11] >= high || pw[j + 12] >= high || pw[j + 13] >= high || pw[j + 14] >= high) { j++; continue }

            java.util.Arrays.fill(msg, 0)
            val base = j + 16
            // read just the first byte to find the downlink format, then decide how many more bits this message has
            for (b in 0 until 8) {
                val bit = if (pw[base + 2 * b] > pw[base + 2 * b + 1]) 1 else 0
                msg[0] = (msg[0].toInt() or (bit shl (7 - b))).toByte()
            }
            val df = (msg[0].toInt() and 0xFF) shr 3
            val totalBits = when (df) { 17, 18 -> 112; 5, 21 -> if (df == 21) 112 else 56; else -> -1 }
            if (totalBits < 0) { j++; continue }
            for (b in 8 until totalBits) {
                val bit = if (pw[base + 2 * b] > pw[base + 2 * b + 1]) 1 else 0
                msg[b shr 3] = (msg[b shr 3].toInt() or (bit shl (7 - (b and 7)))).toByte()
            }

            val ok = if (df == 17 || df == 18) crcZero(112) else identityIcao(totalBits) != null
            if (!ok) { j++; continue }
            if (df == 17 || df == 18) handleEs(now) else handleIdentity(now, totalBits)
            j += 240
        }
        carry = (need - j).coerceAtLeast(0)
        System.arraycopy(pw, need - carry, pw, 0, carry)
    }

    /** CRC-24 remainder (poly 0x1FFF409, same as the Mode S/ADS-B parity check) over the first [nBits] bits of [msg]. */
    private fun crc24(nBits: Int): Int {
        var rem = 0
        for (i in 0 until nBits) {
            val bit = ((msg[i shr 3].toInt() and 0xFF) shr (7 - (i and 7))) and 1
            rem = (rem shl 1) or bit
            if (rem and 0x1000000 != 0) rem = rem xor 0x1FFF409
        }
        return rem and 0xFFFFFF
    }

    private fun crcZero(nBits: Int) = crc24(nBits) == 0

    /** DF5/DF21: the last 24 bits (AP) = ICAO XOR CRC(rest). Recovers the ICAO and accepts the message only
        if it matches an aircraft already tracked from DF17/18 -- a bare DF5/21 can't be trusted on its own. */
    private fun identityIcao(totalBits: Int): Int? {
        val apByte = (totalBits - 24) / 8
        val ap = (u(apByte) shl 16) or (u(apByte + 1) shl 8) or u(apByte + 2)
        val candidate = crc24(totalBits - 24) xor ap
        return if (tracks.containsKey(candidate)) candidate else null
    }

    private fun u(i: Int) = msg[i].toInt() and 0xFF

    /** Mode A squawk (4 octal digits) from the ID field of a DF4/5/20/21 surveillance reply, bytes 2-3.
        Bit layout and formula from the standard dump1090 decoder (the Gillham-coded ID field, ETSI/ICAO Annex 10). */
    private fun decodeSquawk(): String {
        val m2 = u(2); val m3 = u(3)
        val a = ((m3 and 0x80) shr 5) or ((m2 and 0x02)) or ((m2 and 0x08) shr 3)
        val b = ((m3 and 0x02) shl 1) or ((m3 and 0x08) shr 2) or ((m3 and 0x20) shr 5)
        val c = ((m2 and 0x01) shl 2) or ((m2 and 0x04) shr 1) or ((m2 and 0x10) shr 4)
        val d = ((m3 and 0x01) shl 2) or ((m3 and 0x04) shr 1) or ((m3 and 0x10) shr 4)
        return "%d%d%d%d".format(a, b, c, d)
    }

    private fun pushHist(t: Track, now: Long) {
        t.hist.add(HistPoint(now, t.alt, t.speed, t.hdg, t.squawk))
        while (t.hist.size > 40 || (t.hist.isNotEmpty() && now - t.hist[0].ms > 600_000)) t.hist.removeAt(0)
    }

    private fun handleIdentity(now: Long, totalBits: Int) {
        val icao = identityIcao(totalBits) ?: return
        val t = tracks[icao] ?: return
        t.squawk = decodeSquawk()
        t.lastMs = now
        pushHist(t, now)
    }

    private fun handleEs(now: Long) {
        val icao = (u(1) shl 16) or (u(2) shl 8) or u(3)
        val t = tracks.getOrPut(icao) { Track(icao) }
        t.lastMs = now; t.msgs++; total++
        val tc = u(4) shr 3
        var changed = false
        when {
            tc in 1..4 -> {
                var v = 0L
                for (k in 5..10) v = (v shl 8) or u(k).toLong()
                val sb = StringBuilder()
                for (k in 0 until 8) {
                    val c = CHARSET[((v shr (42 - 6 * k)) and 63).toInt()]
                    if (c != '#') sb.append(c)
                }
                val cs = sb.toString().trim()
                if (cs.isNotEmpty() && cs != t.callsign) { t.callsign = cs; changed = true }
            }
            tc in 9..18 -> {
                val ac = (u(5) shl 4) or (u(6) shr 4)
                if (ac and 0x10 != 0) { val newAlt = (((ac and 0x0FE0) shr 1) or (ac and 0x000F)) * 25 - 1000; if (newAlt != t.alt) { t.alt = newAlt; changed = true } }
                val odd = (u(6) shr 2) and 1
                val latCpr = ((u(6) and 3) shl 15) or (u(7) shl 7) or (u(8) shr 1)
                val lonCpr = ((u(8) and 1) shl 16) or (u(9) shl 8) or u(10)
                if (odd == 1) { t.oddLat = latCpr; t.oddLon = lonCpr; t.oddT = now }
                else { t.evenLat = latCpr; t.evenLon = lonCpr; t.evenT = now }
                decodeCpr(t)
            }
            tc == 19 -> {
                val st = u(4) and 7
                if (st == 1 || st == 2) {
                    val ewV = ((u(5) and 3) shl 8) or u(6)
                    val nsV = ((u(7) and 0x7F) shl 3) or (u(8) shr 5)
                    if (ewV != 0 && nsV != 0) {
                        val ew = (ewV - 1) * (if ((u(5) and 4) != 0) -1 else 1)
                        val ns = (nsV - 1) * (if ((u(7) and 0x80) != 0) -1 else 1)
                        val mult = if (st == 2) 4 else 1
                        val newSpeed = (sqrt((ns * ns + ew * ew).toDouble()) * mult).toInt()
                        var h = Math.toDegrees(atan2(ew.toDouble(), ns.toDouble()))
                        if (h < 0) h += 360.0
                        if (newSpeed != t.speed || h.toInt() != t.hdg) changed = true
                        t.speed = newSpeed; t.hdg = h.toInt()
                    }
                }
            }
        }
        if (changed) pushHist(t, now)
    }

    private fun nl(lat: Double): Int {
        val a = abs(lat)
        if (a < 1e-9) return 59
        if (a == 87.0) return 2
        if (a > 87.0) return 1
        return floor(2 * Math.PI / acos(1 - (1 - cos(Math.PI / 30)) / cos(Math.PI / 180 * a).pow(2))).toInt()
    }

    private fun mod(x: Double, y: Double) = x - y * floor(x / y)

    /** Global CPR decode from an even + odd position pair received within 10 s. */
    private fun decodeCpr(t: Track) {
        if (t.evenT == 0L || t.oddT == 0L || abs(t.evenT - t.oddT) > 10_000) return
        val lat0 = t.evenLat.toDouble(); val lat1 = t.oddLat.toDouble()
        val lon0 = t.evenLon.toDouble(); val lon1 = t.oddLon.toDouble()
        val j = floor((59 * lat0 - 60 * lat1) / 131072.0 + 0.5)
        var rlat0 = (360.0 / 60) * (mod(j, 60.0) + lat0 / 131072.0)
        var rlat1 = (360.0 / 59) * (mod(j, 59.0) + lat1 / 131072.0)
        if (rlat0 >= 270) rlat0 -= 360
        if (rlat1 >= 270) rlat1 -= 360
        if (nl(rlat0) != nl(rlat1)) return
        val lat: Double
        var lon: Double
        if (t.evenT > t.oddT) {
            val n = nl(rlat0); val ni = maxOf(n, 1)
            val m = floor((lon0 * (n - 1) - lon1 * n) / 131072.0 + 0.5)
            lon = (360.0 / ni) * (mod(m, ni.toDouble()) + lon0 / 131072.0)
            lat = rlat0
        } else {
            val n = nl(rlat1); val ni = maxOf(n - 1, 1)
            val m = floor((lon0 * (n - 1) - lon1 * n) / 131072.0 + 0.5)
            lon = (360.0 / ni) * (mod(m, ni.toDouble()) + lon1 / 131072.0)
            lat = rlat1
        }
        if (lon > 180) lon -= 360
        if (abs(lat) > 90) return
        t.lat = lat; t.lon = lon
        val lastPt = t.trail.lastOrNull()
        if (lastPt == null || abs(lastPt.first - lat) > 1e-4 || abs(lastPt.second - lon) > 1e-4) {
            t.trail.add(lat to lon)
            if (t.trail.size > 40) t.trail.removeAt(0)
        }
    }

    /** Aircraft heard in the last 60 s, newest first. */
    @Synchronized
    fun snapshot(now: Long): List<Aircraft> {
        tracks.values.removeAll { now - it.lastMs > 60_000 }
        return tracks.values.sortedByDescending { it.lastMs }
            .map { Aircraft(it.icao, it.callsign, it.alt, it.speed, it.hdg, it.lat, it.lon, it.lastMs, it.msgs,
                it.trail.toList(), it.squawk, it.hist.toList()) }
    }

    private companion object {
        const val CHARSET = "#ABCDEFGHIJKLMNOPQRSTUVWXYZ##### ###############0123456789######"
    }
}
