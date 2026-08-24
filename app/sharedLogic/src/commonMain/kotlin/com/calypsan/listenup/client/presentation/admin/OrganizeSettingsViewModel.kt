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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * ViewModel for the admin file-organizer settings screen (#850).
 *
 * Flow: settings load into an edit buffer → the admin picks a schema → **Save** requests a
 * server-side plan preview ([OrganizeSettingsUiState.Ready.preview] renders the consent dialog:
 * scope counts + first-N before→after rows) → **confirm = saveAndExecute** (persists AND runs
 * immediately) → run progress streams into [OrganizeSettingsUiState.Ready.run] to a terminal
 * report. Saving with the toggle off persists without a dialog or a run (disable = stop).
 * A partial failure's Resume re-fires the same save — the server re-plans the remainder.
 */
class OrganizeSettingsViewModel(
    private val repository: OrganizeRepository,
    private val errorBus: ErrorBus,
) : ViewModel() {
    val state: StateFlow<OrganizeSettingsUiState>
        field = MutableStateFlow<OrganizeSettingsUiState>(OrganizeSettingsUiState.Loading)

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

    /** Flips the enable toggle in the edit buffer (nothing persists until Save). */
    fun setEnabled(enabled: Boolean) = updateSettings { it.copy(enabled = enabled) }

    /** Picks the structure preset in the edit buffer. */
    fun setPreset(preset: OrganizePreset) = updateSettings { it.copy(preset = preset) }

    /** Picks the series-prefix style in the edit buffer. */
    fun setSeriesPrefix(prefix: OrganizeSeriesPrefix) = updateSettings { it.copy(seriesPrefix = prefix) }

    /** Picks the author-name form in the edit buffer. */
    fun setAuthorForm(form: OrganizeAuthorForm) = updateSettings { it.copy(authorForm = form) }

    /**
     * Save tapped. Enabled → fetch the plan preview and open the consent dialog; nothing
     * persists or moves yet. Disabled → persist immediately (always possible; no dialog, no run).
     */
    fun save() {
        val ready = state.value as? OrganizeSettingsUiState.Ready ?: return
        if (!ready.settings.enabled) {
            persistDisabled(ready.settings)
            return
        }
        viewModelScope.launch {
            updateReady { it.copy(isWorking = true, error = null) }
            when (val result = repository.preview(ready.settings)) {
                is AppResult.Success -> updateReady { it.copy(isWorking = false, preview = result.data) }
                is AppResult.Failure -> failWorking(result.error)
            }
        }
    }

    /** Consent dialog confirmed — persist the settings and run the reorganization now. */
    fun confirmSave() {
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

    /** Partial-failure Resume: re-fires the same save — the server re-plans the remainder. */
    fun resumeAfterFailure() {
        dismissRunReport()
        save()
    }

    fun clearError() = updateReady { it.copy(error = null) }

    private fun persistDisabled(settings: OrganizeSettingsDto) {
        viewModelScope.launch {
            updateReady { it.copy(isWorking = true, error = null) }
            when (val result = repository.saveAndExecute(settings)) {
                is AppResult.Success -> updateReady { it.copy(isWorking = false) }
                is AppResult.Failure -> failWorking(result.error)
            }
        }
    }

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
