package app.drivebuddy.features.weather

import kotlin.math.abs
import kotlin.math.roundToInt

/** What the weather service told us, already in plain numbers (Celsius). */
/** One hour of the forecast, for the weather card. */
data class HourPoint(
    val hour: Int,            // 0-23, local time
    val tempC: Double,
    val rainPercent: Int?,
    val code: Int,
    val windKmh: Double?,
)

data class WeatherNow(
    val tempC: Double,
    val feelsC: Double,
    val code: Int,
    val highC: Double,
    val lowC: Double,
    val rainPercent: Int?,
    val isDay: Boolean = true,
    val hours: List<HourPoint> = emptyList(),
)

/** Turns [WeatherNow] into one short spoken answer. Pure logic, no Android. */
object WeatherText {

    /** WMO weather code -> short phrase, null when the code is unknown. */
    fun describe(code: Int): String? = when (code) {
        0 -> "Clear skies"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Foggy"
        51, 53, 55 -> "Drizzling"
        56, 57 -> "Freezing drizzle"
        61 -> "Light rain"
        63 -> "Raining"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71 -> "Light snow"
        73 -> "Snowing"
        75 -> "Heavy snow"
        77 -> "Snow grains"
        80 -> "Light showers"
        81 -> "Showers"
        82 -> "Heavy showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorms"
        96, 99 -> "Thunderstorms with hail"
        else -> null
    }

    /** 17 -> "5 pm", 0 -> "12 am". */
    fun hourLabel(hour: Int): String {
        val h = hour % 12
        return "${if (h == 0) 12 else h} ${if (hour < 12) "am" else "pm"}"
    }

    /** "High 27° · Low 18° · Rain 10%" for the card. */
    fun detail(w: WeatherNow): String =
        "High ${w.highC.roundToInt()}° · Low ${w.lowC.roundToInt()}°" + (w.rainPercent?.let { " · Rain $it%" } ?: "")

    private fun say(degrees: Double): String {
        val n = degrees.roundToInt()
        return if (n < 0) "minus ${-n}" else n.toString()
    }

    fun speak(w: WeatherNow): String {
        val parts = mutableListOf<String>()
        val temp = say(w.tempC)
        parts += describe(w.code)?.let { "$it and $temp degrees right now." } ?: "It's $temp degrees right now."
        if (abs(w.feelsC - w.tempC) >= 3) parts += "Feels like ${say(w.feelsC)}."
        parts += "Today's high is ${say(w.highC)}, low ${say(w.lowC)}."
        w.rainPercent?.let { parts += "Chance of rain is $it percent." }
        return parts.joinToString(" ")
    }
}