package com.calypsan.listenup.client.features.shell

import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.ProvideNowPlayingInsets
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Home, Library and Discover beside the rail end above the floating mini player, not under it (spec §11 PR 2 risk). */
@RunWith(RobolectricTestRunner::class)
class RailContentInsetsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the rail layout's content reserves the mini player's footprint`() {
        var bottom: Dp = 0.dp
        composeRule.setContent {
            MaterialTheme {
                ProvideNowPlayingInsets(barVisible = true, latchedFootprint = 120.dp) {
                    bottom = railContentInsets().asPaddingValues().calculateBottomPadding()
                }
            }
        }
        composeRule.waitForIdle()
        bottom shouldBeGreaterThanOrEqualTo 120.dp
    }
}
