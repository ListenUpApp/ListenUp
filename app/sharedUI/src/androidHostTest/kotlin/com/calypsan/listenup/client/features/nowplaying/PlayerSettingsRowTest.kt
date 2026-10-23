package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.features.nowplaying.components.PlayerSettingsRow
import com.calypsan.listenup.client.playback.SleepTimerMode
import com.calypsan.listenup.client.playback.SleepTimerState
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** §5.2's settings row: Speed with its rate, Sleep (armed: the countdown), Chapters; no Boost. */
@Config(qualifiers = "w412dp-h915dp")
@RunWith(RobolectricTestRunner::class)
class PlayerSettingsRowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val opened = mutableListOf<String>()

    private fun row(
        sleep: SleepTimerState = SleepTimerState.Inactive,
        showsChapters: Boolean = true,
    ) {
        composeRule.setContent {
            MaterialTheme {
                PlayerSettingsRow(
                    speed = 1.25f,
                    sleepTimer = sleep,
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
}
