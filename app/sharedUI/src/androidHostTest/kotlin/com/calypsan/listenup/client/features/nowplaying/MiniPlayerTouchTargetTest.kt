package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.presentation.player.PrimaryControl
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
    fun `the phone bar's controls are 48dp and the bar keeps its height`() {
        composeRule.setContent {
            MaterialTheme {
                NowPlayingBar(
                    state = activeState,
                    progress = { progress },
                    isExpanded = false,
                    onTap = {},
                    onPrimary = {},
                    onSkipBack = {},
                    onKeepListening = {},
                    skipBackwardSec = 15,
                    modifier = Modifier.testTag(BAR),
                )
            }
        }

        composeRule.onNodeWithTag(BAR).assertHeightIsEqualTo(MiniBarHeight)
        control("Skip back 15 seconds").assertIsAtLeastMinimumTarget()
        control("Play").assertIsAtLeastMinimumTarget()
    }

    @Test
    fun `the tablet toolbar's controls are 48dp`() {
        composeRule.setContent {
            MaterialTheme {
                NowPlayingToolbar(
                    state = activeState,
                    progress = { progress },
                    isExpanded = false,
                    onTap = {},
                    onPrimary = {},
                    onSkipBack = {},
                    onSkipForward = {},
                    onSeek = {},
                    onSpeedClick = {},
                    onSleepClick = {},
                    onChaptersClick = {},
                    onKeepListening = {},
                    skipBackwardSec = 15,
                    skipForwardSec = 30,
                    modifier = Modifier.testTag(BAR),
                )
            }
        }

        control("Skip back 15 seconds").assertIsAtLeastMinimumTarget()
        control("Play").assertIsAtLeastMinimumTarget()
        control("Skip forward 30 seconds").assertIsAtLeastMinimumTarget()
        control("Open Now Playing").assertIsAtLeastMinimumTarget()
    }

    private fun control(label: String): SemanticsNodeInteraction = composeRule.onNodeWithContentDescription(label, useUnmergedTree = false)

    private fun SemanticsNodeInteraction.assertIsAtLeastMinimumTarget() {
        assertWidthIsAtLeast(48.dp)
        assertHeightIsAtLeast(48.dp)
    }

    private companion object {
        const val BAR = "bar"

        val progress = samplePlayerProgress

        val activeState =
            loadedPlayerState(
                phase = PlayerPhase.Paused,
                primary = PrimaryControl.Play,
                chapterTitle = "Prologue",
                coverPath = "/nonexistent/cover.jpg",
            )
    }
}
