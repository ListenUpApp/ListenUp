package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ListenUpScaffoldTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `content padding reserves the provided player inset`() {
        var bottomPadding: Dp = (-1).dp

        composeRule.setContent {
            CompositionLocalProvider(
                LocalNowPlayingInsets provides WindowInsets(bottom = 88.dp),
            ) {
                ListenUpScaffold(modifier = Modifier.fillMaxSize()) { padding: PaddingValues ->
                    bottomPadding = padding.calculateBottomPadding()
                    Text("content")
                }
            }
        }
        composeRule.waitForIdle()

        bottomPadding shouldBeGreaterThanOrEqualTo 88.dp
    }

    @Test
    fun `content padding collapses to system insets when no player inset`() {
        var bottomPadding: Dp = (-1).dp

        composeRule.setContent {
            // Default LocalNowPlayingInsets is empty (no provider) → only system bars apply.
            ListenUpScaffold(modifier = Modifier.fillMaxSize()) { padding: PaddingValues ->
                bottomPadding = padding.calculateBottomPadding()
                Text("content")
            }
        }
        composeRule.waitForIdle()

        // Robolectric's default device reports zero nav-bar inset, so with no player inset the
        // reserved bottom space is exactly 0 — proving we do not pad when idle.
        bottomPadding shouldBe 0.dp
    }
}
