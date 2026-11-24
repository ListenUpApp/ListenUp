package com.calypsan.listenup.web.features.library

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.ContributorWithBookCount
import com.calypsan.listenup.client.domain.model.SeriesProgress
import com.calypsan.listenup.client.domain.model.SeriesWithBooks
import com.calypsan.listenup.client.domain.model.SyncState
import com.calypsan.listenup.client.presentation.library.BookCardStatus
import com.calypsan.listenup.client.presentation.library.BookStatusCounts
import com.calypsan.listenup.client.presentation.library.BookStatusFilter
import com.calypsan.listenup.client.presentation.library.LetterSection
import com.calypsan.listenup.client.presentation.library.LibraryUiEvent
import com.calypsan.listenup.client.presentation.library.LibraryUiState
import com.calypsan.listenup.client.presentation.library.SortCategory
import com.calypsan.listenup.client.presentation.library.SortDirection
import com.calypsan.listenup.client.presentation.library.SortState
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.web.design.LibraryFacet
import com.calypsan.listenup.web.features.admin.InboxBadgeState
import com.calypsan.listenup.web.features.bookdetail.ShareOutcome
import com.calypsan.listenup.web.features.books.BookSelection
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * An [OpenLibrary] for specs that mount the shell but do not exercise the library.
 *
 * Holds [LibraryUiState.Loading] forever and records nothing. The point is that a spec about
 * routing, panes or auth does not need a database behind the Library route — which is the whole
 * reason [OpenLibrary] is a seam rather than a direct ViewModel lookup.
 */
fun fakeLibrary(state: LibraryUiState = LibraryUiState.Loading): OpenLibrary =
    {
        LibrarySession(
            state = MutableStateFlow(state),
            onEvent = {},
            close = {},
            onShareBook = { _, _ -> ShareOutcome.FAILED },
        )
    }

/** Renders [LibraryPage] with no-op callback defaults for specs. */
@Composable
@Suppress("LongParameterList")
internal fun testLibraryPage(
    state: LibraryUiState,
    onEvent: (LibraryUiEvent) -> Unit = {},
    onOpenBook: (String) -> Unit = {},
    onSelectFacet: (LibraryFacet) -> Unit = {},
    heroBookId: String? = null,
    selecting: Boolean = false,
    selectedIds: Set<String> = emptySet(),
    onToggleSelect: (String) -> Unit = {},
    onStartSelecting: (() -> Unit)? = null,
    inbox: InboxBadgeState = InboxBadgeState(),
    onOpenInbox: () -> Unit = {},
    bookMenu: LibraryBookMenuSource? = null,
) {
    LibraryPage(
        state = state,
        onEvent = onEvent,
        onOpenBook = onOpenBook,
        onSelectFacet = onSelectFacet,
        heroBookId = heroBookId,
        selecting = selecting,
        selectedIds = selectedIds,
        onToggleSelect = onToggleSelect,
        onStartSelecting = onStartSelecting,
        inbox = inbox,
        onOpenInbox = onOpenInbox,
        bookMenu = bookMenu,
    )
}

/** Builds a [BookSelection] with no-op callback defaults for specs. */
@Suppress("LongParameterList")
internal fun testBookSelection(
    isSelecting: Boolean = false,
    selectedIds: Set<String> = emptySet(),
    onToggle: (String) -> Unit = {},
    onStart: () -> Unit = {},
    onShelveOne: (String) -> Unit = {},
    onCollectOne: (String) -> Unit = {},
    onStartWith: (String) -> Unit = {},
    isAdmin: Boolean = false,
): BookSelection =
    BookSelection(
        isSelecting = isSelecting,
        selectedIds = selectedIds,
        onToggle = onToggle,
        onStart = onStart,
        onShelveOne = onShelveOne,
        onCollectOne = onCollectOne,
        onStartWith = onStartWith,
        isAdmin = isAdmin,
    )

/**
 * A `Loaded` state for specs that need one but do not care about most of it — the class contract
 * and the fake session both do. Shared rather than duplicated because `Loaded` has seventeen
 * required fields and every copy is a place for them to drift.
 */
fun contractLibrary(
    books: List<BookListItem> = emptyList(),
    syncing: Boolean = false,
    series: List<SeriesWithBooks> = emptyList(),
    seriesProgress: Map<SeriesId, SeriesProgress> = emptyMap(),
    seriesSortState: SortState = SortState(SortCategory.NAME, SortDirection.ASCENDING),
    isBuildingInitialLibrary: Boolean = false,
    authors: List<ContributorWithBookCount> = emptyList(),
    narrators: List<ContributorWithBookCount> = emptyList(),
    authorsSortState: SortState = SortState(SortCategory.NAME, SortDirection.ASCENDING),
    narratorsSortState: SortState = SortState(SortCategory.NAME, SortDirection.ASCENDING),
    statusFilter: BookStatusFilter = BookStatusFilter.ALL,
    // Every listed book is part-read unless a spec says otherwise, so `isEmpty` reads the books.
    statusCounts: BookStatusCounts =
        BookStatusCounts(all = books.size, inProgress = books.size, notStarted = 0, finished = 0),
    bookLetters: List<LetterSection> = emptyList(),
    seriesLetters: List<LetterSection> = emptyList(),
): LibraryUiState.Loaded =
    LibraryUiState.Loaded(
        booksSortState = SortState(SortCategory.TITLE, SortDirection.ASCENDING),
        seriesSortState = seriesSortState,
        authorsSortState = authorsSortState,
        narratorsSortState = narratorsSortState,
        ignoreTitleArticles = false,
        hideSingleBookSeries = false,
        contentRevision = 1L,
        books = books,
        series = series,
        authors = authors,
        narrators = narrators,
        seriesProgress = seriesProgress,
        syncState = if (syncing) SyncState.Syncing else SyncState.Idle,
        isServerScanning = false,
        scanProgress = null,
        isBuildingInitialLibrary = isBuildingInitialLibrary,
        statusFilter = statusFilter,
        statusCounts = statusCounts,
        bookStatus =
            books.associate {
                it.id to
                    BookCardStatus.InProgress(
                        fraction = PARTIAL_PROGRESS,
                        percent = PARTIAL_PERCENT,
                        timeLeftMs =
                            it.duration / 2,
                    )
            },
        totalDurationMs = books.sumOf { it.duration },
        bookLetters = bookLetters,
        seriesLetters = seriesLetters,
    )

/**
 * Letter sections over [keys] the way the shared ViewModel cuts them for a name or untouched-title
 * sort: runs of one first letter, `#` for anything that does not start with one. A fixture, so a
 * spec can hand a page the letters `LibraryUiState.Loaded` would carry.
 */
fun lettersOf(keys: List<String>): List<LetterSection> {
    val sections = mutableListOf<LetterSection>()
    keys.forEachIndexed { index, key ->
        val letter = (key.firstOrNull()?.uppercaseChar()?.takeIf { it.isLetter() } ?: '#').toString()
        val last = sections.lastOrNull()
        if (last != null && last.letter == letter) {
            sections[sections.lastIndex] = last.copy(count = last.count + 1)
        } else {
            sections += LetterSection(letter, index, 1)
        }
    }
    return sections
}

/**
 * Part-read, so the progress bar actually renders.
 *
 * The card draws its listening bar only for a started book, so a contract built on an unstarted one
 * would assert against markup that was never emitted.
 */
private const val PARTIAL_PROGRESS = 0.4f
private const val PARTIAL_PERCENT = 40

/** A minimal book for contract rendering — only `id` and `title` reach the DOM. */
fun contractBook(
    id: String,
    title: String,
): BookListItem =
    BookListItem(
        id = BookId(id),
        libraryId = LibraryId("lib-1"),
        folderId = FolderId("folder-1"),
        title = title,
        authors = emptyList(),
        narrators = emptyList(),
        duration = 3_600_000L,
        coverPath = null,
        addedAt = Timestamp(0L),
        updatedAt = Timestamp(0L),
    )
