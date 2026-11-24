package app.drivebuddy

import android.app.Application
import android.util.Log
import app.drivebuddy.common.FileLog

class DriveApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FileLog.start(this)
        Log.i("DriveBuddy", "Process start: pid=${android.os.Process.myPid()} sdk=${android.os.Build.VERSION.SDK_INT}")
    }
}