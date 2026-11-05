package app.drivebuddy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyCapTest {
    @Test fun freshInstallAllowsUse() = assertTrue(DailyCap.canUse(null, "2026-11-05", 3))

    @Test fun blocksAtTheLimit() {
        var s: QuotaState? = null
        repeat(3) { s = DailyCap.afterUse(s, "2026-11-05") }
        assertFalse(DailyCap.canUse(s, "2026-11-05", 3))
    }

    @Test fun newDayStartsAtZero() {
        val s = QuotaState("2026-11-05", 3)
        assertTrue(DailyCap.canUse(s, "2026-11-06", 3))
        assertEquals(QuotaState("2026-11-06", 1), DailyCap.afterUse(s, "2026-11-06"))
    }
}