package com.calypsan.listenup.client.features.library.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
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
 * The phone Library's inbox strip (spec a6): one line, "Inbox · 3 waiting", absent at zero, one button that names how
 * many books wait and opens the inbox, and the first item of the Books grid.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryInboxTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var opens = 0

    @Test
    fun `nothing held, no strip — not even a placeholder`() {
        setStrip(heldCount = 0)

        composeRule.onAllNodesWithContentDescription(ENTRY, substring = true).fetchSemanticsNodes().size shouldBe 0
    }

    @Test
    fun `the strip is one line saying how many books wait`() {
        setStrip(heldCount = 3)

        composeRule.onNodeWithText("Inbox · 3 waiting", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `the whole strip is one button, named for TalkBack, that opens the inbox`() {
        setStrip(heldCount = 3)

        composeRule
            .onNodeWithContentDescription(ENTRY)
            .assertHasClickAction()
            .performClick()
        composeRule.runOnIdle { opens shouldBe 1 }
    }

    @Test
    fun `the strip is a full touch target`() {
        setStrip(heldCount = 3)

        composeRule.onNodeWithContentDescription(ENTRY).assertHeightIsAtLeast(48.dp)
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
                    onCategorySelected = {},
                    onDirectionToggle = {},
                    onToggleIgnoreArticles = {},
                    onBookClick = {},
                    onRetry = {},
                    header = { Text(HEADER) },
                )
            }
        }

        // An admin whose every new book is held has an empty grid; the strip is how they find them.
        composeRule.onNodeWithText(HEADER).assertIsDisplayed()
    }

    private fun setStrip(heldCount: Int) {
        composeRule.setContent {
            MaterialTheme {
                LibraryInboxStrip(heldCount = heldCount, onOpenInbox = { opens++ })
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
        const val ENTRY = "Inbox, 3 books waiting for review"
        const val HEADER = "INBOX ENTRY"
    }
}
