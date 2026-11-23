package com.calypsan.listenup.client.features.notifications

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.calypsan.listenup.client.domain.model.AppNotification
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unread is a bold title and a small dot on screen; TalkBack hears it as the row's state, so a
 * listener knows which notifications are new without seeing the dot.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationUnreadA11yTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `an unread notification says it is unread`() {
        setContent(readAt = null)

        composeRule.onNode(hasText(UNKNOWN_TITLE) and hasStateDescription).assert(saysUnread)
    }

    @Test
    fun `a read notification carries no unread state`() {
        setContent(readAt = 0L)

        composeRule.onNode(hasText(UNKNOWN_TITLE) and hasStateDescription).assertDoesNotExist()
    }

    private fun setContent(readAt: Long?) {
        composeRule.setContent {
            MaterialTheme {
                NotificationList(
                    notifications =
                        listOf(
                            AppNotification(
                                id = "n1",
                                type = "from_a_newer_server",
                                event = null,
                                createdAt = 0L,
                                readAt = readAt,
                            ),
                        ),
                    onNotificationClick = {},
                )
            }
        }
    }

    private companion object {
        const val UNKNOWN_TITLE = "Notification"
        val hasStateDescription = SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription)
        val saysUnread = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Unread")
    }
}
