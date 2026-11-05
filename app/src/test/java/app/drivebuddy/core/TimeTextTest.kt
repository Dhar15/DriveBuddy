package app.drivebuddy.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeTextTest {
    @Test fun afternoon() = assertEquals("It's 12:43 PM.", TimeText.time(12, 43))
    @Test fun onTheHour() = assertEquals("It's 3 PM.", TimeText.time(15, 0))
    @Test fun midnight() = assertEquals("It's 12:05 AM.", TimeText.time(0, 5))
    @Test fun date() = assertEquals("It's Thursday, 5 November.", TimeText.date("Thursday", 5, "November"))
}