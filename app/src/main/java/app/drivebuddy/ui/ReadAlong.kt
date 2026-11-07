package app.drivebuddy.ui

/** Works out when to slide a card while a spoken list is being read. Pure logic. */
object ReadAlong {
    /** Where each marker first appears in [text], searching forward; -1 when a marker is missing. */
    fun offsets(text: String, markers: List<String>): List<Int> {
        var from = 0
        return markers.map { marker ->
            val at = text.indexOf(marker, from)
            if (at >= 0) from = at + marker.length
            at
        }
    }
}