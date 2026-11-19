package com.example.rtlsdr

import kotlin.math.abs
import kotlin.math.floor

/** One polygon ring or polyline in degrees: flat [lon0, lat0, lon1, lat1, ...] plus its bounding box. */
class MapRing(val xy: FloatArray, val minLon: Float, val maxLon: Float, val minLat: Float, val maxLat: Float)
class MapPlace(val name: String, val lon: Float, val lat: Float, val popK: Int)
class MapAirport(val iata: String, val lon: Float, val lat: Float, val major: Boolean)

/** What is visible in a view, already projected to pixels. The drawing layer just paints it. */
class ScenePlace(val name: String, val x: Float, val y: Float, val popK: Int)
class SceneAirport(val iata: String, val x: Float, val y: Float, val major: Boolean)
class MapScene(
    /** Closed polygon rings in pixels (flat x,y), land and lakes -- fill with the even-odd rule so holes stay holes. */
    val land: List<FloatArray>, val lakes: List<FloatArray>,
    /** Open polylines in pixels (country borders). */
    val borders: List<FloatArray>,
    /** Cities / airports in view, most important first, already limited to what the zoom level can show. */
    val places: List<ScenePlace>, val airports: List<SceneAirport>
)

/**
 * Natural Earth based map: parses [MapData] once, and for a given view (centre, pixels per degree, size) builds the visible
 * scene. Pure Kotlin on purpose (no Android/Compose types) so it can be tested off-device.
 */
object MapGeo {
    private fun parseRings(chunks: Array<String>, closed: Boolean): List<MapRing> {
        val out = ArrayList<MapRing>()
        for (chunk in chunks) for (line in chunk.split('|')) {
            if (line.isEmpty()) continue
            val pts = line.split(' ')
            val xy = FloatArray(pts.size * 2)
            var x = 0; var y = 0
            var mnLo = Float.MAX_VALUE; var mxLo = -Float.MAX_VALUE; var mnLa = Float.MAX_VALUE; var mxLa = -Float.MAX_VALUE
            for ((i, p) in pts.withIndex()) {
                val c = p.indexOf(',')
                val a = p.substring(0, c).toInt(); val b = p.substring(c + 1).toInt()
                if (i == 0) { x = a; y = b } else { x += a; y += b }
                val lo = x / 50f; val la = y / 50f
                xy[2 * i] = lo; xy[2 * i + 1] = la
                if (lo < mnLo) mnLo = lo; if (lo > mxLo) mxLo = lo; if (la < mnLa) mnLa = la; if (la > mxLa) mxLa = la
            }
            out.add(MapRing(xy, mnLo, mxLo, mnLa, mxLa))
        }
        return out
    }
    val land: List<MapRing> by lazy { parseRings(MapData.LAND, true) }
    val lakes: List<MapRing> by lazy { parseRings(MapData.LAKES, true) }
    val borders: List<MapRing> by lazy { parseRings(MapData.BORDERS, false) }
    /** Most populous first. */
    val places: List<MapPlace> by lazy {
        MapData.PLACES.flatMap { it.split(';') }.mapNotNull { r ->
            val f = r.split(','); if (f.size < 4) null else MapPlace(f[0], f[1].toInt() / 100f, f[2].toInt() / 100f, f[3].toInt())
        }.sortedByDescending { it.popK }
    }
    val airports: List<MapAirport> by lazy {
        MapData.AIRPORTS.flatMap { it.split(';') }.mapNotNull { r ->
            val f = r.split(','); if (f.size < 4) null else MapAirport(f[0], f[1].toInt() / 100f, f[2].toInt() / 100f, f[3] == "1")
        }
    }

    /** Longitude difference [lon] - [mid], wrapped to [-180, 180). */
    fun dl(lon: Double, mid: Double): Double {
        var d = lon - mid
        while (d >= 180) d -= 360
        while (d < -180) d += 360
        return d
    }

    /**
     * Builds the scene for a view centred on ([midLat], [midLon]) of [w] x [h] pixels, where one degree of longitude is [sx]
     * pixels and one degree of latitude is [sy] pixels (north up; for an equirectangular-style projection).
     * Cities and airports are thinned by zoom: the wider the view, the bigger a city has to be to appear.
     */
    fun scene(midLat: Double, midLon: Double, sx: Double, sy: Double, w: Float, h: Float, labels: Boolean): MapScene {
        val halfLon = w / (2 * sx); val halfLat = h / (2 * sy)
        val cx = w / 2f; val cy = h / 2f
        val wrapLon = dl(midLon + 180, 0.0) // the meridian opposite the view centre: rings straddling it can't be drawn flat
        val latLo = midLat - halfLat; val latHi = midLat + halfLat

        fun inView(r: MapRing): Boolean {
            if (r.maxLat < latLo || r.minLat > latHi) return false
            if (r.minLon < wrapLon && wrapLon < r.maxLon) return false
            val hw = (r.maxLon - r.minLon) / 2.0
            return abs(dl((r.minLon + r.maxLon) / 2.0, midLon)) - hw <= halfLon
        }
        fun project(r: MapRing): FloatArray {
            val n = r.xy.size / 2; val o = FloatArray(n * 2)
            for (i in 0 until n) {
                o[2 * i] = cx + (dl(r.xy[2 * i].toDouble(), midLon) * sx).toFloat()
                o[2 * i + 1] = cy - ((r.xy[2 * i + 1] - midLat) * sy).toFloat()
            }
            return o
        }
        val land = land.filter(::inView).map(::project)
        val lakes = lakes.filter(::inView).map(::project)
        val borders = borders.filter(::inView).map(::project)

        val spanDeg = 2 * halfLat
        val places = ArrayList<ScenePlace>()
        val airports = ArrayList<SceneAirport>()
        if (labels) {
            val minPopK = when { spanDeg > 40 -> 3000; spanDeg > 20 -> 1200; spanDeg > 10 -> 500; spanDeg > 5 -> 250; else -> 0 }
            for (p in MapGeo.places) {
                if (p.popK < minPopK) continue
                val d = dl(p.lon.toDouble(), midLon)
                if (abs(d) > halfLon * 1.02 || abs(p.lat - midLat) > halfLat * 1.02) continue
                places.add(ScenePlace(p.name, cx + (d * sx).toFloat(), cy - ((p.lat - midLat) * sy).toFloat(), p.popK))
                if (places.size >= 120) break
            }
            if (spanDeg <= 12) for (a in MapGeo.airports) {
                if (!a.major && spanDeg > 5) continue
                val d = dl(a.lon.toDouble(), midLon)
                if (abs(d) > halfLon || abs(a.lat - midLat) > halfLat) continue
                airports.add(SceneAirport(a.iata, cx + (d * sx).toFloat(), cy - ((a.lat - midLat) * sy).toFloat(), a.major))
                if (airports.size >= 80) break
            }
        }
        return MapScene(land, lakes, borders, places, airports)
    }
}
