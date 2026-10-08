package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.calypsan.listenup.client.playback.NowPlayingState
import com.calypsan.listenup.client.playback.PlaybackProgress
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
                        onPlayPause = {},
                        onSkipBack = {},
                        skipBackwardSec = 15,
                    )
                }
            }
        }
    }

    @Test
    fun `at the largest font the cover steps aside for the title`() {
        bar(fontScale = 2f)
        composeRule.onNodeWithContentDescription("Book cover").assertDoesNotExist()
    }

    @Test
    fun `at the default font the cover stays`() {
        bar(fontScale = 1f)
        composeRule.onNodeWithContentDescription("Book cover").assertExists()
    }

    private companion object {
        val PROGRESS =
            PlaybackProgress(
                bookProgress = 0.25f,
                bookPositionMs = 60_000,
                bookDurationMs = 240_000,
                chapterProgress = 0.5f,
                chapterPositionMs = 30_000,
                chapterDurationMs = 60_000,
            )

        val ACTIVE =
            NowPlayingState.Active(
                bookId = "book-1",
                title = "The Kaiju Preservation Society",
                author = "John Scalzi",
                coverPath = "/nonexistent/cover.jpg",
                coverHash = null,
                authors = emptyList(),
                narrators = emptyList(),
                seriesId = null,
                seriesName = null,
                chapterTitle = "Chapter One",
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
