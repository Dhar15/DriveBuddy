package app.drivebuddy.features.weather

/** Picks the hours the weather card shows. Pure, so it is unit tested. */
object WeatherHours {

    /**
     * The hour [now] falls in, plus the following ones, [count] in all. Times are Open-Meteo's local
     * "2026-11-15T17:00"; [now] is "2026-11-15T17:39". Hours with no temperature are skipped.
     */
    fun pick(
        times: List<String>,
        temps: List<Double?>,
        rains: List<Int?>,
        codes: List<Int?>,
        winds: List<Double?>,
        now: String,
        count: Int = 5,
    ): List<HourPoint> {
        val thisHour = now.take(13) // "2026-11-15T17"
        val start = times.indexOfFirst { it.take(13) >= thisHour }
        if (start < 0) return emptyList()
        val out = mutableListOf<HourPoint>()
        var i = start
        while (i < times.size && out.size < count) {
            val t = temps.getOrNull(i)
            val hour = times[i].substring(11, 13).toIntOrNull()
            if (t != null && hour != null) {
                out += HourPoint(hour, t, rains.getOrNull(i), codes.getOrNull(i) ?: 0, winds.getOrNull(i))
            }
            i++
        }
        return out
    }
}