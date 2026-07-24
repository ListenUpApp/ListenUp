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
    ) : LibraryUiState {
        /** Whether the library is empty (loaded but contains no books). */
        val isEmpty: Boolean
            get() = books.isEmpty()

        /** Whether a sync operation is currently in progress. */
        val isSyncing: Boolean
            get() = syncState is SyncState.Syncing || syncState is SyncState.Progress
    }

    /** Catastrophic load failure (rarely hit). */
    data class Error(
        val message: String,
    ) : LibraryUiState
}
