package com.calypsan.listenup.client.push

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.calypsan.listenup.api.push.PushPayload
import com.calypsan.listenup.client.notifications.NotificationChannels
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Tests for [PushNotificationRenderer]'s decode → enrich → notify pipeline and for the
 * [NotificationChannels.SOCIAL] channel it posts to.
 *
 * [android.app.NotificationManager] and [android.content.Intent] resolution require a real
 * Android resource/service environment, so this uses [RobolectricTestRunner] + JUnit4
 * (consistent with [com.calypsan.listenup.client.playback.AudiobookNotificationProviderTest] and
 * [com.calypsan.listenup.client.presentation.error.AppErrorLocalizationTest]); the
 * `junit-vintage-engine` on the classpath keeps these discoverable alongside Kotest specs.
 *
 * Posted notifications are inspected via Robolectric's `ShadowNotificationManager` rather than by
 * recomputing the renderer's private notification-id scheme.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PushNotificationRendererTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    private fun renderer(
        bookTitleLookup: suspend (String) -> String? = { null },
        inviterNameLookup: suspend (String) -> String? = { null },
    ) = PushNotificationRenderer(context, bookTitleLookup, inviterNameLookup)

    private fun onlyPosted(): Notification = Shadows.shadowOf(notificationManager).allNotifications.single()

    @Test
    fun `social channel is registered`() {
        NotificationChannels.registerAll(context)

        val channel = notificationManager.getNotificationChannel(NotificationChannels.SOCIAL)

        channel shouldNotBe null
        channel.importance shouldBe NotificationManager.IMPORTANCE_HIGH
    }

    @Test
    fun `test payload renders title+body on social channel`() =
        runTest {
            NotificationChannels.registerAll(context)

            renderer().render(PushPayload.TestNotification(sentAtMs = 0L))

            val posted = onlyPosted()
            posted.extras.getString(Notification.EXTRA_TITLE) shouldBe "Test notification"
            posted.extras.getString(Notification.EXTRA_TEXT) shouldBe "Push notifications are working."
            posted.channelId shouldBe NotificationChannels.SOCIAL
        }

    @Test
    fun `campfire invite enriches inviter name from lookup`() =
        runTest {
            NotificationChannels.registerAll(context)
            val payload = PushPayload.CampfireInvite(campfireId = "camp1", bookId = "book1", inviterUserId = "user1")

            renderer(
                bookTitleLookup = { id -> "The Way of Kings".takeIf { id == "book1" } },
                inviterNameLookup = { id -> "Alice".takeIf { id == "user1" } },
            ).render(payload)

            val posted = onlyPosted()
            posted.extras.getString(Notification.EXTRA_TITLE) shouldBe "Alice invited you to listen together"
            posted.extras.getString(Notification.EXTRA_TEXT) shouldBe "The Way of Kings"
        }

    @Test
    fun `campfire invite falls back to unknown-inviter title when lookups fail`() =
        runTest {
            NotificationChannels.registerAll(context)
            val payload = PushPayload.CampfireInvite(campfireId = "camp2", bookId = "book2", inviterUserId = "user2")

            renderer(
                bookTitleLookup = { error("book lookup failed") },
                inviterNameLookup = { error("inviter lookup failed") },
            ).render(payload)

            val posted = onlyPosted()
            posted.extras.getString(Notification.EXTRA_TITLE) shouldBe "You have an invite to listen together"
            posted.extras.getString(Notification.EXTRA_TEXT) shouldBe ""
        }

    @Test
    fun `null payload renders the generic notification`() =
        runTest {
            NotificationChannels.registerAll(context)

            renderer().render(null)

            val posted = onlyPosted()
            posted.extras.getString(Notification.EXTRA_TITLE) shouldBe "ListenUp"
            posted.extras.getString(Notification.EXTRA_TEXT) shouldBe "Something happened on your server."
        }

    @Test
    fun `notification carries a content intent`() =
        runTest {
            NotificationChannels.registerAll(context)

            renderer().render(PushPayload.TestNotification(sentAtMs = 0L))

            onlyPosted().contentIntent shouldNotBe null
        }
}
