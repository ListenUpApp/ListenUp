package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
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
 * A marked book's chapters. The fixtures go through the real [groupedChapters], so nothing here
 * re-derives the outline: Credits, then Part I (folded) with 1 to 6, then Part II (folded) with 7 to 9.
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

    private fun current(index: Int) =
        CurrentChapter(
            index = index,
            number = index + 1,
            title = book[index].title,
            startMs = book[index].startTime,
            elapsedInChapterMs = 0L,
            isInPlayer = true,
        )

    private val plays = mutableListOf<Long>()

    private fun render(
        currentIndex: Int? = 6,
        canPlay: Boolean = true,
        chapters: List<Chapter> = book,
    ) {
        val rows = rowsOf(chapters, currentIndex)
        val window = currentIndex?.let { rows.subList((it - 2).coerceAtLeast(0), (it + 3).coerceAtMost(rows.size)) }
        composeRule.setContent {
            MaterialTheme {
                BookChaptersSection(
                    chapters = rows,
                    preview = window ?: rows.take(5),
                    currentChapter = currentIndex?.let(::current),
                    canPlay = canPlay,
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

    private fun expandedState(value: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

    private fun part(title: String) = composeRule.onNodeWithContentDescription(title, substring = true)

    @Test
    fun `the collapsed list names the part it sits in`() {
        render(currentIndex = 6)

        composeRule.onNodeWithText("Part I").assertIsDisplayed()
    }

    @Test
    fun `the next part's header shows where it falls in the window, with its play action`() {
        render(currentIndex = 7)

        composeRule.onNodeWithContentDescription("Play Part II").performClick()

        plays shouldBe listOf(8 * SPAN_MS)
    }

    @Test
    fun `a row keeps the number it has in the whole book, folded chapters counted`() {
        render(currentIndex = 6)

        composeRule.onNodeWithContentDescription("Play from chapter 7: 5").assertIsDisplayed()
    }

    @Test
    fun `All chapters opens the current part and leaves the others closed`() {
        render(currentIndex = 6)
        openAll()

        part("Part I,").assert(expandedState("Expanded"))
        part("Part II,").assert(expandedState("Collapsed"))
        composeRule.onNodeWithContentDescription("Play from chapter 3: 1").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Play from chapter 10: 7").assertDoesNotExist()
    }

    @Test
    fun `chapters outside any part stay in the list`() {
        render(currentIndex = 6)
        openAll()

        composeRule.onNodeWithContentDescription("Play from chapter 1: Credits").assertIsDisplayed()
    }

    @Test
    fun `tapping a part opens and closes it without playing`() {
        render(currentIndex = 6)
        openAll()

        part("Part II,").performClick()
        composeRule.onNodeWithContentDescription("Play from chapter 10: 7").assertIsDisplayed()
        part("Part II,").assert(expandedState("Expanded"))

        part("Part II,").performClick()
        composeRule.onNodeWithContentDescription("Play from chapter 10: 7").assertDoesNotExist()
        plays shouldBe emptyList()
    }

    @Test
    fun `a part's play is its own control, and starts the chapter folded into it`() {
        render(currentIndex = 6)
        openAll()

        composeRule.onNodeWithContentDescription("Play Part II").performClick()

        plays shouldBe listOf(8 * SPAN_MS)
        part("Part II,").assert(expandedState("Collapsed"))
    }

    @Test
    fun `a part says how many chapters it lists and how long it runs`() {
        render(currentIndex = 6)
        openAll()

        composeRule.onNodeWithText("3 chapters · 40m").assertIsDisplayed()
    }

    @Test
    fun `when the current chapter is the folded one, its header shows as current`() {
        render(currentIndex = 8)
        openAll()

        composeRule.onNodeWithContentDescription("Play Part II").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        composeRule.onNodeWithText("Now playing").assertIsDisplayed()
    }

    @Test
    fun `a book that cannot play can still open its parts, and plays nothing`() {
        render(currentIndex = null, canPlay = false)
        openAll()

        composeRule.onNodeWithContentDescription("Play Part II").assertIsNotEnabled()
        part("Part II,").performClick()
        composeRule.onNodeWithContentDescription("Play from chapter 10: 7").assertIsNotEnabled()
    }

    @Test
    fun `a book header is an outer tier that closes nothing`() {
        val omnibus = book.mapIndexed { i, chapter -> if (i == 1) chapter.copy(bookTitle = "Book One") else chapter }

        render(currentIndex = null, chapters = omnibus)
        openAll()

        composeRule.onNodeWithText("Book One").assertIsDisplayed()
    }
}
