package app.drivebuddy

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import app.drivebuddy.core.AppMatcher
import android.content.ActivityNotFoundException
import android.provider.AlarmClock
import app.drivebuddy.core.AlarmTime
import java.util.Calendar
import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.provider.CalendarContract
import app.drivebuddy.core.RemindWhen
import app.drivebuddy.core.ReminderTime
import java.util.TimeZone

/** Flashlight, Do Not Disturb and app launching. Every function returns what DriveBuddy should say. */
object PhoneControls {
    private const val TAG = "DriveBuddy"

    fun flashlight(context: Context, on: Boolean): String {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return try {
            val id = cm.cameraIdList.firstOrNull {
                val c = cm.getCameraCharacteristics(it)
                c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                        c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
            if (id == null) {
                Log.w(TAG, "flashlight: no back camera with a flash")
                "This phone has no flashlight I can use."
            } else {
                cm.setTorchMode(id, on)
                Log.i(TAG, "flashlight: camera $id -> ${if (on) "on" else "off"}")
                if (on) "Flashlight on." else "Flashlight off."
            }
        } catch (e: CameraAccessException) {
            Log.w(TAG, "flashlight failed: ${e.message}")
            "I couldn't reach the flashlight. Another app may be using the camera."
        }
    }

    fun hasDndAccess(context: Context): Boolean =
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .isNotificationPolicyAccessGranted

    fun dnd(context: Context, on: Boolean): String {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            Log.w(TAG, "dnd: access not granted")
            return "I need Do Not Disturb access first. Open Permissions in DriveBuddy and toggle it on."
        }
        nm.setInterruptionFilter(
            if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL
        )
        Log.i(TAG, "dnd: ${if (on) "on (priority only)" else "off"}")
        return if (on) "Do Not Disturb on." else "Do Not Disturb off."
    }

    fun openApp(context: Context, spoken: String): String {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        val i = AppMatcher.best(spoken, apps.map { it.first })
        if (i < 0) {
            Log.i(TAG, "openApp: no installed app matches \"$spoken\" (${apps.size} apps scanned)")
            return "I couldn't find an app called $spoken."
        }
        val (label, pkg) = apps[i]
        val intent = pm.getLaunchIntentForPackage(pkg)
            ?: return "I couldn't open $label.".also { Log.w(TAG, "openApp: no launch intent for $pkg") }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        Log.i(TAG, "openApp: \"$spoken\" -> $label ($pkg)")
        return "Opening $label."
    }

    /** Starts a countdown in the phone's Clock app. Returns what to say. */
    fun timer(context: Context, seconds: Int): String {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            Log.i(TAG, "timer: asked the clock app for $seconds s")
            "Timer set for ${AlarmTime.speakDuration(seconds)}."
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "timer: no clock app handles ACTION_SET_TIMER")
            "I couldn't find a clock app to set the timer."
        } catch (e: SecurityException) {
            Log.w(TAG, "timer refused: ${e.message}")
            "I'm not allowed to set timers."
        }
    }

    /** Sets an alarm in the phone's Clock app. [pm] is null when no am/pm was said. Returns what to say. */
    fun alarm(context: Context, hour: Int, minute: Int, pm: Boolean?): String {
        val now = Calendar.getInstance()
        val time = AlarmTime.resolve(hour, minute, pm, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
            ?: return "I didn't understand that alarm time.".also { Log.w(TAG, "alarm: impossible time $hour:$minute") }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, time.first)
            .putExtra(AlarmClock.EXTRA_MINUTES, time.second)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            Log.i(TAG, "alarm: heard $hour:$minute pm=$pm, asked the clock app for ${time.first}:${time.second}")
            "Alarm set for ${AlarmTime.speakTime(time.first, time.second)}."
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "alarm: no clock app handles ACTION_SET_ALARM")
            "I couldn't find a clock app to set the alarm."
        } catch (e: SecurityException) {
            Log.w(TAG, "alarm refused: ${e.message}")
            "I'm not allowed to set alarms."
        }
    }

    // --- Notes (Google Keep) and reminders (calendar) -----------------------------------------

    private fun sendToKeep(context: Context, title: String, text: String): Boolean {
        val intent = Intent(Intent.ACTION_SEND)
            .setPackage("com.google.android.keep")
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (title.isNotBlank()) intent.putExtra(Intent.EXTRA_SUBJECT, title)
        return try {
            context.startActivity(intent)
            Log.i(TAG, "keep: shared a note (title=\"$title\", ${text.length} chars)")
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "keep: Google Keep not installed or doesn't accept shared text")
            false
        }
    }

    fun note(context: Context, text: String): String =
        if (sendToKeep(context, "", text)) "Noted." else "I couldn't open Google Keep."

    fun listItem(context: Context, item: String, list: String): String {
        val label = list.replaceFirstChar { it.uppercase() }
        return if (sendToKeep(context, "", "$label: $item")) "Added $item to $list in Keep." else "I couldn't open Google Keep."
    }

    fun hasCalendarAccess(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Puts a timed reminder in the calendar, with an alert at that time. Returns what to say. */
    fun reminder(context: Context, text: String, whenAt: RemindWhen?): String {
        if (whenAt == null) {
            Log.i(TAG, "reminder: no time given for \"$text\"")
            return "When should I remind you? Say it again with a time, like remind me to $text at 5 pm."
        }
        if (!hasCalendarAccess(context)) {
            Log.w(TAG, "reminder: calendar permission missing")
            return "I need calendar access to set reminders. Open Permissions in DriveBuddy and turn it on."
        }
        val now = System.currentTimeMillis()
        val at = ReminderTime.triggerAt(whenAt, now)
            ?: return "I didn't understand that time.".also { Log.w(TAG, "reminder: impossible time $whenAt") }
        return try {
            val calendarId = writableCalendarId(context)
                ?: return "I couldn't find a calendar to put that in.".also { Log.w(TAG, "reminder: no writable calendar") }
            val event = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, text)
                put(CalendarContract.Events.DTSTART, at)
                put(CalendarContract.Events.DTEND, at + 15 * 60_000L)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                put(CalendarContract.Events.HAS_ALARM, 1)
            }
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, event)
            val eventId = uri?.lastPathSegment?.toLongOrNull()
                ?: return "I couldn't save that reminder.".also { Log.w(TAG, "reminder: calendar insert returned $uri") }
            val alert = ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.MINUTES, 0)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            }
            context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, alert)
            Log.i(TAG, "reminder: calendar $calendarId event $eventId at $at \"$text\"")
            "Okay, I'll remind you to $text ${ReminderTime.speak(whenAt, at, now)}."
        } catch (e: SecurityException) {
            Log.w(TAG, "reminder refused: ${e.message}")
            "I'm not allowed to use the calendar."
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "reminder rejected by the calendar: ${e.message}")
            "The calendar wouldn't take that reminder."
        }
    }

    /** A visible, writable calendar, preferring the main Google account's own one. */
    private fun writableCalendarId(context: Context): Long? {
        data class Cal(val id: Long, val account: String, val type: String, val owner: String, val name: String)
        val columns = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.OWNER_ACCOUNT,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
        )
        val selection = "${CalendarContract.Calendars.VISIBLE} = 1 AND " +
                "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}"
        val found = ArrayList<Cal>()
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, columns, selection, null, null)?.use { c ->
            while (c.moveToNext()) {
                found.add(Cal(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getString(3).orEmpty(), c.getString(4).orEmpty()))
            }
        }
        Log.i(TAG, "reminder: writable calendars: " + found.joinToString { "${it.id}:${it.name}(${it.account}/${it.type})" })
        return (found.firstOrNull { it.type == "com.google" && it.account == it.owner } ?: found.firstOrNull())?.id
    }
}