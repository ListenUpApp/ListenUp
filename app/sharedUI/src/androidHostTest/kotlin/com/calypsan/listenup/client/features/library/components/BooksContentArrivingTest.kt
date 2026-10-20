package com.calypsan.listenup.client.features.library.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.SyncState
import com.calypsan.listenup.client.presentation.library.LibraryStatusStrip
import com.calypsan.listenup.client.presentation.library.SortCategory
import com.calypsan.listenup.client.presentation.library.SortDirection
import com.calypsan.listenup.client.presentation.library.SortState
import com.calypsan.listenup.client.testing.Windows
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A Library still arriving never says it is empty: the "isSyncing lies" regression, where a first catch-up over a
 * `Connected` socket fell through to "No audiobooks yet" while thousands of books were on their way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Windows.PHONE)
class BooksContentArrivingTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `an empty grid while arriving says it is arriving, with placeholders, never empty`() {
        render(books = emptyList(), strip = LibraryStatusStrip.Arriving(booksSoFar = 0))

        composeRule.onNodeWithText("Building your library", useUnmergedTree = true).assertExists()
        composeRule.onAllNodesWithContentDescription(PLACEHOLDER).fetchSemanticsNodes().size shouldBe 1
        composeRule.onAllNodesWithText("No audiobooks yet").fetchSemanticsNodes().size shouldBe 0
    }

    @Test
    fun `a settled empty library still says it is empty`() {
        render(books = emptyList(), strip = LibraryStatusStrip.Hidden)

        composeRule.onNodeWithText("No audiobooks yet").assertExists()
        composeRule.onAllNodesWithTag(PLACEHOLDER_TAG, useUnmergedTree = true).fetchSemanticsNodes().size shouldBe 0
    }

    @Test
    fun `books while arriving are followed by four placeholders, one named for TalkBack`() {
        render(books = listOf(book("b1", "Babel"), book("b2", "Circe")), strip = LibraryStatusStrip.Arriving(booksSoFar = 2))

        composeRule.onNodeWithText("Babel").assertExists()
        composeRule.onNodeWithText("Building your library", useUnmergedTree = true).assertExists()
        composeRule.onAllNodesWithTag(PLACEHOLDER_TAG, useUnmergedTree = true).fetchSemanticsNodes().size shouldBe 4
        composeRule.onAllNodesWithContentDescription(PLACEHOLDER).fetchSemanticsNodes().size shouldBe 1
    }

    @Test
    fun `a settled grid has no placeholders`() {
        render(books = listOf(book("b1", "Babel")), strip = LibraryStatusStrip.Hidden)

        composeRule.onNodeWithText("Babel").assertExists()
        composeRule.onAllNodesWithTag(PLACEHOLDER_TAG, useUnmergedTree = true).fetchSemanticsNodes().size shouldBe 0
    }

    private fun render(
        books: List<BookListItem>,
        strip: LibraryStatusStrip,
    ) {
        composeRule.setContent {
            MaterialTheme {
                BooksContent(
                    books = books,
                    hasLoadedBooks = true,
                    syncState = SyncState.Idle,
                    isServerScanning = false,
                    sortState = SortState(SortCategory.ADDED, SortDirection.DESCENDING),
                    ignoreTitleArticles = true,
                    statusStrip = strip,
                    onCategorySelected = {},
                    onDirectionToggle = {},
                    onToggleIgnoreArticles = {},
                    onBookClick = {},
                    onRetry = {},
                )
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
        const val PLACEHOLDER = "More books arriving"
        const val PLACEHOLDER_TAG = "library-arriving-placeholder"
    }
}
