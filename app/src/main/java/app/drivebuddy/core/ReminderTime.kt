package app.drivebuddy.core

import java.util.Calendar
import java.util.TimeZone

/** Turns a spoken [RemindWhen] into a real moment in time, and back into a short spoken phrase. */
object ReminderTime {

    /** Epoch millis for the reminder, or null if the time is impossible. */
    fun triggerAt(w: RemindWhen, now: Long, zone: TimeZone = TimeZone.getDefault()): Long? = when (w) {
        is RemindWhen.After -> if (w.seconds > 0) now + w.seconds * 1000L else null
        is RemindWhen.At -> {
            val c = Calendar.getInstance(zone).apply { timeInMillis = now }
            // "tomorrow at 5" nobody means 5 am: for a bare hour, 1 to 6 and 12 mean afternoon, 7 to 11 morning.
            val pm = w.pm ?: if (w.tomorrow && w.hour in 1..12) (w.hour == 12 || w.hour <= 6) else null
            val hm = AlarmTime.resolve(w.hour, w.minute, pm, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
            if (hm == null) null else {
                c.set(Calendar.HOUR_OF_DAY, hm.first)
                c.set(Calendar.MINUTE, hm.second)
                c.set(Calendar.SECOND, 0)
                c.set(Calendar.MILLISECOND, 0)
                if (w.tomorrow || c.timeInMillis <= now) c.add(Calendar.DAY_OF_YEAR, 1)
                c.timeInMillis
            }
        }
    }

    /** "in 30 minutes", "at 5 PM", "tomorrow at 9 AM". */
    fun speak(w: RemindWhen, trigger: Long, now: Long, zone: TimeZone = TimeZone.getDefault()): String = when (w) {
        is RemindWhen.After -> "in ${AlarmTime.speakDuration(w.seconds)}"
        is RemindWhen.At -> {
            val t = Calendar.getInstance(zone).apply { timeInMillis = trigger }
            val today = Calendar.getInstance(zone).apply { timeInMillis = now }
            val sameDay = t.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) &&
                    t.get(Calendar.YEAR) == today.get(Calendar.YEAR)
            val time = AlarmTime.speakTime(t.get(Calendar.HOUR_OF_DAY), t.get(Calendar.MINUTE))
            if (sameDay) "at $time" else "tomorrow at $time"
        }
    }
}