package app.drivebuddy.features.ride

import android.os.Handler
import android.os.Looper
import android.util.Log
import app.drivebuddy.features.music.MediaListener
import app.drivebuddy.service.Conversation

/** "Start my ride": presses Start on Quick Ride's ride-reminder notification. */
class RideHandler(private val conversation: Conversation) {

    private val main = Handler(Looper.getMainLooper())

    fun start() {
        Log.i(TAG, "ride: start requested")
        val first = MediaListener.startQuickRide()
        if (first != RideResult.NOT_CONNECTED) return finish(first)
        // The notification listener can take a moment to bind after a cold start, so wait and try once more.
        Log.i(TAG, "ride: notification listener not connected, retrying once")
        main.postDelayed({
            if (!conversation.destroyed) finish(MediaListener.startQuickRide())
        }, RETRY_DELAY_MS)
    }

    /** Commute step: presses Start if the reminder is up. Otherwise says nothing and moves on. */
    fun startForCommute() {
        val result = MediaListener.startQuickRide()
        Log.i(TAG, "ride: commute step result=$result")
        if (result == RideResult.PRESSED) conversation.reply(RideText.forResult(result)) else conversation.finish()
    }

    private fun finish(result: RideResult) {
        Log.i(TAG, "ride: start result=$result")
        conversation.reply(RideText.forResult(result))
    }

    private companion object {
        const val TAG = "DriveBuddy"
        const val RETRY_DELAY_MS = 1_500L
    }
}