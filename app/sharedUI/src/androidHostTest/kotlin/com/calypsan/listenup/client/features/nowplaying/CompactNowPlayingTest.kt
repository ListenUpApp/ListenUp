package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.presentation.player.PlayerCardAction
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.client.presentation.player.PrimaryControl
import io.kotest.matchers.shouldBe
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

    private var chaptersOpened = 0
    private val cardActions = mutableListOf<PlayerCardAction>()

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
                    onChaptersClick = { chaptersOpened++ },
                    onSeekToChapter = {},
                    onSleepTimerClick = {},
                    onGoToContributor = {},
                    onShowAuthorPicker = {},
                    onShowNarratorPicker = {},
                    onMenuItem = {},
                    onCardAction = { cardActions += it },
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
        composeRule.onNodeWithText("1.0×").assertExists()
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

    @Test
    @Config(qualifiers = "w412dp-h800dp", fontScale = 2f)
    fun `at twice the text size the screen scrolls, so the card's buttons and the settings row are reachable`() {
        screen(
            loadedPlayerState(
                coverPath = NO_COVER,
                phase = PlayerPhase.Failed(PlaybackError.ConnectionLost()),
                primary = PrimaryControl.Retry,
            ),
        )
        composeRule.onNodeWithText("Dismiss").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("Chapters").performScrollTo().performClick()
        cardActions shouldBe listOf(PlayerCardAction.Dismiss)
        chaptersOpened shouldBe 1
    }

    @Test
    fun `the sleep fade - the gold card replaces the transport`() {
        screen(loadedPlayerState(coverPath = NO_COVER, phase = PlayerPhase.SleepFading))
        composeRule.onNodeWithText("Keep listening").assertExists()
        composeRule.onNodeWithContentDescription("Pause").assertDoesNotExist()
    }
}
