package app.drivebuddy.features.clock

import android.util.Log
import app.drivebuddy.service.Conversation

/** "When's my next alarm" / "show my alarms" / "cancel my alarm". */
class AlarmHandler(private val c: Conversation) {

    private companion object { const val TAG = "DriveBuddy" }

    fun info(showList: Boolean) {
        if (showList) {
            c.reply(AlarmControls.showList(c.context))
            return
        }
        val at = AlarmControls.nextAlarmAt(c.context)
        Log.i(TAG, "alarms: next alarm lookup -> ${if (at == null) "none" else "found"}")
        c.reply(if (at == null) "You have no alarms set." else AlarmText.next(at, System.currentTimeMillis()))
    }

    fun cancel() {
        Log.i(TAG, "alarms: cancel asked; the OnePlus Clock ignores turn-off requests, opening its list instead")
        c.reply("Your clock app won't let me switch alarms off. I'll open your alarms so you can do it.") {
            AlarmControls.showList(c.context)
        }
    }
}