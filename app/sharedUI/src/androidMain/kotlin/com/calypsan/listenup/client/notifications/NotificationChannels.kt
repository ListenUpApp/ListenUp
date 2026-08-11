package com.calypsan.listenup.client.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * Central registry of all notification channels.
 *
 * Channel ids are write-once per device — once a user encounters a channel, the id is locked
 * into their system Settings. Picking ids deliberately at design time matters.
 *
 * - [PLAYBACK]: audiobook playback controls (IMPORTANCE_LOW, no badge).
 * - [SYNC]: background sync activity (IMPORTANCE_LOW, silent, no badge).
 * - [DOWNLOAD]: download progress (IMPORTANCE_LOW, silent, no badge).
 * - [SOCIAL]: push-delivered invites and social activity (IMPORTANCE_HIGH, badge, default sound).
 *
 * Call [registerAll] from `Application.onCreate`. It is idempotent — Android ignores
 * `createNotificationChannel` calls for channels that already exist with the same id.
 */
object NotificationChannels {
    const val PLAYBACK = "listenup_playback"
    const val SYNC = "listenup_sync"
    const val DOWNLOAD = "listenup_download"
    const val SOCIAL = "listenup_social"

    /**
     * User-visible name for the [PLAYBACK] channel.
     *
     * Shared so the central registration in [registerAll] and Media3's
     * `MediaNotification.Provider.NotificationChannelInfo` (in `AudiobookNotificationProvider`)
     * cannot drift apart.
     */
    const val PLAYBACK_NAME = "Playback"

    /** Returns all channel ids in registration order. */
    fun allIds(): List<String> = listOf(PLAYBACK, SYNC, DOWNLOAD, SOCIAL)

    /** Register every channel. Idempotent — safe to call from `Application.onCreate`. */
    fun registerAll(context: Context) {
        val notificationManager = context.getSystemService(NotificationManager::class.java) ?: return
        notificationManager.createNotificationChannel(
            NotificationChannel(PLAYBACK, PLAYBACK_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                description = "Audio playback controls"
                setShowBadge(false)
            },
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(SYNC, "Sync", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Background sync activity"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            },
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(DOWNLOAD, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Audiobook download progress"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            },
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(SOCIAL, "Social", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Invites and social activity"
                setShowBadge(true)
            },
        )
    }
}
