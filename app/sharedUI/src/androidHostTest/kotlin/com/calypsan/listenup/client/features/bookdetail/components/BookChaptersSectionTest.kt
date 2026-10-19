package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.presentation.bookdetail.ChapterUiModel
import com.calypsan.listenup.client.presentation.bookdetail.CurrentChapter
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BookChaptersSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val all =
        (0 until 12).map { i ->
            ChapterUiModel(
                id = "c$i",
                title = "Chapter title ${i + 1}",
                duration = "10:00",
                imageUrl = null,
                isCurrent = i == 5,
                startMs = i * 600_000L,
                durationMs = 600_000L,
            )
        }
    private val window = all.subList(3, 8)
    private val current =
        CurrentChapter(index = 5, number = 6, title = "Chapter title 6", startMs = 3_000_000L, elapsedInChapterMs = 720_000L)

    private fun set(
        canPlay: Boolean = true,
        onPlay: (Long) -> Unit = {},
    ) = composeRule.setContent {
        MaterialTheme {
            BookChaptersSection(
                chapters = all,
                preview = window,
                currentChapter = current,
                canPlay = canPlay,
                onPlayFromChapter = onPlay,
            )
        }
    }

    @Test
    fun `the collapsed list is the five-row window around the current chapter`() {
        set()
        composeRule.onNodeWithContentDescription("Play from chapter 4: Chapter title 4").assertExists()
        composeRule.onNodeWithContentDescription("Play from chapter 8: Chapter title 8").assertExists()
        composeRule.onNodeWithContentDescription("Play from chapter 3: Chapter title 3").assertDoesNotExist()
    }

    @Test
    fun `each row is a button named Play from chapter N, and a tap plays from its start`() {
        var started: Long? = null
        set(onPlay = { started = it })
        composeRule
            .onNodeWithContentDescription("Play from chapter 7: Chapter title 7")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()
        started shouldBe 3_600_000L
    }

    @Test
    fun `the current row says how far in it is`() {
        set()
        composeRule.onNodeWithText("Now playing · 12m in", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `the current row is selected`() {
        set()
        composeRule
            .onNodeWithContentDescription("Play from chapter 6: Chapter title 6")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
    }

    @Test
    fun `All 12 chapters expands the list in place`() {
        set()
        composeRule.onNodeWithText("All 12 chapters").performClick()
        composeRule.onNodeWithContentDescription("Play from chapter 1: Chapter title 1").assertExists()
        composeRule.onNodeWithContentDescription("Play from chapter 12: Chapter title 12").assertExists()
        composeRule.onNodeWithText("All 12 chapters").assertDoesNotExist()
    }

    @Test
    fun `when the book can't play the rows are disabled`() {
        var started: Long? = null
        set(canPlay = false, onPlay = { started = it })
        composeRule.onNodeWithContentDescription("Play from chapter 6: Chapter title 6").assertIsNotEnabled()
        started shouldBe null
    }
}
