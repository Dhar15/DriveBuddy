package app.drivebuddy.core

/** Works out when to slide a card while a spoken list is being read. Pure logic. */
object ReadAlong {
    const val MS_PER_CHAR = 85L // measured from the logs: about 75-90 ms per spoken character
    const val LEAD_MS = 400L    // slide slightly early so the card is there when the words start

    /** Where each marker first appears in [text], searching forward; -1 when a marker is missing. */
    fun offsets(text: String, markers: List<String>): List<Int> {
        var from = 0
        return markers.map { marker ->
            val at = text.indexOf(marker, from)
            if (at >= 0) from = at + marker.length
            at
        }
    }

    fun delayMs(offset: Int): Long = (offset * MS_PER_CHAR - LEAD_MS).coerceAtLeast(0)
}