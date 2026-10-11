package com.calypsan.listenup.client.features.library.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.SyncState
import com.calypsan.listenup.client.presentation.library.BookStatusFilter
import com.calypsan.listenup.client.presentation.library.SortState
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Drive 5a, bug 1: a status filter opens its list at the top, not wherever All was scrolled to. */
@RunWith(RobolectricTestRunner::class)
class BooksContentFilterScrollTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val all = (10..69).map { book("b$it", "Book $it") }

    // Every other book, so the one All was scrolled to is still in the filtered list: a grid that
    // keeps its position by key would hold onto it and open part-way down.
    private val finished = all.filterIndexed { index, _ -> index % 2 == 0 }

    @Test
    fun `changing the status filter scrolls the grid to the top`() {
        var filter by mutableStateOf(BookStatusFilter.ALL)
        composeRule.setContent {
            MaterialTheme {
                BooksContent(
                    books = if (filter == BookStatusFilter.ALL) all else finished,
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
                )
            }
        }
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(41)
        composeRule.waitForIdle()

        composeRule.runOnIdle { filter = BookStatusFilter.FINISHED }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Book 10").assertIsDisplayed()
    }

    private fun book(
        id: String,
        title: String,
    ) = BookListItem(
        id = BookId(id),
        libraryId = LibraryId("lib"),
        folderId = FolderId("folder"),
        title = title,
        authors = emptyList(),
        narrators = emptyList(),
        duration = 3_600_000L,
        // A local path keeps BookCoverImage off the Koin-backed fallback.
        coverPath = "/tmp/cover-$id.webp",
        addedAt = Timestamp(0L),
        updatedAt = Timestamp(0L),
    )
}
