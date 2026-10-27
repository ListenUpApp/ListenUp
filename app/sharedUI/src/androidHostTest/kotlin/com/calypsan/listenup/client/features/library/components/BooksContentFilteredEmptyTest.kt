package com.calypsan.listenup.client.features.library.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.SyncState
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.presentation.library.BookStatusCounts
import com.calypsan.listenup.client.presentation.library.BookStatusFilter
import com.calypsan.listenup.client.presentation.library.SortState
import io.kotest.matchers.shouldBe
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs

/** Spec §3.1.1 / Risk 1: a filter that matches nothing never tells the reader their library is empty. */
@RunWith(RobolectricTestRunner::class)
class BooksContentFilteredEmptyTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a filter that matches nothing says so and offers Show all books, not the empty library`() {
        var showedAll = false
        composeRule.setContent {
            MaterialTheme {
                BooksContent(
                    books = emptyList(),
                    hasLoadedBooks = true,
                    syncState = SyncState.Idle,
                    isServerScanning = false,
                    sortState = SortState.booksDefault,
                    ignoreTitleArticles = true,
                    onCategorySelected = {},
                    onDirectionToggle = {},
                    onToggleIgnoreArticles = {},
                    onBookClick = {},
                    onRetry = {},
                    isFilteredEmpty = true,
                    statusFilter = BookStatusFilter.FINISHED,
                    onShowAllBooks = { showedAll = true },
                )
            }
        }

        composeRule.onNodeWithText("No finished books yet.").assertIsDisplayed()
        composeRule.onNodeWithText("Add audiobooks to your server to get started").assertDoesNotExist()
        composeRule.onNodeWithText("Show all books").performClick()

        assertTrue(showedAll)
    }

    private fun piranesi() =
        BookListItem(
            id = BookId("b1"),
            libraryId = LibraryId("lib"),
            folderId = FolderId("folder"),
            title = "Piranesi",
            authors = emptyList(),
            narrators = emptyList(),
            duration = 3_600_000L,
            // A local path keeps BookCoverImage off the Koin-backed fallback.
            coverPath = "/tmp/cover-b1.webp",
            addedAt = Timestamp(0L),
            updatedAt = Timestamp(0L),
        )

    private fun books(
        counts: BookStatusCounts,
        filter: BookStatusFilter = BookStatusFilter.ALL,
    ) = composeRule.setContent {
        MaterialTheme {
            BooksContent(
                books = listOf(piranesi()),
                hasLoadedBooks = true,
                syncState = SyncState.Idle,
                isServerScanning = false,
                sortState = SortState.booksDefault,
                ignoreTitleArticles = true,
                onCategorySelected = {},
                onDirectionToggle = {},
                onToggleIgnoreArticles = {},
                onBookClick = {},
                onRetry = {},
                statusFilter = filter,
                statusCounts = counts,
            )
        }
    }

    @Test
    fun `the Sort row leads with the book count`() {
        books(BookStatusCounts(all = 248, inProgress = 4, notStarted = 183, finished = 61))

        val count = composeRule.onNodeWithText("248 books").getUnclippedBoundsInRoot()
        val trigger = composeRule.onNodeWithContentDescription("Filter and sort: All, Title, A–Z").getUnclippedBoundsInRoot()
        (count.right <= trigger.left) shouldBe true
        (abs((count.top + count.bottom - trigger.top - trigger.bottom).value) / 2 <= 12f) shouldBe true
    }

    @Test
    fun `the count is the books shown, and one book reads 1 book`() {
        books(BookStatusCounts(all = 248, inProgress = 1, notStarted = 186, finished = 61), filter = BookStatusFilter.IN_PROGRESS)

        composeRule.onNodeWithText("1 book").assertIsDisplayed()
        composeRule.onNodeWithText("1 books").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Filter and sort: In progress, Title, A–Z").assertIsDisplayed()
    }
}
