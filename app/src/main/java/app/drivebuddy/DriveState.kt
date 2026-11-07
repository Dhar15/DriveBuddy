package app.drivebuddy

/**
 * What the assistant is doing right now. The orb UI draws itself from this,
 * so the service reports a state instead of free text.
 */
enum class DriveState { OFF, IDLE, LISTENING, THINKING, SPEAKING, ERROR }

/** One snapshot the UI renders: the state plus a short line of context. */
data class DriveUiState(
    val state: DriveState = DriveState.OFF,
    val detail: String = ""   // e.g. what was heard, or the error text
)

/** One line of the "pick one" card on the home screen. */
data class ChoiceRow(val label: String, val detail: String, val body: String = "")

/** The "pick one" card: a title, up to three rows and a hint line. */
data class ChoiceCard(
    val title: String,
    val rows: List<ChoiceRow>,
    val hint: String,
    val hasMore: Boolean,
    val paged: Boolean = false,
    val action: String = "Navigate",
    val compact: Boolean = false,
    )

/** Single place the service writes to and the screen listens to. */
object DriveBus {
    @Volatile
    var current: DriveUiState = DriveUiState()
        private set

    /** The screen sets this in onResume and clears it in onPause. Always called on the main thread. */
    @Volatile
    var listener: ((DriveUiState) -> Unit)? = null

    /** The card currently on offer, or null. Set by the service, drawn by the home screen. */
    @Volatile
    var choices: ChoiceCard? = null
        private set

    /** Set by the screen like [listener]. Always called on the main thread. */
    @Volatile
    var choicesListener: ((ChoiceCard?) -> Unit)? = null

    /** Set by the service while a pick-one card is on offer; the screen calls [tapChoice] when a button is tapped. */
    @Volatile
    var choiceTap: ((String) -> Unit)? = null

    /** Set by the screen while a paged card is showing; moves it to a page. */
    @Volatile
    var pageTo: ((Int) -> Unit)? = null

    fun slideTo(page: Int) {
        main.post { pageTo?.invoke(page) }
    }

    fun tapChoice(token: String) {
        choiceTap?.invoke(token)
    }

    fun showChoices(card: ChoiceCard?) {
        choices = card
        main.post { choicesListener?.invoke(card) }
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun post(state: DriveState, detail: String = "") {
        val previous = current.state
        val next = DriveUiState(state, detail)
        current = next
        if (previous != state) android.util.Log.i("DriveBuddy", "state $previous -> $state")
        main.post { listener?.invoke(next) }
    }
}