package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.presentation.player.PrimaryControl
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Gap 6 / spec §11 row 1: the mini player no longer hides when a book fails. It keeps the bar, names the
 * cause on its second line, and its main button offers the way out.
 */
@RunWith(RobolectricTestRunner::class)
class MiniPlayerErrorTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a failed book keeps the bar, says why, and its button retries`() {
        var primaryTaps = 0
        composeRule.setContent {
            MaterialTheme {
                NowPlayingBar(
                    state =
                        loadedPlayerState(
                            phase = PlayerPhase.Failed(PlaybackError.ConnectionLost()),
                            primary = PrimaryControl.Retry,
                            // A path (even a missing one) keeps the cover off the Koin-backed fetch.
                            coverPath = "/nonexistent/cover.jpg",
                        ),
                    progress = { samplePlayerProgress },
                    isExpanded = false,
                    onTap = {},
                    onPrimary = { primaryTaps++ },
                    onSkipBack = {},
                    skipBackwardSec = 10,
                )
            }
        }

        composeRule.onNodeWithText("Couldn't play this book.").assertExists()
        composeRule.onNodeWithContentDescription("Retry").performClick()
        primaryTaps shouldBe 1
    }

    @Test
    fun `an unavailable book keeps the bar and names the reason`() {
        composeRule.setContent {
            MaterialTheme {
                NowPlayingBar(
                    state =
                        loadedPlayerState(
                            phase = PlayerPhase.Unavailable(PlaybackError.OfflineNotDownloaded()),
                            primary = PrimaryControl.Retry,
                            // A path (even a missing one) keeps the cover off the Koin-backed fetch.
                            coverPath = "/nonexistent/cover.jpg",
                        ),
                    progress = { samplePlayerProgress },
                    isExpanded = false,
                    onTap = {},
                    onPrimary = {},
                    onSkipBack = {},
                    skipBackwardSec = 10,
                )
            }
        }

        composeRule.onNodeWithText("You're offline.").assertExists()
        composeRule.onNodeWithText("Bridge Four").assertDoesNotExist()
    }
}
