package com.example.rtlsdr

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** A 137 MHz weather satellite that is still transmitting. [freqsKhz] = main frequency first, alternate second. */
class SatDef(val name: String, val norad: Int, val freqsKhz: List<Long>, val mode: String)

object SatCatalog {
    // Meteor-M2-3 and M2-4 send digital LRPT, normally on 137.9 MHz with 137.1 MHz as the alternate (operators switch).
    val all = listOf(
        SatDef("METEOR-M2 3", 57166, listOf(137_900L, 137_100L), "LRPT"),
        SatDef("METEOR-M2 4", 59051, listOf(137_900L, 137_100L), "LRPT")
    )
}

/** Element sets from CelesTrak, cached in SharedPreferences so passes can still be computed offline. */
object TleStore {
    private fun prefs(c: Context) = c.getSharedPreferences("satpass", Context.MODE_PRIVATE)
    fun load(c: Context, norad: Int): Tle? = prefs(c).getString("tle_$norad", null)?.let { Tle.parse(it) }
    fun savedAt(c: Context, norad: Int): Long = prefs(c).getLong("tle_t_$norad", 0L)

    /** Blocking network call: run off the UI thread. Returns an error text, or null on success. */
    fun fetch(c: Context, norad: Int): String? {
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = (java.net.URL("https://celestrak.org/NORAD/elements/gp.php?CATNR=$norad&FORMAT=TLE").openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 8000; readTimeout = 8000; requestMethod = "GET"
                setRequestProperty("User-Agent", "RtlSdrAndroid/1.0 (pass predictor)")
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) return "HTTP $code"
            val tle = Tle.parse(body) ?: return "no valid orbit data in answer"
            if (tle.satNum != norad) return "wrong satellite in answer"
            prefs(c).edit().putString("tle_$norad", body).putLong("tle_t_$norad", System.currentTimeMillis()).apply()
            null
        } catch (_: java.net.UnknownHostException) { "no internet"
        } catch (_: java.net.SocketTimeoutException) { "timed out"
        } catch (e: Exception) { "error ${e.javaClass.simpleName}"
        } finally { conn?.disconnect() }
    }
}

private class Loc(val lat: Double, val lon: Double, val src: String)

/** Approximate position from the public IP (city-level, no permission needed). */
private fun ipLocate(): Loc? {
    for (u in listOf("https://ipwho.is/", "https://ipapi.co/json/")) {
        var conn: java.net.HttpURLConnection? = null
        try {
            conn = (java.net.URL(u).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 6000; readTimeout = 6000; requestMethod = "GET"
                setRequestProperty("User-Agent", "RtlSdrAndroid/1.0")
            }
            if (conn.responseCode !in 200..299) continue
            val j = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val la = j.optDouble("latitude", Double.NaN); val lo = j.optDouble("longitude", Double.NaN)
            if (!la.isNaN() && !lo.isNaN()) return Loc(la, lo, "network")
        } catch (_: Exception) {
        } finally { conn?.disconnect() }
    }
    return null
}

/**
 * The user's position, shared by the satellite screen and the ADS-B map. No GPS and no permission: it comes from the network
 * (public-IP lookup, city-level accuracy -- plenty for 137 MHz passes and for a "you are here" mark) or is typed in by hand.
 */
object UserLocation {
    private fun prefs(c: Context) = c.getSharedPreferences("satpass", Context.MODE_PRIVATE)
    fun get(c: Context): Pair<Double, Double>? {
        val p = prefs(c)
        val la = p.getString("lat", "")?.trim()?.replace(',', '.')?.toDoubleOrNull()
        val lo = p.getString("lon", "")?.trim()?.replace(',', '.')?.toDoubleOrNull()
        return if (la != null && lo != null && la in -90.0..90.0 && lo in -180.0..180.0) la to lo else null
    }
    fun save(c: Context, lat: Double, lon: Double, src: String) {
        prefs(c).edit().putString("lat", "%.4f".format(Locale.US, lat)).putString("lon", "%.4f".format(Locale.US, lon)).putString("src", src).apply()
    }
    /** Looks the position up on a background thread; [onDone] runs on the main thread with true when a position was stored. */
    fun findByNetwork(c: Context, onDone: (Boolean) -> Unit) {
        val app = c.applicationContext; val main = Handler(Looper.getMainLooper())
        Thread { val l = ipLocate(); if (l != null) save(app, l.lat, l.lon, l.src); main.post { onDone(l != null) } }.start()
    }
}

/** One point of a predicted ground track with the look angle from the observer at that moment. */
private class TP(val lat: Double, val lon: Double, val el: Double, val az: Double, val ms: Long)

private val hhmm = SimpleDateFormat("HH:mm", Locale.US)
private val dayHhmm = SimpleDateFormat("EEE HH:mm", Locale.US)

private fun fmtIn(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "${s / 3600}h ${(s % 3600) / 60}m" else if (s >= 60) "${s / 60}m ${s % 60}s" else "${s}s"
}

/**
 * Satellite screen: plain-language status ("reachable now" / next pass), a regional map with your position, the satellite's
 * ground track and its radio footprint, a sky plot of the selected pass, and the next passes. Your position (from the network, or typed in) and orbit
 * data are found automatically.
 */
@Composable
fun PassPredictor(engine: SdrEngine, accent: Color, dim: Color, text: Color, font: FontFamily, modifier: Modifier = Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("satpass", Context.MODE_PRIVATE) }
    val main = remember { Handler(Looper.getMainLooper()) }
    var latS by remember { mutableStateOf(prefs.getString("lat", "") ?: "") }
    var lonS by remember { mutableStateOf(prefs.getString("lon", "") ?: "") }
    var locSrc by remember { mutableStateOf(prefs.getString("src", "") ?: "") }
    var locStatus by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var passes by remember { mutableStateOf<List<SatPass>>(emptyList()) }
    var tleVer by remember { mutableStateOf(0) }
    var tleStatus by remember { mutableStateOf("") }
    var selStart by remember { mutableStateOf(-1L) } // aosMs of the user-selected pass, -1 = the next one
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var lastCompute by remember { mutableStateOf(0L) }

    val obs: Observer? = remember(latS, lonS) {
        val la = latS.trim().replace(',', '.').toDoubleOrNull(); val lo = lonS.trim().replace(',', '.').toDoubleOrNull()
        if (la != null && lo != null && la in -90.0..90.0 && lo in -180.0..180.0) Observer(la, lo, 0.0) else null
    }
    val sats = SatCatalog.all
    val minPeak = 10.0   // only list passes that climb at least this high
    val reachEl = 5.0    // above this elevation the satellite is practically receivable

    fun setLoc(l: Loc) {
        latS = "%.4f".format(Locale.US, l.lat); lonS = "%.4f".format(Locale.US, l.lon); locSrc = l.src; locStatus = ""
        prefs.edit().putString("lat", latS).putString("lon", lonS).putString("src", l.src).apply()
    }
    fun startIp() {
        locStatus = "finding your position..."
        Thread { val l = ipLocate(); main.post { if (l != null) setLoc(l) else locStatus = "couldn't find your position (no internet?) - tap EDIT to type it" } }.start()
    }
    fun locate() { startIp() }

    fun recompute() {
        val o = obs ?: return
        val c = ctx.applicationContext
        lastCompute = System.currentTimeMillis()
        Thread {
            val out = ArrayList<SatPass>()
            val t0 = System.currentTimeMillis() - 10 * 60_000L // keep a pass that's already in progress
            for (s in sats) { val tle = TleStore.load(c, s.norad) ?: continue; out.addAll(SatTrack.passes(tle, o, t0, 24, minPeak)) }
            out.sortBy { it.aosMs }
            main.post { passes = out }
        }.start()
    }
    fun fetchTles() {
        val c = ctx.applicationContext
        tleStatus = "updating orbit data..."
        Thread {
            var fail: String? = null
            for (s in sats) {
                if (System.currentTimeMillis() - TleStore.savedAt(c, s.norad) < 2 * 3_600_000L && TleStore.load(c, s.norad) != null) continue // CelesTrak asks for <= 1 fetch / 2 h
                TleStore.fetch(c, s.norad)?.let { fail = it }
            }
            main.post { tleStatus = fail?.let { "orbit data: $it" } ?: ""; tleVer++ }
        }.start()
    }

    LaunchedEffect(Unit) {
        if (latS.isBlank() || lonS.isBlank()) locate()
        fetchTles()
    }
    LaunchedEffect(obs?.latDeg, obs?.lonDeg, tleVer) { recompute() }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000); if (now - lastCompute > 30 * 60_000L) recompute() } }

    val future = passes.filter { it.losMs > now }
    val inProg = future.firstOrNull { it.aosMs <= now }
    val focus = inProg ?: future.firstOrNull()
    val sel = future.firstOrNull { it.aosMs == selStart } ?: focus
    fun defOf(p: SatPass?) = SatCatalog.all.firstOrNull { it.norad == p?.satNum }

    val small = TextStyle(fontFamily = font, fontSize = 11.sp, color = text)
    @Composable fun chip(label: String, on: Boolean = false, onClick: () -> Unit) {
        Text(label, color = if (on) Color.Black else accent, fontFamily = font, fontSize = 11.sp,
            modifier = Modifier.background(if (on) accent else dim.copy(alpha = 0.18f))
                .clickable(remember { MutableInteractionSource() }, null) { onClick() }
                .padding(horizontal = 9.dp, vertical = 5.dp))
    }

    MapPrefs.load(ctx)
    Column(modifier) {
        // ---------------------------------------------------------------- status card
        val fDef = defOf(focus)
        val fSgp = remember(focus?.satNum, tleVer) { focus?.let { TleStore.load(ctx, it.satNum) }?.let { Sgp4.create(it) } }
        val fLook = if (fSgp != null && obs != null) SatTrack.look(fSgp, obs, now) else null
        val reachable = inProg != null && fLook != null && fLook.el >= reachEl
        Column(Modifier.fillMaxWidth().background(if (reachable) accent.copy(alpha = 0.16f) else dim.copy(alpha = 0.10f))
            .border(1.dp, if (reachable) accent else dim.copy(alpha = 0.35f)).padding(10.dp)) {
            when {
                obs == null -> {
                    Text("WAITING FOR YOUR POSITION", color = accent, fontFamily = font, fontSize = 13.sp)
                    Text(if (locStatus.isNotEmpty()) locStatus else "tap FIND ME below", color = dim, fontFamily = font, fontSize = 10.sp)
                }
                focus == null || fDef == null -> {
                    Text(if (TleStore.load(ctx, sats[0].norad) == null) "WAITING FOR ORBIT DATA" else "NO PASSES IN THE NEXT 24 H", color = accent, fontFamily = font, fontSize = 13.sp)
                    Text(if (tleStatus.isNotEmpty()) tleStatus else "needs internet once to download satellite orbits", color = dim, fontFamily = font, fontSize = 10.sp)
                }
                else -> {
                    Text(if (reachable) "REACHABLE NOW" else if (inProg != null) "ABOVE HORIZON - TOO LOW" else "NOT IN RANGE",
                        color = if (reachable) accent else text, fontFamily = font, fontSize = 15.sp)
                    if (inProg != null && fLook != null) {
                        Text("${focus.sat}  az %.0f° %s  el %.0f°  range %.0f km".format(Locale.US, fLook.az, SatTrack.compass(fLook.az), fLook.el, fLook.rangeKm),
                            color = text, fontFamily = font, fontSize = 11.sp)
                        Text("Doppler %+.1f kHz  ·  pass ends in %s  ·  peak %.0f°".format(Locale.US, fLook.dopplerHz(fDef.freqsKhz[0] * 1000.0) / 1000.0, fmtIn(focus.losMs - now), focus.maxEl),
                            color = dim, fontFamily = font, fontSize = 10.sp)
                    } else {
                        Text("next: ${focus.sat} in ${fmtIn(focus.aosMs - now)}  ·  peak %.0f°  ·  %s → %s".format(Locale.US, focus.maxEl, SatTrack.compass(focus.azAos), SatTrack.compass(focus.azLos)),
                            color = text, fontFamily = font, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Signal: ${fDef.mode} (digital) on %.1f MHz. The receiver below turns it into a picture; use TUNE, and the antenna should be pointing at the sky.".format(Locale.US, fDef.freqsKhz[0] / 1000.0),
                        color = dim, fontFamily = font, fontSize = 9.sp)
                    Spacer(Modifier.height(5.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        for (f in fDef.freqsKhz) chip("TUNE %.1f".format(Locale.US, f / 1000.0)) { engine.khz = f; engine.tune(f) }
                    }
                }
            }
        }

        // ---------------------------------------------------------------- map + sky plot of the selected pass
        if (obs != null && sel != null) {
            val sSgp = remember(sel.satNum, tleVer) { TleStore.load(ctx, sel.satNum)?.let { Sgp4.create(it) } }
            if (sSgp != null) {
                val track = remember(sel.aosMs, sel.satNum, obs.latDeg, obs.lonDeg, tleVer) {
                    val out = ArrayList<TP>(); var t = sel.aosMs - 8 * 60_000L
                    while (t <= sel.losMs + 8 * 60_000L) {
                        val sp = SatTrack.subPoint(sSgp, t); val lk = SatTrack.look(sSgp, obs, t)
                        if (sp != null && lk != null) out.add(TP(sp[0], sp[1], lk.el, lk.az, t))
                        t += 15_000L
                    }
                    out
                }
                val curSp = SatTrack.subPoint(sSgp, now)
                val curLk = SatTrack.look(sSgp, obs, now)
                val foot = if (curSp != null) SatTrack.footprint(curSp[0], curSp[1], curSp[2], reachEl) else emptyList()
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().height(170.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PassMap(Modifier.weight(1.7f).fillMaxHeight(), accent, dim, font, obs, track, curSp, foot)
                    SkyPlot(Modifier.weight(1f).fillMaxHeight(), accent, dim, text, track, curLk)
                }
                Text("${sel.sat}  ${dayHhmm.format(Date(sel.aosMs))}-${hhmm.format(Date(sel.losMs))}  peak %.0f°   map: you = cross, satellite = dot, ring = where it is receivable".format(Locale.US, sel.maxEl),
                    color = dim, fontFamily = font, fontSize = 9.sp)
            }
        }

        // ---------------------------------------------------------------- next passes
        if (future.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("NEXT PASSES  (tap one to show it on the map)", color = dim, fontFamily = font, fontSize = 10.sp)
            for (p in future.take(6)) {
                val isSel = p.aosMs == (sel?.aosMs ?: -1L)
                val live = p.aosMs <= now
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)
                    .background(if (isSel) accent.copy(alpha = 0.14f) else Color.Transparent)
                    .clickable(remember { MutableInteractionSource() }, null) { selStart = p.aosMs }
                    .padding(horizontal = 6.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(p.sat.removePrefix("METEOR-"), color = text, fontFamily = font, fontSize = 11.sp, modifier = Modifier.width(78.dp))
                    Text(if (live) "NOW" else "in ${fmtIn(p.aosMs - now)}", color = if (live) accent else text, fontFamily = font, fontSize = 11.sp, modifier = Modifier.width(78.dp))
                    Text("${hhmm.format(Date(p.aosMs))}-${hhmm.format(Date(p.losMs))}", color = dim, fontFamily = font, fontSize = 10.sp, modifier = Modifier.width(92.dp))
                    Text("%.0f°".format(Locale.US, p.maxEl), color = if (p.maxEl >= 40) accent else dim, fontFamily = font, fontSize = 11.sp)
                }
            }
        }

        // ---------------------------------------------------------------- location + data status
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (obs != null) "You: ${latS}, ${lonS}  (${locSrc.ifEmpty { "manual" }})" else "You: unknown", color = dim, fontFamily = font, fontSize = 10.sp, modifier = Modifier.weight(1f))
            chip("FIND ME") { locate() }
            chip("EDIT", editing) { editing = !editing }
        }
        if (editing) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text("LAT ", style = small.copy(color = dim))
                BasicTextField(latS, { latS = it; locSrc = "manual"; prefs.edit().putString("lat", it.trim()).putString("src", "manual").apply() }, singleLine = true,
                    textStyle = small, cursorBrush = SolidColor(accent), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.width(90.dp).background(dim.copy(alpha = 0.12f)).padding(5.dp))
                Spacer(Modifier.width(10.dp))
                Text("LON ", style = small.copy(color = dim))
                BasicTextField(lonS, { lonS = it; locSrc = "manual"; prefs.edit().putString("lon", it.trim()).putString("src", "manual").apply() }, singleLine = true,
                    textStyle = small, cursorBrush = SolidColor(accent), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.width(90.dp).background(dim.copy(alpha = 0.12f)).padding(5.dp))
            }
        }
        if (locStatus.isNotEmpty()) Text(locStatus, color = dim, fontFamily = font, fontSize = 9.sp)
        val age = sats.mapNotNull { TleStore.savedAt(ctx, it.norad).takeIf { t -> t > 0 } }.minOrNull()
        Text(if (tleStatus.isNotEmpty()) tleStatus else if (age != null) "orbit data updated ${fmtIn(now - age)} ago (refreshes automatically)" else "", color = dim, fontFamily = font, fontSize = 9.sp)
    }
}

/** Regional map centred on you: land, borders, cities, ground track (bright while above your horizon), footprint ring, satellite dot. */
@Composable
private fun PassMap(modifier: Modifier, accent: Color, dim: Color, font: FontFamily, obs: Observer, track: List<TP>, cur: DoubleArray?, foot: List<Pair<Double, Double>>) {
    val tm = androidx.compose.ui.text.rememberTextMeasurer()
    Canvas(modifier.background(dim.copy(alpha = 0.07f)).border(1.dp, dim.copy(alpha = 0.35f))) {
        val w = size.width; val h = size.height
        val lat0 = obs.latDeg; val lon0 = obs.lonDeg
        val kx = cos(Math.toRadians(lat0)).coerceAtLeast(0.25)
        val ppd = w / 60f // pixels per degree of latitude: the window spans +-30 degrees of cos-scaled longitude
        fun dl(lon: Double): Double { var d = lon - lon0; while (d > 180) d -= 360; while (d < -180) d += 360; return d }
        fun px(lon: Double, lat: Double) = Offset((w / 2 + dl(lon) * kx * ppd).toFloat(), (h / 2 - (lat - lat0) * ppd).toFloat())
        clipRect {
            // real map: sea, land, coast, borders, big cities
            val sea = lerp(Color.Black, accent, 0.06f)
            drawBaseMap(lat0, lon0, kx * ppd, ppd.toDouble(), sea, lerp(sea, dim, 0.30f), dim.copy(alpha = 0.80f), dim.copy(alpha = 0.40f),
                dim.copy(alpha = 0.90f), accent, tm, TextStyle(fontFamily = font, fontSize = 8.sp, color = dim), MapPrefs.labels)
            // footprint ring (where the satellite is above ~5 degrees right now)
            if (foot.isNotEmpty()) {
                val fp = Path(); var prev = 0.0
                for ((k, p) in foot.withIndex()) {
                    val d = dl(p.second); val o = px(p.second, p.first)
                    if (k == 0 || abs(d - prev) > 90) fp.moveTo(o.x, o.y) else fp.lineTo(o.x, o.y); prev = d
                }
                drawPath(fp, accent.copy(alpha = 0.55f), style = Stroke(1.5f))
            }
            // ground track
            for (k in 1 until track.size) {
                val a = track[k - 1]; val b = track[k]
                if (abs(dl(a.lon) - dl(b.lon)) > 90) continue
                drawLine(if (b.el >= 0) accent else dim.copy(alpha = 0.55f), px(a.lon, a.lat), px(b.lon, b.lat), if (b.el >= 0) 3f else 1.5f)
            }
            // you
            val me = Offset(w / 2, h / 2)
            drawLine(accent, Offset(me.x - 7f, me.y), Offset(me.x + 7f, me.y), 2f); drawLine(accent, Offset(me.x, me.y - 7f), Offset(me.x, me.y + 7f), 2f)
            // satellite now
            if (cur != null) { val s = px(cur[1], cur[0]); drawCircle(Color.White, 6f, s); drawCircle(accent, 4f, s) }
        }
    }
}

/** Polar sky plot (north up, centre = overhead): the pass's path across the sky and the satellite's current position. */
@Composable
private fun SkyPlot(modifier: Modifier, accent: Color, dim: Color, text: Color, track: List<TP>, cur: Look?) {
    Canvas(modifier.background(dim.copy(alpha = 0.07f)).border(1.dp, dim.copy(alpha = 0.35f))) {
        val cx = size.width / 2; val cy = size.height / 2
        val r = min(size.width, size.height) / 2 - 14f
        val ring = dim.copy(alpha = 0.35f)
        drawCircle(ring, r, Offset(cx, cy), style = Stroke(1f))
        drawCircle(ring, r * 2 / 3, Offset(cx, cy), style = Stroke(1f))
        drawCircle(ring, r / 3, Offset(cx, cy), style = Stroke(1f))
        drawLine(ring, Offset(cx - r, cy), Offset(cx + r, cy), 1f); drawLine(ring, Offset(cx, cy - r), Offset(cx, cy + r), 1f)
        fun pt(az: Double, el: Double): Offset {
            val rr = r * (1 - el.coerceIn(0.0, 90.0) / 90.0).toFloat(); val a = Math.toRadians(az)
            return Offset(cx + rr * sin(a).toFloat(), cy - rr * cos(a).toFloat())
        }
        val path = Path(); var started = false
        for (p in track) if (p.el >= 0) { val o = pt(p.az, p.el); if (!started) { path.moveTo(o.x, o.y); started = true } else path.lineTo(o.x, o.y) }
        if (started) drawPath(path, accent, style = Stroke(3f))
        if (cur != null && cur.el >= 0) { val o = pt(cur.az, cur.el); drawCircle(Color.White, 6f, o); drawCircle(accent, 4f, o) }
        drawIntoCanvas { c ->
            val paint = android.graphics.Paint().apply { color = text.copy(alpha = 0.8f).toArgb(); textSize = 24f; isAntiAlias = true; textAlign = android.graphics.Paint.Align.CENTER }
            c.nativeCanvas.drawText("N", cx, cy - r - 3f, paint); c.nativeCanvas.drawText("S", cx, cy + r + 20f, paint)
            c.nativeCanvas.drawText("E", cx + r + 10f, cy + 8f, paint); c.nativeCanvas.drawText("W", cx - r - 10f, cy + 8f, paint)
        }
    }
}
