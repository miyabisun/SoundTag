package dev.miyabisun.soundtag

import android.app.ActivityOptions
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService

// Notification access authorizes MediaSession queries; notification contents are not used.
class YouTubeAccessService : NotificationListenerService()

internal fun shouldRestoreYouTube(packageName: String, state: Int?): Boolean =
    packageName == "com.google.android.youtube" && state == PlaybackState.STATE_PLAYING

internal object YouTubeRecovery {
    private val handler = Handler(Looper.getMainLooper())

    fun component(context: Context) = ComponentName(context, YouTubeAccessService::class.java)

    fun enabled(context: Context): Boolean = context.getSystemService(NotificationManager::class.java)
        .isNotificationListenerAccessGranted(component(context))

    fun schedule(context: Context) {
        handler.removeCallbacksAndMessages(null)
        if (!enabled(context)) return
        val activity = try {
            context.getSystemService(MediaSessionManager::class.java)
                .getActiveSessions(component(context))
                .firstOrNull { shouldRestoreYouTube(it.packageName, it.playbackState?.state) }
                ?.sessionActivity
        } catch (_: SecurityException) { null }
        if (activity == null || !activity.isActivity) return
        val app = context.applicationContext
        // ponytail: one 1.5s delay lets PiP settle; remeasure if device animations outlast it.
        handler.postDelayed({
            if (enabled(app)) {
                try {
                    activity.send(app, 0, null, null, null, null, ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS).toBundle())
                } catch (_: PendingIntent.CanceledException) {
                    // The player closed its session; do not open a replacement video.
                } catch (_: SecurityException) {
                    // Revoked access must not interrupt the Bluetooth operation.
                }
            }
        }, 1500)
    }
}
