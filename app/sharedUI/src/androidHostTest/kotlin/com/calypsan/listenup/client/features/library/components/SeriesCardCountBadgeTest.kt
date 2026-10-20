package com.calypsan.listenup.client.features.library.components

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.Series
import com.calypsan.listenup.client.domain.model.SeriesProgress
import com.calypsan.listenup.client.domain.model.SeriesWithBooks
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.Timestamp
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A series card (spec c6) shows up to three covers and, for a series of more than one book, how many it holds. */
@RunWith(RobolectricTestRunner::class)
class SeriesCardCountBadgeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun card(bookCount: Int) {
        val books = (1..bookCount).map(::book)
        composeRule.setContent {
            SeriesCard(
                seriesWithBooks =
                    SeriesWithBooks(
                        series = Series(id = SeriesId("dcc"), name = "Dungeon Crawler Carl"),
                        books = books,
                        bookSequences = emptyMap(),
                    ),
                progress = SeriesProgress(finishedCount = 0, totalCount = bookCount),
                onClick = {},
            )
        }
    }

    @Test
    fun `a seven-book series draws at most three covers and a 7 badge, read as 7 books`() {
        card(bookCount = 7)

        // What TalkBack reads: covers parked at the back of the deck are not on screen, so they are not read.
        val covers = (1..7).sumOf { composeRule.onAllNodesWithContentDescription("Book $it").fetchSemanticsNodes().size }
        covers shouldBe 3
        // The numeral is drawn under the badge's one name.
        composeRule.onNodeWithContentDescription("7 books", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `a one-book series has no badge`() {
        card(bookCount = 1)

        composeRule.onNodeWithContentDescription("1 books", useUnmergedTree = true).assertDoesNotExist()
    }

    private fun book(n: Int) =
        BookListItem(
            id = BookId("b$n"),
            libraryId = LibraryId("lib"),
            folderId = FolderId("folder"),
            title = "Book $n",
            authors = emptyList(),
            narrators = emptyList(),
            duration = 3_600_000L,
            // A local path keeps BookCoverImage off the Koin-backed async fallback.
            coverPath = "/tmp/cover-b$n.webp",
            addedAt = Timestamp(0L),
            updatedAt = Timestamp(0L),
        )
}
