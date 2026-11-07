package app.drivebuddy.features.music

/** What a "play …" request is really asking for. Pure logic, no Android. */
sealed class PlayIntent {
    data class Song(val query: String) : PlayIntent()      // "husn by anuv jain"
    data class Artist(val name: String) : PlayIntent()     // "a song by anuv jain"
    data class Playlist(val name: String) : PlayIntent()   // "my driving playlist"
    object AnyPlaylist : PlayIntent()                      // "a song from any of my playlists"
    object Any : PlayIntent()                              // "any song", "something"

    companion object {
        private val kind = "(?:song|songs|track|tracks|music)"
        private val some = "(?:(?:any|a|some|one|a random|some random|any random|random) )?"
        private val fromPrefix = "(?:$some$kind from )?"

        private val anyPlaylistRule = Regex(
            "^$fromPrefix(?:any |one |a random )?(?:of )?my playlists?$|^(?:any|a|one|some|a random|random) playlist$"
        )
        private val namedPrefixRule = Regex("^${fromPrefix}(?:my )?playlist (?:called |named )?(.+)$")
        private val namedSuffixRule = Regex("^$fromPrefix(?:my |the )?(.+?) playlist$")
        private val anyRule = Regex(
            "^(?:$some$kind|something|anything|random|shuffle|shuffle (?:my )?(?:songs|music|liked songs)|" +
                    "(?:my )?liked songs|(?:my )?favou?rites)$"
        )
        private val artistRule = Regex("^(?:$some$kind|something|anything) (?:by|from|of) (.+)$")

        fun parse(query: String): PlayIntent {
            val q = query.trim().lowercase()
            if (anyPlaylistRule.matches(q)) return AnyPlaylist
            namedPrefixRule.find(q)?.let { return Playlist(it.groupValues[1].trim()) }
            namedSuffixRule.find(q)?.let { return Playlist(it.groupValues[1].trim()) }
            artistRule.find(q)?.let { return Artist(it.groupValues[1].trim()) }
            if (anyRule.matches(q)) return Any
            return Song(query.trim())
        }
    }
}