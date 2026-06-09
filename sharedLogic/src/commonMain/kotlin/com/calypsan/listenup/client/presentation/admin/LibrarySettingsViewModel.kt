package com.calypsan.listenup.client.presentation.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.error.ErrorBus
import com.calypsan.listenup.client.data.remote.DirectoryEntryResponse
import com.calypsan.listenup.client.domain.model.Library
import com.calypsan.listenup.client.domain.repository.AdminRepository
import com.calypsan.listenup.client.presentation.error.userMessageFor
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * ViewModel for the library settings screen.
 *
 * Manages viewing and editing a single library's settings:
 * - Inbox quarantine setting
 */
class LibrarySettingsViewModel(
    private val libraryId: String,
    private val adminRepository: AdminRepository,
    private val errorBus: ErrorBus,
) : ViewModel() {
    val state: StateFlow<LibrarySettingsUiState>
        field = MutableStateFlow<LibrarySettingsUiState>(LibrarySettingsUiState.Loading)

    init {
        loadLibrary()
    }

    /**
     * Load the library details from the server.
     *
     * Drives the terminal Loading -> Ready | Error transition. Subsequent
     * refreshes after reaching Ready surface failures via the transient
     * `error` field on [LibrarySettingsUiState.Ready] rather than dropping
     * back to Error.
     */
    private fun loadLibrary() {
        viewModelScope.launch {
            when (val result = adminRepository.getLibrary(libraryId)) {
                is AppResult.Success -> {
                    val library = result.data
                    state.update { current ->
                        if (current is LibrarySettingsUiState.Ready) {
                            current.copy(
                                library = library,
                                inboxEnabled = library.inboxEnabled,
                                error = null,
                            )
                        } else {
                            LibrarySettingsUiState.Ready(
                                library = library,
                                inboxEnabled = library.inboxEnabled,
                            )
                        }
                    }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to load library: $libraryId — ${result.error}" }
                    val message = userMessageFor(result.error)
                    state.update { current ->
                        if (current is LibrarySettingsUiState.Ready) {
                            current.copy(error = message)
                        } else {
                            LibrarySettingsUiState.Error(message)
                        }
                    }
                }
            }
        }
    }

    /**
     * Enable or disable inbox quarantine for the library.
     *
     * Optimistically updates the UI state, then persists via the
     * `LibraryAdminService.setInboxEnabled` RPC. Reverts on failure.
     */
    fun setInboxEnabled(enabled: Boolean) {
        val ready = state.value as? LibrarySettingsUiState.Ready ?: return
        val previousValue = ready.inboxEnabled

        if (enabled == previousValue) return

        // Optimistic update
        updateReady { it.copy(inboxEnabled = enabled, isSaving = true) }

        viewModelScope.launch {
            when (val result = adminRepository.setInboxEnabled(libraryId = libraryId, enabled = enabled)) {
                is AppResult.Success -> {
                    val updatedLibrary = result.data
                    logger.info { "Set inbox enabled for library $libraryId to ${updatedLibrary.inboxEnabled}" }
                    updateReady {
                        it.copy(
                            isSaving = false,
                            library = updatedLibrary,
                            inboxEnabled = updatedLibrary.inboxEnabled,
                        )
                    }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to set inbox enabled for library: $libraryId — ${result.error}" }
                    // Revert to previous value
                    updateReady {
                        it.copy(
                            isSaving = false,
                            inboxEnabled = previousValue,
                            error = userMessageFor(result.error),
                        )
                    }
                }
            }
        }
    }

    /**
     * Remove a folder from the library by its folder id.
     */
    fun removeFolder(folderId: String) {
        if (state.value !is LibrarySettingsUiState.Ready) return
        viewModelScope.launch {
            updateReady { it.copy(isSaving = true) }

            when (val result = adminRepository.removeFolder(libraryId, folderId)) {
                is AppResult.Success -> {
                    val updatedLibrary = result.data
                    logger.info { "Removed folder $folderId from library $libraryId" }
                    updateReady {
                        it.copy(
                            isSaving = false,
                            library = updatedLibrary,
                        )
                    }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to remove folder from library: $libraryId — ${result.error}" }
                    updateReady {
                        it.copy(
                            isSaving = false,
                            error = userMessageFor(result.error),
                        )
                    }
                }
            }
        }
    }

    /**
     * Add a scan path to the library.
     */
    fun addScanPath(path: String) {
        if (state.value !is LibrarySettingsUiState.Ready) return
        viewModelScope.launch {
            updateReady { it.copy(isSaving = true, showFolderBrowser = false) }

            when (val result = adminRepository.addScanPath(libraryId, path)) {
                is AppResult.Success -> {
                    val updatedLibrary = result.data
                    logger.info { "Added scan path to library $libraryId: $path" }
                    updateReady {
                        it.copy(
                            isSaving = false,
                            library = updatedLibrary,
                        )
                    }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to add scan path to library: $libraryId — ${result.error}" }
                    updateReady {
                        it.copy(
                            isSaving = false,
                            error = userMessageFor(result.error),
                        )
                    }
                }
            }
        }
    }

    /**
     * Trigger a manual library rescan.
     */
    fun triggerScan() {
        if (state.value !is LibrarySettingsUiState.Ready) return
        viewModelScope.launch {
            updateReady { it.copy(isScanning = true) }

            when (val result = adminRepository.triggerScan(libraryId)) {
                is AppResult.Success -> {
                    logger.info { "Triggered scan for library $libraryId" }
                    updateReady { it.copy(isScanning = false) }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to trigger scan for library: $libraryId — ${result.error}" }
                    updateReady {
                        it.copy(
                            isScanning = false,
                            error = userMessageFor(result.error),
                        )
                    }
                }
            }
        }
    }

    /**
     * Show or hide the folder browser for adding paths.
     */
    fun setShowFolderBrowser(show: Boolean) {
        if (state.value !is LibrarySettingsUiState.Ready) return
        updateReady {
            it.copy(
                showFolderBrowser = show,
                browserPath = "/",
                browserEntries = emptyList(),
                browserParent = null,
            )
        }
        if (show) {
            loadBrowserDirectory("/")
        }
    }

    /**
     * Load directory contents in the folder browser.
     */
    fun loadBrowserDirectory(path: String) {
        if (state.value !is LibrarySettingsUiState.Ready) return
        viewModelScope.launch {
            updateReady { it.copy(isBrowserLoading = true) }

            when (val result = adminRepository.browseFilesystem(path)) {
                is AppResult.Success -> {
                    val response = result.data
                    updateReady {
                        it.copy(
                            isBrowserLoading = false,
                            browserPath = response.path,
                            browserParent = response.parent,
                            browserEntries = response.entries,
                            browserIsRoot = response.isRoot,
                        )
                    }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to browse directory: $path — ${result.error}" }
                    updateReady {
                        it.copy(
                            isBrowserLoading = false,
                            error = userMessageFor(result.error),
                        )
                    }
                }
            }
        }
    }

    /**
     * Navigate up in the folder browser.
     */
    fun browserNavigateUp() {
        val parent = (state.value as? LibrarySettingsUiState.Ready)?.browserParent
        if (parent != null) {
            loadBrowserDirectory(parent)
        }
    }

    /**
     * Clear the error state.
     */
    fun clearError() {
        updateReady { it.copy(error = null) }
    }

    /**
     * Apply [transform] to state only if it is currently
     * [LibrarySettingsUiState.Ready]. No-ops when state is
     * [LibrarySettingsUiState.Loading] or [LibrarySettingsUiState.Error].
     */
    private fun updateReady(transform: (LibrarySettingsUiState.Ready) -> LibrarySettingsUiState.Ready) {
        state.update { current ->
            if (current is LibrarySettingsUiState.Ready) transform(current) else current
        }
    }
}

/**
 * UI state for the library settings screen.
 *
 * Sealed hierarchy:
 * - [Loading] before the first `adminRepository.getLibrary` response.
 * - [Ready] once the library has loaded; carries the canonical library,
 *   the edit-buffer field (`inboxEnabled`) that mirrors the
 *   server state after optimistic updates, action overlays
 *   (`isSaving`, `isScanning`, `isBrowserLoading`), the folder-browser
 *   overlay fields, and a transient `error` surfaced via snackbar.
 * - [Error] terminal state when the initial load (or a retry from [Error])
 *   fails. Refresh failures after reaching [Ready] surface via the
 *   transient `error` field on [Ready] instead.
 */
sealed interface LibrarySettingsUiState {
    data object Loading : LibrarySettingsUiState

    /**
     * Library has loaded; carries the canonical library, edit-buffer fields,
     * action overlays, the folder-browser overlay state, and a transient `error`.
     */
    data class Ready(
        val library: Library,
        val inboxEnabled: Boolean = false,
        val isSaving: Boolean = false,
        val isScanning: Boolean = false,
        val error: String? = null,
        // Folder browser state
        val showFolderBrowser: Boolean = false,
        val isBrowserLoading: Boolean = false,
        val browserPath: String = "/",
        val browserParent: String? = null,
        val browserEntries: List<DirectoryEntryResponse> = emptyList(),
        val browserIsRoot: Boolean = true,
    ) : LibrarySettingsUiState

    /** Terminal state when the initial library load fails. */
    data class Error(
        val message: String,
    ) : LibrarySettingsUiState
}
