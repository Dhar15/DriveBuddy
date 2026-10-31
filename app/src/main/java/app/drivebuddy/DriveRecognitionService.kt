package app.drivebuddy

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/** Required by Android for assistant apps. DriveBuddy does its own listening, so this only reports "not supported". */
class DriveRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}