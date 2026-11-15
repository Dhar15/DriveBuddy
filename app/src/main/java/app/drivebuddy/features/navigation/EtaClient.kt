package app.drivebuddy.features.navigation

import android.util.Log
import app.drivebuddy.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Asks Google's Routes API how long the drive takes. Call off the main thread. Returns null on any problem. */
object EtaClient {
    private const val TAG = "DriveBuddy"
    private const val ENDPOINT = "https://routes.googleapis.com/directions/v2:computeRoutes"

    /** true = live traffic (billed as Compute Routes Pro); false = free-flow time (Essentials). */
    private const val TRAFFIC = true

    fun driveSeconds(fromLat: Double, fromLng: Double, destination: String): Int? {
        if (BuildConfig.MAPS_API_KEY.isBlank()) {
            Log.w(TAG, "eta: MAPS_API_KEY is empty")
            return null
        }
        val to = EtaText.latLng(destination)
        val destinationJson = if (to != null) {
            JSONObject().put("location", JSONObject().put("latLng", point(to.first, to.second)))
        } else {
            JSONObject().put("address", destination)
        }
        val body = JSONObject()
            .put("origin", JSONObject().put("location", JSONObject().put("latLng", point(fromLat, fromLng))))
            .put("destination", destinationJson)
            .put("travelMode", "DRIVE")
            .put("routingPreference", if (TRAFFIC) "TRAFFIC_AWARE" else "TRAFFIC_UNAWARE")
        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 3_000
            conn.readTimeout = 3_000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-Goog-Api-Key", BuildConfig.MAPS_API_KEY)
            conn.setRequestProperty("X-Goog-FieldMask", "routes.duration")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val ok = conn.responseCode in 200..299
            val text = (if (ok) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (!ok) {
                Log.w(TAG, "eta: lookup failed ${conn.responseCode}: ${errorSummary(text)}")
                return null
            }
            val duration = JSONObject(text).optJSONArray("routes")?.optJSONObject(0)?.optString("duration").orEmpty()
            val seconds = EtaText.parseSeconds(duration)
            Log.i(TAG, "eta: lookup ${conn.responseCode} -> ${seconds ?: "no route"} s (traffic=$TRAFFIC)")
            seconds
        } catch (e: Exception) {
            Log.w(TAG, "eta: lookup failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun point(lat: Double, lng: Double) = JSONObject().put("latitude", lat).put("longitude", lng)

    /** One readable line from Google's error JSON: status, message and the reason. */
    private fun errorSummary(text: String): String = try {
        val e = JSONObject(text).getJSONObject("error")
        val d = e.optJSONArray("details")?.optJSONObject(0)
        "${e.optString("status")} ${e.optString("message")} reason=${d?.optString("reason")} meta=${d?.optJSONObject("metadata")}"
    } catch (ex: Exception) {
        text.take(600).replace("\n", " ")
    }
}