package com.calypsan.listenup.client.design.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.testing.Windows
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An immersive screen (no top bar) that failed to load still has a way back, and a retry where retrying can
 * change anything — the series, contributor and profile pages used to leave one red sentence on an empty screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Windows.PHONE)
class DetailUnavailableTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a failure says what went wrong and offers Back and Try again`() {
        var backs = 0
        var retries = 0
        composeRule.setContent {
            MaterialTheme { DetailUnavailable(message = "Profile not found", onBack = { backs++ }, onRetry = { retries++ }) }
        }

        composeRule.onNodeWithText("Profile not found").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").performClick()
        composeRule.onNodeWithContentDescription("Back").performClick()

        assertEquals(1, retries)
        assertEquals(1, backs)
    }

    @Test
    fun `a failure nothing can retry offers Back alone`() {
        composeRule.setContent {
            MaterialTheme { DetailUnavailable(message = "Series not found", onBack = {}, onRetry = null) }
        }

        composeRule.onNodeWithText("Series not found").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").assertDoesNotExist()
    }

    @Test
    fun `the floating back button goes back`() {
        var backs = 0
        composeRule.setContent { MaterialTheme { FloatingBackButton(onBackClick = { backs++ }) } }

        composeRule.onNodeWithContentDescription("Back").performClick()

        assertEquals(1, backs)
    }
}
