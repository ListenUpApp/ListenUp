package com.calypsan.listenup.client.features.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.api.dto.NotificationPreferenceDto
import com.calypsan.listenup.api.notifications.NotificationPreference
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the notification-preference switches' names. Each row carries two switches, and before this
 * every one was announced as just "In-app" or "Push" — a TalkBack user could not tell WHICH
 * notification type they were about to silence.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationPrefRowTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `each switch names its channel and its notification type`() {
        composeRule.setContent {
            MaterialTheme {
                NotificationPrefRow(pref = PREF, showDivider = false, onChange = {})
            }
        }

        composeRule
            .onNodeWithContentDescription("In-app notifications for Campfire invites")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assertIsOn()
        composeRule
            .onNodeWithContentDescription("Push notifications for Campfire invites")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assertIsOff()
    }

    @Test
    fun `toggling a switch changes only its channel`() {
        var changed: NotificationPreference? = null
        composeRule.setContent {
            MaterialTheme {
                NotificationPrefRow(pref = PREF, showDivider = false, onChange = { changed = it })
            }
        }

        composeRule.onNodeWithContentDescription("Push notifications for Campfire invites").performClick()

        changed shouldBe NotificationPreference(inApp = true, push = true)
    }

    @Test
    fun `a push-ineligible type disables its push switch`() {
        composeRule.setContent {
            MaterialTheme {
                NotificationPrefRow(pref = PREF.copy(pushEligible = false), showDivider = false, onChange = {})
            }
        }

        composeRule.onNodeWithContentDescription("Push notifications for Campfire invites").assertIsNotEnabled()
    }

    private companion object {
        val PREF =
            NotificationPreferenceDto(
                type = "campfire_invite",
                preference = NotificationPreference(inApp = true, push = false),
                pushEligible = true,
            )
    }
}
