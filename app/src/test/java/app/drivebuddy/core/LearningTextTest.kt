package app.drivebuddy.features.learning

import org.junit.Assert.assertEquals
import org.junit.Test

class LearningTextTest {
    @Test fun wordWithAnExample() = assertEquals(
        "Word of the day: ephemeral. It means lasting a very short time. For example: Fame is ephemeral.",
        LearningText.word("ephemeral", "lasting a very short time", "Fame is ephemeral."),
    )

    @Test fun wordWithoutAnExample() = assertEquals(
        "Word of the day: lucid. It means clear and easy to understand.",
        LearningText.word(" lucid ", "clear and easy to understand.", ""),
    )

    @Test fun gkNamesTheTopic() = assertEquals(
        "Your G K of the day, from history: The Quit India Movement began in 1942.",
        LearningText.gk("History", "The Quit India Movement began in 1942"),
    )

    @Test fun gkLeavesOutMiscellaneous() = assertEquals(
        "Your G K of the day: Honey never spoils.",
        LearningText.gk("miscellaneous", "Honey never spoils."),
    )
}
