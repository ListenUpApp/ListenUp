package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.calypsan.listenup.client.features.nowplaying.components.PlayerTransport
import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.client.presentation.player.PrimaryControl
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** P15–P18 and §3.3: the five controls in order, Previous's hint, Next off at the last chapter, skips by state. */
@Config(qualifiers = "w412dp-h915dp")
@RunWith(RobolectricTestRunner::class)
class PlayerTransportTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun transport(state: PlayerUiState.Loaded) {
        composeRule.setContent {
            MaterialTheme {
                PlayerTransport(
                    state = state,
                    onPrimary = {},
                    onSkipBack = {},
                    onSkipForward = {},
                    onPreviousChapter = {},
                    onNextChapter = {},
                )
            }
        }
    }

    @Test
    fun `playing - all five, Previous with its hint, the skips named by the user's intervals`() {
        transport(loadedPlayerState(phase = PlayerPhase.Playing))
        composeRule
            .onNodeWithContentDescription("Previous chapter")
            .assert(
                SemanticsMatcher("click label is the hint") {
                    it.config.getOrNull(SemanticsActions.OnClick)?.label == "Goes to the start of this chapter first."
                },
            )
        composeRule.onNodeWithContentDescription("Skip back 10 seconds").assertIsEnabled()
        composeRule.onNodeWithContentDescription("Pause").assertExists()
        composeRule.onNodeWithContentDescription("Skip forward 30 seconds").assertIsEnabled()
        composeRule.onNodeWithContentDescription("Next chapter").assertIsEnabled()
    }

    @Test
    fun `the last chapter disables Next`() {
        transport(loadedPlayerState(phase = PlayerPhase.Playing, canSkipToNextChapter = false))
        composeRule.onNodeWithContentDescription("Next chapter").assertIsNotEnabled()
    }

    @Test
    fun `a chapterless book disables Previous rather than restarting the book`() {
        transport(loadedPlayerState(phase = PlayerPhase.Playing, isWholeBook = true, canSkipToNextChapter = false))
        composeRule.onNodeWithContentDescription("Previous chapter").assertIsNotEnabled()
    }

    @Test
    fun `chapter 6 keeps Previous enabled`() {
        transport(loadedPlayerState(phase = PlayerPhase.Playing))
        composeRule.onNodeWithContentDescription("Previous chapter").assertIsEnabled()
    }

    @Test
    fun `buffering keeps the skips' slots but disables them, and the busy button still pauses`() {
        transport(loadedPlayerState(phase = PlayerPhase.Buffering, primary = PrimaryControl.Busy))
        composeRule.onNodeWithContentDescription("Skip back 10 seconds").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Skip forward 30 seconds").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Loading. Pause").assertIsEnabled()
    }

    @Test
    fun `restored reads Play`() {
        transport(loadedPlayerState(phase = PlayerPhase.Restored, primary = PrimaryControl.Play))
        composeRule.onNodeWithContentDescription("Play").assertExists()
    }
}
