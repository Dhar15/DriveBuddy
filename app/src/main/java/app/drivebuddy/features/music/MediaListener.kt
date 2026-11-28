package app.drivebuddy.features.music

import android.app.PendingIntent
import android.service.notification.NotificationListenerService
import android.util.Log
import app.drivebuddy.features.navigation.NavExit
import app.drivebuddy.features.navigation.NavExitResult
import android.app.Notification
import android.service.notification.StatusBarNotification
import app.drivebuddy.features.ride.RideButtons
import app.drivebuddy.features.ride.RideResult

/**
 * Gives DriveBuddy notification access (the music controls need it), and presses the Exit button
 * on Google Maps' navigation notification, since Maps has no other way to end a trip from outside.
 */
class MediaListener : NotificationListenerService() {

    companion object {
        private const val TAG = "DriveBuddy"
        private const val MAPS = "com.google.android.apps.maps"
        private const val QUICK_RIDE = "com.disha.quickride"

        @Volatile
        private var instance: MediaListener? = null

        fun exitNavigation(): NavExitResult = instance?.pressExit() ?: NavExitResult.NOT_CONNECTED
        fun startQuickRide(): RideResult = instance?.pressRideStart() ?: RideResult.NOT_CONNECTED
    }

    override fun onListenerConnected() {
        instance = this
        Log.i(TAG, "notification listener connected")
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        Log.i(TAG, "notification listener disconnected")
    }

    /** Probe: records which kinds of notifications Quick Ride posts and what buttons they carry. Never logs their text. */
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val posted = sbn ?: return
        if (posted.packageName != QUICK_RIDE) return
        val n = posted.notification
        val titleChars = n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.length ?: 0
        val buttons = n.actions.orEmpty().map { it.title }
        val ongoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0

        // The ongoing location notification is re-posted constantly and carries nothing useful.
        if (ongoing && buttons.isEmpty() && titleChars == 0) return

        Log.i(
            TAG,
            "ride: Quick Ride notification posted: titleChars=$titleChars buttons=$buttons " +
                    "ongoing=$ongoing channel=${n.channelId}"
        )
    }

    private fun pressRideStart(): RideResult {
        val ride = try {
            activeNotifications.filter { it.packageName == QUICK_RIDE }
        } catch (e: SecurityException) {
            Log.w(TAG, "ride: cannot read notifications: ${e.javaClass.simpleName}")
            return RideResult.NOT_CONNECTED
        }
        if (ride.isEmpty()) {
            Log.i(TAG, "ride: no Quick Ride notification, nothing to start")
            return RideResult.NO_NOTIFICATION
        }
        for (n in ride) {
            val actions = n.notification.actions.orEmpty()
            Log.i(TAG, "ride: Quick Ride notification buttons: ${actions.map { it.title }}")
            val start = actions.firstOrNull { RideButtons.isStartLabel(it.title) } ?: continue
            return try {
                start.actionIntent.send()
                Log.i(TAG, "ride: pressed \"${start.title}\"")
                RideResult.PRESSED
            } catch (e: PendingIntent.CanceledException) {
                Log.w(TAG, "ride: the Start button was no longer valid")
                RideResult.NO_BUTTON
            }
        }
        return RideResult.NO_BUTTON
    }

    private fun pressExit(): NavExitResult {
        val maps = try {
            activeNotifications.filter { it.packageName == MAPS }
        } catch (e: SecurityException) {
            Log.w(TAG, "navigation: cannot read notifications: ${e.javaClass.simpleName}")
            return NavExitResult.NOT_CONNECTED
        }
        if (maps.isEmpty()) {
            Log.i(TAG, "navigation: no Maps notification, nothing to exit")
            return NavExitResult.NO_NOTIFICATION
        }
        for (n in maps) {
            val actions = n.notification.actions.orEmpty()
            Log.i(TAG, "navigation: Maps notification buttons: ${actions.map { it.title }}")
            val exit = actions.firstOrNull { NavExit.isExitLabel(it.title) } ?: continue
            return try {
                exit.actionIntent.send()
                Log.i(TAG, "navigation: pressed \"${exit.title}\"")
                NavExitResult.PRESSED
            } catch (e: PendingIntent.CanceledException) {
                Log.w(TAG, "navigation: the Exit button was no longer valid")
                NavExitResult.NO_BUTTON
            }
        }
        return NavExitResult.NO_BUTTON
    }
}