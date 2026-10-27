package com.example.rtlsdr

/** One answer from OpenCellID's cell/get endpoint: the tower's approximate position and OpenCellID's own
    accuracy estimate (metres), tagged with the serving-cell identity it was looked up for. */
class CellFix(val lat: Double, val lon: Double, val rangeM: Int, val mcc: String, val mnc: String, val lac: String, val cell: String, val atMs: Long)

/**
 * Looks up a serving cell's position from OpenCellID (opencellid.org), the crowd-sourced cell-tower
 * database the classic imsi_catcher.py tooling is traditionally paired with: decode MCC/MNC/LAC/CI off
 * the air (see [GsmImsiCatcher], from System Information Type 3), then resolve that cell to a map
 * position. Needs a free API key from opencellid.org/register and network access. This only ever sends
 * the serving cell's own public identity, never anything about a subscriber.
 */
object CellLocator {
    /** Blocking HTTP call -- always run off the UI thread. Returns null on any failure (bad/missing key,
        cell not in OpenCellID's database, no network, malformed response...). */
    fun lookup(apiKey: String, mcc: String, mnc: String, lac: String, cell: String): CellFix? {
        if (apiKey.isBlank() || mcc.isEmpty() || mnc.isEmpty() || lac.isEmpty() || cell.isEmpty()) return null
        var conn: java.net.HttpURLConnection? = null
        return try {
            val url = java.net.URL("https://opencellid.org/cell/get?key=$apiKey&mcc=$mcc&mnc=$mnc&lac=$lac&cellid=$cell&format=json")
            conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 8000; readTimeout = 8000; requestMethod = "GET"
            }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: return null
            val j = org.json.JSONObject(body)
            if (j.has("error")) return null
            val lat = j.optDouble("lat", Double.NaN)
            val lon = j.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) return null
            CellFix(lat, lon, j.optInt("range", 0), mcc, mnc, lac, cell, System.currentTimeMillis())
        } catch (_: Exception) { null } finally { conn?.disconnect() }
    }
}
