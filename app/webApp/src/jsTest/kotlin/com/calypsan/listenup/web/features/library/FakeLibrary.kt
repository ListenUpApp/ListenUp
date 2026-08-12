package com.calypsan.listenup.web.features.library

import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.SyncState
import com.calypsan.listenup.client.presentation.library.LibraryUiState
import com.calypsan.listenup.client.presentation.library.SortCategory
import com.calypsan.listenup.client.presentation.library.SortDirection
import com.calypsan.listenup.client.presentation.library.SortState
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * An [OpenLibrary] for specs that mount the shell but do not exercise the library.
 *
 * Holds [LibraryUiState.Loading] forever and records nothing. The point is that a spec about
 * routing, panes or auth does not need a database behind the Library route — which is the whole
 * reason [OpenLibrary] is a seam rather than a direct ViewModel lookup.
 */
fun fakeLibrary(state: LibraryUiState = LibraryUiState.Loading): OpenLibrary =
    { LibrarySession(state = MutableStateFlow(state), onEvent = {}, close = {}) }

/**
 * A `Loaded` state for specs that need one but do not care about most of it — the class contract
 * and the fake session both do. Shared rather than duplicated because `Loaded` has seventeen
 * required fields and every copy is a place for them to drift.
 */
fun contractLibrary(
    books: List<BookListItem> = emptyList(),
    syncing: Boolean = false,
): LibraryUiState.Loaded =
    LibraryUiState.Loaded(
        booksSortState = SortState(SortCategory.TITLE, SortDirection.ASCENDING),
        seriesSortState = SortState(SortCategory.NAME, SortDirection.ASCENDING),
        authorsSortState = SortState(SortCategory.NAME, SortDirection.ASCENDING),
        narratorsSortState = SortState(SortCategory.NAME, SortDirection.ASCENDING),
        ignoreTitleArticles = false,
        hideSingleBookSeries = false,
        books = books,
        series = emptyList(),
        authors = emptyList(),
        narrators = emptyList(),
        bookProgress = books.associate { it.id to PARTIAL_PROGRESS },
        bookIsFinished = emptyMap(),
        booksInProgress = emptyList(),
        seriesProgress = emptyMap(),
        syncState = if (syncing) SyncState.Syncing else SyncState.Idle,
        isServerScanning = false,
        scanProgress = null,
    )

/**
 * Part-read, so the progress bar actually renders.
 *
 * The page draws `.lib-progress` only when progress is greater than zero, so a contract built on
 * zero would assert against markup that was never emitted.
 */
private const val PARTIAL_PROGRESS = 0.4f

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
