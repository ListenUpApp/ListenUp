package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.playback.SleepTimerMode
import com.calypsan.listenup.client.playback.SleepTimerState
import com.calypsan.listenup.client.testing.assertTouchTargetAtLeast
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** §5.5.2 (PT1–PT8) on Android: the choices, the running timer, and the fade. */
@Config(qualifiers = "en-rUS-w412dp-h915dp")
@RunWith(RobolectricTestRunner::class)
class SleepTimerSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val set = mutableListOf<SleepTimerMode>()
    private var cancelled = 0
    private val extended = mutableListOf<Int>()
    private var keptListening = 0
    private var dismissed = 0

    private fun sheet(state: SleepSheetState = sleepSheetState()) {
        composeRule.setContent {
            MaterialTheme {
                SleepTimerSheet(
                    state = state,
                    onSetTimer = { set += it },
                    onCancelTimer = { cancelled++ },
                    onExtendTimer = { extended += it },
                    onKeepListening = { keptListening++ },
                    onDismiss = { dismissed++ },
                )
            }
        }
    }

    private fun top(text: String) = composeRule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun `End of chapter comes first, with the chapter and the time`() {
        sheet()
        composeRule.onNodeWithText("Pauses after Bridge Four · in 8m").assertExists()
        top("End of chapter") shouldBeLessThan top("15 min")
    }

    @Test
    fun `a whole-book has no End of chapter`() {
        sheet(sleepSheetState(isWholeBook = true))
        composeRule.onNodeWithText("End of chapter").assertDoesNotExist()
    }

    @Test
    fun `the durations read 15 min, 30 min, 45 min, 1 hour, 2 hours`() {
        sheet()
        listOf("15 min", "30 min", "45 min", "1 hour", "2 hours").forEach {
            composeRule.onNodeWithText(it).assertExists()
        }
    }

    @Test
    fun `picking a duration or End of chapter reports it`() {
        sheet()
        composeRule.onNodeWithText("30 min").performClick()
        composeRule.onNodeWithText("End of chapter").performClick()
        set shouldBe listOf(SleepTimerMode.Duration(30), SleepTimerMode.EndOfChapter)
    }

    @Test
    fun `the custom stepper starts at 20, moves in fives, and Start reports the minutes`() {
        sheet()
        composeRule.onNodeWithText("20 min").assertExists()
        repeat(2) { composeRule.onNodeWithContentDescription("5 minutes more").performClick() }
        composeRule.onNodeWithText("Start").performClick()
        set shouldBe listOf(SleepTimerMode.Duration(30))
    }

    @Test
    fun `the stepper's minus is disabled at 5`() {
        sheet()
        repeat(3) { composeRule.onNodeWithContentDescription("5 minutes less").performClick() }
        composeRule.onNodeWithText("5 min").assertExists()
        composeRule.onNodeWithContentDescription("5 minutes less").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("5 minutes more").assertIsEnabled()
    }

    @Test
    fun `Start and every duration keep a 48dp target`() {
        sheet()
        (listOf("Start", "End of chapter") + listOf("15 min", "30 min", "45 min", "1 hour", "2 hours")).forEach {
            composeRule.onNodeWithText(it).assertTouchTargetAtLeast()
        }
        composeRule.onNodeWithContentDescription("5 minutes less").assertTouchTargetAtLeast()
        composeRule.onNodeWithContentDescription("5 minutes more").assertTouchTargetAtLeast()
    }
}
