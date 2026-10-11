package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.presentation.bookdetail.CurrentChapter
import com.calypsan.listenup.client.presentation.bookdetail.FinishedSpan
import com.calypsan.listenup.client.presentation.bookdetail.ReadingStage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ResumeUnitTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `in progress shows the current chapter, the time left and an 11 percent bar`() {
        composeRule.setContent {
            MaterialTheme {
                ResumeUnit(
                    readingStage = ReadingStage.InProgress,
                    currentChapter =
                        CurrentChapter(index = 5, number = 6, title = "Bridge Four", startMs = 0, elapsedInChapterMs = 720_000),
                    timeRemainingMs = (40 * 60 + 31) * 60_000L,
                    progress = 0.11f,
                    finishedSpan = null,
                )
            }
        }
        composeRule.onNodeWithText("Ch. 6 · Bridge Four").assertExists()
        composeRule.onNodeWithText("40h 31m left").assertExists()
        composeRule
            .onNodeWithContentDescription("11 percent listened")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
    }

    @Test
    fun `not started draws nothing`() {
        composeRule.setContent {
            MaterialTheme {
                ResumeUnit(ReadingStage.NotStarted, currentChapter = null, timeRemainingMs = null, progress = null, finishedSpan = null)
            }
        }
        composeRule.onNodeWithContentDescription("percent listened", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("left", substring = true).assertDoesNotExist()
    }

    @Test
    fun `finished replaces the bar with the dates it was read between`() {
        composeRule.setContent {
            MaterialTheme {
                ResumeUnit(
                    readingStage = ReadingStage.Finished,
                    currentChapter = null,
                    timeRemainingMs = null,
                    progress = null,
                    finishedSpan = FinishedSpan(startedAtMs = 1_770_854_400_000L, finishedAtMs = 1_772_496_000_000L),
                )
            }
        }
        composeRule.onNodeWithText("Finished ·", substring = true).assertExists()
        composeRule.onNodeWithText("2026", substring = true).assertExists()
        composeRule.onNodeWithContentDescription("percent listened", substring = true).assertDoesNotExist()
    }

    @Test
    fun `finished with no recorded date still says Finished`() {
        composeRule.setContent {
            MaterialTheme {
                ResumeUnit(ReadingStage.Finished, currentChapter = null, timeRemainingMs = null, progress = null, finishedSpan = null)
            }
        }
        composeRule.onNodeWithText("Finished").assertExists()
    }
}
