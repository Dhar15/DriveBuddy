package app.drivebuddy

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.Log
import app.drivebuddy.core.Candidate
import app.drivebuddy.core.MediaAction
import android.app.NotificationManager
import android.app.SearchManager
import android.content.ComponentName
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.MediaStore
import android.app.ActivityManager
import android.os.Handler
import android.os.Looper
import app.drivebuddy.core.VolumeOp

class PhoneActions(private val context: Context) {

    private val tag = "DriveBuddy"

    fun hasContactsPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun canOpenOtherApps(): Boolean = Settings.canDrawOverlays(context)

    fun loadContacts(): List<Candidate> {
        val result = ArrayList<Candidate>()
        val nameCol = ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
        val numberCol = ContactsContract.CommonDataKinds.Phone.NUMBER
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(nameCol, numberCol),
                null, null, null,
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndexOrThrow(nameCol)
                val numberIdx = cursor.getColumnIndexOrThrow(numberCol)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx)
                    val number = cursor.getString(numberIdx)
                    if (!name.isNullOrBlank() && !number.isNullOrBlank()) result.add(Candidate(name, number))
                }
            }
        } catch (e: SecurityException) {
            Log.w(tag, "contacts read refused: ${e.message}")
        }
        return result
    }

    fun placeCall(number: String): Boolean {
        if (context.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) return false
        return try {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telecom.placeCall(Uri.fromParts("tel", number, null), Bundle())
            true
        } catch (e: SecurityException) {
            Log.w(tag, "placeCall refused: ${e.message}")
            false
        }
    }

    fun navigate(query: String): Boolean {
        val uri = Uri.parse("google.navigation:q=${Uri.encode(query)}&mode=d")
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .setPackage("com.google.android.apps.maps")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(tag, "Google Maps not installed")
            false
        }
    }

    fun hasNotificationAccess(): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.isNotificationListenerAccessGranted(ComponentName(context, MediaListener::class.java))
    }

    /** Controls the active player (Spotify, YouTube Music...) directly. False if there is nothing to control. */
    fun mediaControl(action: MediaAction): Boolean {
        if (!hasNotificationAccess()) return false
        return try {
            val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val sessions = manager.getActiveSessions(ComponentName(context, MediaListener::class.java))
            val player = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: sessions.firstOrNull()
                ?: return false
            Log.i(tag, "media $action -> ${player.packageName}")
            val controls = player.transportControls
            when (action) {
                MediaAction.PLAY -> controls.play()
                MediaAction.PAUSE -> controls.pause()
                MediaAction.STOP -> {
                    controls.pause()
                    closeLater(player.packageName)
                }
                MediaAction.NEXT -> controls.skipToNext()
                MediaAction.PREVIOUS -> controls.skipToPrevious()
            }
            true
        } catch (e: SecurityException) {
            Log.w(tag, "media control refused: ${e.message}")
            false
        }
    }

    private fun closeLater(pkg: String) {
        if (pkg == context.packageName) return
        Handler(Looper.getMainLooper()).postDelayed({
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.killBackgroundProcesses(pkg)
            Log.i(tag, "asked Android to close $pkg")
        }, 1_500)
    }

    /** Pauses whatever is playing; true only if something was playing (so we know to resume it later). */
    fun pauseIfPlaying(): Boolean {
        if (!hasNotificationAccess()) return false
        return try {
            val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val player = manager.getActiveSessions(ComponentName(context, MediaListener::class.java))
                .firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: return false
            Log.i(tag, "paused ${player.packageName} while listening")
            player.transportControls.pause()
            true
        } catch (e: SecurityException) {
            false
        }
    }

    /** Percent before and after a volume change (0 to 100), so the reply can say what happened. */
    data class VolumeChange(val before: Int, val after: Int, val muted: Boolean)

    /** Changes the media volume, which is the one Spotify plays on. Null if Android refused. */
    fun changeVolume(op: VolumeOp, percent: Int): VolumeChange? {
        return try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val stream = AudioManager.STREAM_MUSIC
            val max = audio.getStreamMaxVolume(stream)
            val min = audio.getStreamMinVolume(stream)
            val current = audio.getStreamVolume(stream)
            val stepLevels = Math.max(1, Math.round(max * percent / 100f)) // at least one notch
            when (op) {
                VolumeOp.MUTE -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                VolumeOp.UNMUTE -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                else -> {
                    val target = when (op) {
                        VolumeOp.UP -> (current + stepLevels).coerceAtMost(max)
                        VolumeOp.DOWN -> (current - stepLevels).coerceAtLeast(min)
                        else -> Math.round(max * percent / 100f).coerceIn(min, max)
                    }
                    audio.setStreamVolume(stream, target, AudioManager.FLAG_SHOW_UI)
                }
            }
            val after = audio.getStreamVolume(stream)
            Log.i(tag, "volume $op: level $current -> $after of $max")
            VolumeChange(Math.round(current * 100f / max), Math.round(after * 100f / max), audio.isStreamMute(stream))
        } catch (e: SecurityException) {
            Log.w(tag, "volume change refused: ${e.message}")
            null
        }
    }

    fun isInstalled(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun openApp(pkg: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    /** Asks Spotify (default) or YouTube Music to search for and play [query]. */
    fun playFromSearch(query: String, target: String?): Boolean {
        val pkg = if (target == "youtube music") "com.google.android.apps.youtube.music" else "com.spotify.music"

        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .setPackage(pkg)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .putExtra(SearchManager.QUERY, query)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(tag, "$pkg cannot play from search")
            false
        }
    }
}