package com.calypsan.listenup.client.design.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class NotificationBellTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun badgeHidesAtZero() {
        composeRule.setContent {
            MaterialTheme {
                NotificationBell(unreadCount = 0, onClick = {})
            }
        }
        composeRule.onNodeWithText("0").assertDoesNotExist()
    }

    @Test
    fun badgeShowsTheUnreadCount() {
        composeRule.setContent {
            MaterialTheme {
                NotificationBell(unreadCount = 7, onClick = {})
            }
        }
        composeRule.onNodeWithText("7").assertIsDisplayed()
    }

    @Test
    fun badgeCapsAtNinetyNinePlus() {
        composeRule.setContent {
            MaterialTheme {
                NotificationBell(unreadCount = 250, onClick = {})
            }
        }
        composeRule.onNodeWithText("99+").assertIsDisplayed()
    }

    @Test
    fun tappingTheBellInvokesOnClick() {
        var clicks = 0
        composeRule.setContent {
            MaterialTheme {
                NotificationBell(unreadCount = 1, onClick = { clicks++ })
            }
        }
        composeRule.onNodeWithContentDescription("Notifications").performClick()
        clicks shouldBe 1
    }
}
