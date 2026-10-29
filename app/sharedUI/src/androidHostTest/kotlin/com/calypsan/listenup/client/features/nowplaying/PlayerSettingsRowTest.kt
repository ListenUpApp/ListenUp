package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.features.nowplaying.components.PlayerSettingsRow
import com.calypsan.listenup.client.playback.SleepTimerMode
import com.calypsan.listenup.client.playback.SleepTimerState
import com.calypsan.listenup.client.presentation.player.PlayerProgress
import com.calypsan.listenup.client.presentation.player.SleepCountdowns
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** §5.2's settings row: Speed with its rate, Sleep (armed: the countdown), Chapters; no Boost, no Cast. */
@Config(qualifiers = "w412dp-h915dp")
@RunWith(RobolectricTestRunner::class)
class PlayerSettingsRowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val opened = mutableListOf<String>()

    private fun row(
        sleep: SleepTimerState = SleepTimerState.Inactive,
        showsChapters: Boolean = true,
        progress: PlayerProgress = samplePlayerProgress,
    ) {
        composeRule.setContent {
            MaterialTheme {
                PlayerSettingsRow(
                    speed = 1.25f,
                    sleepTimer = sleep,
                    sleepCountdownMs = { SleepCountdowns.remainingMs(sleep, progress, speed = 1f) },
                    showsChapters = showsChapters,
                    onSpeedClick = { opened += "speed" },
                    onSleepClick = { opened += "sleep" },
                    onChaptersClick = { opened += "chapters" },
                )
            }
        }
    }

    @Test
    fun `speed shows its rate, and each control opens its sheet`() {
        row()
        composeRule.onNodeWithText("1.25×").performClick()
        composeRule.onNodeWithContentDescription("Sleep timer").performClick()
        composeRule.onNodeWithContentDescription("Chapters").performClick()
        opened shouldBe listOf("speed", "sleep", "chapters")
    }

    @Test
    fun `the row is Speed, Sleep and Chapters, with no Cast, which lives beside More`() {
        row()
        val speed = composeRule.onNodeWithText("1.25×").getUnclippedBoundsInRoot()
        val sleep = composeRule.onNodeWithContentDescription("Sleep timer").getUnclippedBoundsInRoot()
        val chapters = composeRule.onNodeWithContentDescription("Chapters").getUnclippedBoundsInRoot()
        (speed.right <= sleep.left && sleep.right <= chapters.left) shouldBe true
        composeRule.onNodeWithContentDescription("Cast", substring = true).assertDoesNotExist()
    }

    @Test
    fun `there is no boost control in the row`() {
        row()
        composeRule.onNodeWithText("0 dB").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Volume boost").assertDoesNotExist()
    }

    @Test
    fun `an armed timer shows its countdown`() {
        row(sleep = SleepTimerState.Active(SleepTimerMode.Duration(30), remainingMs = 1_421_000, totalMs = 1_800_000, startedAt = 0))
        composeRule.onNodeWithText("23:41").assertExists()
        composeRule.onNodeWithContentDescription("Sleep timer, 23:41 left").assertExists()
    }

    @Test
    fun `chapters can be left out where the list is always visible`() {
        row(showsChapters = false)
        composeRule.onNodeWithContentDescription("Chapters").assertDoesNotExist()
    }

    @Test
    fun `an armed end-of-chapter timer counts down to the chapter's end, not 0 00`() {
        row(
            sleep = SleepTimerState.Active(SleepTimerMode.EndOfChapter, remainingMs = 0, totalMs = 0, startedAt = 0),
            progress = EIGHT_MINUTES_OF_CHAPTER_LEFT,
        )
        composeRule.onNodeWithText("8:00").assertExists()
        composeRule.onNodeWithText("0:00").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Sleep timer, 8:00 left").assertExists()
    }

    private companion object {
        val EIGHT_MINUTES_OF_CHAPTER_LEFT =
            PlayerProgress(
                chapterPositionMs = 755_000,
                chapterDurationMs = 1_235_000,
                bookPositionMs = 18_000_000,
                bookDurationMs = 163_800_000,
            )
    }
}
