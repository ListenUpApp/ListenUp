package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.features.nowplaying.components.PlayerTopBar
import com.calypsan.listenup.client.presentation.player.AudioSource
import com.calypsan.listenup.client.presentation.player.PlayerMenuItem
import com.calypsan.listenup.client.presentation.player.PlayerSeriesRef
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P1, the status slot and P25: Collapse, the chip, and the More menu from the shared builder. The wide header (P3,
 * `NP-AndroidTablet`) reads just "Now playing": the series is a link in the player column, so More leaves it out.
 */
@Config(qualifiers = "w412dp-h915dp")
@RunWith(RobolectricTestRunner::class)
class PlayerTopBarTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val picked = mutableListOf<PlayerMenuItem>()
    private var collapses = 0

    private fun bar(wide: Boolean = false) {
        composeRule.setContent {
            MaterialTheme {
                PlayerTopBar(
                    state =
                        loadedPlayerState(
                            source = AudioSource.StreamingTranscoded,
                            series = listOf(PlayerSeriesRef("s1", "The Stormlight Archive", "1")),
                            firstPdfDocId = "doc-1",
                            boostDb = 6f,
                        ),
                    onCollapse = { collapses++ },
                    onMenuItem = { picked += it },
                    wide = wide,
                )
            }
        }
    }

    @Test
    fun `collapse and the status`() {
        bar()
        composeRule.onNodeWithContentDescription("Collapse player").performClick()
        collapses shouldBe 1
        composeRule.onNodeWithText("Streaming · transcoded").assertExists()
    }

    @Test
    fun `More lists the shared rows, the boost with its value, and no author or narrator rows`() {
        bar()
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Go to book").assertExists()
        composeRule.onNodeWithText("Go to series").assertExists()
        composeRule.onNodeWithText("Open PDF").assertExists()
        composeRule.onNodeWithText("Volume boost · +6 dB").assertExists()
        composeRule.onNodeWithText("Go to author").assertDoesNotExist()
        composeRule.onNodeWithText("Go to narrator").assertDoesNotExist()
        composeRule.onNodeWithText("Close book").performClick()
        picked shouldBe listOf(PlayerMenuItem.CloseBook)
    }

    @Test
    fun `the wide header reads Now playing, not the series, and keeps the status and Collapse`() {
        bar(wide = true)
        composeRule.onNodeWithText("Now playing").assertExists()
        composeRule.onNodeWithText("The Stormlight Archive").assertDoesNotExist()
        composeRule.onNodeWithText("Streaming · transcoded").assertExists()
        composeRule.onNodeWithContentDescription("Collapse player").performClick()
        collapses shouldBe 1
    }

    @Test
    fun `the wide header's More leaves Go to series out`() {
        bar(wide = true)
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Go to book").assertExists()
        composeRule.onNodeWithText("Go to series").assertDoesNotExist()
    }
}
