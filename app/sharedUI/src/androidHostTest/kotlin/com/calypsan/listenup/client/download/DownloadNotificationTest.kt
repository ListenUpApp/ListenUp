package com.calypsan.listenup.client.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.calypsan.listenup.client.notifications.NotificationChannels
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The download notification as the approved canvas draws it: the words, the actions, and that it is silent, ongoing
 * while it runs, on its own channel, and aimed at the app's own receiver.
 */
@RunWith(RobolectricTestRunner::class)
class DownloadNotificationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val notifications = AndroidDownloadNotifications(context, CoroutineScope(Dispatchers.Unconfined))
    private val mib = 1024L * 1024

    private fun active(
        fileCount: Int = 14,
        queuedBehind: Int = 0,
        resolving: Boolean = false,
    ) = notifications.active(
        DownloadNotice.Active(
            bookId = "b1",
            title = "Project Hail Mary",
            downloadedBytes = 212 * mib,
            totalBytes = 640 * mib,
            fileNumber = 3,
            fileCount = fileCount,
            queuedBehind = queuedBehind,
            resolving = resolving,
        ),
    )

    private fun Notification.title() = extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString()

    private fun Notification.text() = extras.getCharSequence(NotificationCompat.EXTRA_TEXT)?.toString()

    @Test
    fun `a running download names the book, the bytes and the file, with Pause and Cancel`() {
        val notification = active()

        notification.title() shouldBe "Downloading Project Hail Mary"
        notification.text() shouldBe "212 MB of 640 MB · file 3 of 14"
        notification.channelId shouldBe NotificationChannels.DOWNLOADS
        notification.category shouldBe Notification.CATEGORY_PROGRESS
        (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) shouldBe true
        (notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0) shouldBe true
        notification.visibility shouldBe Notification.VISIBILITY_PUBLIC
        notification.actions.map { it.title.toString() } shouldBe listOf("Pause", "Cancel")
        notification.extras.getInt(NotificationCompat.EXTRA_PROGRESS_MAX) shouldBe 1_000
        notification.extras.getInt(NotificationCompat.EXTRA_PROGRESS) shouldBe 331
    }

    @Test
    @Config(sdk = [30])
    fun `on Android 11 the bytes line gets its own row, not the end of the title's line`() {
        val notification = active()

        notification.extras.getCharSequence(NotificationCompat.EXTRA_BIG_TEXT)?.toString() shouldBe
            "212 MB of 640 MB · file 3 of 14"
        notification.text() shouldBe "212 MB of 640 MB · file 3 of 14"
    }

    @Test
    @Config(sdk = [34])
    fun `from Android 12 the template already sets the bytes line below the title`() {
        active().extras.getCharSequence(NotificationCompat.EXTRA_BIG_TEXT) shouldBe null
    }

    @Test
    fun `a one-file book drops the file count`() {
        active(fileCount = 1).text() shouldBe "212 MB of 640 MB"
    }

    @Test
    fun `books queued behind it are named in the header line`() {
        active(queuedBehind = 2).extras.getCharSequence(NotificationCompat.EXTRA_SUB_TEXT)?.toString() shouldBe
            "2 more queued"
    }

    @Test
    fun `progress is indeterminate while the file's address resolves`() {
        active(resolving = true).extras.getBoolean(NotificationCompat.EXTRA_PROGRESS_INDETERMINATE) shouldBe true
    }

    @Test
    fun `Pause and Cancel are immutable broadcasts to the app's own receiver`() {
        active().actions.forEach { action ->
            val intent = shadowOf(action.actionIntent).savedIntent
            intent.component?.className shouldBe DownloadActionReceiver::class.java.name
            intent.getStringExtra(DownloadActionReceiver.EXTRA_BOOK_ID) shouldBe "b1"
            shadowOf(action.actionIntent).isBroadcast shouldBe true
            (shadowOf(action.actionIntent).flags and PendingIntent.FLAG_IMMUTABLE != 0) shouldBe true
        }
    }

    @Test
    fun `a download paused from the notification says so, with Resume and Cancel, and can be dismissed`() {
        val paused =
            notifications.held(
                DownloadNotice.Held("b1", "Project Hail Mary", DownloadNotice.HeldKind.PAUSED_BY_YOU, 212 * mib, 640 * mib),
            )

        paused.title() shouldBe "Download paused"
        paused.text() shouldBe "Project Hail Mary · 212 MB of 640 MB"
        paused.actions.map { it.title.toString() } shouldBe listOf("Resume", "Cancel")
        (paused.flags and Notification.FLAG_ONGOING_EVENT != 0) shouldBe false
        // Resume opens the app, where a download can be scheduled again.
        shadowOf(paused.actions.first().actionIntent).isActivity shouldBe true
    }

    @Test
    fun `waiting downloads say what they wait for, and offer Cancel only`() {
        val wifi =
            notifications.held(DownloadNotice.Held("b1", "Dune", DownloadNotice.HeldKind.WAITING_FOR_WIFI, 0, 100))
        val waiting =
            notifications.held(DownloadNotice.Held("b1", "Dune", DownloadNotice.HeldKind.WAITING_TO_RESUME, 0, 100))

        wifi.title() shouldBe "Waiting for Wi-Fi"
        wifi.text() shouldBe "Dune · downloads use Wi-Fi only"
        wifi.actions.map { it.title.toString() } shouldBe listOf("Cancel")
        waiting.title() shouldBe "Waiting to continue"
        waiting.text() shouldBe "Dune · resumes automatically"
        waiting.actions.map { it.title.toString() } shouldBe listOf("Cancel")
    }

    @Test
    fun `the downloads channel is registered silent and badge-free, and the retired one stays gone`() {
        NotificationChannels.registerAll(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel: NotificationChannel = manager.getNotificationChannel(NotificationChannels.DOWNLOADS)

        channel.importance shouldBe NotificationManager.IMPORTANCE_LOW
        channel.canShowBadge() shouldBe false
        channel.name.toString() shouldBe "Downloads"
        (manager.getNotificationChannel("listenup_download") == null) shouldBe true
    }

    @Test
    fun `a held notice replaces the last and clears without touching the running one`() {
        notifications.showHeld(DownloadNotice.Held("b1", "Dune", DownloadNotice.HeldKind.WAITING_TO_RESUME, 0, 100))
        val shadow = shadowOf(context.getSystemService(NotificationManager::class.java))
        (shadow.getNotification(DOWNLOAD_HELD_NOTIFICATION_ID) != null) shouldBe true

        notifications.clearHeld()
        (shadow.getNotification(DOWNLOAD_HELD_NOTIFICATION_ID) == null) shouldBe true
    }
}
