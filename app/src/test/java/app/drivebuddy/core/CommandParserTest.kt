package app.drivebuddy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandParserTest {

    @Test
    fun blankIsEmpty() {
        assertEquals(Command.Empty, CommandParser.parse("   "))
        assertEquals(Command.Empty, CommandParser.parse("..."))
    }

    @Test
    fun stopPhrasesAreRecognisedIgnoringCaseAndPunctuation() {
        assertEquals(Command.Stop, CommandParser.parse("Stop."))
        assertEquals(Command.Stop, CommandParser.parse("Never   mind!"))
        assertEquals(Command.Stop, CommandParser.parse("CANCEL"))
    }

    @Test
    fun otherTextIsEchoedWithOriginalCasing() {
        val c = CommandParser.parse("  Take me to the office ")
        assertTrue(c is Command.Echo)
        assertEquals("Take me to the office", (c as Command.Echo).text)
    }

    @Test
    fun stopInsideALongerSentenceIsNotStop() {
        assertTrue(CommandParser.parse("stop at the next petrol pump") is Command.Echo)
    }

    @Test
    fun playCommandsExtractSongAndApp() {
        assertEquals(Command.Play("despacito", null), CommandParser.parse("play Despacito"))
        assertEquals(Command.Play("despacito", "spotify"), CommandParser.parse("play despacito on Spotify"))
        assertEquals(Command.Play("kesariya", "youtube music"), CommandParser.parse("please play Kesariya on YouTube Music"))
        assertEquals(Command.Media(MediaAction.PLAY), CommandParser.parse("play"))
    }
}
