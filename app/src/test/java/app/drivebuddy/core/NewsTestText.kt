package app.drivebuddy.core

import org.junit.Assert.assertEquals
import org.junit.Test

class NewsTextTest {
    @Test fun cleanRemovesTagsSpacesAndPublisher() =
        assertEquals("Rain hits the city", NewsText.clean("  <b>Rain</b>   hits the\ncity | The Hindu "))

    @Test fun firstBatchHasALeadAndFullStops() =
        assertEquals(
            "Here are the top headlines from The Hindu. One thing. Two things!",
            NewsText.speak(listOf("One thing", "Two things!"), first = true, source = "The Hindu"),
        )

    @Test fun laterBatchesHaveNoLead() =
        assertEquals("Another thing.", NewsText.speak(listOf("Another thing"), first = false, source = "The Hindu"))
}