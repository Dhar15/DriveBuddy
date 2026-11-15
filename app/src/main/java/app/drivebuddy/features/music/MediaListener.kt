package app.drivebuddy.features.music

import android.app.PendingIntent
import android.service.notification.NotificationListenerService
import android.util.Log
import app.drivebuddy.features.navigation.NavExit
import app.drivebuddy.features.navigation.NavExitResult

/**
 * Gives DriveBuddy notification access (the music controls need it), and presses the Exit button
 * on Google Maps' navigation notification, since Maps has no other way to end a trip from outside.
 */
class MediaListener : NotificationListenerService() {

    companion object {
        private const val TAG = "DriveBuddy"
        private const val MAPS = "com.google.android.apps.maps"

        @Volatile
        private var instance: MediaListener? = null

        fun exitNavigation(): NavExitResult = instance?.pressExit() ?: NavExitResult.NOT_CONNECTED
    }

    override fun onListenerConnected() {
        instance = this
        Log.i(TAG, "notification listener connected")
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        Log.i(TAG, "notification listener disconnected")
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