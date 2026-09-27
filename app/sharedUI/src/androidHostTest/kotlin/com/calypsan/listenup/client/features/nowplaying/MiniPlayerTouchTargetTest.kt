package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.playback.NowPlayingState
import com.calypsan.listenup.client.playback.PlaybackProgress
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The mini-player controls are the most-used targets in the app, pressed one-handed and often
 * without looking. Each gets a full 48dp target while the bars themselves keep their height.
 */
@Config(qualifiers = "w1280dp-h800dp")
@RunWith(RobolectricTestRunner::class)
class MiniPlayerTouchTargetTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the floating mini-player's controls are 48dp and the card keeps its height`() {
        composeRule.setContent {
            MaterialTheme {
                NowPlayingBar(
                    state = activeState,
                    progress = { progress },
                    isExpanded = false,
                    onTap = {},
                    onPlayPause = {},
                    onSkipBack = {},
                    skipBackwardSec = 15,
                    modifier = Modifier.testTag(BAR),
                )
            }
        }

        composeRule.onNodeWithTag(BAR).assertHeightIsEqualTo(FLOATING_BAR_HEIGHT)
        control("Skip backward 15 seconds").assertIsAtLeastMinimumTarget()
        control("Play").assertIsAtLeastMinimumTarget()
    }

    @Test
    fun `the docked bar's controls are 48dp and the bar keeps its height`() {
        composeRule.setContent {
            MaterialTheme {
                DockedNowPlayingBar(
                    state = activeState,
                    progress = { progress },
                    isExpanded = false,
                    onTap = {},
                    onPlayPause = {},
                    onSkipBack = {},
                    onSkipForward = {},
                    onSeek = {},
                    onSpeedClick = {},
                    skipBackwardSec = 15,
                    skipForwardSec = 30,
                    modifier = Modifier.testTag(BAR),
                )
            }
        }

        composeRule.onNodeWithTag(BAR).assertHeightIsEqualTo(DOCKED_BAR_HEIGHT)
        control("Skip backward 15 seconds").assertIsAtLeastMinimumTarget()
        control("Play").assertIsAtLeastMinimumTarget()
        control("Skip forward 30 seconds").assertIsAtLeastMinimumTarget()
        control("Expand").assertIsAtLeastMinimumTarget()
    }

    private fun control(label: String): SemanticsNodeInteraction = composeRule.onNodeWithContentDescription(label, useUnmergedTree = false)

    private fun SemanticsNodeInteraction.assertIsAtLeastMinimumTarget() {
        assertWidthIsAtLeast(48.dp)
        assertHeightIsAtLeast(48.dp)
    }

    private companion object {
        const val BAR = "bar"

        // Both heights were measured before the targets grew; they must not move.
        val FLOATING_BAR_HEIGHT = 100.dp
        val DOCKED_BAR_HEIGHT = 72.dp

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
                chapterTitle = "Prologue",
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
