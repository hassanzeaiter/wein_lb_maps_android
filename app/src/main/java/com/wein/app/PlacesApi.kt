package com.wein.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Thin client for the Wein backend directory API (GET /places).
 *
 * Blocking — call from a background dispatcher. Returns the directory as the app's [Place]
 * model so the rest of the app is unchanged. On any failure the caller falls back to the
 * bundled seed [PLACES], so the demo keeps working offline / when the API is down.
 */
object PlacesApi {

    /**
     * Base URL of the backend.
     * - `10.0.2.2` is the Android emulator's alias for the host machine's localhost, so this
     *   reaches a local `wrangler dev` on :8787.
     * - For a real device, point this at the deployed Worker URL.
     */
    private const val BASE_URL = "http://10.0.2.2:8787"

    fun fetchPlaces(query: String = "", category: String? = null): List<Place> {
        val params = buildList {
            if (query.isNotBlank()) add("q=" + URLEncoder.encode(query, "UTF-8"))
            if (category != null) add("category=$category")
        }.joinToString("&")
        val url = URL("$BASE_URL/places" + if (params.isEmpty()) "" else "?$params")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) return emptyList()
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            parse(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(body: String): List<Place> {
        val arr = JSONObject(body).optJSONArray("places") ?: return emptyList()
        val out = ArrayList<Place>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            // Skip rows whose category the app doesn't know (forward-compatible).
            val cat = runCatching { PlaceCategory.valueOf(o.getString("category")) }.getOrNull() ?: continue
            out.add(
                Place(
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
                )
            )
        }
        return out
    }
}
