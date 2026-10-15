package com.calypsan.listenup.client.features.documentviewer

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.client.features.nowplaying.loadedPlayerState
import com.calypsan.listenup.client.features.nowplaying.samplePlayerProgress
import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.client.presentation.player.PrimaryControl
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The reader's now-playing strip (M19): the mini player's rules, in the reader's dock. */
@RunWith(RobolectricTestRunner::class)
class ReaderNowPlayingStripTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var backs = 0
    private var primaryPresses = 0
    private var keepListeningPresses = 0

    private fun strip(state: PlayerUiState.Loaded) {
        composeRule.setContent {
            MaterialTheme {
                ReaderNowPlayingStrip(
                    state = state.copy(book = state.book.copy(coverPath = "/nonexistent/cover.jpg")),
                    progress = { samplePlayerProgress },
                    onPrimary = { primaryPresses++ },
                    onSkipBack = { backs++ },
                    onKeepListening = { keepListeningPresses++ },
                )
            }
        }
    }

    @Test
    fun `playing shows the chapter line with chapter time left, back N and Pause`() {
        strip(loadedPlayerState(phase = PlayerPhase.Playing))
        composeRule.onNodeWithText("The Way of Kings").assertExists()
        composeRule.onNodeWithText("Bridge Four · 8m left").assertExists()
        composeRule.onNodeWithContentDescription("Skip back 10 seconds").performClick()
        backs shouldBe 1
        composeRule.onNodeWithContentDescription("Pause").assertExists()
    }

    @Test
    fun `a stall hides back N`() {
        strip(loadedPlayerState(phase = PlayerPhase.Buffering, primary = PrimaryControl.Busy))
        composeRule.onNodeWithContentDescription("Skip back 10 seconds").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Loading. Pause").assertExists()
    }

    @Test
    fun `a dropped connection names the busy control Reconnecting`() {
        strip(loadedPlayerState(phase = PlayerPhase.Reconnecting, primary = PrimaryControl.Busy))
        composeRule.onNodeWithText("Reconnecting…").assertExists()
        composeRule.onNodeWithContentDescription("Reconnecting. Pause").assertExists()
    }

    @Test
    fun `the sleep fade offers Keep listening in place of Pause`() {
        strip(loadedPlayerState(phase = PlayerPhase.SleepFading))
        composeRule.onNodeWithContentDescription("Pause").assertDoesNotExist()
        composeRule.onNode(hasText("Keep listening")).performClick()
        keepListeningPresses shouldBe 1
        primaryPresses shouldBe 0
    }

    @Test
    fun `a problem says why and offers its way out`() {
        strip(loadedPlayerState(phase = PlayerPhase.Unavailable(AuthError.SessionExpired()), primary = PrimaryControl.SignIn))
        composeRule.onNodeWithText("Signed out · place saved").assertExists()
        composeRule.onNodeWithText("Sign in").performClick()
        primaryPresses shouldBe 1
    }
}
