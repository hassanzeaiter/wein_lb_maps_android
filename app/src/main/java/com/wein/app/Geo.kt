package com.wein.app

import org.maplibre.android.geometry.LatLng
import org.maplibre.geojson.Point
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure geodesic / route-geometry math — no Android, map or Activity state.
 *
 * These are top-level functions in the `com.wein.app` package, so every call site
 * in [MainActivity] reads exactly as before (`haversine(...)`, `snapToRoute(...)`);
 * they were lifted out of the Activity verbatim, keeping behaviour identical while
 * making the map/nav math independently testable.
 */

internal data class SnapResult(
    val pos: LatLng, val distAlong: Double, val bearing: Double, val offRoute: Double,
)

internal fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
        sin(dLon / 2) * sin(dLon / 2)
    return r * 2 * atan2(sqrt(a), sqrt(1 - a))
}

/** Cumulative metre distance to each vertex of the route polyline. */
internal fun cumulative(path: List<Point>): DoubleArray {
    val c = DoubleArray(path.size)
    for (i in 1 until path.size) {
        c[i] = c[i - 1] + haversine(
            path[i - 1].latitude(), path[i - 1].longitude(),
            path[i].latitude(), path[i].longitude(),
        )
    }
    return c
}

/** Distance along the route of the polyline vertex nearest to a maneuver point. */
internal fun distAlong(path: List<Point>, cum: DoubleArray, lat: Double, lng: Double): Double {
    var bestIdx = 0
    var bestD = Double.MAX_VALUE
    for (i in path.indices) {
        val d = haversine(lat, lng, path[i].latitude(), path[i].longitude())
        if (d < bestD) { bestD = d; bestIdx = i }
    }
    return cum[bestIdx]
}

/** Project a live GPS point onto the route: nearest point on the polyline, how far
 *  along the route that is, the segment's travel bearing, and the off-route distance. */
internal fun snapToRoute(path: List<Point>, cum: DoubleArray, p: LatLng): SnapResult {
    var bestD = Double.MAX_VALUE
    var bestPos = LatLng(path[0].latitude(), path[0].longitude())
    var bestDist = 0.0
    var bestBrg = bearingBetween(path[0], path[1])
    for (i in 0 until path.size - 1) {
        val a = path[i]; val b = path[i + 1]
        val (proj, t) = projectOnSegment(p, a, b)
        val d = haversine(p.latitude, p.longitude, proj.latitude, proj.longitude)
        if (d < bestD) {
            bestD = d
            bestPos = proj
            bestDist = cum[i] + (cum[i + 1] - cum[i]) * t
            bestBrg = bearingBetween(a, b)
        }
    }
    return SnapResult(bestPos, bestDist, bestBrg, bestD)
}

/** The point on the route at a given distance along it (used to place the puck at
 *  our monotonic progress, so it never skips backwards on a jittery fix). */
internal fun positionAt(path: List<Point>, cum: DoubleArray, dist: Double): LatLng {
    if (dist <= 0.0) return LatLng(path[0].latitude(), path[0].longitude())
    val total = cum.last()
    if (dist >= total) return LatLng(path.last().latitude(), path.last().longitude())
    var i = 0
    while (i < cum.size - 1 && cum[i + 1] < dist) i++
    val f = ((dist - cum[i]) / (cum[i + 1] - cum[i]).coerceAtLeast(1e-6)).coerceIn(0.0, 1.0)
    val a = path[i]; val b = path[i + 1]
    return LatLng(
        a.latitude() + (b.latitude() - a.latitude()) * f,
        a.longitude() + (b.longitude() - a.longitude()) * f,
    )
}

/** Closest point on segment a→b to p (planar approx), plus the 0..1 position along it. */
internal fun projectOnSegment(p: LatLng, a: Point, b: Point): Pair<LatLng, Double> {
    val mPerDegLat = 111_320.0
    val mPerDegLng = 111_320.0 * cos(Math.toRadians(a.latitude()))
    val bx = (b.longitude() - a.longitude()) * mPerDegLng
    val by = (b.latitude() - a.latitude()) * mPerDegLat
    val px = (p.longitude - a.longitude()) * mPerDegLng
    val py = (p.latitude - a.latitude()) * mPerDegLat
    val len2 = bx * bx + by * by
    val t = if (len2 <= 1e-9) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
    val lat = a.latitude() + (b.latitude() - a.latitude()) * t
    val lng = a.longitude() + (b.longitude() - a.longitude()) * t
    return LatLng(lat, lng) to t
}

internal fun bearingBetween(a: Point, b: Point): Double {
    val lat1 = Math.toRadians(a.latitude())
    val lat2 = Math.toRadians(b.latitude())
    val dLon = Math.toRadians(b.longitude() - a.longitude())
    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

/** Interpolate between two compass bearings along the shortest arc. */
internal fun lerpAngle(from: Double, to: Double, t: Double): Double {
    val diff = ((to - from + 540.0) % 360.0) - 180.0
    return (from + diff * t + 360.0) % 360.0
}

/** Point [dist] metres from (lat,lng) along [bearingDeg] — used to aim ahead of the puck. */
internal fun destinationPoint(lat: Double, lng: Double, bearingDeg: Double, dist: Double): LatLng {
    val r = 6371000.0
    val br = Math.toRadians(bearingDeg)
    val lat1 = Math.toRadians(lat)
    val lng1 = Math.toRadians(lng)
    val dr = dist / r
    val lat2 = asin(sin(lat1) * cos(dr) + cos(lat1) * sin(dr) * cos(br))
    val lng2 = lng1 + atan2(sin(br) * sin(dr) * cos(lat1), cos(dr) - sin(lat1) * sin(lat2))
    return LatLng(Math.toDegrees(lat2), Math.toDegrees(lng2))
}

internal fun formatDist(m: Double): String =
    if (m >= 1000) "%.1f km".format(m / 1000.0) else "${m.roundToInt()} m"
