package com.calypsan.listenup.client.features.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.client.design.components.LocalSnackbarHostState
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.SyncState
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.presentation.books.BookMultiSelectViewModel
import com.calypsan.listenup.client.presentation.library.LibraryUiState
import com.calypsan.listenup.client.presentation.library.SortCategory
import com.calypsan.listenup.client.presentation.library.SortDirection
import com.calypsan.listenup.client.presentation.library.SortState
import com.calypsan.listenup.client.testing.Windows
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val TIMEOUT_MS = 5_000L

/**
 * Where the Library carries its inbox entry, and when it leaves it out.
 *
 * The entry is drawn by [LibraryLoadedContent] rather than by the entry itself, so these render the
 * real loaded Library over a real [BookMultiSelectViewModel] and look for the entry's one TalkBack
 * sentence. Every absence is preceded by a book title appearing, so it is the gate that hid the entry
 * and not a frame that had not settled.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Windows.PHONE)
class LibraryLoadedContentInboxTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a host with a route to the inbox heads the Books grid with the entry`() {
        render(onOpenInbox = {})

        awaitText(BOOK_TITLE)
        composeRule.onNodeWithContentDescription(ENTRY).assertExists()
    }

    @Test
    fun `a host with no route to the inbox offers no entry, however many books are held`() {
        render(onOpenInbox = null)

        awaitText(BOOK_TITLE)
        composeRule.onNodeWithContentDescription(ENTRY).assertDoesNotExist()
    }

    @Test
    fun `the entry heads the Books view only, not In progress or Series`() {
        render(onOpenInbox = {})
        awaitText(BOOK_TITLE)
        composeRule.onNodeWithContentDescription(ENTRY).assertExists()

        // In progress lists the same book, so its title appearing proves the grid is drawn.
        composeRule.onNodeWithText("In progress").performClick()
        awaitText(BOOK_TITLE)
        composeRule.onNodeWithContentDescription(ENTRY).assertDoesNotExist()

        composeRule.onNodeWithText("Series").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(ENTRY).assertDoesNotExist()

        composeRule.onNodeWithText("Books").performClick()
        awaitText(BOOK_TITLE)
        composeRule.onNodeWithContentDescription(ENTRY).assertExists()
    }

    @Test
    fun `selecting books takes the entry away until the selection ends`() {
        val multiSelect = multiSelect()
        render(onOpenInbox = {}, multiSelect = multiSelect)
        awaitText(BOOK_TITLE)
        composeRule.onNodeWithContentDescription(ENTRY).assertExists()

        composeRule.runOnIdle { multiSelect.enterSelectionMode("b1") }
        awaitText(BOOK_TITLE)
        composeRule.onNodeWithContentDescription(ENTRY).assertDoesNotExist()

        composeRule.runOnIdle { multiSelect.exitSelectionMode() }
        awaitText(BOOK_TITLE)
        composeRule.onNodeWithContentDescription(ENTRY).assertExists()
    }

    private fun render(
        onOpenInbox: (() -> Unit)?,
        multiSelect: BookMultiSelectViewModel = multiSelect(),
    ) {
        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalSnackbarHostState provides SnackbarHostState()) {
                    LibraryLoadedContent(
                        state = loaded(),
                        multiSelect = multiSelect,
                        onBookClick = {},
                        onSeriesClick = {},
                        onAuthorClick = {},
                        onNarratorClick = {},
                        appHeader = { leading -> leading() },
                        onEditSelected = null,
                        heldCount = 3,
                        previewBookIds = emptyList(),
                        onOpenInbox = onOpenInbox,
                        onEvent = {},
                    )
                }
            }
        }
    }

    private fun awaitText(text: String) {
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** A real multi-select ViewModel over stubbed repositories; only its observed streams are reached. */
    private fun multiSelect(): BookMultiSelectViewModel {
        val admin =
            User(
                id = UserId("admin-1"),
                email = "admin@example.com",
                displayName = "Admin",
                isAdmin = true,
                createdAtMs = 0L,
                updatedAtMs = 0L,
            )
        return BookMultiSelectViewModel(
            userRepository =
                mock<UserRepository>(MockMode.autoUnit) {
                    every { observeCurrentUser() } returns flowOf(admin)
                },
            collectionRepository =
                mock<CollectionRepository>(MockMode.autoUnit) {
                    every { observeCollections() } returns flowOf(emptyList())
                },
            shelfRepository =
                mock<ShelfRepository>(MockMode.autoUnit) {
                    every { observeMyShelves(any()) } returns flowOf(emptyList())
                },
            addBooksToShelfUseCase = mock(MockMode.autoUnit),
            addBooksToCollectionUseCase = mock(MockMode.autoUnit),
            createShelfUseCase = mock(MockMode.autoUnit),
            createCollectionUseCase = mock(MockMode.autoUnit),
            errorBus = ErrorBus(),
        )
    }

    private fun loaded(): LibraryUiState.Loaded {
        val titleSort = SortState(SortCategory.TITLE, SortDirection.ASCENDING)
        val book = book("b1", BOOK_TITLE)
        return LibraryUiState.Loaded(
            booksSortState = titleSort,
            seriesSortState = titleSort,
            authorsSortState = titleSort,
            narratorsSortState = titleSort,
            ignoreTitleArticles = true,
            hideSingleBookSeries = false,
            contentRevision = 1L,
            books = listOf(book),
            series = emptyList(),
            authors = emptyList(),
            narrators = emptyList(),
            bookProgress = mapOf(book.id to 0.5f),
            bookIsFinished = emptyMap(),
            // In progress is a Books-grid view too: it must not carry the entry.
            booksInProgress = listOf(book),
            seriesProgress = emptyMap(),
            syncState = SyncState.Idle,
            isServerScanning = false,
            scanProgress = null,
            isBuildingInitialLibrary = false,
        )
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
        const val BOOK_TITLE = "Piranesi"
        const val ENTRY = "Inbox, 3 books waiting for review"
    }
}
