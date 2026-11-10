package com.calypsan.listenup.client.presentation.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.DirectoryEntry
import com.calypsan.listenup.api.LibraryAdminService
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
 * - Asking the server whether setup is needed (once, at construction; [retryStatusCheck] after a failure)
 * - Browsing the server filesystem ([loadDirectory])
 * - Selecting one or more folders to add to THE library
 * - Registering each folder via `addFolder` and kicking off the initial scan ([completeSetup])
 *
 * There is one library; the wizard selects which folders it watches. Its state is the sealed
 * [LibrarySetupUiState]: a check that failed is [LibrarySetupUiState.CheckFailed], a state every
 * client renders with a retry — never an indefinite spinner.
 */
class LibrarySetupViewModel internal constructor(
    private val libraryAdminChannel: RpcChannel<LibraryAdminService>,
    private val errorBus: ErrorBus,
    private val appScope: CoroutineScope,
) : ViewModel() {
    val state: StateFlow<LibrarySetupUiState>
        field = MutableStateFlow<LibrarySetupUiState>(LibrarySetupUiState.Checking)

    private val navActionsChannel = Channel<LibrarySetupNavAction>(Channel.BUFFERED)

    /** One-shot navigation events. Collect via [Flow.collect] in a [LaunchedEffect]. */
    val navActions: Flow<LibrarySetupNavAction> = navActionsChannel.receiveAsFlow()

    /** The directory listing in flight, if any; a newer browse cancels it so replies never land out of order. */
    private var directoryJob: Job? = null

    init {
        checkLibraryStatus()
    }

    /**
     * Ask the server again whether setup is needed — the retry [LibrarySetupUiState.CheckFailed] offers.
     * A no-op from any other state: re-checking mid-browse would throw away the reader's selection.
     */
    fun retryStatusCheck() {
        if (state.value is LibrarySetupUiState.CheckFailed) checkLibraryStatus()
    }

    private fun checkLibraryStatus() {
        state.value = LibrarySetupUiState.Checking
        viewModelScope.launch {
            when (val result = libraryAdminChannel.call(idempotent = true) { it.getSetupStatus() }) {
                is AppResult.Success -> {
                    val needsSetup = result.data.needsSetup
                    logger.info { "Setup status: needsSetup=$needsSetup" }
                    if (needsSetup) {
                        state.value = LibrarySetupUiState.Browsing()
                        loadDirectory(ROOT_PATH)
                    } else {
                        state.value = LibrarySetupUiState.NotNeeded
                    }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to check setup status: ${result.error.code}" }
                    state.value = LibrarySetupUiState.CheckFailed(result.error)
                }
            }
        }
    }

    /**
     * Load the contents of a directory from the server filesystem.
     *
     * Only while [LibrarySetupUiState.Browsing]. A newer call cancels one still in flight, so a
     * slow reply for a folder the reader has already left can never overwrite the one they opened.
     *
     * @param path The directory path to load
     */
    fun loadDirectory(path: String) {
        if (state.value !is LibrarySetupUiState.Browsing) return
        directoryJob?.cancel()
        updateBrowsing { it.copy(isLoadingDirectories = true, error = null) }
        directoryJob =
            viewModelScope.launch {
                when (val result = libraryAdminChannel.call(idempotent = true) { it.browseFilesystem(path) }) {
                    is AppResult.Success -> {
                        logger.debug { "Loaded directory: $path, entries=${result.data.size}" }
                        updateBrowsing {
                            it.copy(isLoadingDirectories = false, currentPath = path, directories = result.data)
                        }
                    }

                    is AppResult.Failure -> {
                        errorBus.emit(result.error)
                        logger.error { "Failed to load directory: $path — ${result.error.code}" }
                        updateBrowsing { it.copy(isLoadingDirectories = false, error = result.error) }
                    }
                }
            }
    }

    /**
     * Navigate to the parent directory.
     */
    fun navigateUp() {
        val parent = (state.value as? LibrarySetupUiState.Browsing)?.parentPath ?: return
        loadDirectory(parent)
    }

    /**
     * Toggle a folder path selection.
     * @param path The path to toggle
     */
    fun togglePath(path: String) {
        updateBrowsing { current ->
            val paths =
                if (path in
                    current.selectedPaths
                ) {
                    current.selectedPaths - path
                } else {
                    current.selectedPaths + path
                }
            current.copy(selectedPaths = paths)
        }
    }

    /**
     * Select a folder path for the library (adds to selection).
     * @param path The path to select
     */
    fun selectPath(path: String) {
        updateBrowsing { it.copy(selectedPaths = it.selectedPaths + path) }
    }

    /**
     * Clear all selected paths.
     */
    fun clearSelection() {
        updateBrowsing { it.copy(selectedPaths = emptySet()) }
    }

    /**
     * Clear the inline error left by a failed browse or folder registration.
     */
    fun clearError() {
        updateBrowsing { it.copy(error = null) }
    }

    /**
     * Finish onboarding: register each selected folder under THE library, kick off the
     * initial scan on [appScope] (it outlives the wizard), then emit [LibrarySetupNavAction.Finished].
     * Multi-folder selection is preserved; there is no second library.
     *
     * Refused unless browsing with at least one folder chosen and no registration already running —
     * a second tap while the first is in flight must not register every folder twice.
     */
    fun completeSetup() {
        val current = state.value as? LibrarySetupUiState.Browsing ?: return
        if (current.selectedPaths.isEmpty() || current.isCreatingLibrary) return
        updateBrowsing { it.copy(isCreatingLibrary = true, error = null) }
        viewModelScope.launch {
            for (path in current.selectedPaths) {
                val result = libraryAdminChannel.call { it.addFolder(path) }
                if (result is AppResult.Failure) {
                    errorBus.emit(result.error)
                    updateBrowsing { it.copy(isCreatingLibrary = false, error = result.error) }
                    return@launch
                }
            }
            updateBrowsing { it.copy(isCreatingLibrary = false) }
            triggerInitialScan()
            navActionsChannel.trySend(LibrarySetupNavAction.Finished)
        }
    }

    private inline fun updateBrowsing(transform: (LibrarySetupUiState.Browsing) -> LibrarySetupUiState.Browsing) {
        state.update { current -> if (current is LibrarySetupUiState.Browsing) transform(current) else current }
    }

    /**
     * Kick the first scan off on [appScope] so it outlives the wizard teardown.
     *
     * Runs on [appScope], not [viewModelScope]: the server's `scanLibrary` suspends
     * for the full scan, which easily outlives this wizard (it's torn down the moment
     * onboarding finishes and the host navigates to the Shell). Tying it to the wizard's
     * scope would cancel the scan mid-flight. Progress streams to the Shell over the
     * scanner RPC watch;
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

private const val ROOT_PATH = "/"

/**
 * UI state for the library setup wizard.
 *
 * The states are mutually exclusive, so no client can render a spinner over a check that already
 * failed, or a folder picker for a server that never answered.
 */
sealed interface LibrarySetupUiState {
    /** Asking the server whether setup is needed. */
    data object Checking : LibrarySetupUiState

    /**
     * The server could not say whether setup is needed. Every client shows [error] with a retry
     * ([LibrarySetupViewModel.retryStatusCheck]).
     */
    data class CheckFailed(
        val error: AppError,
    ) : LibrarySetupUiState

    /** The library already watches folders; there is nothing for this wizard to do and the host moves on. */
    data object NotNeeded : LibrarySetupUiState

    /**
     * Choosing folders on the server's filesystem.
     *
     * [selectedPaths] spans every directory visited, not just [directories] on screen. [error] is a
     * browse or registration failure, shown inline without leaving the picker.
     */
    data class Browsing(
        val currentPath: String = ROOT_PATH,
        val directories: List<DirectoryEntry> = emptyList(),
        val isLoadingDirectories: Boolean = true,
        val selectedPaths: Set<String> = emptySet(),
        val isCreatingLibrary: Boolean = false,
        val error: AppError? = null,
    ) : LibrarySetupUiState {
        /** Whether [currentPath] is the filesystem root, which has no parent to go up to. */
        val isRoot: Boolean get() = currentPath == ROOT_PATH

        /** The parent of [currentPath], derived client-side; null at the root. */
        val parentPath: String?
            get() =
                when {
                    isRoot -> null
                    currentPath.lastIndexOf('/') <= 0 -> ROOT_PATH
                    else -> currentPath.substring(0, currentPath.lastIndexOf('/'))
                }
    }
}
