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

/**
 * Pins [HeroNavRow]'s interactive contract: the back control fires `onBack`, and the trailing
 * `actions` slot renders.
 *
 * The status-bar inset itself is NOT asserted pixel-wise: Robolectric
 * reports zero system-bar insets, so `windowInsetsPadding(WindowInsets.statusBars)` resolves to
 * 0.dp here and a positional assertion could not distinguish inset-applied from inset-absent. The
 * inset is instead guaranteed structurally — it is baked into the component and toggled only by the
 * explicit `applyStatusBarInset` flag — so every screen that composes `HeroNavRow` is inset-safe by
 * construction. This test guards that the consolidation kept a working back button + actions slot.
 */
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
