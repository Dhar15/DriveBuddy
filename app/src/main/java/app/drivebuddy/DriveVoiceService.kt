package app.drivebuddy

import android.content.Intent
import android.service.voice.VoiceInteractionService
import android.util.Log
import app.drivebuddy.service.DriveService

/** Makes DriveBuddy selectable as the phone's assistant. Android starts this service by itself, even after a reboot. */
class DriveVoiceService : VoiceInteractionService() {
    private val tag = "DriveBuddy"

    override fun onReady() {
        super.onReady()
        Log.i(tag, "assistant: voice interaction service ready, starting the drive session")
        try {
            startForegroundService(Intent(this, DriveService::class.java).setAction(DriveService.ACTION_START))
        } catch (e: Exception) {
            Log.w(tag, "assistant: could not start the drive session: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}