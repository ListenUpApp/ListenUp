package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.playback.SleepTimerMode
import com.calypsan.listenup.client.playback.SleepTimerState
import com.calypsan.listenup.client.testing.assertTouchTargetAtLeast
import com.calypsan.listenup.client.testing.isLiveRegion
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.util.TimeZone
import org.junit.After
import org.junit.Before
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

    private lateinit var originalZone: TimeZone

    @Before
    fun useUtc() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreZone() {
        TimeZone.setDefault(originalZone)
    }

    private fun hasTextMatching(pattern: Regex) =
        SemanticsMatcher("text matches $pattern") { node ->
            node.config
                .getOrNull(SemanticsProperties.Text)
                .orEmpty()
                .any { pattern.matches(it.text) }
        }

    private fun top(text: String) =
        composeRule
            .onNodeWithText(text)
            .fetchSemanticsNode()
            .boundsInRoot.top

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

    @Test
    fun `running - the countdown, Pauses at, the timer's length, and a meter of what is left`() {
        sheet(sleepSheetState(timer = THIRTY_MINUTES_RUNNING))
        composeRule.onNodeWithText("23:41").assertExists()
        composeRule.onNode(hasTextMatching(Regex("""Pauses at 11:42\s?PM · 30 min timer"""))).assertExists()
        val meter = composeRule.onNodeWithContentDescription("23:41 of 30:00 remaining")
        meter
            .fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo]
            .current shouldBe (0.789f plusOrMinus 0.001f)
    }

    @Test
    fun `the countdown is not a live region`() {
        sheet(sleepSheetState(timer = THIRTY_MINUTES_RUNNING))
        composeRule.onNode(hasText("23:41") and isLiveRegion()).assertDoesNotExist()
    }

    @Test
    fun `plus 10 reports 10`() {
        sheet(sleepSheetState(timer = THIRTY_MINUTES_RUNNING))
        composeRule.onNodeWithText("+10 min").performClick()
        extended shouldBe listOf(10)
    }

    @Test
    fun `Cancel keeps the sheet open`() {
        sheet(sleepSheetState(timer = THIRTY_MINUTES_RUNNING))
        composeRule.onNodeWithText("Cancel timer").performClick()
        cancelled shouldBe 1
        dismissed shouldBe 0
    }

    @Test
    fun `end of chapter running names the chapter and offers no extend`() {
        sheet(
            sleepSheetState(
                timer = SleepTimerState.Active(SleepTimerMode.EndOfChapter, remainingMs = 0, totalMs = 0, startedAt = 0),
                countdownMs = 480_000,
                meterFraction = 0.4f,
            ),
        )
        composeRule.onNodeWithText("8:00").assertExists()
        composeRule.onNodeWithText("Until the end of Bridge Four · 8m").assertExists()
        composeRule.onNodeWithText("+5 min").assertDoesNotExist()
        composeRule.onNodeWithText("Cancel timer").assertExists()
    }

    @Test
    fun `fading - the gold card's words and Keep listening`() {
        sheet(sleepSheetState(timer = SleepTimerState.FadingOut))
        composeRule.onNodeWithText("Fading out…").assertExists()
        composeRule.onNodeWithText("Your sleep timer is ending. Playback pauses in a moment.").assertExists()
        composeRule.onNodeWithText("Keep listening").performClick()
        keptListening shouldBe 1
    }

    private companion object {
        val THIRTY_MINUTES_RUNNING =
            SleepTimerState.Active(SleepTimerMode.Duration(30), remainingMs = 1_421_000, totalMs = 1_800_000, startedAt = 0)
    }
}
