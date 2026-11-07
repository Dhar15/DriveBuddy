package app.drivebuddy.service

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

class OverlayAnchor(private val context: Context) {

    private var view: View? = null

    fun show() {
        if (view != null || !Settings.canDrawOverlays(context)) return
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        try {
            val v = View(context)
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(v, params)
            view = v
        } catch (e: RuntimeException) {
            Log.w("DriveBuddy", "overlay anchor failed: ${e.message}")
        }
    }

    fun hide() {
        val v = view ?: return
        try {
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (e: RuntimeException) {
            Log.w("DriveBuddy", "overlay anchor removal failed: ${e.message}")
        }
        view = null
    }
}