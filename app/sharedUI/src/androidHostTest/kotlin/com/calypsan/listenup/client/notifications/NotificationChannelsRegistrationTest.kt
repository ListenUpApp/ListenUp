package com.calypsan.listenup.client.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [NotificationChannels.registerAll] against a device that still carries the "Sync" and
 * "Downloads" channels an earlier build registered: nothing posts to them, so they are removed
 * rather than left as dead categories in system Settings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationChannelsRegistrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    @Test
    fun `registering removes the retired sync and download channels`() {
        notificationManager.createNotificationChannel(
            NotificationChannel("listenup_sync", "Sync", NotificationManager.IMPORTANCE_LOW),
        )
        notificationManager.createNotificationChannel(
            NotificationChannel("listenup_download", "Downloads", NotificationManager.IMPORTANCE_LOW),
        )

        NotificationChannels.registerAll(context)

        notificationManager.getNotificationChannel("listenup_sync") shouldBe null
        notificationManager.getNotificationChannel("listenup_download") shouldBe null
    }

    @Test
    fun `registering keeps the live channels`() {
        NotificationChannels.registerAll(context)

        notificationManager.getNotificationChannel(NotificationChannels.PLAYBACK) shouldNotBe null
        notificationManager.getNotificationChannel(NotificationChannels.SOCIAL) shouldNotBe null
    }
}
