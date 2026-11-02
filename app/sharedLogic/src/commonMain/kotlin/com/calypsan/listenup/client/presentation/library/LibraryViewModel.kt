package com.calypsan.listenup.client.presentation.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.result.onFailure
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.core.fallbackTo
import com.calypsan.listenup.client.domain.model.ScanProgressState
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.CombinedScore
import com.calypsan.listenup.client.domain.model.ContributorRole
import com.calypsan.listenup.client.domain.model.ContributorWithBookCount
import com.calypsan.listenup.client.domain.model.ListenerAverage
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.client.domain.model.SeriesProgress
import com.calypsan.listenup.client.domain.model.SeriesWithBooks
import com.calypsan.listenup.client.domain.model.SyncState
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.BookRatingRepository
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
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.calypsan.listenup.client.presentation.WhileSubscribedForUi
import com.calypsan.listenup.client.presentation.sync.ManualSyncRefresh

private val logger = KotlinLogging.logger {}

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
    val statusFilter: BookStatusFilter = BookStatusFilter.ALL,
)

/** Snapshot of raw repository content before sorting / filtering. */
private data class RawContent(
    val books: List<BookListItem>,
    val series: List<SeriesWithBooks>,
    val authors: List<ContributorWithBookCount>,
    val narrators: List<ContributorWithBookCount>,
    val listenerAverages: Map<String, ListenerAverage>,
)

/** Snapshot of sync-related state from [SyncRepository]. */
private data class SyncSnapshot(
    val syncState: SyncState,
    val isServerScanning: Boolean,
    val scanProgress: ScanProgressState?,
    val isBuildingInitialLibrary: Boolean,
    val isFirstCatchUp: Boolean,
)

/** Per-book card state and finished flags, derived from playback positions and book durations. */
private data class ProgressSnapshot(
    val finishedMap: Map<BookId, Boolean>,
    val cardStatus: Map<BookId, BookCardStatus>,
)

/**
 * Output of the sort/filter stage: the four sorted collections plus the
 * [LibraryIntent] that produced them. Recomputed only when intent or raw
 * content changes — never on progress or sync ticks. Data-class equality
 * lets `distinctUntilChanged` drop no-op re-emissions from Room.
 */
private data class SortedContent(
    val intent: LibraryIntent,
    val books: List<BookListItem>,
    val series: List<SeriesWithBooks>,
    val authors: List<ContributorWithBookCount>,
    val narrators: List<ContributorWithBookCount>,
    val statusCounts: BookStatusCounts,
    val sectionCounts: LibrarySectionCounts,
    val totalDurationMs: Long,
    val bookLetters: List<LetterSection>,
    val seriesLetters: List<LetterSection>,
    val authorLetters: List<LetterSection>,
    val narratorLetters: List<LetterSection>,
)

/**
 * [SortedContent] stamped with the revision it was published under. The stamp is applied AFTER
 * `distinctUntilChanged`, so only a genuinely new sorted snapshot earns a new revision.
 */
private data class RevisedContent(
    val revision: Long,
    val content: SortedContent,
)

/**
 * ViewModel for the Library screen content.
 *
 * Produces a single sealed [LibraryUiState] by combining repository flows with
 * a private [LibraryIntent] via `combine(...).stateIn(WhileSubscribed)`.
 * Sorting, filtering, and preference handling run inside the combine transform
 * — never upstream — so the pipeline never reads back from its own output.
 * The pipeline is two-stage: [sortedContent] sorts/filters on intent + raw
 * content only, then [uiState] overlays progress/sync onto the pre-sorted
 * result, so playback-position ticks (every ~30s) never trigger a re-sort. Each new sorted snapshot
 * is stamped with [LibraryUiState.Loaded.contentRevision], so a consumer that bridges the lists can
 * tell a content change from a progress or sync one without comparing them.
 *
 * Implements intelligent auto-sync: triggers initial sync automatically
 * if user is authenticated but has never synced before.
 *
 * Multi-select selection state lives in [BookMultiSelectViewModel], a per-screen VM the
 * Library screen drives independently of this content pipeline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val bookRepository: BookRepository,
    seriesRepository: SeriesRepository,
    contributorRepository: ContributorRepository,
    playbackPositionRepository: PlaybackPositionRepository,
    syncRepository: SyncRepository,
    private val authSession: AuthSession,
    private val libraryPreferences: LibraryPreferences,
    private val syncStatusRepository: SyncStatusRepository,
    bookRatingRepository: BookRatingRepository,
    private val manualSyncRefresh: ManualSyncRefresh,
    // CPU-bound sort/filter of the library runs on this dispatcher, off the main thread. Defaulted
    // for production; tests inject their scheduler-backed dispatcher so the pipeline stays controllable.
    private val backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default,
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
            flow =
                bookRepository
                    .observeBookListItems()
                    .fallbackTo { e ->
                        logger.error(e) { "observeBookListItems failed; emitting empty list" }
                        emptyList()
                    },
            flow2 =
                seriesRepository
                    .observeRootSeriesWithBooks()
                    .fallbackTo { e ->
                        logger.error(e) { "observeRootSeriesWithBooks failed; emitting empty list" }
                        emptyList()
                    },
            flow3 =
                contributorRepository
                    .observeContributorsByRole(ContributorRole.AUTHOR.apiValue)
                    .fallbackTo { e ->
                        logger.error(e) { "observeContributorsByRole(AUTHOR) failed; emitting empty list" }
                        emptyList()
                    },
            flow4 =
                contributorRepository
                    .observeContributorsByRole(ContributorRole.NARRATOR.apiValue)
                    .fallbackTo { e ->
                        logger.error(e) { "observeContributorsByRole(NARRATOR) failed; emitting empty list" }
                        emptyList()
                    },
            flow5 =
                bookRatingRepository
                    .observeAverages()
                    .fallbackTo { e ->
                        logger.error(e) { "observeAverages failed; emitting empty map" }
                        emptyMap()
                    },
            transform = ::RawContent,
        ).shareIn(
            scope = viewModelScope,
            started = WhileSubscribedForUi,
            replay = 1,
        )

    // A separate flow rather than a sixth RawContent field — kotlinx.coroutines' combine has no
    // positional overload past five flows, and RawContent's five are already there.
    private val combinedScores: Flow<Map<String, CombinedScore>> =
        bookRatingRepository
            .observeCombinedScores()
            .fallbackTo { e ->
                logger.error(e) { "observeCombinedScores failed; emitting empty map" }
                emptyMap()
            }

    // Playback positions feed two stages (the reading-state snapshot and the progress snapshot), so
    // they are shared once: a second Room subscription would query positions twice per tick.
    private val positions: SharedFlow<Map<BookId, PlaybackPosition>> =
        playbackPositionRepository
            .observeAll()
            .fallbackTo { e ->
                logger.error(e) { "observeAll(positions) failed; emitting empty map" }
                emptyMap()
            }.shareIn(
                scope = viewModelScope,
                started = WhileSubscribedForUi,
                replay = 1,
            )

    // The coarse reading state per book. distinctUntilChanged is the whole point: a position tick
    // that moves no book across a state boundary produces an equal map and is dropped HERE, so the
    // sort stage (and contentRevision) never sees it.
    private val readingStateSnapshot: Flow<Map<BookId, ReadingState>> =
        combine(rawContent, positions) { content, positionMap -> readingStates(content.books, positionMap) }
            .distinctUntilChanged()

    // Stage 1: sort + filter. Depends ONLY on intent, raw content, the outside combined scores and
    // the coarse reading-state snapshot — a playback position tick (every ~30s during playback) that
    // moves no book across a reading-state boundary never reaches this combine, so the O(n log n)
    // sorts don't re-run when only progress changed.
    // distinctUntilChanged (cheap O(n) structural compare) additionally drops
    // Room re-emissions of structurally identical content, keeping downstream
    // list identity stable.
    private val sortedContent: Flow<SortedContent> =
        combine(
            flow = intent,
            flow2 = rawContent,
            flow3 = combinedScores,
            flow4 = readingStateSnapshot,
        ) { intentValue, content, scores, states ->
            val visibleSeries =
                if (intentValue.hideSingleBookSeries) {
                    content.series.filter { it.books.size > 1 }
                } else {
                    content.series
                }
            val sortedBooks =
                sortBooks(
                    books = content.books,
                    state = intentValue.booksSortState,
                    ignoreArticles = intentValue.ignoreTitleArticles,
                    listenerAverages = content.listenerAverages,
                    combinedScores = scores,
                )
            val filteredBooks =
                if (intentValue.statusFilter == BookStatusFilter.ALL) {
                    sortedBooks
                } else {
                    sortedBooks.filter { book ->
                        intentValue.statusFilter.admits(states[book.id] ?: ReadingState.NOT_STARTED)
                    }
                }
            val sortedSeries = sortSeries(visibleSeries, intentValue.seriesSortState, intentValue.ignoreTitleArticles)
            val sortedAuthors = sortContributors(content.authors, intentValue.authorsSortState)
            val sortedNarrators = sortContributors(content.narrators, intentValue.narratorsSortState)
            val statusCounts = countsOf(states.values)
            SortedContent(
                intent = intentValue,
                books = filteredBooks,
                series = sortedSeries,
                authors = sortedAuthors,
                narrators = sortedNarrators,
                statusCounts = statusCounts,
                sectionCounts =
                    LibrarySectionCounts(
                        books = statusCounts.all,
                        series = sortedSeries.size,
                        authors = sortedAuthors.size,
                        narrators = sortedNarrators.size,
                    ),
                totalDurationMs = content.books.sumOf { it.duration },
                // Letters are derived here, once per sorted snapshot, so they move with the revision
                // and never on a position tick.
                bookLetters =
                    bookLetterOf(intentValue.booksSortState.category, intentValue.ignoreTitleArticles)
                        ?.let { letterOf -> letterSections(filteredBooks, letterOf) }
                        .orEmpty(),
                seriesLetters =
                    seriesLetterOf(intentValue.seriesSortState.category, intentValue.ignoreTitleArticles)
                        ?.let { letterOf -> letterSections(sortedSeries) { letterOf(it.series.name) } }
                        .orEmpty(),
                authorLetters =
                    contributorLetterOf(intentValue.authorsSortState.category)
                        ?.let { letterOf -> letterSections(sortedAuthors) { letterOf(it.contributor.name) } }
                        .orEmpty(),
                narratorLetters =
                    contributorLetterOf(intentValue.narratorsSortState.category)
                        ?.let { letterOf -> letterSections(sortedNarrators) { letterOf(it.contributor.name) } }
                        .orEmpty(),
            )
        }.distinctUntilChanged()

    // The last content revision published. Only the single uiState collection advances it (stateIn
    // shares one upstream), but it outlives that collection, so a WhileSubscribed restart continues
    // the sequence rather than reusing a revision a consumer has already seen.
    private val contentRevisions = atomic(0L)

    private val revisedContent: Flow<RevisedContent> =
        sortedContent.map { RevisedContent(revision = contentRevisions.incrementAndGet(), content = it) }

    private val progressSnapshot: Flow<ProgressSnapshot> =
        combine(rawContent, positions) { content, positionMap -> computeProgress(content.books, positionMap) }
            .conflate()

    private val syncSnapshot: Flow<SyncSnapshot> =
        combine(
            flow = syncRepository.syncState,
            flow2 = syncRepository.serverScanning,
            flow3 = syncRepository.scanProgress,
            flow4 = syncRepository.buildingInitialLibrary,
            flow5 = syncRepository.firstCatchUpRunning,
            transform = ::SyncSnapshot,
            // SyncSnapshot is a data class — structural equality prevents re-sorting the library
            // on every firehose heartbeat or scan-progress tick when the values haven't actually changed.
        ).distinctUntilChanged()

    /**
     * Bumped to re-run the state pipeline after it failed — see [onEvent]'s `RefreshRequested`.
     *
     * ⛔ Needed because [LibraryUiState.Error] is what the pipeline's fallback emits as it
     * TERMINATES: the upstream is done, and `stateIn` holds that last value until the subscription
     * lapses. Without a restart, a Retry could re-sync every book into Room while the screen went on
     * saying the library can't be shown. The fallback therefore sits INSIDE the [flatMapLatest], so
     * a failure ends one run of the pipeline rather than the flow the screen is subscribed to.
     */
    private val pipelineRuns = MutableStateFlow(0)

    val uiState: StateFlow<LibraryUiState> =
        pipelineRuns
            .flatMapLatest { libraryStatePipeline() }
            .stateIn(
                scope = viewModelScope,
                started = WhileSubscribedForUi,
                initialValue = LibraryUiState.Loading,
            )

    private fun libraryStatePipeline(): Flow<LibraryUiState> =
        combine(
            revisedContent,
            progressSnapshot,
            syncSnapshot,
        ) { revised, progress, sync ->
            val loaded: LibraryUiState = buildLoaded(revised, progress, sync)
            loaded
        }
            // Under sync churn (e.g. post-import flood) the five upstream flows can emit faster than
            // a full sort+map can complete. Conflation drops intermediate states so we only pay the
            // sort cost for the latest snapshot — the UI always converges to the correct final list.
            .conflate()
            // Sorting/filtering ~1000 books runs in this transform; keep it off the main thread so a
            // burst of position updates (e.g. the post-import sync flood) can't stall input dispatch.
            .flowOn(backgroundDispatcher)
            .fallbackTo { e ->
                logger.error(e) { "Library state pipeline failed" }
                LibraryUiState.Error("Failed to load library")
            }

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
            intent.update { current ->
                current.copy(
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
            intent.update { current ->
                current.copy(
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
                // Only a failed pipeline is restarted. A healthy one is already live on Room, and
                // re-running it would re-sort the whole library to say the same thing.
                if (uiState.value is LibraryUiState.Error) pipelineRuns.update { it + 1 }
                refreshLibrary()
            }

            // Books tab sort events
            is LibraryUiEvent.BooksCategoryChanged -> {
                intent.update { it.copy(booksSortState = it.booksSortState.withCategory(event.category)) }
                persistBooksSort()
            }

            is LibraryUiEvent.BooksDirectionToggled -> {
                intent.update { it.copy(booksSortState = it.booksSortState.toggleDirection()) }
                persistBooksSort()
            }

            is LibraryUiEvent.BooksShownNewestFirst -> {
                intent.update { it.copy(booksSortState = SortState(SortCategory.ADDED, SortDirection.DESCENDING)) }
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

            is LibraryUiEvent.StatusFilterChanged -> {
                intent.update { it.copy(statusFilter = event.filter) }
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
    // PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    private fun refreshBooks() {
        viewModelScope.launch {
            bookRepository
                .refreshBooks()
                .onFailure { logger.warn { "Library refresh failed: ${it.message}" } }
        }
    }

    /** Pull-to-refresh is the same "sync now" the sync indicator offers, so a failure is heard, not just logged. */
    private fun refreshLibrary() {
        viewModelScope.launch { manualSyncRefresh.refresh() }
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
        revised: RevisedContent,
        progress: ProgressSnapshot,
        sync: SyncSnapshot,
    ): LibraryUiState.Loaded {
        val sorted = revised.content
        val arriving = sync.isBuildingInitialLibrary || sync.isFirstCatchUp
        val seriesProgress =
            sorted.series.associate { sb ->
                sb.series.id to
                    SeriesProgress(
                        finishedCount = sb.books.count { progress.finishedMap[it.id] == true },
                        totalCount = sb.books.size,
                    )
            }

        return LibraryUiState.Loaded(
            booksSortState = sorted.intent.booksSortState,
            seriesSortState = sorted.intent.seriesSortState,
            authorsSortState = sorted.intent.authorsSortState,
            narratorsSortState = sorted.intent.narratorsSortState,
            ignoreTitleArticles = sorted.intent.ignoreTitleArticles,
            hideSingleBookSeries = sorted.intent.hideSingleBookSeries,
            contentRevision = revised.revision,
            books = sorted.books,
            series = sorted.series,
            authors = sorted.authors,
            narrators = sorted.narrators,
            seriesProgress = seriesProgress,
            syncState = sync.syncState,
            isServerScanning = sync.isServerScanning,
            scanProgress = sync.scanProgress,
            isBuildingInitialLibrary = sync.isBuildingInitialLibrary,
            isLibraryArriving = arriving,
            statusStrip =
                libraryStatusStrip(
                    isServerScanning = sync.isServerScanning,
                    scanProgress = sync.scanProgress,
                    isLibraryArriving = arriving,
                    booksSoFar = sorted.statusCounts.all,
                ),
            statusFilter = sorted.intent.statusFilter,
            statusCounts = sorted.statusCounts,
            sectionCounts = sorted.sectionCounts,
            bookStatus = progress.cardStatus,
            totalDurationMs = sorted.totalDurationMs,
            bookLetters = sorted.bookLetters,
            seriesLetters = sorted.seriesLetters,
            authorLetters = sorted.authorLetters,
            narratorLetters = sorted.narratorLetters,
        )
    }

    private fun computeProgress(
        books: List<BookListItem>,
        positions: Map<BookId, PlaybackPosition>,
    ): ProgressSnapshot {
        // isFinished is authoritative from the server; seriesProgress counts it.
        val finishedMap = positions.filterValues { it.isFinished }.mapValues { true }
        val cardStatus = books.associate { it.id to cardStatusOf(it, positions[it.id]) }
        return ProgressSnapshot(finishedMap, cardStatus)
    }
}

/**
 * Events that can be triggered from the Library UI.
 */
sealed interface LibraryUiEvent {
    data object RefreshRequested : LibraryUiEvent

    // Books tab

    /** User picked a new sort category for the Books tab. */
    data class BooksCategoryChanged(
        val category: SortCategory,
    ) : LibraryUiEvent

    data object BooksDirectionToggled : LibraryUiEvent

    /**
     * Discover's "In Library" link: Books sorted Added, newest first, for this session only. Unlike a
     * sort the reader picks, it is not saved, so the next launch opens on their own sort (spec §6.3).
     */
    data object BooksShownNewestFirst : LibraryUiEvent

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

    /** The reader picked a reading-state filter for the Books view. Not persisted (session-scoped). */
    data class StatusFilterChanged(
        val filter: BookStatusFilter,
    ) : LibraryUiEvent
}
