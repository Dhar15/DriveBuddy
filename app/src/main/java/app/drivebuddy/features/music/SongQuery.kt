package app.drivebuddy.features.music

/**
 * Turns "husn by anuv jain" into Spotify's field search: track:"husn" artist:"anuv jain".
 * Plain words ("husn") stay a plain search. Returns null when there is nothing precise to ask for.
 */
object SongQuery {
    private val byRule = Regex("^(.+?) by (.+)$")

    fun build(spoken: String): String? {
        val m = byRule.find(spoken.trim().lowercase()) ?: return null
        val title = m.groupValues[1].trim().replace("\"", "")
        val artist = m.groupValues[2].trim().replace("\"", "")
        if (title.isEmpty() || artist.isEmpty()) return null
        return "track:\"$title\" artist:\"$artist\""
    }
}