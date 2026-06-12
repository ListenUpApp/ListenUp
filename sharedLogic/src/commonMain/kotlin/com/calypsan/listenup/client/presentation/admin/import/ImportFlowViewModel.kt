package com.calypsan.listenup.client.presentation.admin.import

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.import.ImportEvent
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.ImportRepository
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.presentation.error.userMessageFor
import com.calypsan.listenup.core.AbsItemId
import com.calypsan.listenup.core.AbsUserId
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FileSource
import com.calypsan.listenup.core.ImportId
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Job
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
 *  3. [ImportFlowUiState.Review] — exposes the full [ImportAnalysis] for the admin
 *     to inspect. [ImportFlowUiState.Review.userMappings] is pre-seeded from STRONG
 *     (non-null [AbsUserMatch.suggestedUserId]) matches; the admin can still override
 *     or add mappings via [setUserMapping]. [ImportFlowUiState.Review.bookOverrides]
 *     starts empty — the server auto-applies confident book matches.
 *  4. [confirmAndApply] — persists the mappings then applies them, again subscribing
 *     observeProgress before apply so live [ImportFlowUiState.Applying] ticks are seen.
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
                    uiState.value = ImportFlowUiState.Error(userMessageFor(uploadResult.error))
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
                                uiState.value = ImportFlowUiState.Error(userMessageFor(analyzeResult.error))
                            }
                        }

                        is AppResult.Success -> {
                            // Only transition to Review if the stream hasn't already signalled
                            // an error. The stream can't set Review itself (Analyzed event only
                            // carries ImportSummary, not the full ImportAnalysis), so the RPC
                            // return is the definitive path to Review.
                            if (uiState.value !is ImportFlowUiState.Error) {
                                progressJob?.cancel()
                                // Seed userMappings from STRONG (non-null suggestedUserId) matches
                                // so that history records are imported for auto-matched users even
                                // when the admin makes no explicit setUserMapping calls. The admin
                                // can still override or extend mappings after Review is shown.
                                val autoUserMappings: Map<AbsUserId, UserId> =
                                    analyzeResult.data.userMatches
                                        .mapNotNull { match -> match.suggestedUserId?.let { match.absUserId to it } }
                                        .toMap()
                                uiState.value =
                                    ImportFlowUiState.Review(
                                        analysis = analyzeResult.data,
                                        userMappings = autoUserMappings,
                                        bookOverrides = emptyMap(),
                                    )
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Update the ABS-user → ListenUp-user mapping for [absUserId] while in [ImportFlowUiState.Review].
     * No-op when the flow is not in [ImportFlowUiState.Review].
     */
    fun setUserMapping(
        absUserId: AbsUserId,
        userId: UserId,
    ) {
        updateReview { it.copy(userMappings = it.userMappings + (absUserId to userId)) }
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
                    uiState.value = ImportFlowUiState.Error(userMessageFor(confirmResult.error))
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
                                uiState.value = ImportFlowUiState.Error(userMessageFor(applyResult.error))
                            }
                        }

                        is AppResult.Success -> {
                            if (uiState.value !is ImportFlowUiState.Error && uiState.value !is ImportFlowUiState.Done) {
                                progressJob?.cancel()
                                uiState.value = ImportFlowUiState.Done(result = applyResult.data)
                                syncRepository.refreshListeningHistory()
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
                // returned. This handles the case where the server signals failure via SSE
                // before (or after) the RPC method returns.
                logger.error { "Import analysis failed via event: ${event.reason}" }
                uiState.value = ImportFlowUiState.Error(event.reason)
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
                    syncRepository.refreshListeningHistory()
                }
            }

            is ImportEvent.Failed -> {
                logger.error { "Import apply failed via event: ${event.reason}" }
                uiState.value = ImportFlowUiState.Error(event.reason)
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
    }
}
