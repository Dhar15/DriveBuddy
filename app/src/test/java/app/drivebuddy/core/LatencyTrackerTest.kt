package app.drivebuddy.core

import app.drivebuddy.common.LatencyTracker
import app.drivebuddy.common.Mark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyTrackerTest {

    private class FakeClock(var now: Long = 1_000L) {
        fun advance(ms: Long) { now += ms }
    }

    private fun tracker(clock: FakeClock) =
        LatencyTracker(monotonicMs = { clock.now }, wallClockMs = { 1_700_000_000_000L })

    @Test
    fun computesDerivedSpans() {
        val c = FakeClock()
        val t = tracker(c)
        t.mark(Mark.TRIGGER)
        c.advance(300); t.mark(Mark.LISTENING)
        c.advance(2000); t.mark(Mark.SPEECH_END)
        c.advance(20); t.mark(Mark.ACK_CUE)
        c.advance(280); t.mark(Mark.STT_FINAL)
        c.advance(900); t.mark(Mark.TTS_START)

        assertEquals(300L, t.elapsed(Mark.TRIGGER, Mark.LISTENING))
        assertEquals(20L, t.elapsed(Mark.SPEECH_END, Mark.ACK_CUE))
        assertEquals(300L, t.elapsed(Mark.SPEECH_END, Mark.STT_FINAL))
        assertEquals(1200L, t.elapsed(Mark.SPEECH_END, Mark.TTS_START))
    }

    @Test
    fun missingMarksGiveNull() {
        val t = tracker(FakeClock())
        t.mark(Mark.TRIGGER)
        assertNull(t.elapsed(Mark.TRIGGER, Mark.TTS_START))
    }

    @Test
    fun duplicateMarkIsIgnored() {
        val c = FakeClock()
        val t = tracker(c)
        t.mark(Mark.TRIGGER)
        c.advance(100); t.mark(Mark.LISTENING)
        c.advance(500); t.mark(Mark.LISTENING)
        assertEquals(100L, t.elapsed(Mark.TRIGGER, Mark.LISTENING))
    }

    @Test
    fun jsonContainsFieldsAndEscapesText() {
        val c = FakeClock()
        val t = tracker(c)
        t.sttEngine = "on-device"
        t.transcript = "say \"hi\"\nthere"
        t.command = "Echo"
        t.mark(Mark.TRIGGER)
        c.advance(10); t.mark(Mark.SPEECH_END)
        val json = t.toJson()
        assertTrue(json.contains("\"ts\":1700000000000"))
        assertTrue(json.contains("\"stt\":\"on-device\""))
        assertTrue(json.contains("say \\\"hi\\\"\\nthere"))
        assertTrue(json.contains("\"marks_ms\":{\"TRIGGER\":0,\"SPEECH_END\":10}"))
        assertTrue(json.contains("\"end_to_ack\":null"))
        assertTrue(json.startsWith("{") && json.endsWith("}"))
    }

    @Test
    fun resetClearsState() {
        val t = tracker(FakeClock())
        t.mark(Mark.TRIGGER)
        t.transcript = "x"
        t.reset()
        assertTrue(!t.has(Mark.TRIGGER))
        assertEquals("", t.transcript)
    }
}
