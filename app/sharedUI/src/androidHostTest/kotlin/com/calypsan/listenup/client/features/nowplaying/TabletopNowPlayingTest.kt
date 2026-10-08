package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.foldable.Fold
import com.calypsan.listenup.client.foldable.LocalFold
import com.calypsan.listenup.client.foldable.Posture
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
 * A foldable half open on a table (the Pixel Fold's inner screen, hinge across the middle) splits
 * Now Playing at the fold: the cover and the title stand up on the top half, the scrubber and the
 * transport lie on the bottom half under the user's hand, and nothing sits across the crease.
 */
@Config(qualifiers = "w673dp-h841dp")
@RunWith(RobolectricTestRunner::class)
class TabletopNowPlayingTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the cover and title sit above the hinge and the transport below it`() {
        setContent(hingeTop = 400.dp, hingeBottom = 424.dp)

        val cover = composeRule.onNodeWithTag(NOW_PLAYING_COVER_TAG).getUnclippedBoundsInRoot()
        // The cover's own fallback also prints the title; this is the line under it.
        val title =
            composeRule
                .onNode(hasText(activeState.title) and !hasAnyAncestor(hasTestTag(NOW_PLAYING_COVER_TAG)))
                .getUnclippedBoundsInRoot()
        val chapter = composeRule.onNodeWithText("Prologue: To Kill").getUnclippedBoundsInRoot()
        val play = composeRule.onNodeWithContentDescription("Play").getUnclippedBoundsInRoot()
        val skipForward = composeRule.onNodeWithContentDescription("Skip forward 30 seconds").getUnclippedBoundsInRoot()
        val chapters = composeRule.onNodeWithContentDescription("Chapters").getUnclippedBoundsInRoot()

        cover.bottom shouldBeLessThanOrEqualTo 400.dp
        title.bottom shouldBeLessThanOrEqualTo 400.dp
        chapter.bottom shouldBeLessThanOrEqualTo 400.dp
        play.top shouldBeGreaterThanOrEqualTo 424.dp
        skipForward.top shouldBeGreaterThanOrEqualTo 424.dp
        chapters.top shouldBeGreaterThanOrEqualTo 424.dp
        chapters.bottom shouldBeLessThanOrEqualTo WINDOW_HEIGHT
    }

    @Test
    fun `a hairline fold still keeps the halves apart`() {
        setContent(hingeTop = 420.dp, hingeBottom = 420.dp)

        composeRule.onNodeWithTag(NOW_PLAYING_COVER_TAG).getUnclippedBoundsInRoot().bottom shouldBeLessThanOrEqualTo
            420.dp
        composeRule.onNodeWithContentDescription("Play").getUnclippedBoundsInRoot().top shouldBeGreaterThanOrEqualTo
            420.dp
    }

    private fun setContent(
        hingeTop: Dp,
        hingeBottom: Dp,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            val hinge =
                with(density) {
                    IntRect(0, hingeTop.roundToPx(), 673.dp.roundToPx(), hingeBottom.roundToPx())
                }
            CompositionLocalProvider(LocalFold provides Fold(Posture.TABLETOP, hinge)) {
                MaterialTheme {
                    NowPlayingScreen(
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
                        onChaptersClick = {},
                        onSleepTimerClick = {},
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
        }
    }

    private companion object {
        val WINDOW_HEIGHT = 841.dp

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
                title = "The Way of Kings",
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
