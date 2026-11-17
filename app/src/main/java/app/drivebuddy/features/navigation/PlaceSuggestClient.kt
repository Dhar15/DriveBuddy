package app.drivebuddy.features.navigation

import android.util.Log
import app.drivebuddy.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One line of the dropdown: a short [title], the area under it, and the full [text] to put in the field. */
data class PlaceSuggestion(val placeId: String, val title: String, val subtitle: String, val text: String)

/** The exact point of a picked suggestion. */
data class PlaceDetail(val lat: Double, val lng: Double, val address: String)

/** Google Places Autocomplete (New) and Place Details. Call off the main thread. Returns null on any problem. */
object PlaceSuggestClient {
    private const val TAG = "DriveBuddy"
    private const val AUTOCOMPLETE = "https://places.googleapis.com/v1/places:autocomplete"
    private const val DETAILS = "https://places.googleapis.com/v1/places/"

    /** [token] groups one typing-and-picking session into a single billed lookup. */
    fun suggest(input: String, token: String, biasLat: Double?, biasLng: Double?): List<PlaceSuggestion>? {
        val body = JSONObject()
            .put("input", input)
            .put("sessionToken", token)
            .put("languageCode", "en")
            .put("includedRegionCodes", JSONArray().put("in"))
        if (biasLat != null && biasLng != null) {
            body.put(
                "locationBias",
                JSONObject().put(
                    "circle",
                    JSONObject()
                        .put("center", JSONObject().put("latitude", biasLat).put("longitude", biasLng))
                        .put("radius", 50_000.0),
                ),
            )
        }
        val mask = "suggestions.placePrediction.placeId,suggestions.placePrediction.text.text," +
                "suggestions.placePrediction.structuredFormat.mainText.text," +
                "suggestions.placePrediction.structuredFormat.secondaryText.text"
        val (code, text) = call(AUTOCOMPLETE, "POST", body.toString(), mask) ?: return null
        if (code !in 200..299) {
            Log.w(TAG, "suggest: failed $code: ${text.take(200)}")
            return null
        }
        return try {
            val arr = JSONObject(text).optJSONArray("suggestions")
            val out = ArrayList<PlaceSuggestion>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val p = arr.getJSONObject(i).optJSONObject("placePrediction") ?: continue
                    val id = p.optString("placeId")
                    if (id.isEmpty()) continue
                    val full = p.optJSONObject("text")?.optString("text").orEmpty()
                    val sf = p.optJSONObject("structuredFormat")
                    val main = sf?.optJSONObject("mainText")?.optString("text").orEmpty().ifEmpty { full }
                    val second = sf?.optJSONObject("secondaryText")?.optString("text").orEmpty()
                    out += PlaceSuggestion(id, main, second, full.ifEmpty { main })
                }
            }
            Log.i(TAG, "suggest: ${input.length} chars -> $code, ${out.size} suggestion(s)")
            out
        } catch (e: Exception) {
            Log.w(TAG, "suggest: unreadable answer: ${e.javaClass.simpleName}")
            null
        }
    }

    fun details(placeId: String, token: String): PlaceDetail? {
        val (code, text) = call("$DETAILS$placeId?sessionToken=$token", "GET", null, "location,formattedAddress") ?: return null
        if (code !in 200..299) {
            Log.w(TAG, "suggest: details failed $code: ${text.take(200)}")
            return null
        }
        return try {
            val json = JSONObject(text)
            val loc = json.getJSONObject("location")
            Log.i(TAG, "suggest: details -> $code, exact point found")
            PlaceDetail(loc.getDouble("latitude"), loc.getDouble("longitude"), json.optString("formattedAddress"))
        } catch (e: Exception) {
            Log.w(TAG, "suggest: details unreadable: ${e.javaClass.simpleName}")
            null
        }
    }

    /** One request to the Places API; returns the status code and body, or null when it could not be made. */
    private fun call(url: String, method: String, body: String?, fieldMask: String): Pair<Int, String>? {
        if (BuildConfig.MAPS_API_KEY.isBlank()) {
            Log.w(TAG, "suggest: MAPS_API_KEY is empty, add it to local.properties and sync")
            return null
        }
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = method
            conn.connectTimeout = 4_000
            conn.readTimeout = 4_000
            conn.setRequestProperty("X-Goog-Api-Key", BuildConfig.MAPS_API_KEY)
            conn.setRequestProperty("X-Goog-FieldMask", fieldMask)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            code to text
        } catch (e: Exception) {
            Log.w(TAG, "suggest: request failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }
}