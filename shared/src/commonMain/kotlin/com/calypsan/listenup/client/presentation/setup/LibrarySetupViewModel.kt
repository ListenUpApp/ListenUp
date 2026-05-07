package com.calypsan.listenup.client.presentation.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.core.error.ErrorBus
import com.calypsan.listenup.client.data.remote.DirectoryEntryResponse
import com.calypsan.listenup.client.data.remote.SetupApiContract
import com.calypsan.listenup.client.data.remote.SetupLibraryRequest
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * ViewModel for the library setup screen.
 *
 * Manages the initial library configuration flow:
 * - Checking if library setup is needed
 * - Browsing the server filesystem
 * - Selecting a folder for the library
 * - Creating the library with the chosen configuration
 */
class LibrarySetupViewModel(
    private val setupApi: SetupApiContract,
    private val errorBus: ErrorBus,
) : ViewModel() {
    val state: StateFlow<LibrarySetupUiState>
        field = MutableStateFlow(LibrarySetupUiState())

    init {
        checkLibraryStatus()
    }

    /**
     * Check if library setup is needed.
     * Called on init to determine if the setup flow should be shown.
     */
    fun checkLibraryStatus() {
        viewModelScope.launch {
            state.update { it.copy(isCheckingStatus = true, error = null) }

            when (val result = setupApi.getLibraryStatus()) {
                is AppResult.Success -> {
                    val status = result.data
                    logger.info { "Library status: exists=${status.exists}, needsSetup=${status.needsSetup}" }
                    state.update {
                        it.copy(
                            isCheckingStatus = false,
                            needsSetup = status.needsSetup,
                        )
                    }
                    if (status.needsSetup) {
                        loadDirectory("/")
                    }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to check library status: ${result.error.message}" }
                    state.update {
                        it.copy(
                            isCheckingStatus = false,
                            error = result.error.message,
                        )
                    }
                }
            }
        }
    }

    /**
     * Load the contents of a directory from the server filesystem.
     * @param path The directory path to load
     */
    fun loadDirectory(path: String) {
        viewModelScope.launch {
            state.update { it.copy(isLoadingDirectories = true, error = null) }

            when (val result = setupApi.browseFilesystem(path)) {
                is AppResult.Success -> {
                    val response = result.data
                    logger.debug { "Loaded directory: ${response.path}, entries=${response.entries.size}" }
                    state.update {
                        it.copy(
                            isLoadingDirectories = false,
                            currentPath = response.path,
                            parentPath = response.parent,
                            directories = response.entries,
                            isRoot = response.isRoot,
                        )
                    }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to load directory: $path — ${result.error.message}" }
                    state.update {
                        it.copy(
                            isLoadingDirectories = false,
                            error = result.error.message,
                        )
                    }
                }
            }
        }
    }

    /**
     * Navigate to the parent directory.
     */
    fun navigateUp() {
        val parent = state.value.parentPath
        if (parent != null) {
            loadDirectory(parent)
        }
    }

    /**
     * Toggle a folder path selection.
     * @param path The path to toggle
     */
    fun togglePath(path: String) {
        state.update { current ->
            val newPaths = current.selectedPaths.toMutableSet()
            if (path in newPaths) {
                newPaths.remove(path)
            } else {
                newPaths.add(path)
            }
            current.copy(selectedPaths = newPaths)
        }
    }

    /**
     * Select a folder path for the library (adds to selection).
     * @param path The path to select
     */
    fun selectPath(path: String) {
        state.update { current ->
            current.copy(selectedPaths = current.selectedPaths + path)
        }
    }

    /**
     * Clear all selected paths.
     */
    fun clearSelection() {
        state.update { it.copy(selectedPaths = emptySet()) }
    }

    /**
     * Update the library name.
     * @param name The new library name
     */
    fun setLibraryName(name: String) {
        state.update { it.copy(libraryName = name) }
    }

    /**
     * Update the skip inbox setting.
     * @param skip Whether to skip the inbox for new books
     */
    fun setSkipInbox(skip: Boolean) {
        state.update { it.copy(skipInbox = skip) }
    }

    /**
     * Create the library with the current configuration.
     * Requires a selected path.
     */
    fun createLibrary() {
        val currentState = state.value
        if (currentState.selectedPaths.isEmpty()) {
            state.update { it.copy(error = "Please select at least one folder for your library") }
            return
        }

        if (currentState.libraryName.isBlank()) {
            state.update { it.copy(error = "Please enter a name for your library") }
            return
        }

        viewModelScope.launch {
            state.update { it.copy(isCreatingLibrary = true, error = null) }

            val request =
                SetupLibraryRequest(
                    name = currentState.libraryName.trim(),
                    scanPaths = currentState.selectedPaths.toList(),
                    skipInbox = currentState.skipInbox,
                )

            when (val result = setupApi.setupLibrary(request)) {
                is AppResult.Success -> {
                    val response = result.data
                    logger.info { "Library created: id=${response.id}, name=${response.name}" }
                    state.update {
                        it.copy(
                            isCreatingLibrary = false,
                            setupComplete = true,
                            needsSetup = false,
                        )
                    }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to create library: ${result.error.message}" }
                    state.update {
                        it.copy(
                            isCreatingLibrary = false,
                            error = result.error.message,
                        )
                    }
                }
            }
        }
    }

    /**
     * Clear the error state.
     */
    fun clearError() {
        state.update { it.copy(error = null) }
    }
}

/**
 * UI state for the library setup screen.
 */
data class LibrarySetupUiState(
    // Status check
    val isCheckingStatus: Boolean = true,
    val needsSetup: Boolean = false,
    // Folder browser
    val currentPath: String = "/",
    val parentPath: String? = null,
    val directories: List<DirectoryEntryResponse> = emptyList(),
    val isLoadingDirectories: Boolean = false,
    val isRoot: Boolean = true,
    // Selection
    val selectedPaths: Set<String> = emptySet(),
    // Setup
    val libraryName: String = "My Library",
    val skipInbox: Boolean = false,
    val isCreatingLibrary: Boolean = false,
    // Results
    val setupComplete: Boolean = false,
    val error: String? = null,
)
