package app.drivebuddy.features.weather

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Asks Open-Meteo (open source, no API key) for the weather at roughly the given spot. Call off the main thread. */
object WeatherClient {
    private const val TAG = "DriveBuddy"

    fun fetch(latitude: Double, longitude: Double): WeatherNow? {
        // Rounded to ~1 km so the service never sees the exact position.
        val lat = String.format(Locale.US, "%.2f", latitude)
        val lon = String.format(Locale.US, "%.2f", longitude)
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,apparent_temperature,weather_code" +
                "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&timezone=auto&forecast_days=1"
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 6_000
            conn.readTimeout = 6_000
            val ok = conn.responseCode in 200..299
            val text = (if (ok) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            Log.i(TAG, "weather: open-meteo -> ${conn.responseCode}")
            if (!ok) {
                Log.w(TAG, "weather: request failed: ${text.take(200)}")
                return null
            }
            val json = JSONObject(text)
            val cur = json.getJSONObject("current")
            val daily = json.getJSONObject("daily")
            val rain = daily.optJSONArray("precipitation_probability_max")?.let { if (it.isNull(0)) null else it.getInt(0) }
            val result = WeatherNow(
                tempC = cur.getDouble("temperature_2m"),
                feelsC = cur.getDouble("apparent_temperature"),
                code = cur.getInt("weather_code"),
                highC = daily.getJSONArray("temperature_2m_max").getDouble(0),
                lowC = daily.getJSONArray("temperature_2m_min").getDouble(0),
                rainPercent = rain,
            )
            Log.i(TAG, "weather: parsed code=${result.code} rain=${result.rainPercent}")
            result
        } catch (e: Exception) {
            Log.w(TAG, "weather: failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }
}