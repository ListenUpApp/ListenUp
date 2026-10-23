package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.client.features.nowplaying.components.MiniLineFit
import com.calypsan.listenup.client.features.nowplaying.components.MiniPlayerLineText
import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.playback.SleepTimerMode
import com.calypsan.listenup.client.playback.SleepTimerState
import com.calypsan.listenup.client.presentation.player.AudioSource
import com.calypsan.listenup.client.presentation.player.MiniPlayerLines
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.client.presentation.player.PrimaryControl
import com.calypsan.listenup.client.presentation.player.SleepCountdowns
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The mini line's words per state, from the `Main`, `Mini-AndroidTablet` and `States-Android` boards. A chapter is
 * named by the book's own title, never by our counter ("Ch. 6"), which counts front matter (Simon, 2026-10-14).
 */
@RunWith(RobolectricTestRunner::class)
class MiniPlayerLineTextTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun line(
        state: PlayerUiState.Loaded,
        fit: MiniLineFit = MiniLineFit.WithTimeLeft,
    ) {
        composeRule.setContent {
            MaterialTheme {
                MiniPlayerLineText(
                    line = MiniPlayerLines.of(state),
                    progress = { samplePlayerProgress },
                    sleepRemainingMs = { SleepCountdowns.remainingMs(state.sleepTimer, samplePlayerProgress, state.settings.speed) },
                    fit = fit,
                )
            }
        }
    }

    @Test
    fun `playing reads the chapter's own title and the chapter time left`() {
        line(loadedPlayerState(phase = PlayerPhase.Playing))
        composeRule.onNodeWithText("Bridge Four · 8m left").assertExists()
    }

    @Test
    fun `the tablet toolbar drops the time, which sits beside its scrubber`() {
        line(loadedPlayerState(phase = PlayerPhase.Playing), MiniLineFit.WithoutTime)
        composeRule.onNodeWithText("Bridge Four").assertExists()
    }

    @Test
    fun `paused says Paused before the chapter`() {
        line(loadedPlayerState(phase = PlayerPhase.Paused, primary = PrimaryControl.Play))
        composeRule.onNodeWithText("Paused · Bridge Four · 8m left").assertExists()
    }

    @Test
    fun `a running sleep timer replaces the time left`() {
        val timer =
            SleepTimerState.Active(SleepTimerMode.Duration(30), remainingMs = 1_380_000, totalMs = 1_800_000, startedAt = 0)
        line(loadedPlayerState(phase = PlayerPhase.Playing, sleepTimer = timer))
        composeRule.onNodeWithText("Bridge Four · Sleep · 23m").assertExists()
    }

    @Test
    fun `an armed end-of-chapter timer counts down to the chapter's end, never Sleep 0m`() {
        val timer = SleepTimerState.Active(SleepTimerMode.EndOfChapter, remainingMs = 0, totalMs = 0, startedAt = 0)
        line(loadedPlayerState(phase = PlayerPhase.Playing, sleepTimer = timer))
        composeRule.onNodeWithText("Bridge Four · Sleep · 8m").assertExists()
    }

    @Test
    fun `a generic chapter title is shown as the book names it`() {
        line(loadedPlayerState(phase = PlayerPhase.Playing, chapterTitle = "Chapter 14", isGenericTitle = true))
        composeRule.onNodeWithText("Chapter 14 · 8m left").assertExists()
    }

    @Test
    fun `an untitled chapter falls back to its number`() {
        line(loadedPlayerState(phase = PlayerPhase.Playing, chapterTitle = ""))
        composeRule.onNodeWithText("Chapter 6 · 8m left").assertExists()
    }

    @Test
    fun `a chapterless book reads Whole book`() {
        line(loadedPlayerState(phase = PlayerPhase.Playing, isWholeBook = true))
        composeRule.onNodeWithText("Whole book · 8m left").assertExists()
    }

    @Test
    fun `casting names the device and the chapter`() {
        line(loadedPlayerState(phase = PlayerPhase.Playing, source = AudioSource.Casting("Living Room")))
        composeRule.onNodeWithText("Playing on Living Room · Bridge Four").assertExists()
    }

    @Test
    fun `preparing reads Starting`() {
        line(loadedPlayerState(phase = PlayerPhase.Preparing))
        composeRule.onNodeWithText("Starting…").assertExists()
    }

    @Test
    fun `buffering reads Loading`() {
        line(loadedPlayerState(phase = PlayerPhase.Buffering))
        composeRule.onNodeWithText("Loading…").assertExists()
    }

    @Test
    fun `a dropped connection reads Reconnecting`() {
        line(loadedPlayerState(phase = PlayerPhase.Reconnecting))
        composeRule.onNodeWithText("Reconnecting…").assertExists()
    }

    @Test
    fun `the sleep fade says it is fading out`() {
        line(loadedPlayerState(phase = PlayerPhase.SleepFading))
        composeRule.onNodeWithText("Sleep timer · fading out").assertExists()
    }

    @Test
    fun `finished reads Finished`() {
        line(loadedPlayerState(phase = PlayerPhase.Finished))
        composeRule.onNodeWithText("Finished").assertExists()
    }

    @Test
    fun `a problem has its short mini line`() {
        line(loadedPlayerState(phase = PlayerPhase.Unavailable(PlaybackError.OfflineNotDownloaded())))
        composeRule.onNodeWithText("Can't play offline").assertExists()
    }

    @Test
    fun `a lapsed session says the place is saved`() {
        line(loadedPlayerState(phase = PlayerPhase.Unavailable(AuthError.SessionExpired())))
        composeRule.onNodeWithText("Signed out · place saved").assertExists()
    }

    @Test
    fun `a problem with no short line falls back to the card title`() {
        line(loadedPlayerState(phase = PlayerPhase.Failed(PlaybackError.ConnectionLost())))
        composeRule.onNodeWithText("Couldn't play this book.").assertExists()
    }
}
