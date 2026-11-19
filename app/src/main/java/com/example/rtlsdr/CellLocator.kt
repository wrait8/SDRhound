package com.example.rtlsdr

/** One answer from OpenCellID's cell/get endpoint: the tower's approximate position and OpenCellID's own
    accuracy estimate (metres), tagged with the serving-cell identity it was looked up for. */
class CellFix(val lat: Double, val lon: Double, val rangeM: Int, val mcc: String, val mnc: String, val lac: String, val cell: String, val atMs: Long,
              val source: String = "OpenCellID")

/**
 * Looks up a serving cell's position from OpenCellID (opencellid.org), the crowd-sourced cell-tower
 * database the classic imsi_catcher.py tooling is traditionally paired with: decode MCC/MNC/LAC/CI off
 * the air (see [GsmImsiCatcher], from System Information Type 3), then resolve that cell to a map
 * position. Needs a free API key from opencellid.org/register and network access. This only ever sends
 * the serving cell's own public identity, never anything about a subscriber.
 */
/** Result of one lookup: either a [fix] or a human-readable [error] saying why there isn't one. */
class CellLookup(val fix: CellFix?, val error: String)

object CellLocator {
    /**
     * Resolves the serving cell to a position WITHOUT requiring any API key. Sources are tried in turn until one
     * knows the cell: OpenCellID (only if you entered a key; biggest database), then Mylnikov's open cell database
     * (free, keyless, ~10M towers, typical accuracy 1-1.6 km), then BeaconDB (free community successor to Mozilla
     * Location Service). All are crowd-sourced, so a new or rural tower can be missing from all of them.
     */
    fun lookupDetailed(apiKey: String, mcc: String, mnc: String, lac: String, cell: String): CellLookup {
        if (mcc.isEmpty() || mnc.isEmpty() || lac.isEmpty() || cell.isEmpty()) return CellLookup(null, "serving cell not decoded yet")
        val errors = ArrayList<String>()
        if (apiKey.isNotBlank()) {
            val r = openCellId(apiKey, mcc, mnc, lac, cell)
            if (r.fix != null) return r
            errors.add("OpenCellID: ${r.error}")
        }
        val my = mylnikov(mcc, mnc, lac, cell)
        if (my.fix != null) return my
        errors.add("Mylnikov: ${my.error}")
        val bc = beaconDb(mcc, mnc, lac, cell)
        if (bc.fix != null) return bc
        errors.add("BeaconDB: ${bc.error}")
        return CellLookup(null, "tower $mcc-$mnc-$lac-$cell not found. " + errors.joinToString("; "))
    }

    /** Mylnikov open cell database: GET, no key. Answer: {"result":200,"data":{"lat":..,"lon":..,"range":..}}. */
    private fun mylnikov(mcc: String, mnc: String, lac: String, cell: String): CellLookup {
        val m = mcc.toIntOrNull(); val n = mnc.toIntOrNull(); val l = lac.toIntOrNull(); val c = cell.toLongOrNull()
        if (m == null || n == null || l == null || c == null) return CellLookup(null, "cell identity not numeric")
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = (java.net.URL("https://api.mylnikov.org/geolocation/cell?v=1.1&data=open&mcc=$m&mnc=$n&lac=$l&cellid=$c").openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 8000; readTimeout = 8000; requestMethod = "GET"
                setRequestProperty("User-Agent", "RtlSdrAndroid/1.0 (GSM cell locator)")
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty().trim()
            val j = try { org.json.JSONObject(text) } catch (_: Exception) { null } ?: return CellLookup(null, "HTTP $code")
            if (j.optInt("result", 0) != 200) return CellLookup(null, "not in its database")
            val d = j.optJSONObject("data") ?: return CellLookup(null, "no data in answer")
            val lat = d.optDouble("lat", Double.NaN)
            val lon = if (d.has("lon")) d.optDouble("lon", Double.NaN) else d.optDouble("lan", Double.NaN) // it once spelled latitude "lan"
            if (lat.isNaN() || lon.isNaN()) return CellLookup(null, "no position in answer")
            CellLookup(CellFix(lat, lon, d.optInt("range", 1500), mcc, mnc, lac, cell, System.currentTimeMillis(), "Mylnikov"), "")
        } catch (e: java.net.UnknownHostException) { CellLookup(null, "no network")
        } catch (e: java.net.SocketTimeoutException) { CellLookup(null, "timed out")
        } catch (e: Exception) { CellLookup(null, "error ${e.javaClass.simpleName}")
        } finally { conn?.disconnect() }
    }

    /** BeaconDB / Ichnaea-style geolocate: POST the cell identity as JSON, get {"location":{"lat","lng"},"accuracy"}. */
    private fun beaconDb(mcc: String, mnc: String, lac: String, cell: String): CellLookup {
        val m = mcc.toIntOrNull(); val n = mnc.toIntOrNull(); val l = lac.toIntOrNull(); val c = cell.toLongOrNull()
        if (m == null || n == null || l == null || c == null) return CellLookup(null, "cell identity not numeric")
        var conn: java.net.HttpURLConnection? = null
        return try {
            val body = org.json.JSONObject().apply {
                put("considerIp", false)
                put("cellTowers", org.json.JSONArray().put(org.json.JSONObject().apply {
                    put("radioType", "gsm"); put("mobileCountryCode", m); put("mobileNetworkCode", n)
                    put("locationAreaCode", l); put("cellId", c)
                }))
            }.toString()
            conn = (java.net.URL("https://api.beacondb.net/v1/geolocate").openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 8000; readTimeout = 8000; requestMethod = "POST"; doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "RtlSdrAndroid/1.0 (GSM cell locator)")
            }
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty().trim()
            if (code == 404) return CellLookup(null, "cell not in its database")
            val j = try { org.json.JSONObject(text) } catch (_: Exception) { null }
                ?: return CellLookup(null, "HTTP $code ${text.take(60)}")
            val loc = j.optJSONObject("location") ?: return CellLookup(null, "no location in answer (HTTP $code)")
            val lat = loc.optDouble("lat", Double.NaN); val lon = loc.optDouble("lng", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) return CellLookup(null, "no position in answer")
            CellLookup(CellFix(lat, lon, j.optDouble("accuracy", 0.0).toInt(), mcc, mnc, lac, cell, System.currentTimeMillis(), "BeaconDB"), "")
        } catch (e: java.net.UnknownHostException) { CellLookup(null, "no network (cannot reach api.beacondb.net)")
        } catch (e: java.net.SocketTimeoutException) { CellLookup(null, "timed out")
        } catch (e: Exception) { CellLookup(null, "error ${e.javaClass.simpleName}")
        } finally { conn?.disconnect() }
    }

    /** Convenience wrapper: just the fix, or null. */
    fun lookup(apiKey: String, mcc: String, mnc: String, lac: String, cell: String): CellFix? =
        lookupDetailed(apiKey, mcc, mnc, lac, cell).fix

    /** Blocking HTTP call -- always run off the UI thread. OpenCellID wants plain numbers, so leading zeros
        ("01" for MNC) are stripped; on failure the reason is reported (no key, bad key, cell not in the
        database, no network...) instead of a generic "failed". */
    private fun openCellId(apiKey: String, mcc: String, mnc: String, lac: String, cell: String): CellLookup {
        if (apiKey.isBlank()) return CellLookup(null, "no OpenCellID API key set")
        if (mcc.isEmpty() || mnc.isEmpty() || lac.isEmpty() || cell.isEmpty()) return CellLookup(null, "serving cell not decoded yet")
        fun num(x: String) = x.trimStart('0').ifEmpty { "0" }
        var conn: java.net.HttpURLConnection? = null
        return try {
            val q = "key=${java.net.URLEncoder.encode(apiKey.trim(), "UTF-8")}&mcc=${num(mcc)}&mnc=${num(mnc)}&lac=${num(lac)}&cellid=${num(cell)}&format=json"
            conn = (java.net.URL("https://opencellid.org/cell/get?$q").openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 8000; readTimeout = 8000; requestMethod = "GET"
                setRequestProperty("User-Agent", "RtlSdrAndroid/1.0")
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty().trim()
            val j = try { org.json.JSONObject(body) } catch (_: Exception) { null }
            if (j == null) return CellLookup(null, "OpenCellID answered HTTP $code: ${body.take(80).ifEmpty { "(empty)" }}")
            if (j.has("error") || j.has("code") && !j.has("lat")) {
                return CellLookup(null, "OpenCellID: ${j.optString("error", "unknown error")} (cell $mcc-$mnc-$lac-$cell)")
            }
            val lat = j.optDouble("lat", Double.NaN)
            val lon = j.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) return CellLookup(null, "OpenCellID returned no position for this cell")
            CellLookup(CellFix(lat, lon, j.optInt("range", 0), mcc, mnc, lac, cell, System.currentTimeMillis()), "")
        } catch (e: java.net.UnknownHostException) { CellLookup(null, "no network (cannot reach opencellid.org)")
        } catch (e: java.net.SocketTimeoutException) { CellLookup(null, "OpenCellID timed out")
        } catch (e: Exception) { CellLookup(null, "lookup error: ${e.javaClass.simpleName}")
        } finally { conn?.disconnect() }
    }
}
