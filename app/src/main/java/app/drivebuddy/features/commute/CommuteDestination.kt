package app.drivebuddy.features.commute

import kotlin.math.abs

/** Works out where the commute is heading. Pure, so it is unit tested. */
object CommuteDestination {
    /** You count as "at" a place when it is at most this far away by road. */
    const val AT_A_PLACE_SECONDS = 600
    /** The two places must differ by at least this much, or we can't tell which one you're at. */
    const val CLEARLY_APART_SECONDS = 300

    /** The place to head to ([home] or [work]), or null when we can't tell where you're starting from. */
    fun pick(homeSeconds: Int?, workSeconds: Int?, home: String, work: String): String? {
        if (homeSeconds == null || workSeconds == null) return null
        if (minOf(homeSeconds, workSeconds) > AT_A_PLACE_SECONDS) return null
        if (abs(homeSeconds - workSeconds) < CLEARLY_APART_SECONDS) return null
        return if (homeSeconds < workSeconds) work else home
    }

    /** The saved place named in [answer] ("to the office", "home please"), or null. The first one said wins. */
    fun place(answer: String, saved: List<String>): String? {
        val said = " " + answer.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim() + " "
        return saved
            .map { it to said.indexOf(" ${it.lowercase().trim()} ") }
            .filter { it.second >= 0 }
            .minByOrNull { it.second }
            ?.first
    }
}