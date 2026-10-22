package app.drivebuddy

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import app.drivebuddy.core.AppMatcher

/** Flashlight, Do Not Disturb and app launching. Every function returns what DriveBuddy should say. */
object PhoneControls {
    private const val TAG = "DriveBuddy"

    fun flashlight(context: Context, on: Boolean): String {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return try {
            val id = cm.cameraIdList.firstOrNull {
                val c = cm.getCameraCharacteristics(it)
                c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                        c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
            if (id == null) {
                Log.w(TAG, "flashlight: no back camera with a flash")
                "This phone has no flashlight I can use."
            } else {
                cm.setTorchMode(id, on)
                Log.i(TAG, "flashlight: camera $id -> ${if (on) "on" else "off"}")
                if (on) "Flashlight on." else "Flashlight off."
            }
        } catch (e: CameraAccessException) {
            Log.w(TAG, "flashlight failed: ${e.message}")
            "I couldn't reach the flashlight. Another app may be using the camera."
        }
    }

    fun hasDndAccess(context: Context): Boolean =
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .isNotificationPolicyAccessGranted

    fun dnd(context: Context, on: Boolean): String {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            Log.w(TAG, "dnd: access not granted")
            return "I need Do Not Disturb access first. Open DriveBuddy and allow it."
        }
        nm.setInterruptionFilter(
            if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL
        )
        Log.i(TAG, "dnd: ${if (on) "on (priority only)" else "off"}")
        return if (on) "Do Not Disturb on." else "Do Not Disturb off."
    }

    fun openApp(context: Context, spoken: String): String {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        val i = AppMatcher.best(spoken, apps.map { it.first })
        if (i < 0) {
            Log.i(TAG, "openApp: no installed app matches \"$spoken\" (${apps.size} apps scanned)")
            return "I couldn't find an app called $spoken."
        }
        val (label, pkg) = apps[i]
        val intent = pm.getLaunchIntentForPackage(pkg)
            ?: return "I couldn't open $label.".also { Log.w(TAG, "openApp: no launch intent for $pkg") }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        Log.i(TAG, "openApp: \"$spoken\" -> $label ($pkg)")
        return "Opening $label."
    }
}