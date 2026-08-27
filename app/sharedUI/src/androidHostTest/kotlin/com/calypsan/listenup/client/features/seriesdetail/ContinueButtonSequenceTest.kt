package com.calypsan.listenup.client.features.seriesdetail

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.BookSeries
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesDetailUiState
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import kotlin.time.Duration.Companion.hours
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The series "Continue / Start Book N" pill — the loudest control on the screen.
 *
 * It read the raw `Double` position, so a whole-numbered book offered "Continue Book 1.0". Same
 * defect as the book-detail series chip, in a second place, which is why the formatting now lives
 * on the model as `sequenceLabel` rather than being re-applied at each call site. These assert the
 * rendered button text so a future site that reaches past the label is caught here.
 */
@RunWith(RobolectricTestRunner::class)
class ContinueButtonSequenceTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun render(
        sequence: Double?,
        inProgress: Boolean,
    ) {
        val bookId = BookId("b1")
        val book =
            BookListItem(
                id = bookId,
                title = "The Final Empire",
                series = listOf(BookSeries(seriesId = "s1", seriesName = "Mistborn", sequence = sequence)),
                coverPath = null,
                authors = emptyList(),
                narrators = emptyList(),
                duration = 1.hours.inWholeMilliseconds,
                libraryId = LibraryId("lib1"),
                folderId = FolderId("folder1"),
                addedAt = Timestamp(0L),
                updatedAt = Timestamp(0L),
            )
        composeRule.setContent {
            MaterialTheme {
                ContinueButton(
                    state =
                        SeriesDetailUiState.Ready(
                            seriesId = "s1",
                            seriesName = "Mistborn",
                            seriesDescription = null,
                            seriesAuthors = emptyList(),
                            seriesNarrator = null,
                            coverPath = null,
                            featuredBookId = null,
                            totalDuration = 1.hours,
                            books = listOf(book),
                            bookProgress = if (inProgress) mapOf(bookId to 0.4f) else emptyMap(),
                            finishedBookIds = emptySet(),
                            resumeTarget = bookId,
                        ),
                    onBookClick = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `a whole-numbered book reads Continue Book 1, not Book 1_0`() {
        render(sequence = 1.0, inProgress = true)

        composeRule.onNodeWithText("Continue Book 1").assertExists()
        composeRule.onNodeWithText("Continue Book 1.0").assertDoesNotExist()
    }

    @Test
    fun `an unstarted whole-numbered book reads Start Book 2`() {
        render(sequence = 2.0, inProgress = false)

        composeRule.onNodeWithText("Start Book 2").assertExists()
        composeRule.onNodeWithText("Start Book 2.0").assertDoesNotExist()
    }

    @Test
    fun `a fractional book keeps its position`() {
        render(sequence = 1.5, inProgress = true)

        composeRule.onNodeWithText("Continue Book 1.5").assertExists()
    }

    @Test
    fun `an unnumbered book falls back to its place in the list`() {
        render(sequence = null, inProgress = true)

        composeRule.onNodeWithText("Continue Book 1").assertExists()
    }
}
