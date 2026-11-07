package app.drivebuddy.features.nearby

import android.util.Log
import app.drivebuddy.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Searches Google Places (Text Search) around a point. Call off the main thread. */
object NearbyClient {
    private const val TAG = "DriveBuddy"
    private const val ENDPOINT = "https://places.googleapis.com/v1/places:searchText"

    fun search(what: String, latitude: Double, longitude: Double): List<NearbyPlace>? {
        if (BuildConfig.MAPS_API_KEY.isBlank()) {
            Log.w(TAG, "places: MAPS_API_KEY is empty, add it to local.properties and sync")
            return null
        }
        val body = JSONObject()
            .put("textQuery", what)
            .put("languageCode", "en")
            .put("rankPreference", "DISTANCE")
            .put(
                "locationBias",
                JSONObject().put(
                    "circle",
                    JSONObject()
                        .put("center", JSONObject().put("latitude", latitude).put("longitude", longitude))
                        .put("radius", 5000.0),
                ),
            )
        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 6_000
            conn.readTimeout = 6_000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-Goog-Api-Key", BuildConfig.MAPS_API_KEY)
            conn.setRequestProperty("X-Goog-FieldMask", "places.displayName,places.location")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val ok = conn.responseCode in 200..299
            val text = (if (ok) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (!ok) {
                Log.w(TAG, "places: search failed ${conn.responseCode}: ${text.take(300)}")
                return null
            }
            val arr = JSONObject(text).optJSONArray("places")
            val out = ArrayList<NearbyPlace>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val p = arr.getJSONObject(i)
                    val name = p.optJSONObject("displayName")?.optString("text").orEmpty()
                    val loc = p.optJSONObject("location") ?: continue
                    if (name.isEmpty()) continue
                    out += NearbyPlace(name, loc.getDouble("latitude"), loc.getDouble("longitude"))
                }
            }
            Log.i(TAG, "places: search '$what' -> ${conn.responseCode}, ${out.size} result(s)")
            out
        } catch (e: Exception) {
            Log.w(TAG, "places: search failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }
}