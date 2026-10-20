package com.calypsan.listenup.client.features.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.client.design.components.LocalSnackbarHostState
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.SyncState
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.presentation.books.BookMultiSelectViewModel
import com.calypsan.listenup.client.presentation.books.SelectionMode
import com.calypsan.listenup.client.presentation.library.BookCardStatus
import com.calypsan.listenup.client.presentation.library.BookStatusCounts
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
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val TIMEOUT_MS = 5_000L

/**
 * In the Library a long press opens the book's menu, not selection (Simon's M3E call; Home, Discover and shelves keep
 * long press = Select). Select is one tap inside the menu, and starts selecting with that book.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Windows.PHONE)
class LibraryLongPressTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val played = mutableListOf<String>()

    @Test
    fun `long-pressing a card opens its menu and does not enter selection`() {
        val multiSelect = multiSelect()
        render(multiSelect)
        awaitText(BOOK_TITLE)

        composeRule.onAllNodesWithText(BOOK_TITLE).onFirst().performTouchInput { longClick() }

        composeRule.onNodeWithText("Resume · 30m").assertExists()
        composeRule.runOnIdle { multiSelect.selectionMode.value shouldBe SelectionMode.None }
    }

    @Test
    fun `choosing Select enters selection with that book`() {
        val multiSelect = multiSelect()
        render(multiSelect)
        awaitText(BOOK_TITLE)

        composeRule.onAllNodesWithText(BOOK_TITLE).onFirst().performTouchInput { longClick() }
        composeRule.onNodeWithText("Select").performClick()

        composeRule.runOnIdle { multiSelect.selectionMode.value shouldBe SelectionMode.Active(setOf("b1")) }
    }

    @Test
    fun `the primary plays the book through the host's play entry`() {
        render(multiSelect())
        awaitText(BOOK_TITLE)

        composeRule.onAllNodesWithText(BOOK_TITLE).onFirst().performTouchInput { longClick() }
        composeRule.onNodeWithText("Resume · 30m").performClick()

        composeRule.runOnIdle { played shouldBe listOf("b1") }
    }

    @Test
    fun `an admin's menu carries the Admin group`() {
        render(multiSelect())
        awaitText(BOOK_TITLE)

        composeRule.onAllNodesWithText(BOOK_TITLE).onFirst().performTouchInput { longClick() }

        composeRule.onNodeWithText("Add to collection…").assertExists()
        composeRule.onNodeWithText("Edit details").assertExists()
    }

    @Test
    fun `Select all in the header takes every book the grid shows`() {
        val multiSelect = multiSelect()
        render(multiSelect)
        awaitText(BOOK_TITLE)

        // Armed with nothing picked yet: Select all is what puts the book in.
        composeRule.runOnIdle { multiSelect.enterSelectionMode() }
        composeRule.onNodeWithText("Select all 1").performClick()

        composeRule.runOnIdle { multiSelect.selectionMode.value shouldBe SelectionMode.Active(setOf("b1")) }
    }

    private fun render(multiSelect: BookMultiSelectViewModel) {
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
                        heldCount = 0,
                        onOpenInbox = null,
                        onEvent = {},
                        onPlayBook = { played += it },
                        onShareBook = { _, _ -> },
                        onEditBook = {},
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
            seriesProgress = emptyMap(),
            syncState = SyncState.Idle,
            isServerScanning = false,
            scanProgress = null,
            isBuildingInitialLibrary = false,
            statusCounts = BookStatusCounts(all = 1, inProgress = 1, notStarted = 0, finished = 0),
            bookStatus = mapOf(book.id to BookCardStatus.InProgress(fraction = 0.5f, timeLeftMs = book.duration / 2)),
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
    }
}
