package app.drivebuddy.features.learning

/** Picks the day's GK fact the same way the Sprint app does, so the car and the home screen agree. */
object DailyGk {
    /** [dayOfYear] counts from 0 on 1 January, like Sprint's `difference(1 Jan).inDays`. */
    fun indexFor(dayOfYear: Int, count: Int): Int = if (count <= 0) -1 else dayOfYear % count
}