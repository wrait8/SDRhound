package com.example.rtlsdr

import kotlin.math.*

/**
 * Satellite pass prediction: TLE parser, SGP4 near-earth propagator (every weather satellite is LEO, period < 225 min,
 * so the deep-space branch is deliberately not implemented and such TLEs are rejected), TEME->ECEF, look angles,
 * Doppler, and a pass finder. SGP4 is ported from the reference implementation (Vallado et al., "Revisiting
 * Spacetrack Report #3", as in the python-sgp4 package, WGS-72 constants, 'improved' mode) and was checked
 * numerically against that package -- see the test notes in the delivery message.
 */
class Tle(val name: String, val line1: String, val line2: String) {
    val satNum: Int = line1.substring(2, 7).trim().toInt()
    /** Julian date of the element-set epoch. */
    val epochJd: Double
    init {
        val yy = line1.substring(18, 20).toInt()
        val day = line1.substring(20, 32).trim().toDouble()
        val year = if (yy < 57) 2000 + yy else 1900 + yy
        val jan1 = 367.0 * year - floor(7.0 * (year + floor(10.0 / 12.0)) / 4.0) + floor(275.0 / 9.0) + 1 + 1721013.5
        epochJd = jan1 + (day - 1.0)
    }
    val epochMs: Long get() = ((epochJd - 2440587.5) * 86400000.0).toLong()

    companion object {
        /** Parses 2- or 3-line TLE text; returns null if it isn't a valid element set (checksums are verified). */
        fun parse(text: String): Tle? {
            val lines = text.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
            val i1 = lines.indexOfFirst { it.startsWith("1 ") && it.length >= 69 }
            if (i1 < 0 || i1 + 1 >= lines.size) return null
            val l1 = lines[i1]; val l2 = lines[i1 + 1]
            if (!l2.startsWith("2 ") || l2.length < 69) return null
            if (!checksumOk(l1) || !checksumOk(l2)) return null
            val name = if (i1 > 0) lines[i1 - 1].trim() else "SAT " + l1.substring(2, 7).trim()
            return try { Tle(name, l1, l2) } catch (_: Exception) { null }
        }
        private fun checksumOk(l: String): Boolean {
            var s = 0
            for (k in 0 until 68) { val c = l[k]; if (c.isDigit()) s += c - '0' else if (c == '-') s += 1 }
            return s % 10 == l[68] - '0'
        }
    }
}

class Sgp4 private constructor() {
    companion object {
        private const val TWOPI = 2.0 * PI
        // WGS-72 (what SGP4 element sets are fitted with)
        private const val MU = 398600.8
        const val RE = 6378.135
        private val XKE = 60.0 / sqrt(RE * RE * RE / MU)
        private const val J2 = 0.001082616
        private const val J3 = -0.00000253881
        private const val J4 = -0.00000165597
        private const val J3OJ2 = J3 / J2

        fun create(t: Tle): Sgp4? {
            val s = Sgp4()
            return if (s.init(t)) s else null
        }

        fun gstime(jdut1: Double): Double {
            val tut1 = (jdut1 - 2451545.0) / 36525.0
            var temp = -6.2e-6 * tut1 * tut1 * tut1 + 0.093104 * tut1 * tut1 + (876600.0 * 3600 + 8640184.812866) * tut1 + 67310.54841
            temp = ((temp * (PI / 180.0) / 240.0) % TWOPI)
            if (temp < 0.0) temp += TWOPI
            return temp
        }
    }

    var epochJd = 0.0; private set
    private var bstar = 0.0; private var ecco = 0.0; private var argpo = 0.0; private var inclo = 0.0
    private var mo = 0.0; private var nodeo = 0.0; private var noUnkozai = 0.0
    private var isimp = false
    private var aycof = 0.0; private var con41 = 0.0; private var cc1 = 0.0; private var cc4 = 0.0; private var cc5 = 0.0
    private var d2 = 0.0; private var d3 = 0.0; private var d4 = 0.0; private var delmo = 0.0; private var eta = 0.0
    private var argpdot = 0.0; private var omgcof = 0.0; private var sinmao = 0.0
    private var t2cof = 0.0; private var t3cof = 0.0; private var t4cof = 0.0; private var t5cof = 0.0
    private var x1mth2 = 0.0; private var x7thm1 = 0.0; private var mdot = 0.0; private var nodedot = 0.0
    private var xlcof = 0.0; private var xmcof = 0.0; private var nodecf = 0.0

    private fun init(t: Tle): Boolean {
        val l1 = t.line1; val l2 = t.line2
        val mant = l1.substring(53, 61).trim()
        val sign = if (mant.startsWith("-")) -1.0 else 1.0
        val digits = mant.trimStart('-', '+', ' ').dropLast(2)
        val ex = mant.takeLast(2).trim().toInt()
        bstar = sign * ("0.$digits").toDouble() * 10.0.pow(ex)
        inclo = l2.substring(8, 16).trim().toDouble() * PI / 180.0
        nodeo = l2.substring(17, 25).trim().toDouble() * PI / 180.0
        ecco = ("0." + l2.substring(26, 33).trim()).toDouble()
        argpo = l2.substring(34, 42).trim().toDouble() * PI / 180.0
        mo = l2.substring(43, 51).trim().toDouble() * PI / 180.0
        val noKozai = l2.substring(52, 63).trim().toDouble() * TWOPI / 1440.0
        epochJd = t.epochJd
        if (TWOPI / noKozai >= 225.0) return false // deep-space orbit: not supported (not a LEO weather satellite)

        // ---- initl ----
        val x2o3 = 2.0 / 3.0
        val eccsq = ecco * ecco; val omeosq = 1.0 - eccsq; val rteosq = sqrt(omeosq)
        val cosio = cos(inclo); val cosio2 = cosio * cosio
        val ak = (XKE / noKozai).pow(x2o3)
        val d1 = 0.75 * J2 * (3.0 * cosio2 - 1.0) / (rteosq * omeosq)
        var del = d1 / (ak * ak)
        val adel = ak * (1.0 - del * del - del * (1.0 / 3.0 + 134.0 * del * del / 81.0))
        del = d1 / (adel * adel)
        noUnkozai = noKozai / (1.0 + del)
        val ao = (XKE / noUnkozai).pow(x2o3)
        val sinio = sin(inclo)
        val po = ao * omeosq
        val con42 = 1.0 - 5.0 * cosio2
        con41 = -con42 - cosio2 - cosio2
        val posq = po * po
        val rp = ao * (1.0 - ecco)

        // ---- sgp4init (near earth) ----
        val ss = 78.0 / RE + 1.0
        val qzms2t = ((120.0 - 78.0) / RE).pow(4)
        isimp = rp < 220.0 / RE + 1.0
        var sfour = ss; var qzms24 = qzms2t
        val perige = (rp - 1.0) * RE
        if (perige < 156.0) {
            sfour = perige - 78.0
            if (perige < 98.0) sfour = 20.0
            qzms24 = ((120.0 - sfour) / RE).pow(4)
            sfour = sfour / RE + 1.0
        }
        val pinvsq = 1.0 / posq
        val tsi = 1.0 / (ao - sfour)
        eta = ao * ecco * tsi
        val etasq = eta * eta; val eeta = ecco * eta
        val psisq = abs(1.0 - etasq)
        val coef = qzms24 * tsi.pow(4.0)
        val coef1 = coef / psisq.pow(3.5)
        val cc2 = coef1 * noUnkozai * (ao * (1.0 + 1.5 * etasq + eeta * (4.0 + etasq)) +
                0.375 * J2 * tsi / psisq * con41 * (8.0 + 3.0 * etasq * (8.0 + etasq)))
        cc1 = bstar * cc2
        var cc3 = 0.0
        if (ecco > 1.0e-4) cc3 = -2.0 * coef * tsi * J3OJ2 * noUnkozai * sinio / ecco
        x1mth2 = 1.0 - cosio2
        cc4 = 2.0 * noUnkozai * coef1 * ao * omeosq * (eta * (2.0 + 0.5 * etasq) + ecco * (0.5 + 2.0 * etasq) -
                J2 * tsi / (ao * psisq) * (-3.0 * con41 * (1.0 - 2.0 * eeta + etasq * (1.5 - 0.5 * eeta)) +
                0.75 * x1mth2 * (2.0 * etasq - eeta * (1.0 + etasq)) * cos(2.0 * argpo)))
        cc5 = 2.0 * coef1 * ao * omeosq * (1.0 + 2.75 * (etasq + eeta) + eeta * etasq)
        val cosio4 = cosio2 * cosio2
        val temp1 = 1.5 * J2 * pinvsq * noUnkozai
        val temp2 = 0.5 * temp1 * J2 * pinvsq
        val temp3 = -0.46875 * J4 * pinvsq * pinvsq * noUnkozai
        mdot = noUnkozai + 0.5 * temp1 * rteosq * con41 + 0.0625 * temp2 * rteosq * (13.0 - 78.0 * cosio2 + 137.0 * cosio4)
        argpdot = -0.5 * temp1 * con42 + 0.0625 * temp2 * (7.0 - 114.0 * cosio2 + 395.0 * cosio4) +
                temp3 * (3.0 - 36.0 * cosio2 + 49.0 * cosio4)
        val xhdot1 = -temp1 * cosio
        nodedot = xhdot1 + (0.5 * temp2 * (4.0 - 19.0 * cosio2) + 2.0 * temp3 * (3.0 - 7.0 * cosio2)) * cosio
        omgcof = bstar * cc3 * cos(argpo)
        xmcof = 0.0
        if (ecco > 1.0e-4) xmcof = -x2o3 * coef * bstar / eeta
        nodecf = 3.5 * omeosq * xhdot1 * cc1
        t2cof = 1.5 * cc1
        val temp4 = 1.5e-12
        xlcof = if (abs(cosio + 1.0) > 1.5e-12) -0.25 * J3OJ2 * sinio * (3.0 + 5.0 * cosio) / (1.0 + cosio)
                else -0.25 * J3OJ2 * sinio * (3.0 + 5.0 * cosio) / temp4
        aycof = -0.5 * J3OJ2 * sinio
        val delmotemp = 1.0 + eta * cos(mo)
        delmo = delmotemp * delmotemp * delmotemp
        sinmao = sin(mo)
        x7thm1 = 7.0 * cosio2 - 1.0
        if (!isimp) {
            val cc1sq = cc1 * cc1
            d2 = 4.0 * ao * tsi * cc1sq
            val temp = d2 * tsi * cc1 / 3.0
            d3 = (17.0 * ao + sfour) * temp
            d4 = 0.5 * temp * ao * tsi * (221.0 * ao + 31.0 * sfour) * cc1
            t3cof = d2 + 2.0 * cc1sq
            t4cof = 0.25 * (3.0 * d3 + cc1 * (12.0 * d2 + 10.0 * cc1sq))
            t5cof = 0.2 * (3.0 * d4 + 12.0 * cc1 * d3 + 6.0 * d2 * d2 + 15.0 * cc1sq * (2.0 * d2 + cc1sq))
        }
        return true
    }

    /** Position (km) and velocity (km/s) in the TEME frame [tsinceMin] minutes after the epoch, or null on a model error. */
    fun propagate(tsinceMin: Double): DoubleArray? {
        val t = tsinceMin
        val vkmpersec = RE * XKE / 60.0
        val xmdf = mo + mdot * t
        val argpdf = argpo + argpdot * t
        val nodedf = nodeo + nodedot * t
        var argpm = argpdf; var mm = xmdf
        val t2 = t * t
        var nodem = nodedf + nodecf * t2
        var tempa = 1.0 - cc1 * t
        var tempe = bstar * cc4 * t
        var templ = t2cof * t2
        if (!isimp) {
            val delomg = omgcof * t
            val delmtemp = 1.0 + eta * cos(xmdf)
            val delm = xmcof * (delmtemp * delmtemp * delmtemp - delmo)
            val temp = delomg + delm
            mm = xmdf + temp; argpm = argpdf - temp
            val t3 = t2 * t; val t4 = t3 * t
            tempa = tempa - d2 * t2 - d3 * t3 - d4 * t4
            tempe += bstar * cc5 * (sin(mm) - sinmao)
            templ += t3cof * t3 + t4 * (t4cof + t * t5cof)
        }
        var nm = noUnkozai; var em = ecco; val inclm = inclo
        if (nm <= 0.0) return null
        val am = (XKE / nm).pow(2.0 / 3.0) * tempa * tempa
        nm = XKE / am.pow(1.5)
        em -= tempe
        if (em >= 1.0 || em < -0.001) return null
        if (em < 1.0e-6) em = 1.0e-6
        mm += noUnkozai * templ
        var xlm = mm + argpm + nodem
        nodem = if (nodem >= 0.0) nodem % TWOPI else -((-nodem) % TWOPI)
        argpm %= TWOPI
        xlm %= TWOPI
        mm = (xlm - argpm - nodem) % TWOPI
        val sinim = sin(inclm); val cosim = cos(inclm)
        val ep = em; val xincp = inclm; val argpp = argpm; val nodep = nodem; val mp = mm
        val sinip = sinim; val cosip = cosim
        val axnl = ep * cos(argpp)
        var temp = 1.0 / (am * (1.0 - ep * ep))
        val aynl = ep * sin(argpp) + temp * aycof
        val xl = mp + argpp + nodep + temp * xlcof * axnl
        var u = (xl - nodep) % TWOPI
        if (u < 0) u += TWOPI
        var eo1 = u; var tem5 = 9999.9; var ktr = 1
        var sineo1 = 0.0; var coseo1 = 0.0
        while (abs(tem5) >= 1.0e-12 && ktr <= 10) {
            sineo1 = sin(eo1); coseo1 = cos(eo1)
            tem5 = 1.0 - coseo1 * axnl - sineo1 * aynl
            tem5 = (u - aynl * coseo1 + axnl * sineo1 - eo1) / tem5
            if (abs(tem5) >= 0.95) tem5 = if (tem5 > 0.0) 0.95 else -0.95
            eo1 += tem5; ktr++
        }
        val ecose = axnl * coseo1 + aynl * sineo1
        val esine = axnl * sineo1 - aynl * coseo1
        val el2 = axnl * axnl + aynl * aynl
        val pl = am * (1.0 - el2)
        if (pl < 0.0) return null
        val rl = am * (1.0 - ecose)
        val rdotl = sqrt(am) * esine / rl
        val rvdotl = sqrt(pl) / rl
        val betal = sqrt(1.0 - el2)
        temp = esine / (1.0 + betal)
        val sinu = am / rl * (sineo1 - aynl - axnl * temp)
        val cosu = am / rl * (coseo1 - axnl + aynl * temp)
        var su = atan2(sinu, cosu)
        val sin2u = (cosu + cosu) * sinu
        val cos2u = 1.0 - 2.0 * sinu * sinu
        temp = 1.0 / pl
        val temp1 = 0.5 * J2 * temp
        val temp2 = temp1 * temp
        val mrt = rl * (1.0 - 1.5 * temp2 * betal * con41) + 0.5 * temp1 * x1mth2 * cos2u
        su -= 0.25 * temp2 * x7thm1 * sin2u
        val xnode = nodep + 1.5 * temp2 * cosip * sin2u
        val xinc = xincp + 1.5 * temp2 * cosip * sinip * cos2u
        val mvt = rdotl - nm * temp1 * x1mth2 * sin2u / XKE
        val rvdot = rvdotl + nm * temp1 * (x1mth2 * cos2u + 1.5 * con41) / XKE
        val sinsu = sin(su); val cossu = cos(su)
        val snod = sin(xnode); val cnod = cos(xnode)
        val sini = sin(xinc); val cosi = cos(xinc)
        val xmx = -snod * cosi; val xmy = cnod * cosi
        val ux = xmx * sinsu + cnod * cossu
        val uy = xmy * sinsu + snod * cossu
        val uz = sini * sinsu
        val vx = xmx * cossu - cnod * sinsu
        val vy = xmy * cossu - snod * sinsu
        val vz = sini * cossu
        if (mrt < 1.0) return null // decayed
        val mr = mrt * RE
        return doubleArrayOf(mr * ux, mr * uy, mr * uz,
            (mvt * ux + rvdot * vx) * vkmpersec, (mvt * uy + rvdot * vy) * vkmpersec, (mvt * uz + rvdot * vz) * vkmpersec)
    }
}

class Observer(val latDeg: Double, val lonDeg: Double, val altM: Double = 0.0)

/** Azimuth/elevation in degrees, range in km, range rate in km/s (positive = receding). */
class Look(val az: Double, val el: Double, val rangeKm: Double, val rangeRateKmS: Double) {
    /** Received frequency for a transmitter at [fHz]. */
    fun dopplerHz(fHz: Double) = -fHz * rangeRateKmS / 299792.458
}

class SatPass(
    val sat: String, val satNum: Int, val aosMs: Long, val losMs: Long, val maxEl: Double, val maxElMs: Long,
    val azAos: Double, val azLos: Double, val azMax: Double, val inProgress: Boolean
)

object SatTrack {
    private const val WE = 7.2921158553e-5 // earth rotation rad/s
    private fun jdOf(ms: Long) = ms / 86400000.0 + 2440587.5

    fun look(sgp: Sgp4, obs: Observer, ms: Long): Look? {
        val jd = jdOf(ms)
        val rv = sgp.propagate((jd - sgp.epochJd) * 1440.0) ?: return null
        val g = Sgp4.gstime(jd)
        val cg = cos(g); val sg = sin(g)
        // TEME -> ECEF (rotation about z by GMST; polar motion ignored)
        val x = cg * rv[0] + sg * rv[1]; val y = -sg * rv[0] + cg * rv[1]; val z = rv[2]
        val vx = cg * rv[3] + sg * rv[4] + WE * y
        val vy = -sg * rv[3] + cg * rv[4] - WE * x
        val vz = rv[5]
        // observer ECEF (WGS-84)
        val lat = Math.toRadians(obs.latDeg); val lon = Math.toRadians(obs.lonDeg); val h = obs.altM / 1000.0
        val a = 6378.137; val f = 1.0 / 298.257223563; val e2 = f * (2 - f)
        val n = a / sqrt(1 - e2 * sin(lat) * sin(lat))
        val ox = (n + h) * cos(lat) * cos(lon); val oy = (n + h) * cos(lat) * sin(lon); val oz = (n * (1 - e2) + h) * sin(lat)
        val dx = x - ox; val dy = y - oy; val dz = z - oz
        val e = -sin(lon) * dx + cos(lon) * dy
        val nn = -sin(lat) * cos(lon) * dx - sin(lat) * sin(lon) * dy + cos(lat) * dz
        val u = cos(lat) * cos(lon) * dx + cos(lat) * sin(lon) * dy + sin(lat) * dz
        val range = sqrt(dx * dx + dy * dy + dz * dz)
        var az = Math.toDegrees(atan2(e, nn)); if (az < 0) az += 360.0
        val el = Math.toDegrees(asin(u / range))
        val rr = (dx * vx + dy * vy + dz * vz) / range
        return Look(az, el, range, rr)
    }

    /**
     * Passes of [tle] within [hours] of [fromMs] whose peak elevation is at least [minMaxEl] degrees, soonest first.
     * Steps 20 s, then refines AOS/LOS (horizon crossing) by bisection and the peak by a fine scan.
     */
    fun passes(tle: Tle, obs: Observer, fromMs: Long, hours: Int, minMaxEl: Double): List<SatPass> {
        val sgp = Sgp4.create(tle) ?: return emptyList()
        val out = ArrayList<SatPass>()
        val step = 20_000L
        val end = fromMs + hours * 3_600_000L
        fun el(ms: Long) = look(sgp, obs, ms)?.el ?: -90.0
        var t = fromMs
        var prev = el(t)
        var inPass = prev >= 0.0
        var aos = if (inPass) fromMs else 0L
        var inProgress = inPass
        while (t < end) {
            val tn = t + step
            val cur = el(tn)
            if (!inPass && prev < 0.0 && cur >= 0.0) { aos = bisect(::el, t, tn, true); inPass = true; inProgress = false }
            else if (inPass && prev >= 0.0 && cur < 0.0) {
                val los = bisect(::el, t, tn, false)
                finish(sgp, obs, tle, aos, los, inProgress, minMaxEl)?.let { out.add(it) }
                inPass = false
            }
            prev = cur; t = tn
        }
        return out
    }

    private fun bisect(el: (Long) -> Double, lo0: Long, hi0: Long, rising: Boolean): Long {
        var lo = lo0; var hi = hi0
        while (hi - lo > 500) {
            val mid = (lo + hi) / 2
            val above = el(mid) >= 0.0
            if (above == rising) hi = mid else lo = mid
        }
        return hi
    }

    private fun finish(sgp: Sgp4, obs: Observer, tle: Tle, aos: Long, los: Long, inProgress: Boolean, minMaxEl: Double): SatPass? {
        var best = -90.0; var bestMs = aos
        var t = aos
        while (t <= los) { val e = look(sgp, obs, t)?.el ?: -90.0; if (e > best) { best = e; bestMs = t }; t += 5_000L }
        if (best < minMaxEl) return null
        val la = look(sgp, obs, aos); val ll = look(sgp, obs, los); val lm = look(sgp, obs, bestMs)
        return SatPass(tle.name, tle.satNum, aos, los, best, bestMs, la?.az ?: 0.0, ll?.az ?: 0.0, lm?.az ?: 0.0, inProgress)
    }

    /** Sub-satellite point: [latitude deg, longitude deg, altitude km] at [ms], or null on a model error. */
    fun subPoint(sgp: Sgp4, ms: Long): DoubleArray? {
        val jd = jdOf(ms)
        val rv = sgp.propagate((jd - sgp.epochJd) * 1440.0) ?: return null
        val g = Sgp4.gstime(jd)
        val x = cos(g) * rv[0] + sin(g) * rv[1]; val y = -sin(g) * rv[0] + cos(g) * rv[1]; val z = rv[2]
        val a = 6378.137; val f = 1.0 / 298.257223563; val e2 = f * (2 - f)
        val p = sqrt(x * x + y * y)
        var lat = atan2(z, p * (1 - e2)); var n = a
        repeat(5) { n = a / sqrt(1 - e2 * sin(lat) * sin(lat)); lat = atan2(z + e2 * n * sin(lat), p) }
        val alt = p / cos(lat) - n
        return doubleArrayOf(Math.toDegrees(lat), Math.toDegrees(atan2(y, x)), alt)
    }

    /** Ground circle (lat, lon pairs) inside which the satellite at [altKm] is above [minEl] degrees, centred on ([lat],[lon]). */
    fun footprint(lat: Double, lon: Double, altKm: Double, minEl: Double): List<Pair<Double, Double>> {
        val re = 6371.0
        val el = Math.toRadians(minEl)
        val d = acos(re * cos(el) / (re + altKm)) - el // earth-central angle
        val la = Math.toRadians(lat); val lo = Math.toRadians(lon)
        return (0..72).map { k ->
            val th = Math.toRadians(k * 5.0)
            val la2 = asin(sin(la) * cos(d) + cos(la) * sin(d) * cos(th))
            val lo2 = lo + atan2(sin(th) * sin(d) * cos(la), cos(d) - sin(la) * sin(la2))
            Math.toDegrees(la2) to Math.toDegrees(lo2)
        }
    }

    fun compass(az: Double): String = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")[((az + 11.25) / 22.5).toInt() % 16]
}
