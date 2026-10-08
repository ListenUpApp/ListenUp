package com.calypsan.listenup.client.features.notifications

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.calypsan.listenup.client.domain.model.AppNotification
import com.calypsan.listenup.client.testing.Windows
import com.calypsan.listenup.client.testing.assertSideBySide
import com.calypsan.listenup.client.testing.assertStacked
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The inbox on a tablet flows its notification cards into columns; on a phone it stays a list.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationsWideLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = Windows.TABLET)
    fun `on a tablet the notifications flow into columns`() {
        setContent()

        val titles = composeRule.onAllNodesWithText(UNKNOWN_TITLE)
        assertSideBySide(titles[0], titles[1])
    }

    @Test
    @Config(qualifiers = Windows.PHONE)
    fun `on a phone the notifications stay one list`() {
        setContent()

        val titles = composeRule.onAllNodesWithText(UNKNOWN_TITLE)
        assertStacked(titles[0], titles[1])
    }

    private fun setContent() {
        composeRule.setContent {
            MaterialTheme {
                NotificationList(notifications = NOTIFICATIONS, onNotificationClick = {})
            }
        }
    }

    private companion object {
        val NOTIFICATIONS =
            (1..4).map { index ->
                AppNotification(
                    id = "n$index",
                    type = "from_a_newer_server",
                    event = null,
                    createdAt = 0L,
                    readAt = 0L,
                )
            }
        const val UNKNOWN_TITLE = "Notification"
    }
}
