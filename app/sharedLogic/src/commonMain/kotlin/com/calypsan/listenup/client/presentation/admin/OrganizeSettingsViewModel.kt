package com.calypsan.listenup.client.presentation.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.organize.OrganizeAuthorForm
import com.calypsan.listenup.api.dto.organize.OrganizePreset
import com.calypsan.listenup.api.dto.organize.OrganizePreviewDto
import com.calypsan.listenup.api.dto.organize.OrganizeRunEvent
import com.calypsan.listenup.api.dto.organize.OrganizeRunId
import com.calypsan.listenup.api.dto.organize.OrganizeSeriesPrefix
import com.calypsan.listenup.api.dto.organize.OrganizeSettingsDto
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.OrganizeRepository
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * ViewModel for the admin file-organizer settings screen (#850).
 *
 * **Two actions, deliberately unalike**, because saving rules and rearranging someone's files are
 * not the same promise:
 * - [saveRules] persists the schema and stops. It is live for future arrivals immediately, not one
 *   file moves, and a one-shot [OrganizeSettingsEvent.RulesSaved] confirms it in a snackbar.
 * - [organize] → [confirmOrganize] is the explicit sweep: fetch the plan preview
 *   ([OrganizeSettingsUiState.Ready.preview] renders the consent dialog — scope counts + first-N
 *   before→after rows), and only on confirm persist AND run. Run progress streams into
 *   [OrganizeSettingsUiState.Ready.run] to a terminal report; a partial failure's Resume re-opens
 *   the same preview, and the server re-plans the remainder.
 */
class OrganizeSettingsViewModel(
    private val repository: OrganizeRepository,
    private val errorBus: ErrorBus,
) : ViewModel() {
    val state: StateFlow<OrganizeSettingsUiState>
        field = MutableStateFlow<OrganizeSettingsUiState>(OrganizeSettingsUiState.Loading)

    private val eventChannel = Channel<OrganizeSettingsEvent>(Channel.BUFFERED)

    /**
     * One-shot events the screen consumes exactly once (the "rules saved" confirmation).
     * A [Channel] per the one-shot-events rubric rule, so re-collection never replays a snackbar.
     */
    val events: Flow<OrganizeSettingsEvent> = eventChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            when (val result = repository.getSettings()) {
                is AppResult.Success -> {
                    state.update { OrganizeSettingsUiState.Ready(settings = result.data) }
                    reattachActiveRun()
                }

                is AppResult.Failure -> {
                    logger.error { "Failed to load organizer settings: ${result.error}" }
                    state.update { OrganizeSettingsUiState.Error(result.error) }
                }
            }
        }
    }

    /** Picks the structure preset in the edit buffer. */
    fun setPreset(preset: OrganizePreset) = updateSettings { it.copy(preset = preset) }

    /** Picks the series-prefix style in the edit buffer. */
    fun setSeriesPrefix(prefix: OrganizeSeriesPrefix) = updateSettings { it.copy(seriesPrefix = prefix) }

    /** Picks the author-name form in the edit buffer. */
    fun setAuthorForm(form: OrganizeAuthorForm) = updateSettings { it.copy(authorForm = form) }

    /**
     * Save tapped — persist the rules and stop. They govern future arrivals from this moment; not
     * one existing file moves. Confirms through a one-shot [OrganizeSettingsEvent.RulesSaved].
     */
    fun saveRules() {
        val ready = state.value as? OrganizeSettingsUiState.Ready ?: return
        viewModelScope.launch {
            updateReady { it.copy(isWorking = true, error = null) }
            when (val result = repository.saveSettings(ready.settings)) {
                is AppResult.Success -> {
                    updateReady { it.copy(isWorking = false) }
                    eventChannel.trySend(OrganizeSettingsEvent.RulesSaved)
                }

                is AppResult.Failure -> {
                    failWorking(result.error)
                }
            }
        }
    }

    /**
     * Organize Library tapped — fetch the plan preview and open the consent dialog. Nothing
     * persists and nothing moves until [confirmOrganize].
     */
    fun organize() {
        val ready = state.value as? OrganizeSettingsUiState.Ready ?: return
        viewModelScope.launch {
            updateReady { it.copy(isWorking = true, error = null) }
            when (val result = repository.preview(ready.settings)) {
                is AppResult.Success -> {
                    val preview = result.data
                    updateReady { it.copy(isWorking = false) }
                    // An empty plan gets no consent dialog. Confirming "do nothing" is the wrong
                    // interaction, and a dialog whose body renders no rows at all reads as broken.
                    if (preview.bookCount == 0 && preview.renamedInPlaceCount == 0) {
                        eventChannel.send(OrganizeSettingsEvent.AlreadyOrganized)
                    } else {
                        updateReady { it.copy(preview = preview) }
                    }
                }

                is AppResult.Failure -> {
                    failWorking(result.error)
                }
            }
        }
    }

    /** Consent dialog confirmed — persist the settings and run the reorganization now. */
    fun confirmOrganize() {
        val ready = state.value as? OrganizeSettingsUiState.Ready ?: return
        viewModelScope.launch {
            updateReady { it.copy(isWorking = true, preview = null, error = null) }
            when (val result = repository.saveAndExecute(ready.settings)) {
                is AppResult.Success -> observeRun(result.data)
                is AppResult.Failure -> failWorking(result.error)
            }
        }
    }

    /** Consent dialog dismissed — no cost, nothing persisted. */
    fun dismissPreview() = updateReady { it.copy(preview = null) }

    /** Dismisses the terminal run report. */
    fun dismissRunReport() = updateReady { it.copy(run = null) }

    /** Partial-failure Resume: re-previews the remainder — the server re-plans what's left. */
    fun resumeAfterFailure() {
        dismissRunReport()
        organize()
    }

    fun clearError() = updateReady { it.copy(error = null) }

    /** Re-attaches the progress view to a run already in flight (e.g. after re-entering the screen). */
    private suspend fun reattachActiveRun() {
        when (val active = repository.resumeRun()) {
            is AppResult.Success -> active.data?.let { observeRun(it) }
            is AppResult.Failure -> logger.warn { "resumeRun failed: ${active.error}" }
        }
    }

    private fun observeRun(runId: OrganizeRunId) {
        viewModelScope.launch {
            updateReady { it.copy(isWorking = false, run = OrganizeRunProgress()) }
            repository.observeRun(runId).collect { event ->
                updateReady { ready -> ready.copy(run = ready.run.fold(event)) }
            }
        }
    }

    private fun failWorking(error: AppError) {
        errorBus.emit(error)
        logger.error { "organizer operation failed: $error" }
        updateReady { it.copy(isWorking = false, error = error) }
    }

    private fun updateSettings(transform: (OrganizeSettingsDto) -> OrganizeSettingsDto) =
        updateReady { it.copy(settings = transform(it.settings)) }

    private fun updateReady(transform: (OrganizeSettingsUiState.Ready) -> OrganizeSettingsUiState.Ready) {
        state.update { current ->
            if (current is OrganizeSettingsUiState.Ready) transform(current) else current
        }
    }
}

/** One-shot events emitted by [OrganizeSettingsViewModel] for the screen to render exactly once. */
sealed interface OrganizeSettingsEvent {
    /** The rules were persisted and nothing moved — the Save confirmation. */
    data object RulesSaved : OrganizeSettingsEvent

    /** The sweep found nothing to do — every book is already where the rules say, under the right name. */
    data object AlreadyOrganized : OrganizeSettingsEvent
}

/** Rolling progress of the in-flight (or just-finished) organize run. */
data class OrganizeRunProgress(
    val completed: Int = 0,
    val total: Int = 0,
    val movedBooks: Int = 0,
    val failedBooks: Int = 0,
    val terminal: Boolean = false,
) {
    /** True once the run finished with at least one failed book — surfaces the Resume action. */
    val hasFailures: Boolean get() = terminal && failedBooks > 0
}

/** Folds one server event into the rolling progress. */
private fun OrganizeRunProgress?.fold(event: OrganizeRunEvent): OrganizeRunProgress {
    val current = this ?: OrganizeRunProgress()
    return when (event) {
        is OrganizeRunEvent.Started -> {
            current.copy(total = event.totalBooks)
        }

        is OrganizeRunEvent.BookMoved -> {
            current.copy(completed = event.completed, total = event.totalBooks)
        }

        is OrganizeRunEvent.BookFailed -> {
            current.copy(completed = event.completed, total = event.totalBooks)
        }

        is OrganizeRunEvent.Completed -> {
            current.copy(
                movedBooks = event.movedBooks,
                failedBooks = event.failedBooks,
                terminal = true,
            )
        }
    }
}

/**
 * UI state for the admin file-organizer settings screen.
 *
 * Sealed hierarchy:
 * - [Loading] before the first settings load.
 * - [Ready] carries the edit-buffer [Ready.settings], the in-flight overlay [Ready.isWorking],
 *   the consent-dialog [Ready.preview] (non-null = dialog visible), the run progress/report
 *   [Ready.run] (non-null = progress UI visible), and a transient [Ready.error].
 * - [Error] terminal state when the initial load fails.
 */
sealed interface OrganizeSettingsUiState {
    /** Initial settings load in flight. */
    data object Loading : OrganizeSettingsUiState

    /** Settings loaded; carries the edit buffer and the preview/run overlays. */
    data class Ready(
        val settings: OrganizeSettingsDto = OrganizeSettingsDto(),
        val isWorking: Boolean = false,
        val preview: OrganizePreviewDto? = null,
        val run: OrganizeRunProgress? = null,
        val error: AppError? = null,
    ) : OrganizeSettingsUiState

    /** Terminal state when the initial settings load fails. */
    data class Error(
        val error: AppError,
    ) : OrganizeSettingsUiState
}
