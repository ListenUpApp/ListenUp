package com.calypsan.listenup.client.design.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class HeroNavRowTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun backControlFiresCallbackAndActionsSlotRenders() {
        var backCount = 0
        composeRule.setContent {
            MaterialTheme {
                HeroNavRow(onBack = { backCount++ }) {
                    Text("ACTION_SLOT")
                }
            }
        }

        composeRule.onNodeWithText("ACTION_SLOT").assertIsDisplayed()
        // The only clickable node is the back button (the slot Text is not clickable).
        composeRule.onAllNodes(hasClickAction())[0].performClick()
        assertEquals(1, backCount)
    }
}
