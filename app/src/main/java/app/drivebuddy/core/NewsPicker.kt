package app.drivebuddy.core

/** One story from a feed: what to say and where to read more. */
data class Headline(val title: String, val link: String)

object NewsPicker {
    /** Stories not read out before, in feed order. Blank links and repeats are dropped. */
    fun fresh(all: List<Headline>, seen: Set<String>): List<Headline> =
        all.filter { it.link.isNotBlank() && it.link !in seen }.distinctBy { it.link }
}