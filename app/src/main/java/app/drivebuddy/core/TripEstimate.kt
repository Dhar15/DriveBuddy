package app.drivebuddy.core

import kotlin.math.max
import kotlin.math.roundToInt

/** A rough drive time from a straight-line distance. Not a live ETA. */
object TripEstimate {
    private const val CITY_SPEED_KMH = 25.0

    fun byCar(meters: Double): String {
        val minutes = max(1, (meters / 1000.0 / CITY_SPEED_KMH * 60).roundToInt())
        return if (minutes == 1) "About 1 min by car" else "About $minutes min by car"
    }
}