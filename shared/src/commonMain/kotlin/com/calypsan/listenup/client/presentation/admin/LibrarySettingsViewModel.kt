package com.calypsan.listenup.client.presentation.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.core.error.ErrorBus
import com.calypsan.listenup.client.core.error.ErrorMapper
import com.calypsan.listenup.client.data.remote.DirectoryEntryResponse
import com.calypsan.listenup.client.domain.model.AccessMode
import com.calypsan.listenup.client.domain.model.Library
import com.calypsan.listenup.client.domain.repository.AdminRepository
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
            try {
                val library = adminRepository.getLibrary(libraryId)
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
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                errorBus.emit(ErrorMapper.map(e))
                logger.error(e) { "Failed to load library: $libraryId" }
                val message = e.message ?: "Failed to load library"
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
            try {
                val updatedLibrary =
                    adminRepository.updateLibrary(
                        libraryId = libraryId,
                        accessMode = accessMode,
                    )
                logger.info { "Updated access mode for library $libraryId to ${accessMode.toApiString()}" }
                updateReady {
                    it.copy(
                        isSaving = false,
                        library = updatedLibrary,
                        accessMode = updatedLibrary.accessMode,
                    )
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                errorBus.emit(ErrorMapper.map(e))
                logger.error(e) { "Failed to update access mode for library: $libraryId" }
                // Revert to previous value
                updateReady {
                    it.copy(
                        isSaving = false,
                        accessMode = previousValue,
                        error = e.message ?: "Failed to update access mode",
                    )
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
            try {
                val updatedLibrary =
                    adminRepository.updateLibrary(
                        libraryId = libraryId,
                        skipInbox = newValue,
                    )
                logger.info { "Updated skip inbox for library $libraryId to $newValue" }
                updateReady {
                    it.copy(
                        isSaving = false,
                        library = updatedLibrary,
                        skipInbox = updatedLibrary.skipInbox,
                    )
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                errorBus.emit(ErrorMapper.map(e))
                logger.error(e) { "Failed to update skip inbox for library: $libraryId" }
                // Revert to previous value
                updateReady {
                    it.copy(
                        isSaving = false,
                        skipInbox = previousValue,
                        error = e.message ?: "Failed to update skip inbox",
                    )
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

            try {
                val updatedLibrary = adminRepository.removeScanPath(libraryId, path)
                logger.info { "Removed scan path from library $libraryId: $path" }
                updateReady {
                    it.copy(
                        isSaving = false,
                        library = updatedLibrary,
                    )
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                errorBus.emit(ErrorMapper.map(e))
                logger.error(e) { "Failed to remove scan path from library: $libraryId" }
                updateReady {
                    it.copy(
                        isSaving = false,
                        error = e.message ?: "Failed to remove scan path",
                    )
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

            try {
                val updatedLibrary = adminRepository.addScanPath(libraryId, path)
                logger.info { "Added scan path to library $libraryId: $path" }
                updateReady {
                    it.copy(
                        isSaving = false,
                        library = updatedLibrary,
                    )
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                errorBus.emit(ErrorMapper.map(e))
                logger.error(e) { "Failed to add scan path to library: $libraryId" }
                updateReady {
                    it.copy(
                        isSaving = false,
                        error = e.message ?: "Failed to add scan path",
                    )
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

            try {
                adminRepository.triggerScan(libraryId)
                logger.info { "Triggered scan for library $libraryId" }
                updateReady { it.copy(isScanning = false) }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                errorBus.emit(ErrorMapper.map(e))
                logger.error(e) { "Failed to trigger scan for library: $libraryId" }
                updateReady {
                    it.copy(
                        isScanning = false,
                        error = e.message ?: "Failed to trigger scan",
                    )
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

            try {
                val response = adminRepository.browseFilesystem(path)
                updateReady {
                    it.copy(
                        isBrowserLoading = false,
                        browserPath = response.path,
                        browserParent = response.parent,
                        browserEntries = response.entries,
                        browserIsRoot = response.isRoot,
                    )
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                errorBus.emit(ErrorMapper.map(e))
                logger.error(e) { "Failed to browse directory: $path" }
                updateReady {
                    it.copy(
                        isBrowserLoading = false,
                        error = e.message ?: "Failed to browse directory",
                    )
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
