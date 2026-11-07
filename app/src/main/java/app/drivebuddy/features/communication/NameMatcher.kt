package app.drivebuddy.features.communication

data class Candidate(val name: String, val number: String)

sealed class MatchResult {
    data class Single(val candidate: Candidate) : MatchResult()
    data class Ambiguous(val candidates: List<Candidate>) : MatchResult()
    object None : MatchResult()
}

object NameMatcher {
    private const val MIN_SCORE = 60
    private const val CLOSE_ENOUGH = 0.66

    fun match(spoken: String, candidates: List<Candidate>): MatchResult {
        val q = normalize(spoken)
        if (q.isEmpty()) return MatchResult.None

        val scored = candidates
            .map { it to score(q, normalize(it.name)) }
            .filter { it.second >= MIN_SCORE }
        if (scored.isEmpty()) return MatchResult.None

        val best = scored.maxOf { it.second }
        val top = scored.filter { it.second == best }.map { it.first }
        // One person with several numbers is not ambiguous: keep the first number per distinct name.
        val people = top.distinctBy { normalize(it.name) }
        return if (people.size == 1) MatchResult.Single(people.first()) else MatchResult.Ambiguous(people.take(3))
    }

    internal fun score(q: String, name: String): Int {
        if (name.isEmpty()) return 0
        if (name == q) return 100
        if (name.startsWith("$q ")) return 90
        val qTokens = q.split(" ")
        val nTokens = name.split(" ")
        if (qTokens.all { it in nTokens }) return 80
        if (name.contains(q)) return 65
        val allClose = qTokens.all { qt -> nTokens.any { similarity(qt, it) >= CLOSE_ENOUGH } }
        return if (allClose) 62 else 0
    }

    internal fun normalize(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    internal fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val longest = maxOf(a.length, b.length)
        if (longest == 0) return 1.0
        return 1.0 - editDistance(a, b).toDouble() / longest
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
}