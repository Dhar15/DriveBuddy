package app.drivebuddy.core

/** One search result, reduced to what matching needs. */
data class TrackCandidate(val name: String, val artists: List<String>, val popularity: Int)

/**
 * Picks the result that best matches what was asked for, or -1 when nothing plausibly does.
 * Filler words ("the", "by") never count, and a word counts as matching when it is close enough
 * to survive a speech-recogniser slip ("husan" ~ "husn").
 */
object TrackPicker {
    private val splitter = Regex("[^a-z0-9]+")
    private val titleCut = Regex(" - | \\| |\\(|\\[")
    private val filler = setOf("the", "a", "an", "by", "of", "to", "in", "on", "at", "my", "and", "feat", "ft", "from", "song", "music")

    private fun words(text: String): List<String> = text.lowercase().split(splitter).filter { it.isNotEmpty() }

    /** Same word, or a near miss on a longer word (one letter off, two for long words). */
    private fun similar(a: String, b: String): Boolean {
        if (a == b) return true
        if (minOf(a.length, b.length) < 4) return false
        val allowed = if (maxOf(a.length, b.length) >= 7) 2 else 1
        return editDistance(a, b) <= allowed
    }

    private fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev[b.length]
    }

    /** Index of the best candidate, or -1 if the list is empty or nothing matches enough of what was said. */
    fun best(query: String, candidates: List<TrackCandidate>): Int {
        val all = words(query)
        val wanted = all.filter { it !in filler }.ifEmpty { all }
        if (wanted.isEmpty()) return -1
        val needed = (wanted.size + 1) / 2 // at least half of the meaningful words must match
        var bestIndex = -1
        var bestScore = -1.0
        candidates.forEachIndexed { i, c ->
            val nameWords = words(c.name)
            val artistWords = c.artists.flatMap { words(it) }
            val inName = wanted.count { w -> nameWords.any { similar(w, it) } }
            val inArtist = wanted.count { w -> artistWords.any { similar(w, it) } && nameWords.none { n -> similar(w, n) } }
            if (inName + inArtist < needed) return@forEachIndexed
            val mainTitle = words(c.name.lowercase().split(titleCut)[0])
            var score = inName * 10.0 + inArtist * 6.0
            if (mainTitle == wanted) score += 50.0 // the title is exactly what was said
            score += c.popularity / 100.0           // Spotify no longer sends this, so it is a harmless tie-break
            if (score > bestScore) {
                bestScore = score
                bestIndex = i
            }
        }
        return bestIndex
    }
}