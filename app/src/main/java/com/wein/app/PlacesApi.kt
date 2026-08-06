package com.wein.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A community review from GET /places/:id/reviews. */
data class Review(val author: String, val rating: Int, val body: String)

/** One of the signed-in user's own reviews, from GET /me/reviews. */
data class MyReview(
    val placeId: String,
    val placeName: String,
    val placeArea: String,
    val rating: Int,
    val body: String,
)

/** One of the signed-in user's uploaded photos, from GET /me/photos. */
data class MyPhoto(val placeId: String, val placeName: String, val r2Key: String)

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

    /**
     * Full detail for one place (GET /places/:id). Null if not found / unreachable.
     * Pass the session [token] to also learn whether the user has saved this place.
     */
    fun fetchPlace(id: String, token: String? = null): Place? {
        val body = get("/places/" + URLEncoder.encode(id, "UTF-8"), token) ?: return null
        return placeFromJson(JSONObject(body))
    }

    /**
     * The signed-in user's bookmarked places (GET /me/saved), newest-saved first.
     * Returns null if the request fails (so callers can tell "load error" from "no saves").
     */
    fun fetchSaved(token: String): List<Place>? {
        val body = get("/me/saved", token) ?: return null
        val arr = JSONObject(body).optJSONArray("places") ?: return emptyList()
        val out = ArrayList<Place>(arr.length())
        for (i in 0 until arr.length()) placeFromJson(arr.getJSONObject(i))?.let { out.add(it) }
        return out
    }

    /** Bookmark (save=true) or un-bookmark a place. Returns true on success. */
    fun setSaved(placeId: String, save: Boolean, token: String): Boolean {
        val url = URL("$API_BASE_URL/places/" + URLEncoder.encode(placeId, "UTF-8") + "/save")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = if (save) "POST" else "DELETE"
            connectTimeout = 8000; readTimeout = 8000
            setRequestProperty("Authorization", "Bearer $token")
        }
        return try {
            conn.responseCode in 200..299
        } catch (e: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }

    /** The signed-in user's own reviews (GET /me/reviews), newest first. Null on request failure. */
    fun fetchMyReviews(token: String): List<MyReview>? {
        val body = get("/me/reviews", token) ?: return null
        val arr = JSONObject(body).optJSONArray("reviews") ?: return emptyList()
        val out = ArrayList<MyReview>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                MyReview(
                    placeId = o.optString("placeId", ""),
                    placeName = o.optString("placeName", ""),
                    placeArea = o.optString("placeArea", ""),
                    rating = o.optInt("rating", 0),
                    body = o.optString("body", ""),
                )
            )
        }
        return out
    }

    /** The signed-in user's uploaded photos (GET /me/photos), newest first. Null on request failure. */
    fun fetchMyPhotos(token: String): List<MyPhoto>? {
        val body = get("/me/photos", token) ?: return null
        val arr = JSONObject(body).optJSONArray("photos") ?: return emptyList()
        val out = ArrayList<MyPhoto>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                MyPhoto(
                    placeId = o.optString("placeId", ""),
                    placeName = o.optString("placeName", ""),
                    r2Key = o.optString("r2Key", ""),
                )
            )
        }
        return out
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

    /** Post (or update) the signed-in user's review. Returns true on success. */
    fun postReview(placeId: String, rating: Int, body: String, token: String): Boolean {
        val url = URL("$API_BASE_URL/places/" + URLEncoder.encode(placeId, "UTF-8") + "/reviews")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 8000; readTimeout = 8000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
        }
        return try {
            conn.outputStream.use { it.write(JSONObject().put("rating", rating).put("body", body).toString().toByteArray()) }
            conn.responseCode in 200..299
        } catch (e: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }

    /** GET a path; returns the response body on 2xx, else null. Sends a bearer [token] if given. */
    private fun get(path: String, token: String? = null): String? {
        val conn = (URL("$API_BASE_URL$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
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
            saved = o.optBoolean("saved", false),
        )
    }
}
