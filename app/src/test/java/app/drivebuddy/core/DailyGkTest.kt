import app.drivebuddy.features.learning.DailyGk
import org.junit.Assert.assertEquals
import org.junit.Test

class DailyGkTest {
    @Test fun wrapsAround() {
        assertEquals(0, DailyGk.indexFor(0, 365))
        assertEquals(1, DailyGk.indexFor(366, 365))
        assertEquals(10, DailyGk.indexFor(10, 400))
    }
    @Test fun emptyListHasNoIndex() = assertEquals(-1, DailyGk.indexFor(10, 0))
}