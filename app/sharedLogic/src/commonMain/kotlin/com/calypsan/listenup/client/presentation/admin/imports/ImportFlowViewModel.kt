package com.calypsan.listenup.client.presentation.admin.imports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.imports.ImportEvent
import com.calypsan.listenup.api.error.ImportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.onFailure
import com.calypsan.listenup.client.domain.model.SearchHitType
import com.calypsan.listenup.client.domain.repository.AdminRepository
import com.calypsan.listenup.client.domain.repository.ImportRepository
import com.calypsan.listenup.client.domain.repository.SearchRepository
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.core.AbsItemId
import com.calypsan.listenup.core.AbsUserId
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FileSource
import com.calypsan.listenup.core.ImportId
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * ViewModel for the rebuilt Audiobookshelf import flow.
 *
 * Drives a linear pipeline keyed on a single [ImportId]:
 *  1. [start] — streams the backup file to the server, receiving an [ImportId].
 *  2. Analyze — subscribes [ImportRepository.observeProgress] *before* calling
 *     [ImportRepository.analyze] so no early events are missed, then exposes
 *     live [ImportFlowUiState.Analyzing] ticks while the server processes.
 *  3. [ImportFlowUiState.Review] — exposes the full [ImportAnalysis] for the admin to
 *     inspect. [ImportFlowUiState.Review.userMappings] starts **empty** — the admin must
 *     explicitly assign each ABS user via [setUserMapping] or skip them via [skipUser].
 *     Analysis suggestions ([ImportFlowUiState.Review.analysis] `userMatches[].suggestedUserId`)
 *     remain visible for one-tap acceptance in the UI, but nothing is pre-applied.
 *     [ImportFlowUiState.Review.listenupUsers] is loaded from [AdminRepository.getUsers] on
 *     entering Review; a load failure is non-fatal (logs a warning, uses empty list, emits
 *     to [ErrorBus]).
 *  4. [confirmAndApply] — persists the admin's explicit mappings then applies them, again
 *     subscribing observeProgress before apply so live [ImportFlowUiState.Applying] ticks
 *     are seen. Only [ImportFlowUiState.Review.userMappings] (explicit assignments) are sent;
 *     skipped and unresolved ABS users are simply absent, so the server imports nothing for them.
 *  5. [ImportFlowUiState.Done] — carries the [ImportResult]; triggers a
 *     [SyncRepository.refreshListeningHistory] to pull the written events into Room.
 *
 * Progress events drive intermediate [ImportFlowUiState.Analyzing] and
 * [ImportFlowUiState.Applying] updates via a persistent [progressJob]. The RPC method
 * return values carry the definitive outcomes: [ImportFlowUiState.Review] (from
 * [ImportRepository.analyze]'s AppResult) and [ImportFlowUiState.Done] (from
 * [ImportRepository.apply]'s AppResult). A [ImportEvent.Failed] from the progress stream
 * overrides any state set by the RPC return and moves to [ImportFlowUiState.Error].
 *
 * Any [AppResult.Failure] or [ImportEvent.Failed] transitions to [ImportFlowUiState.Error]
 * and emits the typed [AppError] to the global [ErrorBus]. [reset] returns to
 * [ImportFlowUiState.Idle] from either [ImportFlowUiState.Error] or [ImportFlowUiState.Done].
 */
class ImportFlowViewModel(
    private val importRepository: ImportRepository,
    private val errorBus: ErrorBus,
    private val syncRepository: SyncRepository,
    private val adminRepository: AdminRepository,
    private val searchRepository: SearchRepository,
) : ViewModel() {
    /**
     * Current phase of the import flow. Transitions are always total replacements
     * of the sealed variant — no partial updates inside a phase except [ImportFlowUiState.Review]
     * mapping mutations, which use copy().
     */
    val uiState: StateFlow<ImportFlowUiState>
        field = MutableStateFlow<ImportFlowUiState>(ImportFlowUiState.Idle)

    /** Tracks the import ID issued by the server after a successful upload. */
    private var currentImportId: ImportId? = null

    /** In-flight progress collection job; cancelled on reset or a new start(). */
    private var progressJob: Job? = null

    /**
     * In-flight book-search debounce+fetch job for the search panel.
     *
     * Cancelled and replaced each time [updateBookSearchQuery] is called, ensuring only
     * the most-recently-typed query's results can land. Also cancelled by [reset] and
     * [onCleared].
     */
    private var bookSearchJob: Job? = null

    // ─── public API ───────────────────────────────────────────────────────────

    /**
     * Begin the import flow by uploading [fileSource] to the server.
     *
     * Transitions immediately to [ImportFlowUiState.Uploading], then on success
     * subscribes [ImportRepository.observeProgress] before invoking
     * [ImportRepository.analyze] — ensuring no early progress ticks are dropped.
     * Failures at either step produce [ImportFlowUiState.Error].
     *
     * @param fileSource Platform-agnostic streaming source for the backup file.
     */
    fun start(fileSource: FileSource) {
        progressJob?.cancel()
        uiState.value = ImportFlowUiState.Uploading(filename = fileSource.filename)

        viewModelScope.launch {
            // Step 1: upload
            when (val uploadResult = importRepository.upload(fileSource)) {
                is AppResult.Failure -> {
                    errorBus.emit(uploadResult.error)
                    logger.error { "Import upload failed: ${uploadResult.error.message}" }
                    uiState.value = ImportFlowUiState.Error(uploadResult.error)
                    return@launch
                }

                is AppResult.Success -> {
                    val importId = uploadResult.data.id
                    currentImportId = importId
                    uiState.value =
                        ImportFlowUiState.Analyzing(
                            done = 0,
                            total = 0,
                            currentItem = null,
                            usersMatched = 0,
                            booksMatched = 0,
                        )

                    // Step 2: subscribe progress BEFORE analyze so early ticks aren't missed.
                    // The job stays alive until a terminal event (Failed) or until analyze()
                    // returns and we cancel it in the success path.
                    progressJob =
                        launch {
                            importRepository.observeProgress(importId).collect { event ->
                                handleAnalyzeEvent(event)
                            }
                        }

                    // Step 3: trigger analyze
                    when (val analyzeResult = importRepository.analyze(importId)) {
                        is AppResult.Failure -> {
                            // Only set Error if the stream hasn't already done so
                            if (uiState.value !is ImportFlowUiState.Error) {
                                progressJob?.cancel()
                                errorBus.emit(analyzeResult.error)
                                logger.error { "Import analyze failed: ${analyzeResult.error.message}" }
                                uiState.value = ImportFlowUiState.Error(analyzeResult.error)
                            }
                        }

                        is AppResult.Success -> {
                            // Only transition to Review if the stream hasn't already signalled
                            // an error. The stream can't set Review itself (Analyzed event only
                            // carries ImportSummary, not the full ImportAnalysis), so the RPC
                            // return is the definitive path to Review.
                            if (uiState.value !is ImportFlowUiState.Error) {
                                progressJob?.cancel()
                                // Load ListenUp users for the user picker. A failure is non-fatal:
                                // log a warning, emit to the error bus, and continue with an empty
                                // list so the admin can still skip users. CancellationException
                                // from the suspend call propagates naturally (not caught here).
                                val listenupUsers =
                                    when (val usersResult = adminRepository.getUsers()) {
                                        is AppResult.Success -> {
                                            usersResult.data
                                        }

                                        is AppResult.Failure -> {
                                            logger.warn {
                                                "Failed to load ListenUp users for import picker: " +
                                                    usersResult.error.message
                                            }
                                            errorBus.emit(usersResult.error)
                                            emptyList()
                                        }
                                    }
                                uiState.value =
                                    ImportFlowUiState.Review(
                                        analysis = analyzeResult.data,
                                        userMappings = emptyMap(),
                                        skippedUsers = emptySet(),
                                        bookOverrides = emptyMap(),
                                        listenupUsers = listenupUsers,
                                    )
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Explicitly assign [absUserId] to [userId] while in [ImportFlowUiState.Review].
     *
     * Assigning un-skips the ABS user: [absUserId] is removed from
     * [ImportFlowUiState.Review.skippedUsers] if present. No-op when not in Review.
     */
    fun setUserMapping(
        absUserId: AbsUserId,
        userId: UserId,
    ) {
        updateReview {
            it.copy(
                userMappings = it.userMappings + (absUserId to userId),
                skippedUsers = it.skippedUsers - absUserId,
            )
        }
    }

    /**
     * Mark [absUserId] as explicitly skipped while in [ImportFlowUiState.Review].
     *
     * Skipping removes any existing mapping for [absUserId] from
     * [ImportFlowUiState.Review.userMappings]. No history is imported for skipped users.
     * No-op when not in Review.
     */
    fun skipUser(absUserId: AbsUserId) {
        updateReview {
            it.copy(
                skippedUsers = it.skippedUsers + absUserId,
                userMappings = it.userMappings - absUserId,
            )
        }
    }

    /**
     * Override the book match for [absItemId] while in [ImportFlowUiState.Review].
     * A null [bookId] marks the item as explicitly skipped.
     * No-op when the flow is not in [ImportFlowUiState.Review].
     */
    fun setBookOverride(
        absItemId: AbsItemId,
        bookId: BookId?,
    ) {
        updateReview { it.copy(bookOverrides = it.bookOverrides + (absItemId to bookId)) }
    }

    /**
     * Open the book-search panel for [absItemId] while in [ImportFlowUiState.Review].
     *
     * Sets [ImportFlowUiState.Review.bookSearch] to a blank initial state for the given item.
     * Opening a panel while another is already open replaces it (only one panel at a time).
     * No-op when the flow is not in [ImportFlowUiState.Review].
     */
    fun openBookSearch(absItemId: AbsItemId) {
        updateReview {
            it.copy(
                bookSearch =
                    BookSearchState(
                        absItemId = absItemId,
                        query = "",
                        results = emptyList(),
                        isSearching = false,
                    ),
            )
        }
    }

    /**
     * Close the book-search panel while in [ImportFlowUiState.Review].
     *
     * Sets [ImportFlowUiState.Review.bookSearch] to null. Also cancels any in-flight search.
     * No-op when the flow is not in [ImportFlowUiState.Review].
     */
    fun closeBookSearch() {
        bookSearchJob?.cancel()
        updateReview { it.copy(bookSearch = null) }
    }

    /**
     * Update the book-search query and trigger a debounced search.
     *
     * Cancels the previous search job and launches a new one. After a 300 ms debounce,
     * calls [SearchRepository.search] filtered to [SearchHitType.BOOK] and maps the hits
     * to [BookSearchHit]s. A blank [query] clears results without triggering a search call.
     *
     * Stale-result guard: results are only applied when the current
     * [ImportFlowUiState.Review.bookSearch]'s [absItemId] and query still match the search
     * that produced them — superseded queries are silently discarded.
     *
     * Re-throws [CancellationException] if the coroutine is cancelled; all other exceptions
     * are logged and swallowed so a transient search failure never breaks the panel.
     *
     * No-op when the flow is not in [ImportFlowUiState.Review] or no panel is open.
     */
    fun updateBookSearchQuery(query: String) {
        val review = uiState.value as? ImportFlowUiState.Review ?: return
        val currentSearch = review.bookSearch ?: return
        val absItemId = currentSearch.absItemId

        // Update query immediately so the text field stays responsive
        updateReview { it.copy(bookSearch = it.bookSearch?.copy(query = query)) }

        bookSearchJob?.cancel()

        if (query.isBlank()) {
            updateReview { it.copy(bookSearch = it.bookSearch?.copy(results = emptyList(), isSearching = false)) }
            return
        }

        bookSearchJob =
            viewModelScope.launch {
                // Mark searching BEFORE debounce so the spinner can appear immediately
                updateReview { it.copy(bookSearch = it.bookSearch?.copy(isSearching = true)) }
                delay(BOOK_SEARCH_DEBOUNCE_MS)

                try {
                    val result =
                        searchRepository.search(
                            query = query,
                            types = listOf(SearchHitType.BOOK),
                        )
                    val hits =
                        result.hits.map { hit ->
                            BookSearchHit(
                                bookId = BookId(hit.id),
                                title = hit.name,
                                author = hit.author ?: "",
                            )
                        }
                    // Stale-result guard: only apply if still on the same item and query
                    val currentReview = uiState.value as? ImportFlowUiState.Review
                    val currentBookSearch = currentReview?.bookSearch
                    if (currentBookSearch?.absItemId == absItemId && currentBookSearch.query == query) {
                        updateReview { it.copy(bookSearch = it.bookSearch?.copy(results = hits, isSearching = false)) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "Book search failed for query '$query'" }
                    updateReview { it.copy(bookSearch = it.bookSearch?.copy(isSearching = false)) }
                }
            }
    }

    /**
     * Select a book from the search panel for [absItemId] while in [ImportFlowUiState.Review].
     *
     * Records [bookId] as the override for [absItemId] and closes the search panel.
     * Equivalent to calling [setBookOverride] then [closeBookSearch] atomically.
     * No-op when the flow is not in [ImportFlowUiState.Review].
     */
    fun selectBook(
        absItemId: AbsItemId,
        bookId: BookId,
    ) {
        bookSearchJob?.cancel()
        updateReview {
            it.copy(
                bookOverrides = it.bookOverrides + (absItemId to bookId),
                bookSearch = null,
            )
        }
    }

    /**
     * Skip [absItemId] in [ImportFlowUiState.Review], marking it as intentionally excluded.
     *
     * Records a null value in [ImportFlowUiState.Review.bookOverrides] for [absItemId].
     * The server interprets a null override as "skip this item — import no history for it".
     * No-op when the flow is not in [ImportFlowUiState.Review].
     */
    fun skipBook(absItemId: AbsItemId) {
        setBookOverride(absItemId, null)
    }

    /**
     * Persist the admin's mappings via [ImportRepository.confirmMapping], then apply
     * the import via [ImportRepository.apply]. Subscribes [ImportRepository.observeProgress]
     * before calling apply so live ticks are not missed.
     *
     * No-op unless the current state is [ImportFlowUiState.Review].
     * On success, calls [SyncRepository.refreshListeningHistory] and transitions to [ImportFlowUiState.Done].
     * On failure, transitions to [ImportFlowUiState.Error] and emits to [ErrorBus].
     */
    fun confirmAndApply() {
        val review = uiState.value as? ImportFlowUiState.Review ?: return
        val importId = currentImportId ?: return
        progressJob?.cancel()

        viewModelScope.launch {
            // Step 1: confirm mappings
            when (
                val confirmResult =
                    importRepository.confirmMapping(
                        importId = importId,
                        userMappings = review.userMappings,
                        bookOverrides = review.bookOverrides,
                    )
            ) {
                is AppResult.Failure -> {
                    errorBus.emit(confirmResult.error)
                    logger.error { "Import confirmMapping failed: ${confirmResult.error.message}" }
                    uiState.value = ImportFlowUiState.Error(confirmResult.error)
                    return@launch
                }

                is AppResult.Success -> {
                    uiState.value =
                        ImportFlowUiState.Applying(
                            done = 0,
                            total = 0,
                            currentItem = null,
                            sessionsWritten = 0,
                        )

                    // Step 2: subscribe progress BEFORE apply so early ticks aren't missed.
                    progressJob =
                        launch {
                            importRepository.observeProgress(importId).collect { event ->
                                handleApplyEvent(event)
                            }
                        }

                    // Step 3: trigger apply
                    when (val applyResult = importRepository.apply(importId)) {
                        is AppResult.Failure -> {
                            if (uiState.value !is ImportFlowUiState.Error) {
                                progressJob?.cancel()
                                errorBus.emit(applyResult.error)
                                logger.error { "Import apply failed: ${applyResult.error.message}" }
                                uiState.value = ImportFlowUiState.Error(applyResult.error)
                            }
                        }

                        is AppResult.Success -> {
                            if (uiState.value !is ImportFlowUiState.Error && uiState.value !is ImportFlowUiState.Done) {
                                progressJob?.cancel()
                                uiState.value = ImportFlowUiState.Done(result = applyResult.data)
                                syncRepository
                                    .refreshListeningHistory()
                                    .onFailure {
                                        logger.warn { "Listening-history refresh after import failed: ${it.message}" }
                                    }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Return to [ImportFlowUiState.Idle] from any terminal state.
     *
     * Cancels any in-flight progress collection and clears the [currentImportId].
     * Can be called from [ImportFlowUiState.Error] or [ImportFlowUiState.Done].
     */
    fun reset() {
        progressJob?.cancel()
        bookSearchJob?.cancel()
        currentImportId = null
        uiState.value = ImportFlowUiState.Idle
    }

    // ─── private helpers ──────────────────────────────────────────────────────

    private fun handleAnalyzeEvent(event: ImportEvent) {
        when (event) {
            is ImportEvent.Parsing -> {
                // Stay in current Analyzing state; no progress numbers yet.
            }

            is ImportEvent.Matching -> {
                // Only update if we are still in Analyzing; if analyze() already returned
                // and moved us to Review, these intermediate ticks are no-ops.
                if (uiState.value is ImportFlowUiState.Analyzing) {
                    uiState.value =
                        ImportFlowUiState.Analyzing(
                            done = event.done,
                            total = event.total,
                            currentItem = event.currentItem,
                            usersMatched = event.usersMatched,
                            booksMatched = event.booksMatched,
                        )
                }
            }

            is ImportEvent.Analyzed -> {
                // The full ImportAnalysis is only available from the RPC return; this event
                // carries only ImportSummary. The analyze() AppResult drives Review; nothing
                // to do here unless the RPC somehow never returns (shouldn't happen).
            }

            is ImportEvent.Failed -> {
                // A server-side failure on the progress stream overrides whatever the RPC
                // returned. This handles the case where the server signals failure on the progress stream
                // before (or after) the RPC method returns.
                logger.error { "Import analysis failed via event: ${event.reason}" }
                uiState.value = ImportFlowUiState.Error(ImportError.AnalysisFailed(debugInfo = event.reason))
            }

            // Apply-phase events during analyze — ignore.
            is ImportEvent.Applying, is ImportEvent.Applied -> {}
        }
    }

    private suspend fun handleApplyEvent(event: ImportEvent) {
        when (event) {
            is ImportEvent.Applying -> {
                if (uiState.value is ImportFlowUiState.Applying) {
                    uiState.value =
                        ImportFlowUiState.Applying(
                            done = event.done,
                            total = event.total,
                            currentItem = event.currentItem,
                            sessionsWritten = event.sessionsWritten,
                        )
                }
            }

            is ImportEvent.Applied -> {
                // The terminal Applied event from the stream carries the result and can
                // deliver Done before (or racing with) the apply() AppResult.
                if (uiState.value !is ImportFlowUiState.Done && uiState.value !is ImportFlowUiState.Error) {
                    uiState.value = ImportFlowUiState.Done(result = event.result)
                    syncRepository
                        .refreshListeningHistory()
                        .onFailure { logger.warn { "Listening-history refresh after import failed: ${it.message}" } }
                }
            }

            is ImportEvent.Failed -> {
                logger.error { "Import apply failed via event: ${event.reason}" }
                uiState.value = ImportFlowUiState.Error(ImportError.ApplyFailed(debugInfo = event.reason))
            }

            // Analyze-phase events during apply — ignore.
            is ImportEvent.Parsing, is ImportEvent.Matching, is ImportEvent.Analyzed -> {}
        }
    }

    /**
     * Apply [transform] to state only when it is [ImportFlowUiState.Review].
     * No-ops for all other phases.
     */
    private fun updateReview(transform: (ImportFlowUiState.Review) -> ImportFlowUiState.Review) {
        val current = uiState.value
        if (current is ImportFlowUiState.Review) {
            uiState.value = transform(current)
        }
    }

    override fun onCleared() {
        super.onCleared()
        progressJob?.cancel()
        bookSearchJob?.cancel()
    }

    companion object {
        /** Debounce delay for book-search queries, in milliseconds. */
        private const val BOOK_SEARCH_DEBOUNCE_MS = 300L
    }
}
