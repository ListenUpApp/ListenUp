package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.presentation.player.PrimaryControl
import com.calypsan.listenup.client.testing.AtFontScale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * At the largest font the mini-player's title shrank to "The Ka…" beside its cover. The cover steps aside there
 * — the title already names the book — so the title has the room; at the default size the cover stays.
 */
@Config(qualifiers = "w345dp-h767dp")
@RunWith(RobolectricTestRunner::class)
class MiniPlayerLargeTextTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun bar(fontScale: Float) {
        composeRule.setContent {
            AtFontScale(fontScale) {
                MaterialTheme {
                    NowPlayingBar(
                        state = ACTIVE,
                        progress = { PROGRESS },
                        isExpanded = false,
                        onTap = {},
                        onPrimary = {},
                        onSkipBack = {},
                        onKeepListening = {},
                        skipBackwardSec = 15,
                    )
                }
            }
        }
    }

    @Test
    fun `at the largest font the cover steps aside for the title`() {
        bar(fontScale = 2f)
        composeRule.onNodeWithTag(MiniCoverTag, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `at the default font the cover stays`() {
        bar(fontScale = 1f)
        composeRule.onNodeWithTag(MiniCoverTag, useUnmergedTree = true).assertExists()
    }

    private companion object {
        val PROGRESS = samplePlayerProgress

        val ACTIVE =
            loadedPlayerState(
                title = "The Kaiju Preservation Society",
                phase = PlayerPhase.Paused,
                primary = PrimaryControl.Play,
                chapterTitle = "Chapter One",
                coverPath = "/nonexistent/cover.jpg",
            )
    }
}
