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

/** Single place the service writes to and the screen listens to. */
object DriveBus {
    @Volatile
    var current: DriveUiState = DriveUiState()
        private set

    /** The screen sets this in onResume and clears it in onPause. Always called on the main thread. */
    @Volatile
    var listener: ((DriveUiState) -> Unit)? = null

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun post(state: DriveState, detail: String = "") {
        val previous = current.state
        val next = DriveUiState(state, detail)
        current = next
        if (previous != state) android.util.Log.i("DriveBuddy", "state $previous -> $state")
        main.post { listener?.invoke(next) }
    }
}