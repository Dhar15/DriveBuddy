package app.drivebuddy.core

/** Spoken forms of the time and date. Pure logic: the caller supplies the clock. */
object TimeText {
    fun time(hour: Int, minute: Int): String {
        val h12 = if (hour % 12 == 0) 12 else hour % 12
        val suffix = if (hour < 12) "AM" else "PM"
        return if (minute == 0) "It's $h12 $suffix." else "It's $h12:${minute.toString().padStart(2, '0')} $suffix."
    }

    fun date(dayOfWeek: String, day: Int, month: String): String = "It's $dayOfWeek, $day $month."
}