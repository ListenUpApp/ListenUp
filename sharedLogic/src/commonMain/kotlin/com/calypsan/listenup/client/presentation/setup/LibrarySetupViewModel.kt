package com.calypsan.listenup.client.presentation.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.CreateLibraryRequest
import com.calypsan.listenup.api.dto.DirectoryEntry
import com.calypsan.listenup.api.dto.Library
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.LibraryAdminRpcFactory
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

private const val DEFAULT_LIBRARY_NAME = "My Library"

/**
 * One-shot navigation events emitted by [LibrarySetupViewModel].
 *
 * [LibraryCreated] fires after each successful library creation — the wizard
 * loops back to allow adding another. [Finished] fires when [LibrarySetupViewModel.finishOnboarding]
 * is called, signalling the host to navigate away from the wizard.
 */
sealed interface LibrarySetupNavAction {
    /** A library was successfully created. The wizard may loop for another. */
    data class LibraryCreated(
        val library: Library,
    ) : LibrarySetupNavAction

    /** The user tapped "Done" — all library setup is complete. */
    data object Finished : LibrarySetupNavAction
}

/**
 * ViewModel for the library setup wizard.
 *
 * Manages the initial library configuration flow, supporting creation of multiple
 * libraries in a single onboarding session:
 * - Checking if library setup is needed ([getSetupStatus])
 * - Browsing the server filesystem ([browseFilesystem])
 * - Selecting one or more folders for the library
 * - Creating the library with the chosen configuration
 * - Looping back for another library or finishing
 *
 * Replaces the legacy [com.calypsan.listenup.client.data.remote.SetupApiContract] surface
 * with [LibraryAdminRpcFactory] backed by the [com.calypsan.listenup.api.LibraryAdminService] RPC.
 */
class LibrarySetupViewModel(
    private val libraryAdminRpcFactory: LibraryAdminRpcFactory,
    private val errorBus: ErrorBus,
) : ViewModel() {
    val state: StateFlow<LibrarySetupUiState>
        field = MutableStateFlow(LibrarySetupUiState())

    private val _navActions = Channel<LibrarySetupNavAction>(Channel.BUFFERED)

    /** One-shot navigation events. Collect via [Flow.collect] in a [LaunchedEffect]. */
    val navActions: Flow<LibrarySetupNavAction> = _navActions.receiveAsFlow()

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

            when (val result = libraryAdminRpcFactory.get().getSetupStatus()) {
                is AppResult.Success -> {
                    val status = result.data
                    logger.info { "Setup status: needsSetup=${status.needsSetup}, libraryCount=${status.libraryCount}" }
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
                    logger.error { "Failed to check setup status: ${result.error.message}" }
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
     *
     * Sets [LibrarySetupUiState.currentPath] to [path], derives [LibrarySetupUiState.parentPath]
     * client-side (parent segment of [path]), and sets [LibrarySetupUiState.isRoot] when
     * [path] is "/".
     *
     * @param path The directory path to load
     */
    fun loadDirectory(path: String) {
        viewModelScope.launch {
            state.update { it.copy(isLoadingDirectories = true, error = null) }

            when (val result = libraryAdminRpcFactory.get().browseFilesystem(path)) {
                is AppResult.Success -> {
                    val entries = result.data
                    val isRoot = path == "/"
                    val parentPath =
                        if (isRoot) {
                            null
                        } else {
                            val lastSlash = path.lastIndexOf('/')
                            if (lastSlash <= 0) "/" else path.substring(0, lastSlash)
                        }
                    logger.debug { "Loaded directory: $path, entries=${entries.size}" }
                    state.update {
                        it.copy(
                            isLoadingDirectories = false,
                            currentPath = path,
                            parentPath = parentPath,
                            directories = entries,
                            isRoot = isRoot,
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
     * Clear the error state.
     */
    fun clearError() {
        state.update { it.copy(error = null) }
    }

    /**
     * Create the library with the current configuration.
     *
     * On success, appends the created library to [LibrarySetupUiState.createdLibraries],
     * resets folder selection and library name for the next entry, and emits a
     * [LibrarySetupNavAction.LibraryCreated] nav action. Does NOT flip
     * [LibrarySetupUiState.setupComplete] — only [finishOnboarding] does that.
     *
     * Requires at least one selected path and a non-blank library name.
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
                CreateLibraryRequest(
                    name = currentState.libraryName.trim(),
                    folderPaths = currentState.selectedPaths.toList(),
                )

            when (val result = libraryAdminRpcFactory.get().createLibrary(request)) {
                is AppResult.Success -> {
                    val library = result.data
                    logger.info { "Library created: id=${library.id}, name=${library.name}" }
                    state.update {
                        it.copy(
                            isCreatingLibrary = false,
                            createdLibraries = it.createdLibraries + library,
                            selectedPaths = emptySet(),
                            libraryName = DEFAULT_LIBRARY_NAME,
                            error = null,
                        )
                    }
                    _navActions.trySend(LibrarySetupNavAction.LibraryCreated(library))
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
     * Signal that onboarding is complete — the user is done adding libraries.
     *
     * Flips [LibrarySetupUiState.setupComplete] and emits [LibrarySetupNavAction.Finished]
     * so the host screen can navigate away. Only call this after at least one library
     * has been created.
     */
    fun finishOnboarding() {
        state.update { it.copy(setupComplete = true) }
        _navActions.trySend(LibrarySetupNavAction.Finished)
    }
}

/**
 * UI state for the library setup wizard.
 */
data class LibrarySetupUiState(
    // Status check
    val isCheckingStatus: Boolean = true,
    val needsSetup: Boolean = false,
    // Folder browser
    val currentPath: String = "/",
    val parentPath: String? = null,
    val directories: List<DirectoryEntry> = emptyList(),
    val isLoadingDirectories: Boolean = false,
    val isRoot: Boolean = true,
    // Selection
    val selectedPaths: Set<String> = emptySet(),
    // Setup
    val libraryName: String = DEFAULT_LIBRARY_NAME,
    val isCreatingLibrary: Boolean = false,
    // Results
    val createdLibraries: List<Library> = emptyList(),
    val setupComplete: Boolean = false,
    val error: String? = null,
)
