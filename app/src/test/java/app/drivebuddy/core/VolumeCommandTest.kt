package app.drivebuddy.core

import kotlin.collections.iterator

class VolumeCommandTest {

    private fun vol(op: VolumeOp, percent: Int = 0) = Command.Volume(op, percent)

    @Test
    fun `direction and amount words are understood`() {
        val cases = mapOf(
            "volume up" to vol(VolumeOp.UP, 10),
            "turn it up" to vol(VolumeOp.UP, 10),
            "turn the music up a bit" to vol(VolumeOp.UP, 5),
            "turn the volume up a lot" to vol(VolumeOp.UP, 25),
            "can you please make it louder" to vol(VolumeOp.UP, 10),
            "volume down" to vol(VolumeOp.DOWN, 10),
            "lower the volume a little" to vol(VolumeOp.DOWN, 5),
            "make it quieter" to vol(VolumeOp.DOWN, 10),
            "hey oreo, softer please" to vol(VolumeOp.DOWN, 10),
        )
        for ((input, expected) in cases) assertEquals(input, expected, CommandParser.parse(input))
    }

    @Test
    fun `set, max, half and mute`() {
        val cases = mapOf(
            "set the volume to 60 percent" to vol(VolumeOp.SET, 60),
            "volume 30" to vol(VolumeOp.SET, 30),
            "volume to 200" to vol(VolumeOp.SET, 100),
            "max volume" to vol(VolumeOp.SET, 100),
            "half volume" to vol(VolumeOp.SET, 50),
            "mute the music" to vol(VolumeOp.MUTE),
            "unmute" to vol(VolumeOp.UNMUTE),
        )
        for ((input, expected) in cases) assertEquals(input, expected, CommandParser.parse(input))
    }

    @Test
    fun `other commands are not mistaken for volume`() {
        assertEquals(Command.Play("turn down for what", null), CommandParser.parse("play turn down for what"))
        assertEquals(Command.Navigate("volume restaurant"), CommandParser.parse("take me to the volume restaurant"))
        assertEquals(Command.Echo("volume"), CommandParser.parse("volume"))
        assertEquals(Command.Echo("turn left"), CommandParser.parse("turn left"))
    }
}