package app.drivebuddy.core

/** Matches a spoken app name ("whatsapp", "google maps") against the labels of installed apps. */
object AppMatcher {
    private val nonWord = Regex("[^a-z0-9]+")
    private fun norm(s: String) = s.lowercase().replace(nonWord, " ").trim()

    /** Index of the best label, or -1. Exact beats starts-with beats contains; shorter label wins ties. */
    fun best(spoken: String, labels: List<String>): Int {
        val want = norm(spoken)
        if (want.isEmpty()) return -1
        var bestIndex = -1
        var bestRank = Int.MAX_VALUE
        var bestLen = Int.MAX_VALUE
        labels.forEachIndexed { i, label ->
            val l = norm(label)
            val rank = when {
                l == want -> 0
                l.startsWith("$want ") -> 1
                l.contains(" $want") || l.contains("$want ") -> 2
                want.length >= 4 && l.contains(want) -> 3
                else -> return@forEachIndexed
            }
            if (rank < bestRank || (rank == bestRank && l.length < bestLen)) {
                bestIndex = i; bestRank = rank; bestLen = l.length
            }
        }
        return bestIndex
    }
}