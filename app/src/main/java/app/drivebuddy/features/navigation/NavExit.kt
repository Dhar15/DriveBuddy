package app.drivebuddy.features.navigation

/** What happened when we tried to end the navigation. */
enum class NavExitResult { PRESSED, NO_BUTTON, NO_NOTIFICATION, NOT_CONNECTED }

/** Picks the "exit" button out of Google Maps' navigation notification. Pure, so it is unit tested. */
object NavExit {
    fun isExitLabel(label: CharSequence?): Boolean {
        val l = label?.toString()?.trim()?.lowercase().orEmpty()
        return l == "exit" || l.startsWith("exit ") || l == "end" || l.startsWith("end ") || l == "stop" || l.contains("stop navigation")
    }
}