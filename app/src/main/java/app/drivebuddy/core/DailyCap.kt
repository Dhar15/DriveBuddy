package app.drivebuddy.core

/** How many chats have been used on which day. */
data class QuotaState(val day: String, val used: Int)

/** Daily chat allowance. Pure logic: the caller supplies today's date and the stored state. */
object DailyCap {
    private fun usedToday(state: QuotaState?, today: String) = if (state?.day == today) state.used else 0

    fun canUse(state: QuotaState?, today: String, limit: Int): Boolean = usedToday(state, today) < limit

    fun afterUse(state: QuotaState?, today: String): QuotaState = QuotaState(today, usedToday(state, today) + 1)
}