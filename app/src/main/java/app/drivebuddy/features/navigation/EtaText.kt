package app.drivebuddy.features.navigation

/** Turns route durations into speech, and recognises "lat,lng" destinations. Pure, so it is unit tested. */
object EtaText {

    private val LATLNG = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*$""")

    /** Google answers durations like "1234s" or "1234.5s". */
    fun parseSeconds(duration: String): Int? =
        duration.trim().removeSuffix("s").toDoubleOrNull()?.let { Math.round(it).toInt() }

    /** "about a minute", "about 35 minutes", "about 1 hour 10 minutes". Hours are rounded to 5 minutes. */
    fun spoken(seconds: Int): String {
        val total = Math.round(seconds / 60.0).toInt().coerceAtLeast(1)
        if (total < 60) return if (total == 1) "about a minute" else "about $total minutes"
        val rounded = ((total + 2) / 5) * 5
        val h = rounded / 60
        val m = rounded % 60
        val hours = if (h == 1) "1 hour" else "$h hours"
        return if (m == 0) "about $hours" else "about $hours $m minutes"
    }

    /** The coordinates when [query] is exactly "lat,lng" (what a picked nearby place sends), otherwise null. */
    fun latLng(query: String): Pair<Double, Double>? {
        val m = LATLNG.matchEntire(query) ?: return null
        return m.groupValues[1].toDouble() to m.groupValues[2].toDouble()
    }
}