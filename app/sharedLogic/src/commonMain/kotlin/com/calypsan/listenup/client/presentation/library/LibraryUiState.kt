package com.calypsan.listenup.client.presentation.library

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.client.domain.model.ScanProgressState
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.ContributorWithBookCount
import com.calypsan.listenup.client.domain.model.SeriesProgress
import com.calypsan.listenup.client.domain.model.SeriesWithBooks
import com.calypsan.listenup.client.domain.model.SyncState

/**
 * UI state for the Library screen.
 *
 * Sealed hierarchy composed from repository flows and a private intent
 * [kotlinx.coroutines.flow.MutableStateFlow] via
 * `combine(...).stateIn(WhileSubscribed)` inside [LibraryViewModel]. User intent
 * (sort, filter, display preferences) lives on the intent flow; repository-backed
 * content joins it inside the top-level transform, never by reading the state
 * back from itself.
 *
 * - [Loading] — pre-first-emission placeholder, also used as the initial value.
 * - [Loaded] — content ready; renders the Books / Series / Authors / Narrators tabs.
 * - [Error] — catastrophic pipeline failure surfaced to the UI.
 */
sealed interface LibraryUiState {
    /** Pre-first-emission placeholder. */
    data object Loading : LibraryUiState

    /**
     * Library content is ready. Every field carries a sensible default before its
     * upstream produces real data, so the screen can render as soon as the pipeline
     * emits its first value.
     */
    data class Loaded(
        // Intent snapshot for UI rendering
        val booksSortState: SortState,
        val seriesSortState: SortState,
        val authorsSortState: SortState,
        val narratorsSortState: SortState,
        val ignoreTitleArticles: Boolean,
        val hideSingleBookSeries: Boolean,
        /**
         * Advances whenever [books], [series], [authors], [narrators], the sort/filter intent that
         * ordered them, or a book's reading-state *transition* (which can move it in or out of
         * [books]) changes — and ONLY then. A progress or sync emission carries the previous
         * value, so a consumer that bridges these lists (iOS maps each row across Swift Export) can
         * skip the re-map whenever the revision is unchanged. Monotonic for this ViewModel's lifetime.
         */
        val contentRevision: Long,
        // Sorted content
        val books: List<BookListItem>,
        val series: List<SeriesWithBooks>,
        val authors: List<ContributorWithBookCount>,
        val narrators: List<ContributorWithBookCount>,
        // Progress derived from playback positions
        val bookProgress: Map<BookId, Float>,
        val bookIsFinished: Map<BookId, Boolean>,
        /** Books with partial progress: started (progress > 0) but not yet finished. */
        val booksInProgress: List<BookListItem>,
        /** Per-series finished/total aggregation for the series-list progress affordance. */
        val seriesProgress: Map<SeriesId, SeriesProgress>,
        // Sync
        val syncState: SyncState,
        val isServerScanning: Boolean,
        val scanProgress: ScanProgressState?,
        /**
         * Whether the first population of this library is still arriving.
         *
         * Distinct from [isSyncing], which tracks the CONNECTION: the socket is `Connected` for the
         * whole of an initial seed, so [isSyncing] is false while thousands of books are still
         * streaming in. An empty screen must consult this instead, or it tells a reader their
         * library is empty while it is being filled.
         */
        val isBuildingInitialLibrary: Boolean,
        /** The active reading-state filter. [books] is already filtered by it. Session-scoped. */
        val statusFilter: BookStatusFilter = BookStatusFilter.ALL,
        /** Counts per reading state over the WHOLE library, whatever [statusFilter] is. */
        val statusCounts: BookStatusCounts = BookStatusCounts(all = 0, inProgress = 0, notStarted = 0, finished = 0),
        /**
         * What each book's card says, for EVERY book in the library (not only the filtered [books]).
         * Moves on a position tick without advancing [contentRevision]: a consumer that gates on the
         * revision (iOS) must read this outside the gate.
         */
        val bookStatus: Map<BookId, BookCardStatus> = emptyMap(),
        /** The whole library's length in ms ("41 days of listening"), whatever the filter. */
        val totalDurationMs: Long = 0L,
    ) : LibraryUiState {
        /** Whether the library itself has no books. A filter that matches nothing is [isFilteredEmpty], not this. */
        val isEmpty: Boolean
            get() = statusCounts.all == 0

        /** Whether the library has books but the active [statusFilter] matches none of them. */
        val isFilteredEmpty: Boolean
            get() = books.isEmpty() && statusCounts.all > 0

        /**
         * Whether a sync PASS is running.
         *
         * ⛔ Not "is my library still arriving". This tracks the connection, which is `Connected`
         * for the whole of an initial seed, so this is false while thousands of books stream in.
         * Anything explaining a short or empty shelf must read [isBuildingInitialLibrary] instead.
         */
        val isSyncing: Boolean
            get() = syncState is SyncState.Syncing || syncState is SyncState.Progress
    }

    /** Catastrophic load failure (rarely hit). */
    data class Error(
        val message: String,
    ) : LibraryUiState
}
