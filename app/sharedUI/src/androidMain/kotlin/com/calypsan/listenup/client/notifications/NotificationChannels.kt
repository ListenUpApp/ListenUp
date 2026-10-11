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
 * - [SOCIAL]: push-delivered invites and social activity (IMPORTANCE_HIGH, badge, default sound).
 *
 * Call [registerAll] from `Application.onCreate`. It is idempotent — Android ignores
 * `createNotificationChannel` calls for channels that already exist with the same id.
 */
object NotificationChannels {
    const val PLAYBACK = "listenup_playback"
    const val SOCIAL = "listenup_social"

    /**
     * User-visible name for the [PLAYBACK] channel.
     *
     * Shared so the central registration in [registerAll] and Media3's
     * `MediaNotification.Provider.NotificationChannelInfo` (in `AudiobookNotificationProvider`)
     * cannot drift apart.
     */
    const val PLAYBACK_NAME = "Playback"

    // Earlier builds registered "Sync" and "Downloads" channels that nothing ever posted to; deleting
    // them keeps existing installs from showing dead categories in system notification settings.
    private val RETIRED_CHANNEL_IDS = listOf("listenup_sync", "listenup_download")

    /** Returns all channel ids in registration order. */
    fun allIds(): List<String> = listOf(PLAYBACK, SOCIAL)

    /** Register every channel. Idempotent — safe to call from `Application.onCreate`. */
    fun registerAll(context: Context) {
        val notificationManager = context.getSystemService(NotificationManager::class.java) ?: return
        RETIRED_CHANNEL_IDS.forEach(notificationManager::deleteNotificationChannel)
        notificationManager.createNotificationChannel(
            NotificationChannel(PLAYBACK, PLAYBACK_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                description = "Audio playback controls"
                setShowBadge(false)
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
