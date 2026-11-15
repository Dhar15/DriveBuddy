package app.drivebuddy.features.navigation

import android.util.Log
import app.drivebuddy.common.LocationHelper
import app.drivebuddy.features.phone.PhoneActions
import app.drivebuddy.service.Conversation
import app.drivebuddy.ui.DriveState
import kotlin.concurrent.thread
import app.drivebuddy.features.music.MediaListener

/** "Navigate to work": says how long the drive takes (when it can find out), then opens maps. */
class NavigationHandler(private val c: Conversation, private val actions: PhoneActions) {

    private companion object { const val TAG = "DriveBuddy" }

    private val quota by lazy { EtaQuota(c.context) }

    fun navigate(spoken: String) {
        if (!actions.canOpenOtherApps()) {
            c.reply("I need permission to display over other apps before I can open maps.")
            return
        }
        go(spoken, SavedPlaces.resolve(c.context, spoken))
    }

    /** Says where we're going (and how long it takes, when known), then opens maps. */
    fun go(name: String, query: String, lead: String = "Navigating to") {
        withEta(query) { eta ->
            val said = if (eta == null) "$lead $name." else "$lead $name. The ETA is $eta."
            c.reply(said) { start(name, query) }
        }
    }

    /** Opens maps for [query] and records it as the last trip. */
    fun start(name: String, query: String): Boolean {
        val started = actions.navigate(query)
        if (started) {
            Log.i(TAG, "navigation started: \"$name\"")
            Journeys.record(c.context, name, query)
        } else {
            Log.w(TAG, "navigation to \"$name\" could not be started")
        }
        return started
    }

    /** Calls [then] with the spoken ETA, or null when it can't be had. The trip never waits on it for long. */
    private fun withEta(query: String, then: (String?) -> Unit) {
        if (!quota.canUse()) {
            Log.i(TAG, "eta: daily allowance used up, going without it")
            then(null)
            return
        }
        if (!LocationHelper.hasPermission(c.context)) {
            Log.i(TAG, "eta: no location permission, going without it")
            then(null)
            return
        }
        if (!LocationHelper.isLocationOn(c.context)) {
            Log.i(TAG, "eta: location is switched off, going without it")
            then(null)
            return
        }
        c.pauseWatchdog() // location + network can outlast the watchdog
        c.publish("Checking the route...", DriveState.THINKING)
        LocationHelper.current(c.context) { location ->
            if (location == null) {
                Log.i(TAG, "eta: no location fix, going without it")
                then(null)
                return@current
            }
            thread(name = "eta") {
                quota.record()
                val seconds = EtaClient.driveSeconds(location.latitude, location.longitude, query)
                c.post { if (!c.destroyed) then(seconds?.let { EtaText.spoken(it) }) }
            }
        }
    }

    fun stop() {
        Log.i(TAG, "navigation: stop asked")
        when (MediaListener.exitNavigation()) {
            NavExitResult.PRESSED -> c.reply("Okay, stopping navigation.")
            NavExitResult.NO_NOTIFICATION -> c.reply("I don't see a navigation running.")
            NavExitResult.NO_BUTTON ->
                c.reply("Maps didn't give me an exit button. I'll open it so you can end the trip.") {
                    actions.openApp("com.google.android.apps.maps")
                }
            NavExitResult.NOT_CONNECTED ->
                c.reply("I need notification access to do that. Open Permissions in DriveBuddy and turn it on.")
        }
    }
}