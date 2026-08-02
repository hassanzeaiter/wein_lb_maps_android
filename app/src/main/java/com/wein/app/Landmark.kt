package com.wein.app

import org.maplibre.android.geometry.LatLng

/**
 * A well-known Beirut landmark. In this demo the landmark list is the product's
 * core dataset — people here navigate by these, not by street addresses.
 */
data class Landmark(
    val name: String,
    val kind: String,   // mall, square, mosque, church, university, hospital, bank, seaside…
    val lat: Double,
    val lng: Double,
) {
    val latLng: LatLng get() = LatLng(lat, lng)
}

/**
 * Seed landmark graph for Greater Beirut. Coordinates are approximate but good
 * enough to demo real routing + landmark-phrased directions. Growing this list
 * (and its relationships) is the real, defensible work of the product.
 */
val BEIRUT_LANDMARKS: List<Landmark> = listOf(
    Landmark("AUB Main Gate", "university", 33.9008, 35.4820),
    Landmark("Hamra Street", "street", 33.8965, 35.4816),
    Landmark("Banque du Liban (BDL)", "bank", 33.8930, 35.4890),
    Landmark("Verdun – Dunes Center", "mall", 33.8790, 35.4870),
    Landmark("Raouché / Pigeon Rocks", "seaside", 33.8908, 35.4700),
    Landmark("Manara", "seaside", 33.8990, 35.4720),
    Landmark("Zaitunay Bay", "seaside", 33.9038, 35.4966),
    Landmark("Beirut Souks", "mall", 33.8977, 35.5065),
    Landmark("Martyrs' Square", "square", 33.8955, 35.5085),
    Landmark("Mohammad Al-Amin Mosque", "mosque", 33.8952, 35.5079),
    Landmark("Gemmayzeh – Rue Gouraud", "street", 33.8955, 35.5145),
    Landmark("Mar Mikhael", "street", 33.8985, 35.5240),
    Landmark("Charles Helou Station", "station", 33.8998, 35.5140),
    Landmark("ABC Mall Achrafieh", "mall", 33.8869, 35.5205),
    Landmark("Sassine Square", "square", 33.8862, 35.5178),
    Landmark("Sodeco Square", "square", 33.8843, 35.5133),
    Landmark("Hôtel-Dieu de France", "hospital", 33.8830, 35.5175),
    Landmark("Badaro", "street", 33.8760, 35.5185),
    Landmark("National Museum (Mathaf)", "museum", 33.8767, 35.5155),
    Landmark("Barbir", "junction", 33.8730, 35.5065),
    Landmark("Cola Intersection", "junction", 33.8710, 35.5000),
    Landmark("Tayouneh Roundabout", "junction", 33.8640, 35.5130),
    Landmark("HZ Home", "home", 33.854391519180474, 35.55533019638527),
    Landmark("OLX Lebanon", "office", 33.88167808748248, 35.54333166570173),
)
