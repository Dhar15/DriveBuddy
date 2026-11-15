package app.drivebuddy.features.commute

enum class CommuteMusicChoice { LIKED, PLAYLIST, NONE, UNKNOWN }

/** Understands the answer to "liked songs, or a playlist?". Pure, so it is unit tested. */
object CommuteMusic {
    private val none = Regex("\\b(?:no|none|nothing|skip|neither|cancel|never ?mind|not now)\\b")
    private val liked = Regex("\\b(?:liked|like|favou?rites?|songs?)\\b")

    fun parse(answer: String): CommuteMusicChoice {
        val t = answer.lowercase().replace(Regex("[^a-z0-9 ]"), " ")
        return when {
            none.containsMatchIn(t) -> CommuteMusicChoice.NONE
            "playlist" in t -> CommuteMusicChoice.PLAYLIST
            liked.containsMatchIn(t) -> CommuteMusicChoice.LIKED
            else -> CommuteMusicChoice.UNKNOWN
        }
    }
}