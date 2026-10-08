package com.calypsan.listenup.client.design.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
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

    /**
     * At a large font scale the badge grows; it must grow up and away from the bell, never back
     * across it. The count stays in the bell's top-right quarter, so the glyph stays readable.
     */
    @Test
    fun theBadgeGrowsAwayFromTheBellAtLargeFontScales() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                MaterialTheme {
                    NotificationBell(unreadCount = 2, onClick = {})
                }
            }
        }

        val bell = composeRule.onNodeWithContentDescription("Notifications").getUnclippedBoundsInRoot()
        val bellCenterX = (bell.left + bell.right) / 2
        val bellCenterY = (bell.top + bell.bottom) / 2
        val count = composeRule.onNodeWithText("2").getUnclippedBoundsInRoot()
        count.left shouldBeGreaterThanOrEqualTo bellCenterX
        count.bottom shouldBeLessThanOrEqualTo bellCenterY
    }
}
