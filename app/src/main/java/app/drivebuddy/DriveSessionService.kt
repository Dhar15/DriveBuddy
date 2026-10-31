package app.drivebuddy

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log

class DriveSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = DriveSession(this)
}

/** Opened by the power-button hold or assist gesture: start listening, with no screen of its own. */
class DriveSession(private val appContext: Context) : VoiceInteractionSession(appContext) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.i("DriveBuddy", "assistant: invoked by the system, starting to listen")
        try {
            appContext.startForegroundService(
                Intent(appContext, DriveService::class.java).setAction(DriveService.ACTION_TALK)
            )
        } catch (e: Exception) {
            Log.w("DriveBuddy", "assistant: could not start listening: ${e.javaClass.simpleName}: ${e.message}")
        }
        hide()
    }
}