package com.calypsan.listenup.client.features.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import com.calypsan.listenup.client.features.library.components.INBOX_PILL_TAG
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.client.design.components.LocalSnackbarHostState
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.SyncState
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.presentation.books.BookMultiSelectViewModel
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
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

private const val TIMEOUT_MS = 5_000L

/**
 * The Android tablet Library (≥ 840dp, spec §3.1.3) leads with two header bands: the title, its count line, the
 * sections and the Inbox pill on one row; Filter and sort on the next. A phone keeps its own header.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryTabletHeaderTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = Windows.TABLET)
    fun `at 1280dp band 1 holds Library, the count line, the four sections and the Inbox pill`() {
        render(heldCount = 3)
        awaitText(COUNT_LINE)

        val title = composeRule.onNodeWithText("Library").getUnclippedBoundsInRoot()
        val count = composeRule.onNodeWithText(COUNT_LINE).getUnclippedBoundsInRoot()
        val books = composeRule.onNodeWithText("Books").getUnclippedBoundsInRoot()
        val narrators = composeRule.onNodeWithText("Narrators").getUnclippedBoundsInRoot()
        // The pill shares the phone strip's spoken name: one inbox, one sentence.
        composeRule.onNodeWithTag(INBOX_PILL_TAG).assertContentDescriptionEquals(PILL)
        val pill = composeRule.onNodeWithTag(INBOX_PILL_TAG).getUnclippedBoundsInRoot()

        listOf(count.top, books.top, narrators.top, pill.top).forEach { top ->
            abs((top - title.top).value).dp shouldBeLessThanOrEqualTo 48.dp
        }
        val windowRight = composeRule.onRoot().getUnclippedBoundsInRoot().right
        windowRight - pill.right shouldBeLessThanOrEqualTo Spacing.screenMargin + 1.dp
    }

    @Test
    @Config(qualifiers = Windows.TABLET)
    fun `band 2 holds the sort, no chips`() {
        render(heldCount = 3)
        awaitText(COUNT_LINE)

        val sort = composeRule.onNodeWithContentDescription(SORT).getUnclippedBoundsInRoot()
        val books = composeRule.onNodeWithText("Books").getUnclippedBoundsInRoot()
        // Below band 1, at the end of the row.
        (sort.top >= books.bottom) shouldBe true
        val windowRight = composeRule.onRoot().getUnclippedBoundsInRoot().right
        windowRight - sort.right shouldBeLessThanOrEqualTo Spacing.screenMargin + 1.dp
        composeRule.onNodeWithText("All 248").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = Windows.PHONE)
    fun `on a phone there is no status chip row, and the count leads the sort row`() {
        render(heldCount = 3)
        awaitText(BOOK_TITLE)

        composeRule.onNodeWithText(COUNT_LINE).assertDoesNotExist()
        composeRule.onNodeWithTag(INBOX_PILL_TAG).assertDoesNotExist()
        composeRule.onNodeWithText("All 248").assertDoesNotExist()
        val sort = composeRule.onNodeWithContentDescription(SORT).getUnclippedBoundsInRoot()
        val count = composeRule.onNodeWithText("248 books").getUnclippedBoundsInRoot()
        (count.right <= sort.left) shouldBe true
    }

    @Test
    @Config(qualifiers = Windows.TABLET)
    fun `the pill is absent at zero held`() {
        render(heldCount = 0)
        awaitText(COUNT_LINE)

        composeRule.onNodeWithTag(INBOX_PILL_TAG).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = Windows.TABLET)
    fun `the pill is absent for a host with no route to the inbox`() {
        render(heldCount = 3, onOpenInbox = null)
        awaitText(COUNT_LINE)

        composeRule.onNodeWithTag(INBOX_PILL_TAG).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = Windows.TABLET)
    fun `entering selection keeps band 1 and band 2 still`() {
        val multiSelect = multiSelect()
        render(heldCount = 3, multiSelect = multiSelect)
        awaitText(COUNT_LINE)
        val booksBefore = composeRule.onNodeWithText("Books").getUnclippedBoundsInRoot()
        val narratorsBefore = composeRule.onNodeWithText("Narrators").getUnclippedBoundsInRoot()
        val sortBefore = composeRule.onNodeWithContentDescription(SORT).getUnclippedBoundsInRoot()

        composeRule.runOnIdle { multiSelect.enterSelectionMode("b1") }
        composeRule.waitForIdle()

        // The sections do not jump wider into the pill's place: its space is kept while it is out of reach.
        composeRule.onNodeWithText("Books").getUnclippedBoundsInRoot() shouldBe booksBefore
        composeRule.onNodeWithText("Narrators").getUnclippedBoundsInRoot() shouldBe narratorsBefore
        composeRule.onNodeWithContentDescription(PILL).assertDoesNotExist()
        // The trigger stays, stilled while books are picked: filtering mid-selection would change what is being picked.
        composeRule.onNodeWithContentDescription(SORT).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(SORT).getUnclippedBoundsInRoot() shouldBe sortBefore
    }

    private fun render(
        heldCount: Int,
        onOpenInbox: (() -> Unit)? = {},
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
                        heldCount = heldCount,
                        // A cover without a local path takes the Koin-backed loader; the fan is decoration.
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
            userRepository = mock<UserRepository>(MockMode.autoUnit) { every { observeCurrentUser() } returns flowOf(admin) },
            collectionRepository =
                mock<CollectionRepository>(MockMode.autoUnit) { every { observeCollections() } returns flowOf(emptyList()) },
            shelfRepository =
                mock<ShelfRepository>(MockMode.autoUnit) { every { observeMyShelves(any()) } returns flowOf(emptyList()) },
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
            statusCounts = BookStatusCounts(all = 248, inProgress = 12, notStarted = 175, finished = 61),
            totalDurationMs = 41L * 86_400_000 + 3_600_000,
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
        coverPath = "/tmp/cover-$id.webp",
        addedAt = Timestamp(0L),
        updatedAt = Timestamp(0L),
    )

    private companion object {
        const val BOOK_TITLE = "Piranesi"
        const val COUNT_LINE = "248 books · 41 days of listening"
        const val PILL = "Inbox, 3 books waiting for review"
        const val SORT = "Filter and sort: All, Title, A–Z"
    }
}
