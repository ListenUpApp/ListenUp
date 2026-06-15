package com.calypsan.listenup.client.presentation.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.error.ErrorBus
import com.calypsan.listenup.client.domain.usecase.admin.LoadServerSettingsUseCase
import com.calypsan.listenup.client.domain.usecase.admin.UpdateServerSettingsUseCase
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * ViewModel for admin server settings screen.
 *
 * Manages server-identity settings: display name and optional public remote URL.
 * All changes are local-only until the user taps the Save FAB, which persists
 * everything at once via [saveAll].
 */
class AdminSettingsViewModel(
    private val loadServerSettingsUseCase: LoadServerSettingsUseCase,
    private val updateServerSettingsUseCase: UpdateServerSettingsUseCase,
    private val errorBus: ErrorBus,
) : ViewModel() {
    val state: StateFlow<AdminSettingsUiState>
        field = MutableStateFlow<AdminSettingsUiState>(AdminSettingsUiState.Loading)

    /** Baseline values from the server, used to compute dirty state. */
    private var savedServerName: String = ""
    private var savedRemoteUrl: String = ""
    private var savedInboxEnabled: Boolean = false

    init {
        loadSettings()
    }

    fun loadSettings() {
        viewModelScope.launch {
            when (val result = loadServerSettingsUseCase()) {
                is AppResult.Success -> {
                    savedServerName = result.data.serverName
                    savedRemoteUrl = result.data.remoteUrl ?: ""
                    savedInboxEnabled = result.data.inboxEnabled
                    state.update { current ->
                        if (current is AdminSettingsUiState.Ready) {
                            current.copy(
                                serverName = result.data.serverName,
                                remoteUrl = result.data.remoteUrl ?: "",
                                inboxEnabled = result.data.inboxEnabled,
                                error = null,
                            )
                        } else {
                            AdminSettingsUiState.Ready(
                                serverName = result.data.serverName,
                                remoteUrl = result.data.remoteUrl ?: "",
                                inboxEnabled = result.data.inboxEnabled,
                            )
                        }
                    }
                }

                is AppResult.Failure -> {
                    logger.error { "Failed to load server settings: ${result.error}" }
                    state.update { current ->
                        if (current is AdminSettingsUiState.Ready) {
                            current.copy(error = result.error)
                        } else {
                            AdminSettingsUiState.Error(result.error)
                        }
                    }
                }
            }
        }
    }

    /**
     * Update the server display name (local only).
     */
    fun setServerName(name: String) {
        updateReady { it.copy(serverName = name).withDirty() }
    }

    /**
     * Update the remote access URL (local only).
     */
    fun setRemoteUrl(url: String) {
        updateReady { it.copy(remoteUrl = url).withDirty() }
    }

    /**
     * Toggle the server-wide inbox quarantine gate (local only).
     */
    fun setInboxEnabled(enabled: Boolean) {
        updateReady { it.copy(inboxEnabled = enabled).withDirty() }
    }

    /**
     * Persist all current settings to the server.
     *
     * Each changed field is saved independently. A failure in any one field
     * surfaces as a transient error on [AdminSettingsUiState.Ready] and aborts
     * the remaining saves — the error is also forwarded to the global error bus.
     */
    @Suppress("ReturnCount")
    fun saveAll() {
        val ready = state.value as? AdminSettingsUiState.Ready ?: return

        viewModelScope.launch {
            updateReady { it.copy(isSaving = true, error = null) }

            // Save server name if changed
            if (ready.serverName != savedServerName) {
                when (val result = updateServerSettingsUseCase.updateServerName(ready.serverName)) {
                    is AppResult.Success -> {
                        savedServerName = result.data.serverName
                        logger.info { "Server name saved: ${result.data.serverName}" }
                    }

                    is AppResult.Failure -> {
                        errorBus.emit(result.error)
                        logger.error { "Failed to save server name: ${result.error}" }
                        updateReady {
                            it
                                .copy(
                                    isSaving = false,
                                    error = result.error,
                                ).withDirty()
                        }
                        return@launch
                    }
                }
            }

            // Save remote URL if changed
            if (ready.remoteUrl != savedRemoteUrl) {
                when (val result = updateServerSettingsUseCase.updateRemoteUrl(ready.remoteUrl)) {
                    is AppResult.Success -> {
                        savedRemoteUrl = ready.remoteUrl
                        logger.info { "Remote URL saved: ${ready.remoteUrl}" }
                    }

                    is AppResult.Failure -> {
                        errorBus.emit(result.error)
                        logger.error { "Failed to save remote URL: ${result.error}" }
                        updateReady {
                            it
                                .copy(
                                    isSaving = false,
                                    error = result.error,
                                ).withDirty()
                        }
                        return@launch
                    }
                }
            }

            // Save inbox enabled if changed
            if (ready.inboxEnabled != savedInboxEnabled) {
                when (val result = updateServerSettingsUseCase.updateInboxEnabled(ready.inboxEnabled)) {
                    is AppResult.Success -> {
                        savedInboxEnabled = ready.inboxEnabled
                        logger.info { "Inbox setting saved: ${ready.inboxEnabled}" }
                    }

                    is AppResult.Failure -> {
                        errorBus.emit(result.error)
                        logger.error { "Failed to save inbox setting: ${result.error}" }
                        updateReady {
                            it
                                .copy(
                                    isSaving = false,
                                    error = result.error,
                                ).withDirty()
                        }
                        return@launch
                    }
                }
            }

            updateReady { it.copy(isSaving = false).withDirty() }
        }
    }

    fun clearError() {
        updateReady { it.copy(error = null) }
    }

    /**
     * Apply [transform] to state only if it is currently [AdminSettingsUiState.Ready].
     * No-ops when state is [AdminSettingsUiState.Loading] or [AdminSettingsUiState.Error].
     */
    private fun updateReady(transform: (AdminSettingsUiState.Ready) -> AdminSettingsUiState.Ready) {
        state.update { current ->
            if (current is AdminSettingsUiState.Ready) transform(current) else current
        }
    }

    /**
     * Recompute [AdminSettingsUiState.Ready.isDirty] by comparing the edit-buffer fields
     * against the saved baseline captured from the server.
     */
    private fun AdminSettingsUiState.Ready.withDirty(): AdminSettingsUiState.Ready =
        copy(
            isDirty =
                serverName != savedServerName ||
                    remoteUrl != savedRemoteUrl ||
                    inboxEnabled != savedInboxEnabled,
        )
}

/**
 * UI state for the admin server settings screen.
 *
 * Sealed hierarchy:
 * - [Loading] before the first `LoadServerSettingsUseCase` response.
 * - [Ready] once settings have loaded; carries the edit-buffer fields
 *   (`serverName`, `remoteUrl`) that the user mutates before tapping Save,
 *   plus the `isDirty` flag (recomputed on every edit-buffer mutation),
 *   the `isSaving` overlay, and a transient `error` surfaced via snackbar.
 * - [Error] terminal state when the initial load fails. Refresh failures
 *   after reaching [Ready] surface via the transient `error` field on
 *   [Ready] instead.
 */
sealed interface AdminSettingsUiState {
    data object Loading : AdminSettingsUiState

    /**
     * Settings have loaded; carries edit-buffer fields, `isDirty`, `isSaving`,
     * and a transient `error`.
     */
    data class Ready(
        val serverName: String = "",
        val remoteUrl: String = "",
        val inboxEnabled: Boolean = false,
        val isDirty: Boolean = false,
        val isSaving: Boolean = false,
        val error: AppError? = null,
    ) : AdminSettingsUiState

    /** Terminal state when the initial settings load fails. */
    data class Error(
        val error: AppError,
    ) : AdminSettingsUiState
}
