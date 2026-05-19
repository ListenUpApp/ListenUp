package com.calypsan.listenup.client.presentation.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.core.error.ErrorBus
import com.calypsan.listenup.client.data.remote.DirectoryEntryResponse
import com.calypsan.listenup.client.domain.model.AccessMode
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
 * - Access mode (open vs restricted)
 * - Skip inbox setting
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
                                accessMode = library.accessMode,
                                skipInbox = library.skipInbox,
                                error = null,
                            )
                        } else {
                            LibrarySettingsUiState.Ready(
                                library = library,
                                accessMode = library.accessMode,
                                skipInbox = library.skipInbox,
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
     * Set the access mode for the library.
     *
     * Optimistically updates the UI state, then saves to server.
     * Reverts on failure.
     */
    fun setAccessMode(accessMode: AccessMode) {
        val ready = state.value as? LibrarySettingsUiState.Ready ?: return
        val previousValue = ready.accessMode

        if (accessMode == previousValue) return

        // Optimistic update
        updateReady { it.copy(accessMode = accessMode, isSaving = true) }

        viewModelScope.launch {
            when (val result = adminRepository.updateLibrary(libraryId = libraryId, accessMode = accessMode)) {
                is AppResult.Success -> {
                    val updatedLibrary = result.data
                    logger.info { "Updated access mode for library $libraryId to ${accessMode.toApiString()}" }
                    updateReady {
                        it.copy(
                            isSaving = false,
                            library = updatedLibrary,
                            accessMode = updatedLibrary.accessMode,
                        )
                    }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to update access mode for library: $libraryId — ${result.error}" }
                    // Revert to previous value
                    updateReady {
                        it.copy(
                            isSaving = false,
                            accessMode = previousValue,
                            error = userMessageFor(result.error),
                        )
                    }
                }
            }
        }
    }

    /**
     * Toggle the skip inbox setting.
     *
     * Optimistically updates the UI state, then saves to server.
     * Reverts on failure.
     */
    fun toggleSkipInbox() {
        val ready = state.value as? LibrarySettingsUiState.Ready ?: return
        val previousValue = ready.skipInbox
        val newValue = !previousValue

        // Optimistic update
        updateReady { it.copy(skipInbox = newValue, isSaving = true) }

        viewModelScope.launch {
            when (val result = adminRepository.updateLibrary(libraryId = libraryId, skipInbox = newValue)) {
                is AppResult.Success -> {
                    val updatedLibrary = result.data
                    logger.info { "Updated skip inbox for library $libraryId to $newValue" }
                    updateReady {
                        it.copy(
                            isSaving = false,
                            library = updatedLibrary,
                            skipInbox = updatedLibrary.skipInbox,
                        )
                    }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to update skip inbox for library: $libraryId — ${result.error}" }
                    // Revert to previous value
                    updateReady {
                        it.copy(
                            isSaving = false,
                            skipInbox = previousValue,
                            error = userMessageFor(result.error),
                        )
                    }
                }
            }
        }
    }

    /**
     * Remove a scan path from the library.
     */
    fun removeScanPath(path: String) {
        if (state.value !is LibrarySettingsUiState.Ready) return
        viewModelScope.launch {
            updateReady { it.copy(isSaving = true) }

            when (val result = adminRepository.removeScanPath(libraryId, path)) {
                is AppResult.Success -> {
                    val updatedLibrary = result.data
                    logger.info { "Removed scan path from library $libraryId: $path" }
                    updateReady {
                        it.copy(
                            isSaving = false,
                            library = updatedLibrary,
                        )
                    }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to remove scan path from library: $libraryId — ${result.error}" }
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
 *   the edit-buffer fields (`accessMode`, `skipInbox`) that mirror the
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
        val accessMode: AccessMode,
        val skipInbox: Boolean,
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
