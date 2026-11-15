package app.drivebuddy.features.weather

import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherHoursTest {
    private val times = listOf("2026-11-15T16:00", "2026-11-15T17:00", "2026-11-15T18:00",
        "2026-11-15T19:00", "2026-11-15T20:00", "2026-11-15T21:00", "2026-11-15T22:00")
    private val temps = listOf<Double?>(25.0, 24.0, 23.0, 22.0, 22.0, 21.0, 20.0)
    private val rains = listOf<Int?>(10, 10, 10, 10, 10, 10, 20)
    private val codes = listOf<Int?>(3, 3, 3, 3, 3, 3, 3)
    private val winds = listOf<Double?>(5.0, 6.0, 7.0, 8.0, 9.0, 10.0, 11.0)

    @Test fun startsAtTheCurrentHour() {
        val hours = WeatherHours.pick(times, temps, rains, codes, winds, "2026-11-15T17:39")
        assertEquals(listOf(17, 18, 19, 20, 21), hours.map { it.hour })
        assertEquals(24.0, hours.first().tempC, 0.0)
    }

    @Test fun runsOutOfData() {
        val hours = WeatherHours.pick(times, temps, rains, codes, winds, "2026-11-15T21:10")
        assertEquals(listOf(21, 22), hours.map { it.hour })
    }

    @Test fun nothingAfterNow() =
        assertEquals(emptyList<HourPoint>(), WeatherHours.pick(times, temps, rains, codes, winds, "2026-11-16T01:00"))

    @Test fun skipsHoursWithoutATemperature() {
        val gappy = temps.toMutableList().also { it[2] = null }
        val hours = WeatherHours.pick(times, gappy, rains, codes, winds, "2026-11-15T17:00", count = 3)
        assertEquals(listOf(17, 19, 20), hours.map { it.hour })
    }
}