package com.wein.app

/**
 * Travel modes. Drive/Walk hit real OSRM profiles; Taxi rides on the same car
 * network — the difference is the fare + request flow, not the routing.
 * (Real transit routing would need a GTFS feed, which is future work.)
 */
internal enum class TravelMode(
    val label: String,
    val endpoint: String,
    val mps: Double,        // simulated travel speed (metres/second) — keeps turns evenly paced
    val camZoom: Double,
    val suffix: String,
) {
    DRIVE("Drive", "https://routing.openstreetmap.de/routed-car/route/v1/driving/", 8.0, 17.0, "by car"),
    WALK("Walk", "https://routing.openstreetmap.de/routed-foot/route/v1/foot/", 1.4, 18.0, "on foot"),
    TAXI("Taxi", "https://routing.openstreetmap.de/routed-car/route/v1/driving/", 8.0, 17.0, "by taxi"),
}
