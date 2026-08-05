package com.wein.app

import org.json.JSONObject
import org.maplibre.geojson.Point
import java.net.HttpURLConnection
import java.net.URL

/**
 * OSRM routing: fetch a driving/walking route between two points and parse it into
 * a [RouteResult]. No Android UI, map or Activity state — a plain network + JSON layer
 * (safe to call off the main thread; that's the caller's job).
 */

internal data class Maneuver(
    val lat: Double, val lng: Double,
    val type: String, val modifier: String, val name: String,
)

internal data class RouteResult(
    val geometry: List<Point>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val steps: List<Maneuver>,
)

internal fun fetchRoute(o: Landmark, d: Landmark, mode: TravelMode): RouteResult? {
    return try {
        val url = URL(
            mode.endpoint +
                "${o.lng},${o.lat};${d.lng},${d.lat}" +
                "?overview=full&geometries=geojson&steps=true"
        )
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 12000
            requestMethod = "GET"
            instanceFollowRedirects = true
            // FOSSGIS' public OSRM rejects the default Dalvik user-agent.
            setRequestProperty("User-Agent", "WeinDemo/0.1 (Lebanon landmark maps demo)")
            setRequestProperty("Accept", "application/json")
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        android.util.Log.i("Wein", "OSRM HTTP $code, ${body.length} bytes")
        parseRoute(body)
    } catch (e: Exception) {
        android.util.Log.e("Wein", "route fetch failed", e)
        null
    }
}

private fun parseRoute(body: String): RouteResult? {
    val json = JSONObject(body)
    val routes = json.optJSONArray("routes") ?: return null
    if (routes.length() == 0) return null
    val route = routes.getJSONObject(0)

    val coords = route.getJSONObject("geometry").getJSONArray("coordinates")
    val points = ArrayList<Point>(coords.length())
    for (i in 0 until coords.length()) {
        val c = coords.getJSONArray(i)
        points.add(Point.fromLngLat(c.getDouble(0), c.getDouble(1)))
    }

    val maneuvers = ArrayList<Maneuver>()
    val legs = route.optJSONArray("legs")
    if (legs != null && legs.length() > 0) {
        val steps = legs.getJSONObject(0).optJSONArray("steps")
        if (steps != null) {
            for (i in 0 until steps.length()) {
                val step = steps.getJSONObject(i)
                val man = step.getJSONObject("maneuver")
                val loc = man.getJSONArray("location")
                maneuvers.add(
                    Maneuver(
                        lat = loc.getDouble(1),
                        lng = loc.getDouble(0),
                        type = man.optString("type", ""),
                        modifier = man.optString("modifier", ""),
                        name = step.optString("name", ""),
                    )
                )
            }
        }
    }
    return RouteResult(
        geometry = points,
        distanceMeters = route.optDouble("distance", 0.0),
        durationSeconds = route.optDouble("duration", 0.0),
        steps = maneuvers,
    )
}
