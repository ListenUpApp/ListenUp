package com.calypsan.listenup.client.presentation.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.DirectoryEntry
import com.calypsan.listenup.api.LibraryAdminService
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * One-shot navigation events emitted by [LibrarySetupViewModel].
 *
 * [Finished] fires when [LibrarySetupViewModel.completeSetup] succeeds, signalling
 * the host to navigate away from the wizard.
 */
sealed interface LibrarySetupNavAction {
    /** Onboarding complete — host should navigate away from the wizard. */
    data object Finished : LibrarySetupNavAction
}

/**
 * ViewModel for the library setup wizard.
 *
 * Manages the initial library configuration flow for the single-library model:
 * - Checking if library setup is needed ([checkLibraryStatus])
 * - Browsing the server filesystem ([browseFilesystem])
 * - Selecting one or more folders to add to THE library
 * - Registering each folder via [addFolder] and kicking off the initial scan
 *
 * There is one library; the wizard selects which folders it watches.
 */
class LibrarySetupViewModel internal constructor(
    private val libraryAdminChannel: RpcChannel<LibraryAdminService>,
    private val errorBus: ErrorBus,
    private val appScope: CoroutineScope,
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

            when (val result = libraryAdminChannel.call(idempotent = true) { it.getSetupStatus() }) {
                is AppResult.Success -> {
                    val status = result.data
                    logger.info { "Setup status: needsSetup=${status.needsSetup}" }
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

            when (val result = libraryAdminChannel.call(idempotent = true) { it.browseFilesystem(path) }) {
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
     * Clear the error state.
     */
    fun clearError() {
        state.update { it.copy(error = null) }
    }

    /**
     * Finish onboarding: register each selected folder under THE library, kick off the
     * initial scan on [appScope] (it outlives the wizard), then emit [LibrarySetupNavAction.Finished].
     * Multi-folder selection is preserved; there is no second library.
     */
    fun completeSetup() {
        val current = state.value
        if (current.selectedPaths.isEmpty()) {
            state.update { it.copy(error = "Please select at least one folder for your library") }
            return
        }
        viewModelScope.launch {
            state.update { it.copy(isCreatingLibrary = true, error = null) }
            for (path in current.selectedPaths) {
                when (val result = libraryAdminChannel.call { it.addFolder(path) }) {
                    is AppResult.Failure -> {
                        errorBus.emit(result.error)
                        state.update { it.copy(isCreatingLibrary = false, error = result.error.message) }
                        return@launch
                    }

                    is AppResult.Success -> {
                        // folder registered; continue to next path
                    }
                }
            }
            state.update { it.copy(isCreatingLibrary = false) }
            triggerInitialScan()
            _navActions.trySend(LibrarySetupNavAction.Finished)
        }
    }

    /**
     * Kick the first scan off on [appScope] so it outlives the wizard teardown.
     *
     * Runs on [appScope], not [viewModelScope]: the server's `scanLibrary` suspends
     * for the full scan, which easily outlives this wizard (it's torn down the moment
     * onboarding finishes and the host navigates to the Shell). Tying it to the wizard's
     * scope would cancel the scan mid-flight. Progress streams to the Shell over SSE;
     * a failure surfaces on the global error bus.
     */
    private fun triggerInitialScan() {
        appScope.launch {
            when (val result = libraryAdminChannel.call { it.scanLibrary() }) {
                is AppResult.Success -> {
                    logger.info { "Initial scan started" }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Initial scan failed: ${result.error.message}" }
                }
            }
        }
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
    val isCreatingLibrary: Boolean = false,
    val error: String? = null,
)
