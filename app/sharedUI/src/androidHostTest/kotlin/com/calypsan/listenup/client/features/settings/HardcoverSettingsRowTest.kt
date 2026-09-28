package com.calypsan.listenup.client.features.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.presentation.settings.HardcoverRowState
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Settings → Account → Hardcover reads the connection's state in its subtitle, and isn't there at
 * all on a server without Hardcover (a null row state) — nobody is offered a dead end.
 */
@RunWith(RobolectricTestRunner::class)
class HardcoverSettingsRowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var opened = 0

    private fun render(rowState: HardcoverRowState?) {
        composeRule.setContent {
            MaterialTheme {
                Column {
                    HardcoverSettingsRow(rowState = rowState, onClick = { opened++ })
                }
            }
        }
    }

    @Test
    fun `the row is absent when Hardcover is not offered`() {
        render(null)

        composeRule.onNodeWithText("Hardcover").assertDoesNotExist()
    }

    @Test
    fun `not connected invites sharing`() {
        render(HardcoverRowState.NotConnected)

        composeRule.onNodeWithText("Hardcover").assertIsDisplayed()
        composeRule.onNodeWithText("Share what you finish with Hardcover").assertIsDisplayed()
    }

    @Test
    fun `connected names the account`() {
        render(HardcoverRowState.Connected(username = "simon"))

        composeRule.onNodeWithText("Connected as simon").assertIsDisplayed()
    }

    @Test
    fun `connecting says it is waiting for approval`() {
        render(HardcoverRowState.Connecting)

        composeRule.onNodeWithText("Waiting for you to approve on Hardcover").assertIsDisplayed()
    }

    @Test
    fun `a broken connection asks for a reconnect`() {
        render(HardcoverRowState.NeedsAttention)

        composeRule.onNodeWithText("Needs reconnecting").assertIsDisplayed()
    }

    @Test
    fun `tapping the row opens the Hardcover screen`() {
        render(HardcoverRowState.NotConnected)

        composeRule.onNodeWithText("Hardcover").performClick()

        opened shouldBe 1
    }
}
