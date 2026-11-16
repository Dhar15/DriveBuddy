package app.drivebuddy.features.learning

import android.util.Log
import app.drivebuddy.service.Conversation
import app.drivebuddy.ui.DriveState
import kotlin.concurrent.thread

/** The word and the GK fact of the day, read out. Used as steps of the commute. */
class LearningHandler(private val c: Conversation) {

    private companion object { const val TAG = "DriveBuddy" }

    fun word() {
        Log.i(TAG, "word: asked")
        c.pauseWatchdog()
        c.publish("Getting the word of the day...", DriveState.THINKING)
        thread(name = "word") {
            val word = WordOfDayClient.fetch()
            c.post {
                if (c.destroyed) return@post
                if (word == null) c.reply("I couldn't get the word of the day.")
                else c.reply(LearningText.word(word.word, word.meaning, word.example))
            }
        }
    }

    fun gk() {
        Log.i(TAG, "gk: asked")
        c.pauseWatchdog()
        c.publish("Getting the G K of the day...", DriveState.THINKING)
        thread(name = "gk") {
            val fact = GkClient.fetch()
            c.post {
                if (c.destroyed) return@post
                if (fact == null) c.reply("I couldn't get the G K of the day.")
                else c.reply(LearningText.gk(fact.category, fact.fact))
            }
        }
    }
}