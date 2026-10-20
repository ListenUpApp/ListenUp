package com.calypsan.listenup.client.features.library.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.SyncState
import com.calypsan.listenup.client.presentation.library.BookMenuPrimary
import com.calypsan.listenup.client.presentation.library.SortCategory
import com.calypsan.listenup.client.presentation.library.SortDirection
import com.calypsan.listenup.client.presentation.library.SortState
import com.calypsan.listenup.client.testing.Windows
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Board `Library-AndroidPhone-ContextMenu`: a book's menu hangs below its cover. A card low in the viewport scrolls up
 * enough for the menu to fit beneath it, rather than the menu flipping up over the chips or over the cover itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Windows.PHONE)
class LibraryBookMenuPlacementTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a card low in the viewport still gets its menu below the cover`() {
        composeRule.setContent {
            MaterialTheme {
                BooksContent(
                    books = (1..20).map { book("b$it", "Book $it") },
                    hasLoadedBooks = true,
                    syncState = SyncState.Idle,
                    isServerScanning = false,
                    sortState = SortState(SortCategory.ADDED, SortDirection.DESCENDING),
                    ignoreTitleArticles = true,
                    onCategorySelected = {},
                    onDirectionToggle = {},
                    onToggleIgnoreArticles = {},
                    onBookClick = {},
                    onRetry = {},
                    bookMenu =
                        LibraryBookMenuHost(
                            primaryFor = { BookMenuPrimary.Play },
                            actionsFor = {
                                LibraryBookMenuActions(
                                    onPrimary = {},
                                    onAddToShelf = {},
                                    onShare = {},
                                    onSelect = {},
                                    onAddToCollection = {},
                                    onEditDetails = {},
                                )
                            },
                        ),
                )
            }
        }

        composeRule.onNodeWithText("Book 3").performTouchInput { longClick() }
        composeRule.waitForIdle()

        // Screen positions: the menu is a popup with a window of its own. The card is square-covered, so its cover's
        // foot is its top plus its width.
        val card = composeRule.onNodeWithText("Book 3").fetchSemanticsNode()
        val coverFoot = card.positionOnScreen.y + card.size.width
        val menuTop =
            composeRule
                .onNodeWithText("Play")
                .fetchSemanticsNode()
                .positionOnScreen.y
        menuTop shouldBeGreaterThanOrEqual coverFoot - COVER_FOOT_SLACK
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
        /** The menu's own top padding and rounding between the cover's foot and its first row. */
        const val COVER_FOOT_SLACK = 4f
    }
}
