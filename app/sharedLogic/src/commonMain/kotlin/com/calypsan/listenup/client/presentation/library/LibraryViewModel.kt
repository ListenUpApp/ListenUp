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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
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
    val totalDurationMs: Long,
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
 * Precomputed sort keys for the three-level SERIES sort on [BookListItem].
 *
 * Extracted as a file-level private class (rather than a local data class inside a `when` branch)
 * to avoid the duplicate JVM class-name error that arises when two `when` branches in the same
 * function each declare a local `data class` with the same name.
 */
private data class BookSeriesSortKey(
    val book: BookListItem,
    val seriesName: String,
    val sequence: Double,
    val title: String,
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
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
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
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
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
            SortedContent(
                intent = intentValue,
                books =
                    if (intentValue.statusFilter == BookStatusFilter.ALL) {
                        sortedBooks
                    } else {
                        sortedBooks.filter { book ->
                            intentValue.statusFilter.admits(states[book.id] ?: ReadingState.NOT_STARTED)
                        }
                    },
                series = sortSeries(visibleSeries, intentValue.seriesSortState, intentValue.ignoreTitleArticles),
                authors = sortContributors(content.authors, intentValue.authorsSortState),
                narrators = sortContributors(content.narrators, intentValue.narratorsSortState),
                statusCounts = countsOf(states.values),
                totalDurationMs = content.books.sumOf { it.duration },
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
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
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
                refreshBooks()
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
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════

    private var closed = false

    override fun onCleared() {
        super.onCleared()
        close()
    }

    /**
     * Cancels this ViewModel's coroutines. Idempotent. Android reaches it via [onCleared] when the
     * `ViewModelStore` clears the entry; iOS has no store, so the owning wrapper (the CarPlay
     * Library tab's) calls it from its `isolated deinit` (#1192) — else viewModelScope
     * streams/one-shots orphan when the session goes.
     */
    fun close() {
        if (closed) return
        closed = true
        viewModelScope.cancel()
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
            statusFilter = sorted.intent.statusFilter,
            statusCounts = sorted.statusCounts,
            bookStatus = progress.cardStatus,
            totalDurationMs = sorted.totalDurationMs,
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

    // ═══════════════════════════════════════════════════════════════════════
    // SORTING HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    @Suppress("CyclomaticComplexMethod", "CognitiveComplexMethod")
    private fun sortBooks(
        books: List<BookListItem>,
        state: SortState,
        ignoreArticles: Boolean,
        listenerAverages: Map<String, ListenerAverage>,
        combinedScores: Map<String, CombinedScore>,
    ): List<BookListItem> {
        val isAsc = state.direction == SortDirection.ASCENDING

        // Schwartzian transform: compute each sort key ONCE per element, then sort by the
        // precomputed value. Without this, comparators like `.sortedBy { it.title.sortableTitle(...) }`
        // invoke the key function O(n*log n) times per sort — with regex + toLowerCase that's
        // significant allocation churn on a 1000+ book library every time the flow emits.
        return when (state.category) {
            SortCategory.TITLE -> {
                // Key: sortable title string (regex strip + lowercase) — computed once per element.
                val keyed = books.map { it to it.title.sortableTitle(ignoreArticles) }
                val sorted = if (isAsc) keyed.sortedBy { it.second } else keyed.sortedByDescending { it.second }
                sorted.map { it.first }
            }

            SortCategory.AUTHOR -> {
                // Primary key: authorNames (joinToString + lowercase) — computed once per element.
                // Secondary key: title lowercase (tie-breaker), also precomputed.
                val keyed = books.map { Triple(it, it.authorNames.lowercase(), it.title.lowercase()) }
                val sorted =
                    if (isAsc) {
                        keyed.sortedWith(compareBy({ it.second }, { it.third }))
                    } else {
                        keyed.sortedWith(
                            compareByDescending<Triple<BookListItem, String, String>> { it.second }.thenBy { it.third },
                        )
                    }
                sorted.map { it.first }
            }

            SortCategory.DURATION -> {
                if (isAsc) books.sortedBy { it.duration } else books.sortedByDescending { it.duration }
            }

            SortCategory.YEAR -> {
                // Primary key: publish year (null sentinel differs by direction) + secondary: title lowercase.
                val nullYear = if (isAsc) Int.MAX_VALUE else 0
                val keyed = books.map { Triple(it, it.publishYear ?: nullYear, it.title.lowercase()) }
                val sorted =
                    if (isAsc) {
                        keyed.sortedWith(compareBy({ it.second }, { it.third }))
                    } else {
                        keyed.sortedWith(
                            compareByDescending<Triple<BookListItem, Int, String>> { it.second }.thenBy { it.third },
                        )
                    }
                sorted.map { it.first }
            }

            SortCategory.ADDED -> {
                if (isAsc) {
                    books.sortedBy {
                        it.addedAt.epochMillis
                    }
                } else {
                    books.sortedByDescending { it.addedAt.epochMillis }
                }
            }

            SortCategory.RATING -> {
                sortByRatedAverage(books, isAsc) { combinedScores[it.id.value]?.average }
            }

            SortCategory.LISTENER_RATING -> {
                sortByRatedAverage(books, isAsc) { listenerAverages[it.id.value]?.averageHalfStars }
            }

            SortCategory.SERIES -> {
                // Three-level key: series name (lowercase, null sentinel), sequence (Float), title.
                // All three keys are precomputed once per element via BookSeriesSortKey (file-level
                // private class — can't use a local data class here without a JVM name collision
                // across the when-branches).
                val nullSeriesName = if (isAsc) "￿" else ""
                val nullSequence = if (isAsc) Double.MAX_VALUE else 0.0
                val keyed =
                    books.map { book ->
                        BookSeriesSortKey(
                            book = book,
                            seriesName = book.seriesName?.lowercase() ?: nullSeriesName,
                            sequence = book.seriesSequence ?: nullSequence,
                            title = book.title.lowercase(),
                        )
                    }
                val sorted =
                    if (isAsc) {
                        keyed.sortedWith(compareBy({ it.seriesName }, { it.sequence }, { it.title }))
                    } else {
                        keyed.sortedWith(
                            compareByDescending<BookSeriesSortKey> { it.seriesName }
                                .thenByDescending { it.sequence }
                                .thenBy { it.title },
                        )
                    }
                sorted.map { it.book }
            }

            // Not applicable for books
            SortCategory.NAME, SortCategory.BOOK_COUNT -> {
                books
            }
        }
    }

    /**
     * Shared by [SortCategory.RATING] and [SortCategory.LISTENER_RATING]: unrated books last in
     * BOTH directions (an absent average is not a zero), equal averages falling back to title
     * (lowercase, ascending) — like AUTHOR and YEAR. [averageFor] is the one thing that differs
     * between the two categories.
     */
    private fun sortByRatedAverage(
        books: List<BookListItem>,
        isAsc: Boolean,
        averageFor: (BookListItem) -> Double?,
    ): List<BookListItem> {
        val keyed =
            books.mapNotNull { book ->
                averageFor(book)?.let { average -> Triple(book, average, book.title.lowercase()) }
            }
        val unrated = books.filter { averageFor(it) == null }
        val sorted =
            if (isAsc) {
                keyed.sortedWith(compareBy({ it.second }, { it.third }))
            } else {
                keyed.sortedWith(
                    compareByDescending<Triple<BookListItem, Double, String>> { it.second }.thenBy { it.third },
                )
            }
        return sorted.map { it.first } + unrated
    }

    private fun sortSeries(
        series: List<SeriesWithBooks>,
        state: SortState,
        ignoreArticles: Boolean,
    ): List<SeriesWithBooks> {
        val isAsc = state.direction == SortDirection.ASCENDING

        return when (state.category) {
            SortCategory.NAME -> {
                // Key: article-aware sortable name (so "The Wandering Inn" files under W) — computed once.
                val keyed = series.map { it to it.series.name.sortableTitle(ignoreArticles) }
                val sorted = if (isAsc) keyed.sortedBy { it.second } else keyed.sortedByDescending { it.second }
                sorted.map { it.first }
            }

            SortCategory.BOOK_COUNT -> {
                if (isAsc) series.sortedBy { it.books.size } else series.sortedByDescending { it.books.size }
            }

            SortCategory.ADDED -> {
                if (isAsc) {
                    series.sortedBy { it.series.createdAt.epochMillis }
                } else {
                    series.sortedByDescending { it.series.createdAt.epochMillis }
                }
            }

            // Default to name sort for unsupported categories
            SortCategory.TITLE,
            SortCategory.AUTHOR,
            SortCategory.DURATION,
            SortCategory.YEAR,
            SortCategory.RATING,
            SortCategory.LISTENER_RATING,
            SortCategory.SERIES,
            -> {
                val keyed = series.map { it to it.series.name.sortableTitle(ignoreArticles) }
                keyed.sortedBy { it.second }.map { it.first }
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
                // Key: name lowercase — computed once per element.
                val keyed = contributors.map { it to it.contributor.name.lowercase() }
                val sorted = if (isAsc) keyed.sortedBy { it.second } else keyed.sortedByDescending { it.second }
                sorted.map { it.first }
            }

            SortCategory.BOOK_COUNT -> {
                if (isAsc) contributors.sortedBy { it.bookCount } else contributors.sortedByDescending { it.bookCount }
            }

            // Default to name sort for unsupported categories
            SortCategory.TITLE,
            SortCategory.AUTHOR,
            SortCategory.DURATION,
            SortCategory.YEAR,
            SortCategory.ADDED,
            SortCategory.RATING,
            SortCategory.LISTENER_RATING,
            SortCategory.SERIES,
            -> {
                val keyed = contributors.map { it to it.contributor.name.lowercase() }
                keyed.sortedBy { it.second }.map { it.first }
            }
        }
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
