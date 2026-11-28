package app.drivebuddy.common

/** Checks a From/To pair picked on the log screen. Pure Kotlin, so it is unit-tested. */
object LogRange {
    sealed class Check {
        data class Ok(val fromMs: Long?, val toMs: Long?, val toClamped: Boolean = false) : Check()
        data class Error(val message: String) : Check()
    }

    private const val MINUTE_MS = 60_000L

    /** Pickers have minute precision, so "To 10:30" means up to the end of 10:30. */
    fun endOfMinute(ms: Long) = ms + MINUTE_MS - 1

    /** [oldestMs] is the start of the oldest log file, or null when there are no files. */
    fun validate(fromMs: Long?, toMs: Long?, nowMs: Long, oldestMs: Long?): Check {
        if (fromMs != null && fromMs > nowMs) return Check.Error("\"From\" can't be in the future")
        if (fromMs != null && toMs != null && toMs < fromMs) return Check.Error("\"To\" can't be before \"From\"")
        if (toMs != null && oldestMs != null && toMs < oldestMs) return Check.Error("There are no logs that far back")
        if (toMs != null && toMs > nowMs) return Check.Ok(fromMs, nowMs, toClamped = true)
        return Check.Ok(fromMs, toMs)
    }
}