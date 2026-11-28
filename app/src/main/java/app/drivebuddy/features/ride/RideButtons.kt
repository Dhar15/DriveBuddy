package app.drivebuddy.features.ride

/** What happened when we tried to press a button on another app's ride notification. */
enum class RideResult { PRESSED, NOT_CONNECTED, NO_NOTIFICATION, NO_BUTTON }

object RideButtons {
    /** The "Start" button on Quick Ride's "Start ride" reminder. "Reschedule" must never match. */
    fun isStartLabel(label: CharSequence?): Boolean {
        val t = label?.toString()?.trim()?.lowercase() ?: return false
        return t == "start" || t == "start ride" || t == "start now"
    }
}