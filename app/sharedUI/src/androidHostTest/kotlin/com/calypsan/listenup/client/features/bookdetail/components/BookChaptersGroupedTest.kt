package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.presentation.bookdetail.ChapterUiModel
import com.calypsan.listenup.client.presentation.bookdetail.CurrentChapter
import com.calypsan.listenup.client.presentation.bookdetail.groupedChapters
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val SPAN_MS = 600_000L

/**
 * A marked book's chapters under part dividers. The fixtures go through the real [groupedChapters],
 * so nothing here re-derives the outline: Credits, then Part I (its own "Part I" chapter, then 1 to 6),
 * then Part II (its own "Part II" chapter, then 7 to 9). Every chapter is a row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w800dp-h3000dp")
class BookChaptersGroupedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val book: List<Chapter> =
        listOf("Credits", "Part I", "1", "2", "3", "4", "5", "6", "Part II", "7", "8", "9").mapIndexed { i, title ->
            Chapter(
                id = "c$i",
                title = title,
                duration = SPAN_MS,
                startTime = i * SPAN_MS,
                partTitle = title.takeIf { it.startsWith("Part") },
            )
        }

    private fun rowsOf(
        chapters: List<Chapter>,
        currentIndex: Int?,
    ) = chapters.mapIndexed { i, chapter ->
        ChapterUiModel(
            id = chapter.id,
            title = chapter.title,
            duration = "10:00",
            imageUrl = null,
            isCurrent = i == currentIndex,
            startMs = chapter.startTime,
            durationMs = chapter.duration,
        )
    }

    private fun current(
        chapters: List<Chapter>,
        index: Int,
    ) = CurrentChapter(
        index = index,
        number = index + 1,
        title = chapters[index].title,
        startMs = chapters[index].startTime,
        elapsedInChapterMs = 0L,
        isInPlayer = true,
    )

    private val plays = mutableListOf<Long>()

    private fun render(
        currentIndex: Int? = 6,
        chapters: List<Chapter> = book,
    ) {
        composeRule.setContent {
            val rows = rowsOf(chapters, currentIndex)
            val window =
                currentIndex?.let { rows.subList((it - 2).coerceAtLeast(0), (it + 3).coerceAtMost(rows.size)) }
            MaterialTheme {
                BookChaptersSection(
                    chapters = rows,
                    preview = window ?: rows.take(5),
                    currentChapter = currentIndex?.let { current(chapters, it) },
                    canPlay = true,
                    onPlayFromChapter = { plays += it },
                    grouped = chapters.groupedChapters(currentIndex),
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun openAll() {
        composeRule.onNodeWithText("All 12 chapters").performClick()
        composeRule.waitForIdle()
    }

    private fun divider(sentence: String) = composeRule.onNodeWithContentDescription(sentence)

    private fun chapterRow(label: String) = composeRule.onNodeWithContentDescription(label)

    private val notAButton = SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick)

    private val neverCurrent = SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected)

    @Test
    fun `every chapter the audiobook has is a row, the part's own chapter included, numbered in audio order`() {
        render(currentIndex = 6)
        openAll()

        chapterRow("Play from chapter 1: Credits").assertIsDisplayed()
        chapterRow("Play from chapter 2: Part I").assertIsDisplayed()
        chapterRow("Play from chapter 9: Part II").assertIsDisplayed()
        chapterRow("Play from chapter 12: 9").assertIsDisplayed()
    }

    @Test
    fun `a divider is a heading that says its title, how many chapters it holds and how long it runs`() {
        render(currentIndex = 6)
        openAll()

        divider("Part I, 7 chapters, 1 hour 10 minutes").assert(isHeading()).assert(notAButton)
        divider("Part II, 4 chapters, 40 minutes").assert(isHeading()).assert(notAButton)
    }

    @Test
    fun `a divider shows its count and length quietly under its title`() {
        render(currentIndex = 6)
        openAll()

        composeRule.onNodeWithText("4 chapters · 40m", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `the collapsed window is headed by the part it starts inside, and shows the divider it crosses`() {
        render(currentIndex = 6)

        divider("Part I, 7 chapters, 1 hour 10 minutes").assertIsDisplayed()
        divider("Part II, 4 chapters, 40 minutes").assertIsDisplayed()
        // Five rows: chapters 5 to 9, the last of them Part II's own chapter.
        chapterRow("Play from chapter 5: 3").assertIsDisplayed()
        chapterRow("Play from chapter 9: Part II").assertIsDisplayed()
        chapterRow("Play from chapter 10: 7").assertDoesNotExist()
    }

    @Test
    fun `a window inside one part shows no other part's divider`() {
        render(currentIndex = 3)

        divider("Part I, 7 chapters, 1 hour 10 minutes").assertIsDisplayed()
        divider("Part II, 4 chapters, 40 minutes").assertDoesNotExist()
    }

    @Test
    fun `only the row is current, never its part's divider`() {
        render(currentIndex = 8)
        openAll()

        chapterRow("Play from chapter 9: Part II").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        divider("Part II, 4 chapters, 40 minutes").assert(neverCurrent)
    }

    @Test
    fun `a part's own chapter plays like any other row`() {
        render(currentIndex = 6)
        openAll()

        chapterRow("Play from chapter 9: Part II").performClick()

        plays shouldBe listOf(8 * SPAN_MS)
    }

    @Test
    fun `an omnibus heads each book above its parts, and numbers its chapters from 1 again`() {
        val omnibus =
            book.mapIndexed { i, chapter ->
                when (i) {
                    0 -> chapter.copy(bookTitle = "Book One")
                    8 -> chapter.copy(bookTitle = "Book Two")
                    else -> chapter
                }
            }

        render(currentIndex = 9, chapters = omnibus)
        openAll()

        composeRule.onNodeWithText("Book Two").assert(isHeading()).assert(notAButton)
        chapterRow("Play from chapter 8: 6").assertIsDisplayed()
        chapterRow("Play from chapter 1: Part II").assertIsDisplayed()
        // Indexed by its place in the whole list, not by its number: the row plays its own chapter.
        chapterRow("Play from chapter 2: 7").performClick()
        plays shouldBe listOf(9 * SPAN_MS)
    }
}
