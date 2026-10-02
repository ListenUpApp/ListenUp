package com.calypsan.listenup.client.features.library.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.SyncState
import com.calypsan.listenup.client.presentation.library.SortCategory
import com.calypsan.listenup.client.presentation.library.SortDirection
import com.calypsan.listenup.client.presentation.library.SortState
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Library's inbox entry: absent at zero, one button that says how many books wait and opens the
 * inbox, and the first item of the Books grid. Covers are left out of the preview here because a
 * cover without a local path takes the Koin-backed loader; the fan is decoration (hidden from
 * TalkBack), so the contract under test is the count, the words and the tap.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryInboxEntryTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var opens = 0

    @Test
    fun `nothing held, no entry — not even a placeholder`() {
        setEntry(heldCount = 0)

        composeRule.onNodeWithText(SUBTITLE).assertDoesNotExist()
    }

    @Test
    fun `the entry says how many books wait, and what for`() {
        setEntry(heldCount = 3)

        composeRule.onNodeWithText("Inbox · 3 new books").assertIsDisplayed()
        composeRule.onNodeWithText(SUBTITLE).assertIsDisplayed()
    }

    @Test
    fun `one book is one book`() {
        setEntry(heldCount = 1)

        composeRule.onNodeWithText("Inbox · 1 new book").assertIsDisplayed()
    }

    @Test
    fun `the whole entry is one button that opens the inbox`() {
        setEntry(heldCount = 3)

        composeRule
            .onNodeWithContentDescription("Inbox, 3 books waiting for review")
            .assertHasClickAction()
            .performClick()
        composeRule.runOnIdle { opens shouldBe 1 }
    }

    @Test
    fun `the Books grid carries its header above the books`() {
        composeRule.setContent {
            MaterialTheme {
                BooksContent(
                    books = listOf(book("b1", "Piranesi")),
                    hasLoadedBooks = true,
                    syncState = SyncState.Idle,
                    isServerScanning = false,
                    sortState = SortState(SortCategory.TITLE, SortDirection.ASCENDING),
                    ignoreTitleArticles = true,
                    bookProgress = emptyMap(),
                    onCategorySelected = {},
                    onDirectionToggle = {},
                    onToggleIgnoreArticles = {},
                    onBookClick = {},
                    onRetry = {},
                    header = { Text(HEADER) },
                )
            }
        }

        composeRule.onNodeWithText(HEADER).assertIsDisplayed()
        composeRule.onNodeWithText("Piranesi").assertIsDisplayed()
    }

    @Test
    fun `an empty library still shows its header`() {
        composeRule.setContent {
            MaterialTheme {
                BooksContent(
                    books = emptyList(),
                    hasLoadedBooks = true,
                    syncState = SyncState.Idle,
                    isServerScanning = false,
                    sortState = SortState(SortCategory.TITLE, SortDirection.ASCENDING),
                    ignoreTitleArticles = true,
                    bookProgress = emptyMap(),
                    onCategorySelected = {},
                    onDirectionToggle = {},
                    onToggleIgnoreArticles = {},
                    onBookClick = {},
                    onRetry = {},
                    header = { Text(HEADER) },
                )
            }
        }

        // An admin whose every new book is held has an empty grid; the entry is how they find them.
        composeRule.onNodeWithText(HEADER).assertIsDisplayed()
    }

    private fun setEntry(heldCount: Int) {
        composeRule.setContent {
            MaterialTheme {
                LibraryInboxEntry(heldCount = heldCount, previewBookIds = emptyList(), onOpenInbox = { opens++ })
            }
        }
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
        // A local path keeps BookCoverImage off the Koin-backed async fallback.
        coverPath = "/tmp/cover-$id.webp",
        addedAt = Timestamp(0L),
        updatedAt = Timestamp(0L),
    )

    private companion object {
        const val SUBTITLE = "Waiting for you to release"
        const val HEADER = "INBOX ENTRY"
    }
}
