package com.calypsan.listenup.client.design.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Spec §3.8: a failed section says so in one line and offers its own Try again. The line is a
 * polite live region (§8.2 status row) and the button meets the 48dp target.
 */
@RunWith(RobolectricTestRunner::class)
class InlineRetryTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the line is announced politely and Try again is a 48dp button that retries`() {
        var retries = 0
        composeRule.setContent {
            MaterialTheme { InlineRetry(line = "Couldn't load activity", onRetry = { retries++ }) }
        }

        composeRule
            .onNodeWithText("Couldn't load activity")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        composeRule
            .onNode(hasText("Try again") and hasClickAction())
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        assertEquals(1, retries)
    }
}
