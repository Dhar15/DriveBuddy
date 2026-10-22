package app.drivebuddy.core

/** One search result, reduced to what matching needs. */
data class TrackCandidate(val name: String, val artists: List<String>, val popularity: Int)

/**
 * Picks the result that best matches what was asked for. Spotify's own top result is a popularity-weighted
 * guess, so "play Husn" can come back as a different song by the same artist; matching the words you said
 * against the title and artist fixes that. Ties keep Spotify's order.
 */
object TrackPicker {
    private val splitter = Regex("[^a-z0-9]+")
    private val titleCut = Regex(" - | \\| |\\(|\\[")

    private fun words(text: String): List<String> = text.lowercase().split(splitter).filter { it.isNotEmpty() }

    /** Index of the best candidate, or -1 if the list is empty. */
    fun best(query: String, candidates: List<TrackCandidate>): Int {
        val wanted = words(query).filter { it != "by" }
        var bestIndex = -1
        var bestScore = -1.0
        candidates.forEachIndexed { i, c ->
            val nameWords = words(c.name).toSet()
            val artistWords = c.artists.flatMap { words(it) }.toSet()
            val mainTitle = words(c.name.lowercase().split(titleCut)[0])
            var score = wanted.count { it in nameWords } * 10.0 +
                    wanted.count { it in artistWords && it !in nameWords } * 6.0
            if (wanted.isNotEmpty() && mainTitle == wanted) score += 50.0 // the title is exactly what was said
            score += c.popularity / 100.0 // only breaks near-ties
            if (score > bestScore) {
                bestScore = score
                bestIndex = i
            }
        }
        return bestIndex
    }
}