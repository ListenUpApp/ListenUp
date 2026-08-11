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
 * The text fields are local-only until the user taps the Save FAB ([saveAll]);
 * the inbox quarantine toggle, being a switch, persists immediately on tap
 * ([setInboxEnabled]) and reverts if the server rejects it.
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
    private var savedPushNotificationsEnabled: Boolean = true

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
                    savedPushNotificationsEnabled = result.data.pushNotificationsEnabled
                    state.update { current ->
                        if (current is AdminSettingsUiState.Ready) {
                            current.copy(
                                serverName = result.data.serverName,
                                remoteUrl = result.data.remoteUrl ?: "",
                                inboxEnabled = result.data.inboxEnabled,
                                pushNotificationsEnabled = result.data.pushNotificationsEnabled,
                                error = null,
                            )
                        } else {
                            AdminSettingsUiState.Ready(
                                serverName = result.data.serverName,
                                remoteUrl = result.data.remoteUrl ?: "",
                                inboxEnabled = result.data.inboxEnabled,
                                pushNotificationsEnabled = result.data.pushNotificationsEnabled,
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
     * Toggle the server-wide inbox quarantine gate. Unlike the batched text fields, a switch applies
     * on tap: this optimistically reflects the flip, persists it immediately, and reverts to the last
     * server-confirmed value if the save fails.
     */
    fun setInboxEnabled(enabled: Boolean) {
        // Optimistically reflect the flip so the switch tracks the tap.
        updateReady { it.copy(inboxEnabled = enabled).withDirty() }
        viewModelScope.launch {
            when (val result = updateServerSettingsUseCase.updateInboxEnabled(enabled)) {
                is AppResult.Success -> {
                    savedInboxEnabled = enabled
                    logger.info { "Inbox setting saved: $enabled" }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to save inbox setting: ${result.error}" }
                    // Revert the optimistic flip to the last server-confirmed value.
                    updateReady { it.copy(inboxEnabled = savedInboxEnabled, error = result.error).withDirty() }
                }
            }
        }
    }

    /**
     * Toggle server-wide push notifications. Like [setInboxEnabled], a switch applies on tap:
     * this optimistically reflects the flip, persists it immediately, and reverts to the last
     * server-confirmed value if the save fails.
     */
    fun setPushNotificationsEnabled(enabled: Boolean) {
        // Optimistically reflect the flip so the switch tracks the tap.
        updateReady { it.copy(pushNotificationsEnabled = enabled).withDirty() }
        viewModelScope.launch {
            when (val result = updateServerSettingsUseCase.updatePushNotificationsEnabled(enabled)) {
                is AppResult.Success -> {
                    savedPushNotificationsEnabled = enabled
                    logger.info { "Push notifications setting saved: $enabled" }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to save push notifications setting: ${result.error}" }
                    // Revert the optimistic flip to the last server-confirmed value.
                    updateReady {
                        it
                            .copy(
                                pushNotificationsEnabled = savedPushNotificationsEnabled,
                                error = result.error,
                            ).withDirty()
                    }
                }
            }
        }
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

            // Note: the inbox toggle is NOT saved here — [setInboxEnabled] persists it immediately on tap.

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
                    remoteUrl != savedRemoteUrl,
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
        val pushNotificationsEnabled: Boolean = true,
        val isDirty: Boolean = false,
        val isSaving: Boolean = false,
        val error: AppError? = null,
    ) : AdminSettingsUiState

    /** Terminal state when the initial settings load fails. */
    data class Error(
        val error: AppError,
    ) : AdminSettingsUiState
}
