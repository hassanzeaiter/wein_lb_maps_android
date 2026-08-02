package com.wein.app

import org.maplibre.android.geometry.LatLng

/**
 * A place in the directory — the product's core model. Later gains photos, reviews,
 * comments, promoted flags, business claims, etc. For the MVP this is seed data.
 */
enum class PlaceCategory(val label: String, val glyph: Int) {
    RESTAURANT("Restaurant", R.drawable.ic_cat_restaurant),
    CAFE("Café", R.drawable.ic_cat_cafe),
    BAKERY("Bakery", R.drawable.ic_cat_cafe),
    BAR("Bar", R.drawable.ic_cat_nightlife),
    NIGHTCLUB("Nightclub", R.drawable.ic_cat_nightlife),
    CINEMA("Cinema", R.drawable.ic_cat_nightlife),
    GAS("Gas station", R.drawable.ic_cat_gas),
    PHARMACY("Pharmacy", R.drawable.ic_cat_health),
    HOSPITAL("Hospital", R.drawable.ic_cat_health),
    ATM("ATM · Bank", R.drawable.ic_cat_atm),
    GROCERY("Grocery", R.drawable.ic_cat_shopping),
    MALL("Mall", R.drawable.ic_cat_shopping),
    SALON("Salon", R.drawable.ic_cat_shopping),
    HOTEL("Hotel", R.drawable.ic_cat_hotel),
    GUESTHOUSE("Guesthouse", R.drawable.ic_cat_hotel),
    RESORT("Beach resort", R.drawable.ic_cat_water),
    BEACH("Beach", R.drawable.ic_cat_water),
    GYM("Gym", R.drawable.ic_cat_gym),
    HOME("Home", R.drawable.ic_cat_home),
    OFFICE("Office", R.drawable.ic_cat_office),
}

data class Place(
    val name: String,
    val category: PlaceCategory,
    val rating: Double,
    val reviews: Int,
    val price: Int,        // 0 = n/a, 1..3 = $ .. $$$
    val area: String,      // neighbourhood
    val landmark: String,  // Lebanese-style location cue
    val lat: Double,
    val lng: Double,
    val promoted: Boolean = false,
    val openNow: Boolean = true,
) {
    val latLng: LatLng get() = LatLng(lat, lng)
    val priceText: String get() = if (price <= 0) "" else "$".repeat(price)
}

/** A quick-filter chip. `cats == null` means "All". */
data class PlaceChip(val label: String, val glyph: Int?, val cats: Set<PlaceCategory>?)

val PLACE_CHIPS: List<PlaceChip> = listOf(
    PlaceChip("All", null, null),
    PlaceChip("Restaurants", R.drawable.ic_cat_restaurant, setOf(PlaceCategory.RESTAURANT)),
    PlaceChip("Cafés", R.drawable.ic_cat_cafe, setOf(PlaceCategory.CAFE, PlaceCategory.BAKERY)),
    PlaceChip("Hotels", R.drawable.ic_cat_hotel, setOf(PlaceCategory.HOTEL, PlaceCategory.GUESTHOUSE)),
    PlaceChip("Gas", R.drawable.ic_cat_gas, setOf(PlaceCategory.GAS)),
    PlaceChip("Pharmacies", R.drawable.ic_cat_health, setOf(PlaceCategory.PHARMACY)),
    PlaceChip("ATMs", R.drawable.ic_cat_atm, setOf(PlaceCategory.ATM)),
    PlaceChip("Beaches", R.drawable.ic_cat_water, setOf(PlaceCategory.BEACH, PlaceCategory.RESORT)),
    PlaceChip("Nightlife", R.drawable.ic_cat_nightlife, setOf(PlaceCategory.BAR, PlaceCategory.NIGHTCLUB, PlaceCategory.CINEMA)),
    PlaceChip("Shopping", R.drawable.ic_cat_shopping, setOf(PlaceCategory.MALL, PlaceCategory.GROCERY, PlaceCategory.SALON)),
    PlaceChip("Hospitals", R.drawable.ic_cat_health, setOf(PlaceCategory.HOSPITAL)),
    PlaceChip("Gyms", R.drawable.ic_cat_gym, setOf(PlaceCategory.GYM)),
)

val PLACES: List<Place> = listOf(
    // Food & drink
    Place("Em Sherif", PlaceCategory.RESTAURANT, 4.7, 1840, 3, "Achrafieh", "Off Damascus Rd, near Sioufi", 33.8846, 35.5199, promoted = true),
    Place("Tawlet", PlaceCategory.RESTAURANT, 4.6, 1220, 2, "Mar Mikhael", "Naher St, near the railway", 33.8987, 35.5236),
    Place("Al Falamanki", PlaceCategory.RESTAURANT, 4.5, 2600, 2, "Sodeco", "Next to Sodeco Square", 33.8848, 35.5138),
    Place("Enab", PlaceCategory.RESTAURANT, 4.4, 980, 2, "Mar Mikhael", "Armenia St, above the pharmacy", 33.8979, 35.5219),
    Place("Babel Bay", PlaceCategory.RESTAURANT, 4.5, 1510, 3, "Zaitunay Bay", "On the marina boardwalk", 33.9041, 35.4972, promoted = true),
    Place("Le Chef", PlaceCategory.RESTAURANT, 4.6, 760, 1, "Gemmayzeh", "Gouraud St, near Saint Nicolas stairs", 33.8958, 35.5150),
    Place("Café Younes", PlaceCategory.CAFE, 4.6, 1340, 1, "Hamra", "Nehme Yafet St, off Hamra", 33.8969, 35.4823, promoted = true),
    Place("Kalei Coffee Co.", PlaceCategory.CAFE, 4.7, 890, 2, "Mar Mikhael", "Pharaon St, behind the church", 33.8975, 35.5228),
    Place("Urbanista", PlaceCategory.CAFE, 4.3, 1120, 2, "Hamra", "Makdessi St, near Costa", 33.8972, 35.4808),
    Place("Sip", PlaceCategory.CAFE, 4.2, 540, 1, "Achrafieh", "Near Sassine Square", 33.8863, 35.5175),
    Place("Wooden Bakery", PlaceCategory.BAKERY, 4.3, 2100, 1, "Verdun", "Verdun St, near Dunes", 33.8793, 35.4869),

    // Nightlife
    Place("Anise", PlaceCategory.BAR, 4.5, 430, 2, "Mar Mikhael", "Armenia St, above Enab", 33.8981, 35.5222),
    Place("Central Station", PlaceCategory.BAR, 4.4, 610, 2, "Gemmayzeh", "Gouraud St, near the stairs", 33.8956, 35.5142),
    Place("The Grand Factory", PlaceCategory.NIGHTCLUB, 4.5, 980, 3, "Karantina", "Rooftop, near the port", 33.9008, 35.5290),
    Place("Cinemacity", PlaceCategory.CINEMA, 4.4, 3200, 2, "Downtown", "Beirut Souks, level 2", 33.8981, 35.5062),

    // Everyday essentials
    Place("Medco Station", PlaceCategory.GAS, 4.1, 210, 0, "Hamra", "Below BLC Bank, Sadat St", 33.8938, 35.4842),
    Place("Total Achrafieh", PlaceCategory.GAS, 4.0, 180, 0, "Achrafieh", "On Independence St", 33.8880, 35.5188),
    Place("IPT Badaro", PlaceCategory.GAS, 4.2, 150, 0, "Badaro", "Badaro St, near the park", 33.8752, 35.5182),
    Place("Mazen Pharmacy", PlaceCategory.PHARMACY, 4.4, 320, 0, "Hamra", "Hamra St, next to Librairie", 33.8965, 35.4818, openNow = true),
    Place("Pharmacy 24/7", PlaceCategory.PHARMACY, 4.3, 260, 0, "Achrafieh", "Near ABC Mall, always open", 33.8872, 35.5202),
    Place("BLOM Bank ATM", PlaceCategory.ATM, 4.0, 90, 0, "Hamra", "Hamra St, near the crossing", 33.8967, 35.4830),
    Place("Bank Audi", PlaceCategory.ATM, 4.1, 130, 0, "Achrafieh", "Sassine Square", 33.8861, 35.5178),
    Place("Spinneys", PlaceCategory.GROCERY, 4.3, 1450, 2, "Achrafieh", "Below ABC, Sofil Center", 33.8885, 35.5210),

    // Health
    Place("AUB Medical Center", PlaceCategory.HOSPITAL, 4.5, 2400, 0, "Hamra", "Cairo St, AUBMC gate", 33.8990, 35.4838),
    Place("Hôtel-Dieu de France", PlaceCategory.HOSPITAL, 4.3, 1800, 0, "Achrafieh", "Alfred Naccache Ave", 33.8830, 35.5175),

    // Shopping / services
    Place("ABC Achrafieh", PlaceCategory.MALL, 4.5, 8600, 2, "Achrafieh", "Sassine, the big mall", 33.8869, 35.5205, promoted = true),
    Place("Beirut Souks", PlaceCategory.MALL, 4.4, 5400, 2, "Downtown", "Downtown, near the mosque", 33.8977, 35.5065),
    Place("Fitness Zone", PlaceCategory.GYM, 4.2, 420, 2, "Achrafieh", "Sodeco, above the bank", 33.8846, 35.5140),

    // Stays
    Place("Phoenicia Hotel", PlaceCategory.HOTEL, 4.6, 3100, 3, "Downtown", "Minet El Hosn, near Zaitunay", 33.9026, 35.4949, promoted = true),
    Place("Le Gray", PlaceCategory.HOTEL, 4.7, 1900, 3, "Downtown", "Martyrs' Square side", 33.8961, 35.5079),
    Place("Baffa House", PlaceCategory.GUESTHOUSE, 4.5, 210, 2, "Mar Mikhael", "Quiet lane off Armenia St", 33.8983, 35.5231),

    // Beaches / resorts
    Place("Sporting Club", PlaceCategory.BEACH, 4.2, 1600, 2, "Manara", "On the Corniche, below Raouché", 33.8930, 35.4735),
    Place("Riviera Beach", PlaceCategory.RESORT, 4.1, 1300, 3, "Manara", "Corniche, at the Riviera hotel", 33.8955, 35.4780),

    // Personal pins
    Place("HZ Home", PlaceCategory.HOME, 5.0, 1, 0, "Beirut", "HZ's home", 33.854391519180474, 35.55533019638527),
    Place("OLX Lebanon", PlaceCategory.OFFICE, 5.0, 1, 0, "Beirut", "OLX Lebanon office", 33.88167808748248, 35.54333166570173),
)
