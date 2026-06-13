package com.calypsan.listenup.client.presentation.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.core.fallbackTo
import com.calypsan.listenup.client.domain.model.ScanProgressState
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.ContributorRole
import com.calypsan.listenup.client.domain.model.ContributorWithBookCount
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.client.domain.model.SeriesProgress
import com.calypsan.listenup.client.domain.model.SeriesWithBooks
import com.calypsan.listenup.client.domain.model.SyncState
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import com.calypsan.listenup.client.domain.repository.LibraryPreferences
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.domain.repository.SeriesRepository
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.domain.repository.SyncStatusRepository
import com.calypsan.listenup.client.util.sortableTitle
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}
private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

/**
 * User intent for the Library screen.
 *
 * Held in a private [MutableStateFlow] inside [LibraryViewModel] so that sort /
 * filter / preference changes drive the top-level combine without the state
 * pipeline having to read back from its own output.
 */
private data class LibraryIntent(
    val booksSortState: SortState = SortState.booksDefault,
    val seriesSortState: SortState = SortState.seriesDefault,
    val authorsSortState: SortState = SortState.contributorDefault,
    val narratorsSortState: SortState = SortState.contributorDefault,
    val ignoreTitleArticles: Boolean = true,
    val hideSingleBookSeries: Boolean = true,
)

/** Snapshot of raw repository content before sorting / filtering. */
private data class RawContent(
    val books: List<BookListItem>,
    val series: List<SeriesWithBooks>,
    val authors: List<ContributorWithBookCount>,
    val narrators: List<ContributorWithBookCount>,
)

/** Snapshot of sync-related state from [SyncRepository]. */
private data class SyncSnapshot(
    val syncState: SyncState,
    val isServerScanning: Boolean,
    val scanProgress: ScanProgressState?,
)

/** Progress maps derived from playback positions and book durations. */
private data class ProgressSnapshot(
    val progressMap: Map<BookId, Float>,
    val finishedMap: Map<BookId, Boolean>,
)

/**
 * ViewModel for the Library screen content.
 *
 * Produces a single sealed [LibraryUiState] by combining repository flows with
 * a private [LibraryIntent] via `combine(...).stateIn(WhileSubscribed)`.
 * Sorting, filtering, and preference handling run inside the combine transform
 * — never upstream — so the pipeline never reads back from its own output.
 *
 * Implements intelligent auto-sync: triggers initial sync automatically
 * if user is authenticated but has never synced before.
 *
 * Selection state is managed by [LibrarySelectionManager] which is shared
 * with [LibraryActionsViewModel] for coordinated batch operations.
 */
class LibraryViewModel(
    private val bookRepository: BookRepository,
    private val seriesRepository: SeriesRepository,
    private val contributorRepository: ContributorRepository,
    private val playbackPositionRepository: PlaybackPositionRepository,
    private val syncRepository: SyncRepository,
    private val authSession: AuthSession,
    private val libraryPreferences: LibraryPreferences,
    private val syncStatusRepository: SyncStatusRepository,
    private val selectionManager: LibrarySelectionManager,
) : ViewModel() {
    // ═══════════════════════════════════════════════════════════════════════
    // INTENT
    // ═══════════════════════════════════════════════════════════════════════

    private val intent = MutableStateFlow(LibraryIntent())

    // ═══════════════════════════════════════════════════════════════════════
    // PIPELINE
    // ═══════════════════════════════════════════════════════════════════════

    private val rawContent: SharedFlow<RawContent> =
        combine(
            bookRepository
                .observeBookListItems()
                .fallbackTo { e ->
                    logger.error(e) { "observeBookListItems failed; emitting empty list" }
                    emptyList()
                },
            seriesRepository
                .observeAllWithBooks()
                .fallbackTo { e ->
                    logger.error(e) { "observeAllWithBooks failed; emitting empty list" }
                    emptyList()
                },
            contributorRepository
                .observeContributorsByRole(ContributorRole.AUTHOR.apiValue)
                .fallbackTo { e ->
                    logger.error(e) { "observeContributorsByRole(AUTHOR) failed; emitting empty list" }
                    emptyList()
                },
            contributorRepository
                .observeContributorsByRole(ContributorRole.NARRATOR.apiValue)
                .fallbackTo { e ->
                    logger.error(e) { "observeContributorsByRole(NARRATOR) failed; emitting empty list" }
                    emptyList()
                },
            ::RawContent,
        ).shareIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            replay = 1,
        )

    private val progressSnapshot: Flow<ProgressSnapshot> =
        combine(
            rawContent,
            playbackPositionRepository
                .observeAll()
                .fallbackTo { e ->
                    logger.error(e) { "observeAll(positions) failed; emitting empty map" }
                    emptyMap()
                },
        ) { content, positions ->
            computeProgress(content.books, positions)
        }

    private val syncSnapshot: Flow<SyncSnapshot> =
        combine(
            syncRepository.syncState,
            syncRepository.isServerScanning,
            syncRepository.scanProgress,
            ::SyncSnapshot,
        )

    val uiState: StateFlow<LibraryUiState> =
        combine(
            intent,
            rawContent,
            progressSnapshot,
            syncSnapshot,
            selectionManager.selectionMode,
        ) { intentValue, content, progress, sync, selection ->
            val loaded: LibraryUiState =
                buildLoaded(intentValue, content, progress, sync, selection)
            loaded
        }.fallbackTo { e ->
            logger.error(e) { "Library state pipeline failed" }
            LibraryUiState.Error("Failed to load library")
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            initialValue = LibraryUiState.Loading,
        )

    // ═══════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════════

    @Volatile private var hasPerformedInitialSync = false

    init {
        // Load persisted sort states and display preferences into intent.
        viewModelScope.launch {
            libraryPreferences.getBooksSortState()?.let { SortState.fromPersistenceKey(it) }?.let { loaded ->
                intent.update { it.copy(booksSortState = loaded) }
            }
            libraryPreferences.getSeriesSortState()?.let { SortState.fromPersistenceKey(it) }?.let { loaded ->
                intent.update { it.copy(seriesSortState = loaded) }
            }
            libraryPreferences.getAuthorsSortState()?.let { SortState.fromPersistenceKey(it) }?.let { loaded ->
                intent.update { it.copy(authorsSortState = loaded) }
            }
            libraryPreferences.getNarratorsSortState()?.let { SortState.fromPersistenceKey(it) }?.let { loaded ->
                intent.update { it.copy(narratorsSortState = loaded) }
            }
            intent.update {
                it.copy(
                    ignoreTitleArticles = libraryPreferences.getIgnoreTitleArticles(),
                    hideSingleBookSeries = libraryPreferences.getHideSingleBookSeries(),
                )
            }
        }

        logger.debug { "Initialized (auto-sync deferred until screen visible)" }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Called when the Library screen becomes visible.
     * Reloads preferences that may have changed in Settings and, once per VM,
     * performs an initial sync if the user is authenticated but has never synced.
     */
    fun onScreenVisible() {
        // Reload preferences in case user changed them in Settings
        viewModelScope.launch {
            intent.update {
                it.copy(
                    hideSingleBookSeries = libraryPreferences.getHideSingleBookSeries(),
                    ignoreTitleArticles = libraryPreferences.getIgnoreTitleArticles(),
                )
            }
        }

        if (hasPerformedInitialSync) return
        hasPerformedInitialSync = true

        logger.debug { "Screen became visible, checking if initial sync needed..." }
        viewModelScope.launch {
            val isAuthenticated = authSession.getAccessToken() != null
            val lastSyncTime = syncStatusRepository.getLastSyncTime()

            if (isAuthenticated && lastSyncTime == null) {
                logger.info { "User authenticated but never synced, triggering initial sync..." }
                refreshBooks()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // UI EVENTS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Handle UI events from the Library screen.
     */
    fun onEvent(event: LibraryUiEvent) {
        when (event) {
            is LibraryUiEvent.RefreshRequested -> {
                refreshBooks()
            }

            is LibraryUiEvent.BookClicked -> { /* Navigation handled by parent */ }

            // Books tab sort events
            is LibraryUiEvent.BooksCategoryChanged -> {
                intent.update { it.copy(booksSortState = it.booksSortState.withCategory(event.category)) }
                persistBooksSort()
            }

            is LibraryUiEvent.BooksDirectionToggled -> {
                intent.update { it.copy(booksSortState = it.booksSortState.toggleDirection()) }
                persistBooksSort()
            }

            // Series tab sort events
            is LibraryUiEvent.SeriesCategoryChanged -> {
                intent.update { it.copy(seriesSortState = it.seriesSortState.withCategory(event.category)) }
                persistSeriesSort()
            }

            is LibraryUiEvent.SeriesDirectionToggled -> {
                intent.update { it.copy(seriesSortState = it.seriesSortState.toggleDirection()) }
                persistSeriesSort()
            }

            // Authors tab sort events
            is LibraryUiEvent.AuthorsCategoryChanged -> {
                intent.update { it.copy(authorsSortState = it.authorsSortState.withCategory(event.category)) }
                persistAuthorsSort()
            }

            is LibraryUiEvent.AuthorsDirectionToggled -> {
                intent.update { it.copy(authorsSortState = it.authorsSortState.toggleDirection()) }
                persistAuthorsSort()
            }

            // Narrators tab sort events
            is LibraryUiEvent.NarratorsCategoryChanged -> {
                intent.update { it.copy(narratorsSortState = it.narratorsSortState.withCategory(event.category)) }
                persistNarratorsSort()
            }

            is LibraryUiEvent.NarratorsDirectionToggled -> {
                intent.update { it.copy(narratorsSortState = it.narratorsSortState.toggleDirection()) }
                persistNarratorsSort()
            }

            // Title sort article handling
            is LibraryUiEvent.ToggleIgnoreTitleArticles -> {
                val newValue = !intent.value.ignoreTitleArticles
                intent.update { it.copy(ignoreTitleArticles = newValue) }
                viewModelScope.launch { libraryPreferences.setIgnoreTitleArticles(newValue) }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SELECTION ACTIONS (delegated to shared manager)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Enter selection mode with the given book as the initial selection.
     *
     * @param bookId The ID of the book that was long-pressed
     */
    fun enterSelectionMode(bookId: String) = selectionManager.enterSelectionMode(bookId)

    /**
     * Toggle the selection state of a book.
     *
     * @param bookId The ID of the book to toggle
     */
    fun toggleBookSelection(bookId: String) = selectionManager.toggleSelection(bookId)

    /**
     * Exit selection mode and clear all selections.
     */
    fun exitSelectionMode() = selectionManager.exitSelectionMode()

    // ═══════════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    private fun refreshBooks() {
        viewModelScope.launch {
            bookRepository.refreshBooks()
        }
    }

    private fun persistBooksSort() {
        val state = intent.value.booksSortState
        viewModelScope.launch { libraryPreferences.setBooksSortState(state.persistenceKey) }
    }

    private fun persistSeriesSort() {
        val state = intent.value.seriesSortState
        viewModelScope.launch { libraryPreferences.setSeriesSortState(state.persistenceKey) }
    }

    private fun persistAuthorsSort() {
        val state = intent.value.authorsSortState
        viewModelScope.launch { libraryPreferences.setAuthorsSortState(state.persistenceKey) }
    }

    private fun persistNarratorsSort() {
        val state = intent.value.narratorsSortState
        viewModelScope.launch { libraryPreferences.setNarratorsSortState(state.persistenceKey) }
    }

    private fun buildLoaded(
        intent: LibraryIntent,
        content: RawContent,
        progress: ProgressSnapshot,
        sync: SyncSnapshot,
        selection: SelectionMode,
    ): LibraryUiState.Loaded {
        val sortedBooks = sortBooks(content.books, intent.booksSortState, intent.ignoreTitleArticles)
        val visibleSeries =
            if (intent.hideSingleBookSeries) content.series.filter { it.books.size > 1 } else content.series
        val sortedSeries = sortSeries(visibleSeries, intent.seriesSortState)
        val sortedAuthors = sortContributors(content.authors, intent.authorsSortState)
        val sortedNarrators = sortContributors(content.narrators, intent.narratorsSortState)
        val seriesProgress =
            sortedSeries.associate { sb ->
                sb.series.id to
                    SeriesProgress(
                        finishedCount = sb.books.count { progress.finishedMap[it.id] == true },
                        totalCount = sb.books.size,
                    )
            }

        return LibraryUiState.Loaded(
            booksSortState = intent.booksSortState,
            seriesSortState = intent.seriesSortState,
            authorsSortState = intent.authorsSortState,
            narratorsSortState = intent.narratorsSortState,
            ignoreTitleArticles = intent.ignoreTitleArticles,
            hideSingleBookSeries = intent.hideSingleBookSeries,
            books = sortedBooks,
            series = sortedSeries,
            authors = sortedAuthors,
            narrators = sortedNarrators,
            bookProgress = progress.progressMap,
            bookIsFinished = progress.finishedMap,
            seriesProgress = seriesProgress,
            syncState = sync.syncState,
            isServerScanning = sync.isServerScanning,
            scanProgress = sync.scanProgress,
            selectionMode = selection,
        )
    }

    private fun computeProgress(
        books: List<BookListItem>,
        positions: Map<BookId, PlaybackPosition>,
    ): ProgressSnapshot {
        val bookDurations = books.associate { it.id to it.duration }
        val progressMap = mutableMapOf<BookId, Float>()
        val finishedMap = mutableMapOf<BookId, Boolean>()

        for ((bookId, position) in positions) {
            // Track isFinished for all positions (authoritative from server)
            if (position.isFinished) {
                finishedMap[bookId] = true
            }

            // Track progress for books with valid duration
            val duration = bookDurations[bookId] ?: continue
            if (duration <= 0) continue
            val progress = (position.positionMs.toFloat() / duration).coerceIn(0f, 1f)
            if (progress > 0f) {
                progressMap[bookId] = progress
            }
        }

        return ProgressSnapshot(progressMap, finishedMap)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SORTING HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    @Suppress("CyclomaticComplexMethod")
    private fun sortBooks(
        books: List<BookListItem>,
        state: SortState,
        ignoreArticles: Boolean,
    ): List<BookListItem> {
        val isAsc = state.direction == SortDirection.ASCENDING

        return when (state.category) {
            SortCategory.TITLE -> {
                if (isAsc) {
                    books.sortedBy { it.title.sortableTitle(ignoreArticles) }
                } else {
                    books.sortedByDescending { it.title.sortableTitle(ignoreArticles) }
                }
            }

            SortCategory.AUTHOR -> {
                if (isAsc) {
                    books.sortedWith(
                        compareBy<BookListItem> { it.authorNames.lowercase() }
                            .thenBy { it.title.lowercase() },
                    )
                } else {
                    books.sortedWith(
                        compareByDescending<BookListItem> { it.authorNames.lowercase() }
                            .thenBy { it.title.lowercase() },
                    )
                }
            }

            SortCategory.DURATION -> {
                if (isAsc) {
                    books.sortedBy { it.duration }
                } else {
                    books.sortedByDescending { it.duration }
                }
            }

            SortCategory.YEAR -> {
                if (isAsc) {
                    books.sortedWith(
                        compareBy<BookListItem> { it.publishYear ?: Int.MAX_VALUE }
                            .thenBy { it.title.lowercase() },
                    )
                } else {
                    books.sortedWith(
                        compareByDescending<BookListItem> { it.publishYear ?: 0 }
                            .thenBy { it.title.lowercase() },
                    )
                }
            }

            SortCategory.ADDED -> {
                if (isAsc) {
                    books.sortedBy { it.addedAt.epochMillis }
                } else {
                    books.sortedByDescending { it.addedAt.epochMillis }
                }
            }

            SortCategory.SERIES -> {
                if (isAsc) {
                    books.sortedWith(
                        compareBy<BookListItem> { it.seriesName?.lowercase() ?: "\uFFFF" }
                            .thenBy { it.seriesSequence?.toFloatOrNull() ?: Float.MAX_VALUE }
                            .thenBy { it.title.lowercase() },
                    )
                } else {
                    books.sortedWith(
                        compareByDescending<BookListItem> { it.seriesName?.lowercase() ?: "" }
                            .thenByDescending { it.seriesSequence?.toFloatOrNull() ?: 0f }
                            .thenBy { it.title.lowercase() },
                    )
                }
            }

            // Not applicable for books
            SortCategory.NAME, SortCategory.BOOK_COUNT -> {
                books
            }
        }
    }

    private fun sortSeries(
        series: List<SeriesWithBooks>,
        state: SortState,
    ): List<SeriesWithBooks> {
        val isAsc = state.direction == SortDirection.ASCENDING

        return when (state.category) {
            SortCategory.NAME -> {
                if (isAsc) {
                    series.sortedBy { it.series.name.lowercase() }
                } else {
                    series.sortedByDescending { it.series.name.lowercase() }
                }
            }

            SortCategory.BOOK_COUNT -> {
                if (isAsc) {
                    series.sortedBy { it.books.size }
                } else {
                    series.sortedByDescending { it.books.size }
                }
            }

            SortCategory.ADDED -> {
                if (isAsc) {
                    series.sortedBy { it.series.createdAt.epochMillis }
                } else {
                    series.sortedByDescending { it.series.createdAt.epochMillis }
                }
            }

            // Default to name sort for unsupported categories
            else -> {
                series.sortedBy { it.series.name.lowercase() }
            }
        }
    }

    private fun sortContributors(
        contributors: List<ContributorWithBookCount>,
        state: SortState,
    ): List<ContributorWithBookCount> {
        val isAsc = state.direction == SortDirection.ASCENDING

        return when (state.category) {
            SortCategory.NAME -> {
                if (isAsc) {
                    contributors.sortedBy { it.contributor.name.lowercase() }
                } else {
                    contributors.sortedByDescending { it.contributor.name.lowercase() }
                }
            }

            SortCategory.BOOK_COUNT -> {
                if (isAsc) {
                    contributors.sortedBy { it.bookCount }
                } else {
                    contributors.sortedByDescending { it.bookCount }
                }
            }

            // Default to name sort for unsupported categories
            else -> {
                contributors.sortedBy { it.contributor.name.lowercase() }
            }
        }
    }
}

/**
 * Events that can be triggered from the Library UI.
 */
sealed interface LibraryUiEvent {
    data object RefreshRequested : LibraryUiEvent

    /** User tapped a book row; navigation is handled by the parent route. */
    data class BookClicked(
        val bookId: String,
    ) : LibraryUiEvent

    // Books tab

    /** User picked a new sort category for the Books tab. */
    data class BooksCategoryChanged(
        val category: SortCategory,
    ) : LibraryUiEvent

    data object BooksDirectionToggled : LibraryUiEvent

    data object ToggleIgnoreTitleArticles : LibraryUiEvent

    // Series tab

    /** User picked a new sort category for the Series tab. */
    data class SeriesCategoryChanged(
        val category: SortCategory,
    ) : LibraryUiEvent

    data object SeriesDirectionToggled : LibraryUiEvent

    // Authors tab

    /** User picked a new sort category for the Authors tab. */
    data class AuthorsCategoryChanged(
        val category: SortCategory,
    ) : LibraryUiEvent

    data object AuthorsDirectionToggled : LibraryUiEvent

    // Narrators tab

    /** User picked a new sort category for the Narrators tab. */
    data class NarratorsCategoryChanged(
        val category: SortCategory,
    ) : LibraryUiEvent

    data object NarratorsDirectionToggled : LibraryUiEvent
}

/**
 * Selection mode state for multi-select functionality.
 */
sealed interface SelectionMode {
    /** No selection active - normal library behavior. */
    data object None : SelectionMode

    /** Multi-select mode is active with the given selected book IDs. */
    data class Active(
        val selectedIds: Set<String>,
    ) : SelectionMode
}
