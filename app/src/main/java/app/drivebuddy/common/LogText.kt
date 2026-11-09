package app.drivebuddy.common

/** How spoken or heard text appears in the log. Private text shows only its length. */
object LogText {
    fun shown(text: String, quiet: Boolean): String =
        if (quiet) "(${text.length} characters, not logged)" else "\"$text\""
}