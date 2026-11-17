package app.drivebuddy.features.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaceQueryTest {
    @Test fun textWhenThereIsNoExactPoint() =
        assertEquals("Wipro - Kodathi", Place("Work", "Wipro - Kodathi").query)

    @Test fun coordinatesWhenThereIsAnExactPoint() =
        assertEquals("12.873400,77.761200", Place("Work", "Wipro - Kodathi", 12.8734, 77.7612).query)

    @Test fun halfAPointIsIgnored() =
        assertEquals("Wipro - Kodathi", Place("Work", "Wipro - Kodathi", 12.8734, null).query)
}