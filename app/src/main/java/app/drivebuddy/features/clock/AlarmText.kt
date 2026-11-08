package app.drivebuddy.features.clock

import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** What DriveBuddy says about alarms. Pure logic. */
object AlarmText {
    fun next(triggerAt: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val at = Instant.ofEpochMilli(triggerAt).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val day = when (ChronoUnit.DAYS.between(today, at.toLocalDate())) {
            0L -> "today"
            1L -> "tomorrow"
            else -> at.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        }
        return "Your next alarm is ${AlarmTime.speakTime(at.hour, at.minute)} $day."
    }
}