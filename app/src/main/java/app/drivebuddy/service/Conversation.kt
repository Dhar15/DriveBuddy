package app.drivebuddy.service

import android.content.Context
import app.drivebuddy.ui.ChoiceCard
import app.drivebuddy.ui.DriveState

/** What a feature handler may ask of the voice service. Keeps handlers testable and the service small. */
interface Conversation {
    val context: Context
    val destroyed: Boolean

    /** Speaks [text]; [afterSpeaking] runs once it has been spoken (unless the driver interrupted). */
    fun reply(
        text: String,
        quiet: Boolean = false,
        onProgress: ((Int) -> Unit)? = null,
        afterSpeaking: (() -> Unit)? = null,
    )

    /** Speaks [prompt], then listens and hands the answer (or "tap:<token>") to [onAnswer]. */
    fun ask(prompt: String, onProgress: ((Int) -> Unit)? = null, quiet: Boolean = false, onAnswer: (String) -> Unit)

    fun publish(status: String, state: DriveState = DriveState.IDLE)

    /** Runs [block] on the main thread. */
    fun post(block: () -> Unit)

    /** For slow network work: stop the inactivity timer, which would otherwise fire mid-request. */
    fun pauseWatchdog()

    /** Ends the interaction without speaking. */
    fun finish()

    /** Shows a card, makes its buttons answer the pending question, and brings the screen forward. */
    fun showCards(card: ChoiceCard)

    /** A callback for the voice position that moves the card to each marker as it is spoken. */
    fun slideAlong(text: String, markers: List<String>): (Int) -> Unit

    /** True when location can be read; otherwise says (or asks) what to fix and returns false. */
    fun locationReady(feature: String): Boolean

    /** Opens maps for [query] and records it as the last trip. [name] is only for the journey record. */
    fun startNavigation(name: String, query: String): Boolean

    /** A yes/no question. A mumbled answer is asked once more, then treated as no. */
    fun askYesNo(prompt: String, quiet: Boolean = false, onYes: () -> Unit)

    /** Speaks a message that arrives after the interaction has finished (a failure we only learned about later). */
    fun speakLate(text: String)
}