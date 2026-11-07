package app.drivebuddy.features.clock

/** Turns a spoken alarm time into a real 24-hour time, and turns times and durations back into speech. */
object AlarmTime {
    /**
     * [pm] null means the driver gave no am/pm: pick whichever reading comes up next after the current time.
     * Returns (hour24, minute), or null if the time is impossible.
     */
    fun resolve(hour: Int, minute: Int, pm: Boolean?, nowHour: Int, nowMinute: Int): Pair<Int, Int>? {
        if (hour !in 0..23 || minute !in 0..59) return null
        if (hour > 12 || hour == 0) return hour to minute // already 24-hour
        if (pm != null) return (hour % 12 + if (pm) 12 else 0) to minute
        val now = nowHour * 60 + nowMinute
        fun wait(h: Int) = ((h * 60 + minute) - now + 24 * 60) % (24 * 60)
        val am = hour % 12
        val pmHour = am + 12
        return (if (wait(am) <= wait(pmHour)) am else pmHour) to minute
    }

    fun speakTime(hour24: Int, minute: Int): String {
        val h = if (hour24 % 12 == 0) 12 else hour24 % 12
        val suffix = if (hour24 < 12) "AM" else "PM"
        return if (minute == 0) "$h $suffix" else "$h:${minute.toString().padStart(2, '0')} $suffix"
    }

    fun speakDuration(totalSeconds: Int): String {
        val h = totalSeconds / 3600
        val m = totalSeconds % 3600 / 60
        val s = totalSeconds % 60
        val parts = buildList {
            if (h > 0) add("$h ${if (h == 1) "hour" else "hours"}")
            if (m > 0) add("$m ${if (m == 1) "minute" else "minutes"}")
            if (s > 0) add("$s ${if (s == 1) "second" else "seconds"}")
        }
        return parts.joinToString(" ")
    }
}