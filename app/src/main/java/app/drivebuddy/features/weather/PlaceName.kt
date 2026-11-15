package app.drivebuddy.features.weather

import android.content.Context
import android.location.Geocoder
import android.util.Log
import java.util.Locale

/** "Bengaluru, Karnataka" for a position, using the phone's own geocoder. Blocking: call off the main thread. */
object PlaceName {
    private const val TAG = "DriveBuddy"

    fun of(context: Context, latitude: Double, longitude: Double): String? = try {
        if (!Geocoder.isPresent()) {
            Log.i(TAG, "weather: no geocoder on this phone, leaving the place off")
            null
        } else {
            @Suppress("DEPRECATION")
            val address = Geocoder(context, Locale.getDefault()).getFromLocation(latitude, longitude, 1)?.firstOrNull()
            val name = listOfNotNull(address?.locality ?: address?.subAdminArea, address?.adminArea)
                .distinct().joinToString(", ").ifBlank { null }
            Log.i(TAG, "weather: place ${if (name != null) "found" else "not found"}")
            name
        }
    } catch (e: Exception) {
        Log.w(TAG, "weather: place lookup failed: ${e.javaClass.simpleName}")
        null
    }
}