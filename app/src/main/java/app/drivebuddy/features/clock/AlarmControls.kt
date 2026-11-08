package app.drivebuddy.features.clock

import android.app.AlarmManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log

/** Reads and turns off alarms through the phone's own Clock app. */
object AlarmControls {
    private const val TAG = "DriveBuddy"

    /** When the next alarm rings (epoch millis), or null when none is scheduled. */
    fun nextAlarmAt(context: Context): Long? =
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).nextAlarmClock?.triggerTime

    fun showList(context: Context): String = try {
        context.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Log.i(TAG, "alarms: opened the clock app's alarm list")
        "Opening your alarms."
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "alarms: no clock app handles SHOW_ALARMS")
        "I couldn't find a clock app."
    }
}