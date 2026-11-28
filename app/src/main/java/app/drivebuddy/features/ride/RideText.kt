package app.drivebuddy.features.ride

/** What DriveBuddy says for each outcome of pressing Start. Pure Kotlin, so it is unit-tested. */
object RideText {
    fun forResult(result: RideResult): String = when (result) {
        RideResult.PRESSED -> "Starting your ride."
        RideResult.NOT_CONNECTED -> "I can't see Quick Ride's notifications. Please check that notification access is on for DriveBuddy."
        RideResult.NO_NOTIFICATION -> "I don't see a Quick Ride start prompt. Is the ride scheduled yet?"
        RideResult.NO_BUTTON -> "Quick Ride is showing a notification, but I can't find its start button."
    }
}