package com.wein.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A community review from GET /places/:id/reviews. */
data class Review(val author: String, val rating: Int, val body: String)

/**
 * Thin client for the Wein backend directory API (GET /places).
 *
 * Blocking — call from a background dispatcher. Returns the directory as the app's [Place]
 * model so the rest of the app is unchanged. On any failure the caller falls back to the
 * bundled seed [PLACES], so the demo keeps working offline / when the API is down.
 */
object PlacesApi {

    fun fetchPlaces(query: String = "", category: String? = null): List<Place> {
        val params = buildList {
            if (query.isNotBlank()) add("q=" + URLEncoder.encode(query, "UTF-8"))
            if (category != null) add("category=$category")
        }.joinToString("&")
        val body = get("/places" + if (params.isEmpty()) "" else "?$params") ?: return emptyList()
        val arr = JSONObject(body).optJSONArray("places") ?: return emptyList()
        val out = ArrayList<Place>(arr.length())
        for (i in 0 until arr.length()) placeFromJson(arr.getJSONObject(i))?.let { out.add(it) }
        return out
    }

    /** Full detail for one place (GET /places/:id). Null if not found / unreachable. */
    fun fetchPlace(id: String): Place? {
        val body = get("/places/" + URLEncoder.encode(id, "UTF-8")) ?: return null
        return placeFromJson(JSONObject(body))
    }

    /** Published reviews for a place (GET /places/:id/reviews), newest first. */
    fun fetchReviews(placeId: String): List<Review> {
        val body = get("/places/" + URLEncoder.encode(placeId, "UTF-8") + "/reviews") ?: return emptyList()
        val arr = JSONObject(body).optJSONArray("reviews") ?: return emptyList()
        val out = ArrayList<Review>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                Review(
                    author = o.optString("author", ""),
                    rating = o.optInt("rating", 0),
                    body = o.optString("body", ""),
                )
            )
        }
        return out
    }

    /** GET a path; returns the response body on 2xx, else null. */
    private fun get(path: String): String? {
        val conn = (URL("$API_BASE_URL$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun placeFromJson(o: JSONObject): Place? {
        // Skip rows whose category the app doesn't know (forward-compatible).
        val cat = runCatching { PlaceCategory.valueOf(o.getString("category")) }.getOrNull() ?: return null
        return Place(
            name = o.getString("name"),
            category = cat,
            rating = o.getDouble("rating"),
            reviews = o.getInt("reviews"),
            price = o.getInt("price"),
            area = o.getString("area"),
            landmark = o.getString("landmark"),
            lat = o.getDouble("lat"),
            lng = o.getDouble("lng"),
            promoted = o.optBoolean("promoted", false),
            openNow = o.optBoolean("openNow", true),
            id = o.optString("id", ""),
            phone = if (o.isNull("phone")) null else o.optString("phone").ifBlank { null },
        )
    }
}
