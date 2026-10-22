package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assert
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.client.presentation.player.PrimaryControl
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** §5.2 on a phone: the order, the card in the transport's place, the pane title. */
@Config(qualifiers = "w412dp-h915dp")
@RunWith(RobolectricTestRunner::class)
class CompactNowPlayingTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun screen(state: PlayerUiState.Loaded) {
        composeRule.setContent {
            MaterialTheme {
                NowPlayingScreen(
                    state = state,
                    progress = { samplePlayerProgress },
                    onCollapse = {},
                    onPrimary = {},
                    onSeek = {},
                    onSkipBack = {},
                    onSkipForward = {},
                    onPreviousChapter = {},
                    onNextChapter = {},
                    onSpeedClick = {},
                    onChaptersClick = {},
                    onSleepTimerClick = {},
                    onGoToContributor = {},
                    onShowAuthorPicker = {},
                    onShowNarratorPicker = {},
                    onMenuItem = {},
                    onCardAction = {},
                    onRate = {},
                )
            }
        }
    }

    @Test
    fun `playing - the transport, the scrubber with the book sentence, the settings row, and the pane title`() {
        screen(loadedPlayerState(coverPath = NO_COVER))
        composeRule.onNodeWithContentDescription("Pause").assertExists()
        composeRule.onNodeWithText("Book 10% · 40h 30m left").assertExists()
        composeRule.onNodeWithText("1.0x").assertExists()
        composeRule
            .onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Now playing"))
            .assertExists()
    }

    @Test
    fun `a failure - the card replaces the transport, and the scrubber holds still`() {
        screen(
            loadedPlayerState(
                coverPath = NO_COVER,
                phase = PlayerPhase.Failed(PlaybackError.ConnectionLost()),
                primary = PrimaryControl.Retry,
            ),
        )
        composeRule.onNodeWithText("Couldn't play this book.").assertExists()
        composeRule.onNodeWithContentDescription("Pause").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Play").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Chapter position").assertIsNotEnabled()
    }

    @Test
    fun `reconnecting draws as buffering`() {
        screen(loadedPlayerState(coverPath = NO_COVER, phase = PlayerPhase.Reconnecting, primary = PrimaryControl.Busy))
        composeRule.onNodeWithContentDescription("Loading. Pause").assertExists()
    }
}
