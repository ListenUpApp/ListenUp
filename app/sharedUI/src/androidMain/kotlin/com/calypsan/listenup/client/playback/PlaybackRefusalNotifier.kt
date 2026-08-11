package com.calypsan.listenup.client.playback

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.client.localization.SystemStringsHolder
import com.calypsan.listenup.client.notifications.NotificationChannels
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

private const val RES_TYPE_DRAWABLE = "drawable"

/**
 * Notification id for the refusal notice.
 *
 * Distinct from [AudiobookNotificationProvider.NOTIFICATION_ID] (1) so posting this can never
 * replace or be replaced by the media notification — the two can legitimately coexist, and the
 * media one is what the listener taps to try again.
 */
internal const val REFUSAL_NOTIFICATION_ID = 2

/**
 * Offers a way out when the platform refuses to start playback in the background.
 *
 * Android 17 refuses the audio-focus request outright when the app has no visible activity and no
 * foreground service, and refuses the foreground-service start that would have made it eligible.
 * Nothing throws, nothing sounds, and the lock-screen play button simply does nothing. We cannot
 * force playback — but we can stop the dead end from being silent, and name the one action that
 * does work: bring the app to the foreground, which makes the app eligible again.
 *
 * This is the Never Stranded principle applied to a refusal we don't control.
 */
internal class PlaybackRefusalNotifier(
    private val context: Context,
    private val strings: SystemStringsHolder,
) {
    private val notificationManager: NotificationManager?
        get() = context.getSystemService(NotificationManager::class.java)

    /**
     * Posts the "open the app to resume" notice.
     *
     * Takes its copy from [error] so the wording lives with the typed error rather than being
     * duplicated here. Silently does nothing when the listener has denied notifications — a
     * degraded path, not a crash.
     */
    fun notifyRefused(error: PlaybackError) {
        val manager = notificationManager ?: return
        val launchIntent =
            context.packageManager?.getLaunchIntentForPackage(context.packageName) ?: run {
                logger.warn { "No launch intent; cannot offer a way back into the app" }
                return
            }

        val contentIntent =
            PendingIntent.getActivity(
                context,
                0,
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        val icon =
            context.resources
                .getIdentifier("ic_notification", RES_TYPE_DRAWABLE, context.packageName)
                .takeIf { it != 0 }
                ?: android.R.drawable.ic_media_play

        val notification =
            NotificationCompat
                .Builder(context, NotificationChannels.PLAYBACK)
                .setSmallIcon(icon)
                .setContentTitle(strings.current.playerRefusalTitle)
                .setContentText(error.message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(error.message))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()

        manager.notify(REFUSAL_NOTIFICATION_ID, notification)
        logger.info { "Posted playback-refusal notice (${error.code})" }
    }

    /** Clears the notice — playback is going, so the dead end is behind us. */
    fun clearRefusal() {
        notificationManager?.cancel(REFUSAL_NOTIFICATION_ID)
    }
}
