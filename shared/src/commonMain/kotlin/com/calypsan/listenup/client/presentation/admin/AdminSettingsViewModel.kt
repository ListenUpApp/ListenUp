package com.calypsan.listenup.client.presentation.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.core.error.ErrorBus
import com.calypsan.listenup.client.core.error.ErrorMapper
import com.calypsan.listenup.client.domain.repository.AdminRepository
import com.calypsan.listenup.client.domain.repository.InstanceRepository
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
 * Manages server-wide settings like the server name and inbox workflow toggle.
 * All changes are local-only until the user taps the Save FAB, which persists
 * everything at once via [saveAll].
 *
 * When inbox is disabled with pending books, they are automatically
 * released with their staged collections.
 */
class AdminSettingsViewModel(
    private val loadServerSettingsUseCase: LoadServerSettingsUseCase,
    private val updateServerSettingsUseCase: UpdateServerSettingsUseCase,
    private val instanceRepository: InstanceRepository,
    private val adminRepository: AdminRepository,
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
                is Success -> {
                    savedServerName = result.data.serverName
                    savedInboxEnabled = result.data.inboxEnabled
                    state.update { current ->
                        if (current is AdminSettingsUiState.Ready) {
                            current.copy(
                                serverName = result.data.serverName,
                                inboxEnabled = result.data.inboxEnabled,
                                inboxCount = result.data.inboxCount,
                                error = null,
                            )
                        } else {
                            AdminSettingsUiState.Ready(
                                serverName = result.data.serverName,
                                inboxEnabled = result.data.inboxEnabled,
                                inboxCount = result.data.inboxCount,
                            )
                        }
                    }
                    // Also load remote URL from instance
                    loadRemoteUrl()
                }

                is Failure -> {
                    logger.error { "Failed to load server settings: ${result.message}" }
                    state.update { current ->
                        if (current is AdminSettingsUiState.Ready) {
                            current.copy(error = result.message)
                        } else {
                            AdminSettingsUiState.Error(result.message)
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
     * Toggle the inbox workflow on/off (local only).
     *
     * When disabling with pending books, shows confirmation first.
     */
    fun setInboxEnabled(enabled: Boolean) {
        val ready = state.value as? AdminSettingsUiState.Ready ?: return

        // If disabling and there are books in inbox, show confirmation
        if (!enabled && ready.inboxCount > 0) {
            updateReady { it.copy(showDisableConfirmation = true) }
            return
        }

        updateReady { it.copy(inboxEnabled = enabled).withDirty() }
    }

    /**
     * Confirm disabling inbox workflow (local only).
     * Called after user confirms they want to release all pending books.
     */
    fun confirmDisableInbox() {
        updateReady {
            it
                .copy(
                    showDisableConfirmation = false,
                    inboxEnabled = false,
                ).withDirty()
        }
    }

    /**
     * Cancel disabling inbox workflow.
     */
    fun cancelDisableInbox() {
        updateReady { it.copy(showDisableConfirmation = false) }
    }

    private fun loadRemoteUrl() {
        viewModelScope.launch {
            when (val result = instanceRepository.getInstance(forceRefresh = true)) {
                is Success -> {
                    val url = result.data.remoteUrl ?: ""
                    savedRemoteUrl = url
                    updateReady { it.copy(remoteUrl = url).withDirty() }
                }

                is Failure -> {
                    // Non-fatal, just leave remote URL empty
                }
            }
        }
    }

    /**
     * Update the remote access URL (local only).
     */
    fun setRemoteUrl(url: String) {
        updateReady { it.copy(remoteUrl = url).withDirty() }
    }

    /**
     * Persist all current settings to the server.
     */
    @Suppress("ThrowsCount") // Boundary function: rethrow CancellationException + throw per-field failures
    fun saveAll() {
        val ready = state.value as? AdminSettingsUiState.Ready ?: return

        viewModelScope.launch {
            updateReady { it.copy(isSaving = true, error = null) }

            try {
                // Save server name if changed
                if (ready.serverName != savedServerName) {
                    when (val result = updateServerSettingsUseCase.updateServerName(ready.serverName)) {
                        is Success -> {
                            savedServerName = result.data.serverName
                            logger.info { "Server name saved: ${result.data.serverName}" }
                        }

                        is Failure -> {
                            throw IllegalStateException(result.message)
                        }
                    }
                }

                // Save remote URL if changed
                if (ready.remoteUrl != savedRemoteUrl) {
                    adminRepository.updateInstanceRemoteUrl(ready.remoteUrl)
                    savedRemoteUrl = ready.remoteUrl
                    logger.info { "Remote URL saved: ${ready.remoteUrl}" }
                }

                // Save inbox enabled if changed
                if (ready.inboxEnabled != savedInboxEnabled) {
                    when (val result = updateServerSettingsUseCase(ready.inboxEnabled)) {
                        is Success -> {
                            savedInboxEnabled = result.data.inboxEnabled
                            val refreshedCount = result.data.inboxCount
                            updateReady { it.copy(inboxCount = refreshedCount) }
                            logger.info { "Inbox workflow ${if (ready.inboxEnabled) "enabled" else "disabled"}" }
                        }

                        is Failure -> {
                            throw IllegalStateException(result.message)
                        }
                    }
                }

                updateReady { it.copy(isSaving = false).withDirty() }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                errorBus.emit(ErrorMapper.map(e))
                logger.error(e) { "Failed to save settings" }
                updateReady {
                    it
                        .copy(
                            isSaving = false,
                            error = "Failed to save settings: ${e.message}",
                        ).withDirty()
                }
            }
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
 *   (`serverName`, `remoteUrl`, `inboxEnabled`) that the user mutates before
 *   tapping Save, plus the server-owned `inboxCount`, the `isDirty` flag
 *   (recomputed on every edit-buffer mutation), the `isSaving` and
 *   `showDisableConfirmation` overlays, and a transient `error` surfaced via
 *   snackbar.
 * - [Error] terminal state when the initial load fails. Refresh failures
 *   after reaching [Ready] surface via the transient `error` field on
 *   [Ready] instead.
 */
sealed interface AdminSettingsUiState {
    data object Loading : AdminSettingsUiState

    /**
     * Settings have loaded; carries edit-buffer fields, the server-owned `inboxCount`,
     * `isDirty`, action overlays, and a transient `error`.
     */
    data class Ready(
        val serverName: String = "",
        val remoteUrl: String = "",
        val inboxEnabled: Boolean = false,
        val inboxCount: Int = 0,
        val showDisableConfirmation: Boolean = false,
        val isDirty: Boolean = false,
        val isSaving: Boolean = false,
        val error: String? = null,
    ) : AdminSettingsUiState {
        val hasPendingBooks: Boolean get() = inboxCount > 0
    }

    /** Terminal state when the initial settings load fails. */
    data class Error(
        val message: String,
    ) : AdminSettingsUiState
}
