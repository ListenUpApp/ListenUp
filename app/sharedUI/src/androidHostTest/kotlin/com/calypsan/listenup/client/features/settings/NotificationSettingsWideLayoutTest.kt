package com.calypsan.listenup.client.features.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.calypsan.listenup.api.dto.NotificationPreferenceDto
import com.calypsan.listenup.api.notifications.NotificationPreference
import com.calypsan.listenup.client.testing.Windows
import com.calypsan.listenup.client.testing.assertSideBySide
import com.calypsan.listenup.client.testing.assertStacked
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Notification preferences on a tablet give each notification type its own card across the width;
 * on a phone the types stay rows of one group.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationSettingsWideLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = Windows.TABLET)
    fun `on a tablet the notification types sit side by side`() {
        setContent()

        assertSideBySide(
            composeRule.onNodeWithContentDescription("In-app notifications for Campfire invites"),
            composeRule.onNodeWithContentDescription("In-app notifications for Registration decisions"),
        )
    }

    @Test
    @Config(qualifiers = Windows.PHONE)
    fun `on a phone the notification types stay rows of one column`() {
        setContent()

        assertStacked(
            composeRule.onNodeWithContentDescription("In-app notifications for Campfire invites"),
            composeRule.onNodeWithContentDescription("In-app notifications for Registration decisions"),
        )
    }

    private fun setContent() {
        composeRule.setContent {
            MaterialTheme {
                NotificationPrefsContent(prefs = PREFS, onChange = { _, _ -> })
            }
        }
    }

    private companion object {
        val PREFS =
            listOf("campfire_invite", "registration_decision", "registration_approval").map { type ->
                NotificationPreferenceDto(
                    type = type,
                    preference = NotificationPreference(inApp = true, push = false),
                    pushEligible = true,
                )
            }
    }
}
