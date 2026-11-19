package com.example.rtlsdr

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp

/** Map options chosen in Settings (ADS-B section), remembered between runs. */
object MapPrefs {
    /** false = radar-style range rings, true = real map (land, borders, cities, airports). */
    var coast by mutableStateOf(false); private set
    /** Real map only: draw city names and airport codes. */
    var labels by mutableStateOf(true); private set
    private var loaded = false
    private fun prefs(c: Context) = c.getSharedPreferences("adsb_ui", Context.MODE_PRIVATE)
    fun load(c: Context) {
        if (loaded) return
        loaded = true
        coast = prefs(c).getBoolean("coast", false); labels = prefs(c).getBoolean("labels", true)
    }
    fun setCoast(c: Context, v: Boolean) { coast = v; prefs(c).edit().putBoolean("coast", v).apply() }
    fun setLabels(c: Context, v: Boolean) { labels = v; prefs(c).edit().putBoolean("labels", v).apply() }
}

/**
 * Paints the real map for a view centred on ([midLat], [midLon]) where one degree of longitude is [sx] pixels and one degree
 * of latitude is [sy] pixels: sea, filled land (lakes cut out), coastlines, dashed country borders, then cities and airports.
 * All geometry (culling, projection, which places are big enough for this zoom) comes from [MapGeo.scene].
 */
fun DrawScope.drawBaseMap(
    midLat: Double, midLon: Double, sx: Double, sy: Double,
    sea: Color, land: Color, coast: Color, border: Color, cityColor: Color, airportColor: Color,
    tm: TextMeasurer, labelStyle: TextStyle, labels: Boolean
) {
    val sc = MapGeo.scene(midLat, midLon, sx, sy, size.width, size.height, labels)
    drawRect(sea)
    fun path(rs: List<FloatArray>, close: Boolean): Path {
        val p = Path(); p.fillType = PathFillType.EvenOdd
        for (r in rs) {
            if (r.size < 4) continue
            p.moveTo(r[0], r[1])
            var i = 2
            while (i < r.size) { p.lineTo(r[i], r[i + 1]); i += 2 }
            if (close) p.close()
        }
        return p
    }
    val landP = path(sc.land, true)
    val lakeP = path(sc.lakes, true)
    drawPath(landP, land)
    drawPath(lakeP, sea)
    drawPath(landP, coast, style = Stroke(width = 1.2f))
    drawPath(lakeP, coast.copy(alpha = coast.alpha * 0.55f), style = Stroke(width = 1f))
    if (sc.borders.isNotEmpty())
        drawPath(path(sc.borders, false), border, style = Stroke(width = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))))

    val placed = ArrayList<Rect>()
    var shown = 0
    for (p in sc.places) {
        drawCircle(cityColor, 2.5.dp.toPx(), Offset(p.x, p.y))
        if (shown >= 40) continue
        val lr = tm.measure(p.name, labelStyle)
        val r = Rect(p.x + 5.dp.toPx(), p.y - lr.size.height / 2f, p.x + 5.dp.toPx() + lr.size.width, p.y + lr.size.height / 2f)
        if (placed.none { it.overlaps(r) }) { drawText(lr, topLeft = Offset(r.left, r.top)); placed.add(r); shown++ }
    }
    val aStyle = labelStyle.copy(color = airportColor)
    for (a in sc.airports) {
        val s = (if (a.major) 3.5f else 2.5f).dp.toPx()
        drawRect(airportColor, Offset(a.x - s, a.y - s), androidx.compose.ui.geometry.Size(2 * s, 2 * s), style = Stroke(width = 1.2f))
        val lr = tm.measure(a.iata, aStyle)
        val r = Rect(a.x + s + 3.dp.toPx(), a.y - lr.size.height / 2f, a.x + s + 3.dp.toPx() + lr.size.width, a.y + lr.size.height / 2f)
        if (placed.none { it.overlaps(r) }) { drawText(lr, topLeft = Offset(r.left, r.top)); placed.add(r) }
    }
}
