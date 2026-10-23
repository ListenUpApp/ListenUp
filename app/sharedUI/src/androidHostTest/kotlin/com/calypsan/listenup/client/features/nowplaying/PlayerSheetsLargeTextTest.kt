package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.playback.SleepTimerMode
import com.calypsan.listenup.client.playback.SleepTimerState
import com.calypsan.listenup.client.testing.AtFontScale
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §8's guards for the Now Playing sheets, written before the sheets were rebuilt: at Android's largest font every sheet
 * scrolls to its last control (PR 3's review found the speed and sleep sheets running off the bottom), and every
 * control in them keeps a 48dp target.
 */
@Config(qualifiers = "w412dp-h915dp")
@RunWith(RobolectricTestRunner::class)
class PlayerSheetsLargeTextTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun at2x(content: @Composable () -> Unit) =
        composeRule.setContent { AtFontScale { MaterialTheme { content() } } }

    @Composable
    private fun SpeedSheet() =
        PlaybackSpeedSheet(
            currentSpeed = 1.25f,
            defaultSpeed = 1f,
            bookRemainingAtSpeedMs = { 116_640_000 },
            onSpeedChange = {},
            onStep = {},
            onResetToDefault = {},
            onDismiss = {},
        )

    @Test
    fun `at the largest font the speed sheet scrolls to its reset`() {
        at2x { SpeedSheet() }
        composeRule.onNodeWithText("Reset to 1.0× (your default)").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `at the largest font the idle sleep sheet scrolls to Start`() {
        at2x {
            SleepTimerSheet(
                state = sleepSheetState(),
                onSetTimer = {},
                onCancelTimer = {},
                onExtendTimer = {},
                onKeepListening = {},
                onDismiss = {},
            )
        }
        composeRule.onNodeWithText("Start").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `at the largest font the running sleep sheet scrolls to Cancel timer`() {
        at2x {
            SleepTimerSheet(
                state =
                    sleepSheetState(
                        timer =
                            SleepTimerState.Active(
                                SleepTimerMode.Duration(30),
                                remainingMs = 1_421_000,
                                totalMs = 1_800_000,
                                startedAt = 0,
                            ),
                    ),
                onSetTimer = {},
                onCancelTimer = {},
                onExtendTimer = {},
                onKeepListening = {},
                onDismiss = {},
            )
        }
        composeRule.onNodeWithText("Cancel timer").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `at the largest font the boost sheet scrolls to its note`() {
        at2x {
            VolumeBoostSheet(
                currentBoostDb = 6f,
                defaultBoostDb = 0f,
                onBoostChange = {},
                onResetToDefault = {},
                onDismiss = {},
            )
        }
        composeRule
            .onNodeWithText("Louder for quiet recordings. Applies to this book only.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `every control in the speed sheet is at least 48dp`() {
        at2x { SpeedSheet() }
        val minimum = with(composeRule.density) { 48.dp.toPx() }
        composeRule.onAllNodes(hasClickAction()).fetchSemanticsNodes().forEach { node ->
            withClue(node.config.toString()) { (node.touchBoundsInRoot.height >= minimum) shouldBe true }
        }
    }
}
