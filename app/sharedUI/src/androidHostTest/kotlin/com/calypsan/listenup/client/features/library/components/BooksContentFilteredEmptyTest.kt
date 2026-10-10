package com.calypsan.listenup.client.features.library.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.domain.model.SyncState
import com.calypsan.listenup.client.presentation.library.BookStatusFilter
import com.calypsan.listenup.client.presentation.library.SortState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
}
