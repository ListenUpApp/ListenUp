package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.playback.NowPlayingState
import com.calypsan.listenup.client.playback.PlaybackProgress
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A small phone on its side (780×360dp) gets the side-by-side player; the whole transport and the
 * secondary actions have to be on screen without scrolling, or the layout has failed at the one
 * thing a car dock needs. Robolectric reports no system bars, so the window here is that phone's
 * 360dp less about 40dp of status and gesture bars — the height the player really gets.
 */
@Config(qualifiers = "w780dp-h320dp-land")
@RunWith(RobolectricTestRunner::class)
class WideNowPlayingShortHeightTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `on a phone held sideways the transport fits inside the window`() {
        composeRule.setContent {
            MaterialTheme {
                WideNowPlaying(
                    state = activeState,
                    progress = { progress },
                    onCollapse = {},
                    onPlayPause = {},
                    onSeek = {},
                    onSkipBack = {},
                    onSkipForward = {},
                    onPreviousChapter = {},
                    onNextChapter = {},
                    onSpeedClick = {},
                    onBoostClick = {},
                    onSleepClick = {},
                    onChaptersClick = {},
                    onGoToBook = {},
                    onGoToSeries = {},
                    onGoToContributor = {},
                    onShowAuthorPicker = {},
                    onShowNarratorPicker = {},
                    onCloseBook = {},
                    skipBackwardSec = 15,
                    skipForwardSec = 30,
                )
            }
        }

        val collapse = composeRule.onNodeWithContentDescription("Collapse player").getUnclippedBoundsInRoot()
        val play = composeRule.onNodeWithContentDescription("Play").getUnclippedBoundsInRoot()
        val skipForward = composeRule.onNodeWithContentDescription("Skip forward 30 seconds").getUnclippedBoundsInRoot()
        val chapters = composeRule.onNodeWithContentDescription("Chapters").getUnclippedBoundsInRoot()

        collapse.top shouldBeGreaterThanOrEqualTo 0.dp
        play.top shouldBeGreaterThanOrEqualTo 0.dp
        play.bottom shouldBeLessThanOrEqualTo WINDOW_HEIGHT
        skipForward.bottom shouldBeLessThanOrEqualTo WINDOW_HEIGHT
        chapters.bottom shouldBeLessThanOrEqualTo WINDOW_HEIGHT
    }

    private companion object {
        val WINDOW_HEIGHT = 320.dp

        val progress =
            PlaybackProgress(
                bookProgress = 0.25f,
                bookPositionMs = 60_000,
                bookDurationMs = 240_000,
                chapterProgress = 0.5f,
                chapterPositionMs = 30_000,
                chapterDurationMs = 60_000,
            )

        val activeState =
            NowPlayingState.Active(
                bookId = "book-1",
                title = "The Stormlight Archive: The Way of Kings, the Complete Unabridged Edition",
                author = "Brandon Sanderson",
                coverPath = "/nonexistent/cover.jpg",
                coverHash = null,
                authors = emptyList(),
                narrators = emptyList(),
                seriesId = null,
                seriesName = null,
                chapterTitle = "Prologue: To Kill",
                chapterIndex = 0,
                totalChapters = 10,
                chapters = emptyList(),
                isPlaying = false,
                isBuffering = false,
                playbackSpeed = 1f,
                defaultPlaybackSpeed = 1f,
                volumeBoostDb = 0f,
                defaultVolumeBoostDb = 0f,
            )
    }
}
