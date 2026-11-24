package app.drivebuddy.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogFilterTest {
    private fun l(s: String) = LogParser.parse(s)!!
    private val trigger = l("11-24 20:23:10.034 25012 25012 I DriveBuddy: triggered by wake word")
    private val info = l("11-24 20:23:10.113 25012 25012 I DriveBuddy: state IDLE -> LISTENING")
    private val warn = l("11-24 20:23:11.000 25012 25012 W DriveBuddy: slow step")
    private val noise = l("11-24 20:23:11.500 25012 25012 D AudioTrack: set(): type=audio")

    @Test fun parsesThreadtimeAndPaddedTags() {
        assertEquals("DriveBuddy", info.tag)
        assertEquals('I', info.level)
        assertEquals("libc", l("11-24 20:22:59.921 25012 25012 W libc    : Access denied").tag)
    }

    @Test fun ignoresHeadersAndBlankLines() {
        assertNull(LogParser.parse("--------- beginning of main"))
        assertNull(LogParser.parse(""))
        assertTrue(LogParser.parse("========== NEW SESSION 2026-11-24 pid=1 ==========")!!.isBreak)
    }

    @Test fun appOnlyDropsOtherTags() {
        val r = LogFilter.apply(listOf(info, noise), LogQuery(appOnly = true))
        assertEquals(1, r.total)
        assertEquals(2, LogFilter.apply(listOf(info, noise), LogQuery(appOnly = false)).total)
    }

    @Test fun minLevelAndTextFilter() {
        assertEquals(1, LogFilter.apply(listOf(info, warn), LogQuery(minLevel = 'W')).total)
        assertEquals(1, LogFilter.apply(listOf(info, warn), LogQuery(text = "SLOW")).total)
    }

    @Test fun dividerBeforeInteractionAndNewestFirstReverses() {
        val r = LogFilter.apply(listOf(trigger, info), LogQuery())
        assertTrue(r.rows[0].isBreak)
        val rev = LogFilter.apply(listOf(trigger, info), LogQuery(newestFirst = true))
        assertEquals("state IDLE -> LISTENING", rev.rows[0].message)
        assertNotNull(rev.rows.last().isBreak.takeIf { it })
    }

    @Test fun keepsOnlyNewestRowsWhenOverCap() {
        val many = (1..10).map { info }
        assertEquals(3, LogFilter.apply(many, LogQuery(), maxRows = 3).rows.size)
    }
}