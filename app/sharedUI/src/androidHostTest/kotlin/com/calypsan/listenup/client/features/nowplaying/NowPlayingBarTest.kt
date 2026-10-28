package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.client.presentation.player.PrimaryControl
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The phone mini player (`Main`, `States-Android`): its controls by state, its wave, and its one open target. */
@Config(qualifiers = "w412dp-h915dp")
@RunWith(RobolectricTestRunner::class)
class NowPlayingBarTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var primaryPresses = 0
    private var keepListeningPresses = 0

    private fun bar(
        state: PlayerUiState.Loaded,
        bottomInset: WindowInsets = WindowInsets(left = 0, top = 0, right = 0, bottom = 0),
    ) {
        composeRule.setContent {
            MaterialTheme {
                NowPlayingBar(
                    state = state.copy(book = state.book.copy(coverPath = "/nonexistent/cover.jpg")),
                    progress = { samplePlayerProgress },
                    isExpanded = false,
                    onTap = {},
                    onPrimary = { primaryPresses++ },
                    onSkipBack = {},
                    onKeepListening = { keepListeningPresses++ },
                    skipBackwardSec = 10,
                    bottomInset = bottomInset,
                    modifier = Modifier.testTag(BAR),
                )
            }
        }
    }

    private fun backShown(shown: Boolean) {
        val back = composeRule.onNodeWithContentDescription("Skip back 10 seconds")
        if (shown) back.assertExists() else back.assertDoesNotExist()
    }

    @Test
    fun `playing offers back and Pause, with the wave`() {
        bar(loadedPlayerState(phase = PlayerPhase.Playing))
        backShown(true)
        composeRule.onNodeWithContentDescription("Pause").assertExists()
        composeRule.onNodeWithText("Bridge Four · 8m left", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag(MINI_PROGRESS_WAVY_TAG).assertExists()
        composeRule.onNodeWithTag(MINI_PROGRESS_FLAT_TAG).assertDoesNotExist()
    }

    @Test
    fun `a sleep fade still waves, as Now Playing's scrubber and the flying line do`() {
        // The book is still audible while the sleep timer fades it out; the flat line would mean it had stopped.
        bar(loadedPlayerState(phase = PlayerPhase.SleepFading))
        composeRule.onNodeWithTag(MINI_PROGRESS_WAVY_TAG).assertExists()
        composeRule.onNodeWithTag(MINI_PROGRESS_FLAT_TAG).assertDoesNotExist()
    }

    @Test
    fun `paused offers back and Play, on a flat line`() {
        bar(loadedPlayerState(phase = PlayerPhase.Paused, primary = PrimaryControl.Play))
        backShown(true)
        composeRule.onNodeWithContentDescription("Play").assertExists()
        composeRule.onNodeWithTag(MINI_PROGRESS_FLAT_TAG).assertExists()
        composeRule.onNodeWithTag(MINI_PROGRESS_WAVY_TAG).assertDoesNotExist()
    }

    @Test
    fun `a cold-start restore offers back and Play`() {
        bar(loadedPlayerState(phase = PlayerPhase.Restored, primary = PrimaryControl.Play))
        backShown(true)
        composeRule.onNodeWithContentDescription("Play").assertExists()
    }

    @Test
    fun `preparing shows the new book, Starting, and a busy button with no back`() {
        bar(loadedPlayerState(title = "Words of Radiance", phase = PlayerPhase.Preparing, primary = PrimaryControl.Busy))
        // The merged tree: the cover placeholder letters the title too, but carries no semantics.
        composeRule.onNodeWithText("Words of Radiance").assertExists()
        composeRule.onNodeWithText("Starting…", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithContentDescription("Loading. Pause").assertExists()
        backShown(false)
    }

    @Test
    fun `buffering hides back and is flat`() {
        bar(loadedPlayerState(phase = PlayerPhase.Buffering, primary = PrimaryControl.Busy))
        backShown(false)
        composeRule.onNodeWithTag(MINI_PROGRESS_FLAT_TAG).assertExists()
    }

    @Test
    fun `a dropped connection reads Reconnecting, once and politely, on the busy control`() {
        bar(loadedPlayerState(phase = PlayerPhase.Reconnecting, primary = PrimaryControl.Busy))
        composeRule.onNodeWithText("Reconnecting…", useUnmergedTree = true).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
        )
        composeRule.onNodeWithContentDescription("Reconnecting. Pause").assertExists()
        backShown(false)
        composeRule.onNodeWithTag(MINI_PROGRESS_FLAT_TAG).assertExists()
    }

    @Test
    fun `the sleep fade keeps back, says it is fading, and offers Keep listening in place of Pause`() {
        bar(loadedPlayerState(phase = PlayerPhase.SleepFading))
        backShown(true)
        composeRule.onNodeWithText("Sleep timer · fading out", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag(MINI_PROGRESS_WAVY_TAG).assertExists()
        composeRule.onNodeWithContentDescription("Pause").assertDoesNotExist()
        composeRule.onNode(hasText("Keep listening")).performClick()
        keepListeningPresses shouldBe 1
        primaryPresses shouldBe 0
    }

    @Test
    fun `finished offers Listen again and no back`() {
        bar(loadedPlayerState(phase = PlayerPhase.Finished, primary = PrimaryControl.ListenAgain))
        composeRule.onNodeWithText("Finished", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithContentDescription("Listen again to The Way of Kings").assertExists()
        backShown(false)
    }

    @Test
    fun `a failure keeps the bar, says why, and retries`() {
        bar(loadedPlayerState(phase = PlayerPhase.Failed(PlaybackError.ConnectionLost()), primary = PrimaryControl.Retry))
        composeRule.onNodeWithText("Couldn't play this book.", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Retry").performClick()
        primaryPresses shouldBe 1
        backShown(false)
    }

    @Test
    fun `a lapsed session offers Sign in`() {
        bar(loadedPlayerState(phase = PlayerPhase.Unavailable(AuthError.SessionExpired()), primary = PrimaryControl.SignIn))
        composeRule.onNodeWithText("Signed out · place saved", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Sign in").assertExists()
    }

    @Test
    fun `a revoked book offers Close`() {
        bar(loadedPlayerState(phase = PlayerPhase.Unavailable(PlaybackError.NoLongerAvailable()), primary = PrimaryControl.Close))
        composeRule.onNodeWithText("No longer in your library", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithContentDescription("Close book").assertExists()
    }

    @Test
    fun `a background refusal offers Resume`() {
        bar(loadedPlayerState(phase = PlayerPhase.Failed(PlaybackError.BlockedInBackground()), primary = PrimaryControl.Play))
        composeRule.onNodeWithContentDescription("Resume").assertExists()
    }

    @Test
    fun `the body is one target that opens Now Playing, and no button sits inside it`() {
        bar(loadedPlayerState(phase = PlayerPhase.Playing))
        composeRule.onNodeWithTag(MINI_BODY_TAG).assert(
            SemanticsMatcher("opens Now Playing") { node ->
                node.config.getOrNull(SemanticsActions.OnClick)?.label == "Open Now Playing"
            },
        )
        composeRule.onNode(hasContentDescription("Pause") and hasAnyAncestor(hasTestTag(MINI_BODY_TAG))).assertDoesNotExist()
        composeRule
            .onNode(hasContentDescription("Skip back 10 seconds") and hasAnyAncestor(hasTestTag(MINI_BODY_TAG)))
            .assertDoesNotExist()
    }

    @Test
    fun `the bar keeps its height through a phase change`() {
        bar(loadedPlayerState(phase = PlayerPhase.Playing))
        composeRule.onNodeWithTag(BAR).assertHeightIsEqualTo(MiniBarHeight)
    }

    @Test
    fun `on a detail screen the bar runs under the gesture area and pads its row clear of it`() {
        bar(loadedPlayerState(phase = PlayerPhase.Playing), bottomInset = WindowInsets(bottom = 24.dp))
        composeRule.onNodeWithTag(BAR).assertHeightIsEqualTo(MiniBarHeight + 24.dp)
    }

    private companion object {
        const val BAR = "bar"
    }
}
