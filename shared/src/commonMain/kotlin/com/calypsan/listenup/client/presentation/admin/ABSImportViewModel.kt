package com.calypsan.listenup.client.presentation.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.FileSource
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.core.error.ErrorBus
import com.calypsan.listenup.client.core.error.ErrorMapper
import com.calypsan.listenup.client.data.remote.ABSImportApiContract
import com.calypsan.listenup.client.data.remote.BackupApiContract
import com.calypsan.listenup.client.data.remote.DirectoryEntryResponse
import com.calypsan.listenup.client.data.remote.SearchApiContract
import com.calypsan.listenup.client.data.remote.SearchHitResponse
import com.calypsan.listenup.client.data.remote.UserSearchResult
import com.calypsan.listenup.client.data.remote.model.ABSBookMatch
import com.calypsan.listenup.client.data.remote.model.ABSUserMatch
import com.calypsan.listenup.client.data.remote.model.AnalyzeABSRequest
import com.calypsan.listenup.client.data.remote.model.AnalyzeABSResponse
import com.calypsan.listenup.client.data.remote.model.ImportABSRequest
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.presentation.error.userMessageFor
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

@Suppress("MagicNumber")
private const val ANALYSIS_POLL_INTERVAL_MS = 1_500L

private const val MIN_SEARCH_QUERY_LEN = 2
private const val SEARCH_LIMIT = 10

/**
 * Source type for the ABS backup file.
 */
enum class ABSSourceType {
    /** File from user's device (phone/tablet) */
    LOCAL,

    /** File already on the server */
    REMOTE,
}

/**
 * Step in the ABS import wizard.
 */
enum class ABSImportStep {
    /** Choose between local file or server file */
    SOURCE_SELECTION,

    /** Browsing server filesystem (remote mode) */
    FILE_BROWSER,

    /** Uploading local file to server */
    UPLOADING,

    /** Analyzing the backup */
    ANALYZING,

    /** Mapping ABS users to ListenUp users */
    USER_MAPPING,

    /** Mapping ABS books to ListenUp books */
    BOOK_MAPPING,

    /** Configuring import options */
    IMPORT_OPTIONS,

    /** Import in progress */
    IMPORTING,

    /** Import complete, showing results */
    RESULTS,
}

/**
 * Tab selection for user mapping step.
 */
enum class UserMappingTab {
    /** Users that need manual mapping */
    NEEDS_REVIEW,

    /** Auto-matched users for verification */
    AUTO_MATCHED,
}

/**
 * Tab selection for book mapping step.
 */
enum class BookMappingTab {
    /** Books that need manual mapping */
    NEEDS_REVIEW,

    /** Auto-matched books for verification */
    AUTO_MATCHED,
}

/**
 * Display information for a selected user mapping.
 */
data class SelectedUserDisplay(
    val userId: String,
    val email: String,
    val displayName: String? = null,
)

/**
 * Display information for a selected book mapping.
 */
data class SelectedBookDisplay(
    val bookId: String,
    val title: String,
    val author: String? = null,
    val durationMs: Long? = null,
)

/**
 * UI state for the ABS import wizard.
 *
 * Sealed hierarchy:
 * - [Loading] declared for future async-init hooks; the current VM has no initial
 *   load and starts directly in [Ready].
 * - [Ready] carries the full wizard state: the current [ABSImportStep], every
 *   pipeline's inputs/outputs, overlays (`isUploading`, `isAnalyzing`,
 *   `isLoadingDirectories`, `isSearchingUsers`, `isSearchingBooks`, `isImporting`,
 *   plus item-level loading ids), and intent fields (tab selections, active search
 *   ids, search queries, import-option toggles, mapping displays). A transient
 *   `error` field surfaces mutation failures as snackbar-style banners.
 * - [Error] terminal state declared for parity with other W5 migrations; no code
 *   path currently reaches it, since pipeline failures fall back to
 *   [ABSImportStep.SOURCE_SELECTION] with a transient `error` on [Ready].
 *
 * W5 minimal-flatten note: `ABSImportStep` already discriminates the wizard
 * phase and the fields it carries overlap across adjacent steps (e.g. back-nav
 * from `BOOK_MAPPING` consults `usersPending`). Splitting [Ready] into per-step
 * sub-records would duplicate shared data; decomposition is deferred to W6.
 */
sealed interface ABSImportUiState {
    data object Loading : ABSImportUiState

    /**
     * Wizard is interactive. [step] discriminates the current phase; the remaining fields
     * carry pipeline inputs/outputs, action overlays, and a transient `error` for snackbar
     * surfacing. See the parent KDoc for the W5 minimal-flatten note.
     */
    @Suppress("LongParameterList")
    data class Ready(
        val step: ABSImportStep = ABSImportStep.SOURCE_SELECTION,
        val sourceType: ABSSourceType? = null,
        // Local file state
        val selectedLocalFile: SelectedLocalFile? = null,
        val isUploading: Boolean = false,
        val uploadProgress: Float = 0f,
        // Remote file browser state
        val currentPath: String = "/",
        val parentPath: String? = null,
        val directories: List<DirectoryEntryResponse> = emptyList(),
        val isLoadingDirectories: Boolean = false,
        val isRoot: Boolean = true,
        val selectedRemotePath: String = "",
        // After file is selected/uploaded
        val backupPath: String = "",
        val isAnalyzing: Boolean = false,
        val analysisComplete: Boolean = false,
        val analyzePhase: String = "",
        val analyzeCurrent: Int = 0,
        val analyzeTotal: Int = 0,
        // Analysis results
        val summary: String = "",
        val totalUsers: Int = 0,
        val totalBooks: Int = 0,
        val totalSessions: Int = 0,
        val usersMatched: Int = 0,
        val usersPending: Int = 0,
        val booksMatched: Int = 0,
        val booksPending: Int = 0,
        val sessionsReady: Int = 0,
        val sessionsPending: Int = 0,
        val progressReady: Int = 0,
        val progressPending: Int = 0,
        val userMatches: List<ABSUserMatch> = emptyList(),
        val bookMatches: List<ABSBookMatch> = emptyList(),
        val analysisWarnings: List<String> = emptyList(),
        // User/book mappings - ABS ID -> ListenUp ID
        val userMappings: Map<String, String> = emptyMap(),
        val bookMappings: Map<String, String> = emptyMap(),
        // User mapping UI state
        val userMappingTab: UserMappingTab = UserMappingTab.NEEDS_REVIEW,
        // Display info for selected users (absUserId -> display info)
        val selectedUserDisplays: Map<String, SelectedUserDisplay> = emptyMap(),
        // Inline user search state (for the currently active search field)
        val activeSearchAbsUserId: String? = null,
        val userSearchQuery: String = "",
        val userSearchResults: List<UserSearchResult> = emptyList(),
        val isSearchingUsers: Boolean = false,
        // ID of the user result item currently being processed (shows loading spinner)
        val loadingUserItemId: String? = null,
        // Book mapping UI state
        val bookMappingTab: BookMappingTab = BookMappingTab.NEEDS_REVIEW,
        // Display info for selected books (absItemId -> display info)
        val selectedBookDisplays: Map<String, SelectedBookDisplay> = emptyMap(),
        // Inline book search state (for the currently active search field)
        val activeSearchAbsItemId: String? = null,
        val bookSearchQuery: String = "",
        val bookSearchResults: List<SearchHitResponse> = emptyList(),
        val isSearchingBooks: Boolean = false,
        // ID of the book result item currently being processed (shows loading spinner)
        val loadingBookItemId: String? = null,
        // Import options
        val importSessions: Boolean = true,
        val importProgress: Boolean = true,
        val rebuildProgress: Boolean = true,
        // Import results
        val isImporting: Boolean = false,
        val importResults: ABSImportResults? = null,
        // Transient mutation-failure error (snackbar/banner).
        val error: String? = null,
    ) : ABSImportUiState

    /** Terminal failure state declared for parity with other W5 migrations; not currently reached. */
    data class Error(
        val message: String,
    ) : ABSImportUiState
}

/**
 * A locally selected file ready for upload.
 *
 * Uses [FileSource] for streaming access to avoid loading the entire file into memory.
 * This is critical for large backup files that could otherwise cause OOM crashes.
 */
data class SelectedLocalFile(
    val fileSource: FileSource,
    val filename: String,
    val size: Long,
)

/**
 * Results from the ABS import.
 */
data class ABSImportResults(
    val sessionsImported: Int,
    val sessionsSkipped: Int,
    val progressImported: Int,
    val progressSkipped: Int,
    val eventsCreated: Int,
    val affectedUsers: Int,
    val duration: String,
    val warnings: List<String>,
    val errors: List<String>,
)

/**
 * ViewModel for ABS import flow.
 *
 * Supports two source types:
 * - LOCAL: User picks file from device, uploads to server, then analyzes
 * - REMOTE: User browses server filesystem, selects file, then analyzes
 *
 * TODO: Split into smaller pieces — e.g. extract UserMappingHandler and BookMappingHandler
 *  delegate classes to reduce class size below the detekt LargeClass threshold.
 */
@Suppress("LargeClass", "TooManyFunctions")
class ABSImportViewModel(
    private val backupApi: BackupApiContract,
    private val searchApi: SearchApiContract,
    private val absImportApi: ABSImportApiContract,
    private val syncRepository: SyncRepository,
    private val errorBus: ErrorBus,
) : ViewModel() {
    val state: StateFlow<ABSImportUiState>
        field = MutableStateFlow<ABSImportUiState>(ABSImportUiState.Ready())

    // === Source Selection ===

    fun selectSourceType(type: ABSSourceType) {
        updateReady { it.copy(sourceType = type, error = null) }
        when (type) {
            ABSSourceType.LOCAL -> {
                // Stay on source selection, user will pick file via UI
            }

            ABSSourceType.REMOTE -> {
                // Navigate to file browser and load root
                updateReady { it.copy(step = ABSImportStep.FILE_BROWSER) }
                loadDirectory("/")
            }
        }
    }

    // === Local File Handling ===

    /**
     * Called when user picks a local file via the document picker.
     *
     * @param fileSource Streaming source for the file content (avoids loading into memory)
     * @param filename Original filename for display
     * @param size File size in bytes
     */
    fun setLocalFile(
        fileSource: FileSource,
        filename: String,
        size: Long,
    ) {
        updateReady {
            it.copy(
                selectedLocalFile = SelectedLocalFile(fileSource, filename, size),
                error = null,
            )
        }
    }

    /**
     * Clear the selected local file.
     */
    fun clearLocalFile() {
        updateReady { it.copy(selectedLocalFile = null) }
    }

    /**
     * Upload the selected local file to the server and proceed to analysis.
     *
     * Uses streaming upload to avoid loading the entire file into memory.
     */
    fun uploadAndAnalyze() {
        val ready = state.value as? ABSImportUiState.Ready ?: return
        val file = ready.selectedLocalFile ?: return

        viewModelScope.launch {
            updateReady { it.copy(step = ABSImportStep.UPLOADING, isUploading = true, error = null) }

            when (val result = backupApi.uploadABSBackup(file.fileSource)) {
                is AppResult.Success -> {
                    val uploadResult = result.data
                    // Proceed to analysis with the returned path
                    updateReady {
                        it.copy(
                            isUploading = false,
                            backupPath = uploadResult.path,
                        )
                    }
                    analyzeBackup(uploadResult.path)
                }
                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to upload ABS backup: ${result.error.message}" }
                    updateReady {
                        it.copy(
                            step = ABSImportStep.SOURCE_SELECTION,
                            isUploading = false,
                            error = userMessageFor(result.error),
                        )
                    }
                }
            }
        }
    }

    // === Remote File Browser ===

    /**
     * Load directory contents for the file browser.
     */
    fun loadDirectory(path: String) {
        viewModelScope.launch {
            updateReady { it.copy(isLoadingDirectories = true, error = null) }

            when (val result = backupApi.browseFilesystem(path)) {
                is AppResult.Success -> {
                    val browseResult = result.data
                    updateReady {
                        it.copy(
                            currentPath = browseResult.path,
                            parentPath = browseResult.parent,
                            directories = browseResult.entries,
                            isRoot = browseResult.isRoot,
                            isLoadingDirectories = false,
                        )
                    }
                }
                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to browse filesystem: ${result.error.message}" }
                    updateReady {
                        it.copy(
                            isLoadingDirectories = false,
                            error = userMessageFor(result.error),
                        )
                    }
                }
            }
        }
    }

    /**
     * Navigate up to parent directory.
     */
    fun navigateUp() {
        val ready = state.value as? ABSImportUiState.Ready ?: return
        val parent = ready.parentPath
        if (parent != null) {
            loadDirectory(parent)
        }
    }

    /**
     * Set the remote file path and proceed to analysis.
     * User enters filename within the current directory.
     */
    fun setRemoteFilePath(filename: String) {
        val ready = state.value as? ABSImportUiState.Ready ?: return
        val currentPath = ready.currentPath
        val fullPath =
            if (currentPath.endsWith("/")) {
                "$currentPath$filename"
            } else {
                "$currentPath/$filename"
            }

        updateReady { it.copy(selectedRemotePath = fullPath, backupPath = fullPath) }
        analyzeBackup(fullPath)
    }

    /**
     * Set the full remote path directly and proceed to analysis.
     */
    fun setFullRemotePath(path: String) {
        updateReady { it.copy(selectedRemotePath = path, backupPath = path) }
        analyzeBackup(path)
    }

    // === Analysis ===

    @Suppress("CyclomaticComplexMethod")
    private fun analyzeBackup(path: String) {
        viewModelScope.launch {
            updateReady {
                it.copy(
                    step = ABSImportStep.ANALYZING,
                    isAnalyzing = true,
                    error = null,
                    analyzePhase = "",
                    analyzeCurrent = 0,
                    analyzeTotal = 0,
                )
            }

            // Start async analysis
            val asyncResult = backupApi.analyzeABSBackupAsync(
                AnalyzeABSRequest(
                    backupPath = path,
                    matchByEmail = true,
                    matchByPath = true,
                    fuzzyMatchBooks = true,
                    fuzzyThreshold = 0.85,
                ),
            )

            val asyncResponse = when (asyncResult) {
                is AppResult.Success -> asyncResult.data
                is AppResult.Failure -> {
                    errorBus.emit(asyncResult.error)
                    logger.error { "Failed to analyze ABS backup: ${asyncResult.error.message}" }
                    updateReady {
                        it.copy(
                            isAnalyzing = false,
                            step = ABSImportStep.SOURCE_SELECTION,
                            error = userMessageFor(asyncResult.error),
                        )
                    }
                    return@launch
                }
            }

            // Poll for status — helper unwraps AppResult<AnalysisStatusResponse> or
            // emits the error, updates state, and returns null to signal early exit.
            suspend fun pollStatus(analysisId: String) =
                backupApi.getAnalysisStatus(analysisId).let { result ->
                    when (result) {
                        is AppResult.Success -> result.data
                        is AppResult.Failure -> {
                            errorBus.emit(result.error)
                            logger.error { "Failed to poll analysis status: ${result.error.message}" }
                            updateReady {
                                it.copy(
                                    isAnalyzing = false,
                                    step = ABSImportStep.SOURCE_SELECTION,
                                    error = userMessageFor(result.error),
                                )
                            }
                            null
                        }
                    }
                }

            val analysisId = asyncResponse.analysisId
            var statusResponse = pollStatus(analysisId) ?: return@launch

            while (statusResponse.status == "running") {
                updateReady {
                    it.copy(
                        analyzePhase = statusResponse.phase,
                        analyzeCurrent = statusResponse.current,
                        analyzeTotal = statusResponse.total,
                        totalBooks = maxOf(it.totalBooks, statusResponse.totalBooks),
                        totalUsers = maxOf(it.totalUsers, statusResponse.totalUsers),
                    )
                }
                delay(ANALYSIS_POLL_INTERVAL_MS)
                statusResponse = pollStatus(analysisId) ?: return@launch
            }

            if (statusResponse.status == "failed") {
                val errorMessage = statusResponse.error ?: "Analysis failed"
                logger.error { "Analysis reported failed status: $errorMessage" }
                updateReady {
                    it.copy(
                        isAnalyzing = false,
                        step = ABSImportStep.SOURCE_SELECTION,
                        error = errorMessage,
                    )
                }
                return@launch
            }

            val result = statusResponse.result!!

            // Build initial mappings from server-matched items
            // All items with listenupId are auto-matched; users can review and change
            val initialUserMappings =
                result.userMatches
                    .filter { it.listenupId != null }
                    .associate { it.absUserId to it.listenupId!! }

            val initialBookMappings =
                result.bookMatches
                    .filter { it.listenupId != null }
                    .associate { it.absItemId to it.listenupId!! }

            // Pre-populate display info for auto-matched books
            val initialBookDisplays = buildInitialBookDisplays(result)

            // Determine next step based on what needs mapping
            val nextStep =
                when {
                    result.usersPending > 0 -> ABSImportStep.USER_MAPPING
                    result.booksPending > 0 -> ABSImportStep.BOOK_MAPPING
                    else -> ABSImportStep.IMPORT_OPTIONS
                }

            updateReady {
                it.copy(
                    isAnalyzing = false,
                    analysisComplete = true,
                    step = nextStep,
                    summary = result.summary,
                    totalUsers = result.totalUsers,
                    totalBooks = result.totalBooks,
                    totalSessions = result.totalSessions,
                    usersMatched = result.usersMatched,
                    usersPending = result.usersPending,
                    booksMatched = result.booksMatched,
                    booksPending = result.booksPending,
                    sessionsReady = result.sessionsReady,
                    sessionsPending = result.sessionsPending,
                    progressReady = result.progressReady,
                    progressPending = result.progressPending,
                    userMatches = result.userMatches,
                    bookMatches = result.bookMatches,
                    analysisWarnings = result.warnings,
                    userMappings = initialUserMappings,
                    bookMappings = initialBookMappings,
                    selectedBookDisplays = initialBookDisplays,
                )
            }
        }
    }

    private fun buildInitialBookDisplays(result: AnalyzeABSResponse): Map<String, SelectedBookDisplay> =
        result.bookMatches
            .filter { it.listenupId != null }
            .associate { match ->
                val listenupId = match.listenupId!!
                // Try to find the matched book in suggestions for full details
                val matchedSuggestion = match.suggestions.firstOrNull { it.bookId == listenupId }
                // Fallback to first suggestion if matched book not in list
                val suggestion = matchedSuggestion ?: match.suggestions.firstOrNull()

                val display =
                    if (suggestion != null) {
                        SelectedBookDisplay(
                            bookId = listenupId,
                            title = suggestion.title,
                            author = suggestion.author,
                            durationMs = suggestion.durationMs,
                        )
                    } else {
                        // No suggestions available — use ABS metadata for display
                        SelectedBookDisplay(
                            bookId = listenupId,
                            title = match.absTitle,
                            author = match.absAuthor,
                            durationMs = null,
                        )
                    }
                match.absItemId to display
            }

    // === Mapping ===

    fun setUserMapping(
        absUserId: String,
        listenupUserId: String?,
    ) {
        updateReady { current ->
            val newMappings = current.userMappings.toMutableMap()
            if (listenupUserId != null) {
                newMappings[absUserId] = listenupUserId
            } else {
                newMappings.remove(absUserId)
            }
            current.copy(userMappings = newMappings)
        }
    }

    fun setBookMapping(
        absItemId: String,
        listenupBookId: String?,
    ) {
        updateReady { current ->
            val newMappings = current.bookMappings.toMutableMap()
            if (listenupBookId != null) {
                newMappings[absItemId] = listenupBookId
            } else {
                newMappings.remove(absItemId)
            }
            current.copy(bookMappings = newMappings)
        }
    }

    // === User Mapping Tab ===

    /**
     * Set the active tab in the user mapping step.
     */
    fun setUserMappingTab(tab: UserMappingTab) {
        updateReady { it.copy(userMappingTab = tab) }
    }

    // === Inline User Search ===

    /**
     * Called when a user search field gains focus.
     * Activates search for that specific user and clears previous search state.
     */
    fun activateUserSearch(absUserId: String) {
        updateReady {
            it.copy(
                activeSearchAbsUserId = absUserId,
                userSearchQuery = "",
                userSearchResults = emptyList(),
                isSearchingUsers = false,
            )
        }
    }

    /**
     * Called when a user search field loses focus.
     * Clears the active search state.
     */
    fun deactivateUserSearch() {
        updateReady {
            it.copy(
                activeSearchAbsUserId = null,
                userSearchQuery = "",
                userSearchResults = emptyList(),
                isSearchingUsers = false,
            )
        }
    }

    /**
     * Update search query for the active user search field.
     */
    fun updateUserSearchQuery(query: String) {
        updateReady { it.copy(userSearchQuery = query) }

        if (query.length < MIN_SEARCH_QUERY_LEN) {
            updateReady { it.copy(userSearchResults = emptyList(), isSearchingUsers = false) }
            return
        }

        viewModelScope.launch {
            updateReady { it.copy(isSearchingUsers = true) }
            try {
                when (val result = absImportApi.searchUsers(query, limit = SEARCH_LIMIT)) {
                    is Success -> {
                        updateReady {
                            it.copy(
                                userSearchResults = result.data,
                                isSearchingUsers = false,
                            )
                        }
                    }

                    is Failure -> {
                        logger.error { "User search failed: ${null as Exception?}" }
                        updateReady {
                            it.copy(
                                userSearchResults = emptyList(),
                                isSearchingUsers = false,
                            )
                        }
                    }
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                errorBus.emit(ErrorMapper.map(e))
                logger.error(e) { "User search failed: ${e.message}" }
                updateReady {
                    it.copy(
                        userSearchResults = emptyList(),
                        isSearchingUsers = false,
                    )
                }
            }
        }
    }

    /**
     * Select a user from search results or suggestions and apply the mapping.
     */
    fun selectUser(
        absUserId: String,
        userId: String,
        email: String,
        displayName: String?,
    ) {
        viewModelScope.launch {
            // Show loading spinner on the tapped result while state propagates
            updateReady { it.copy(loadingUserItemId = userId) }

            // Store display info for the selected user
            val displayInfo =
                SelectedUserDisplay(
                    userId = userId,
                    email = email,
                    displayName = displayName,
                )

            updateReady { s ->
                val newDisplays = s.selectedUserDisplays.toMutableMap()
                newDisplays[absUserId] = displayInfo

                val newMappings = s.userMappings.toMutableMap()
                newMappings[absUserId] = userId

                s.copy(
                    selectedUserDisplays = newDisplays,
                    userMappings = newMappings,
                    // Clear search state
                    activeSearchAbsUserId = null,
                    userSearchQuery = "",
                    userSearchResults = emptyList(),
                    loadingUserItemId = null,
                )
            }
        }
    }

    /**
     * Clear the user mapping for an ABS user (allows re-searching).
     */
    fun clearUserMapping(absUserId: String) {
        updateReady { s ->
            val newDisplays = s.selectedUserDisplays.toMutableMap()
            newDisplays.remove(absUserId)

            val newMappings = s.userMappings.toMutableMap()
            newMappings.remove(absUserId)

            s.copy(
                selectedUserDisplays = newDisplays,
                userMappings = newMappings,
            )
        }
    }

    // === Book Mapping Tab ===

    /**
     * Set the active tab in the book mapping step.
     */
    fun setBookMappingTab(tab: BookMappingTab) {
        updateReady { it.copy(bookMappingTab = tab) }
    }

    // === Inline Book Search ===

    /**
     * Called when a book search field gains focus.
     * Activates search for that specific book and clears previous search state.
     */
    fun activateBookSearch(absItemId: String) {
        updateReady {
            it.copy(
                activeSearchAbsItemId = absItemId,
                bookSearchQuery = "",
                bookSearchResults = emptyList(),
                isSearchingBooks = false,
            )
        }
    }

    /**
     * Called when a book search field loses focus.
     * Clears the active search state.
     */
    fun deactivateBookSearch() {
        updateReady {
            it.copy(
                activeSearchAbsItemId = null,
                bookSearchQuery = "",
                bookSearchResults = emptyList(),
                isSearchingBooks = false,
            )
        }
    }

    /**
     * Update search query for the active book search field.
     */
    fun updateBookSearchQuery(query: String) {
        updateReady { it.copy(bookSearchQuery = query) }

        if (query.length < MIN_SEARCH_QUERY_LEN) {
            updateReady { it.copy(bookSearchResults = emptyList(), isSearchingBooks = false) }
            return
        }

        viewModelScope.launch {
            updateReady { it.copy(isSearchingBooks = true) }
            try {
                val response =
                    searchApi.search(
                        query = query,
                        types = "book",
                        genres = null,
                        genrePath = null,
                        minDuration = null,
                        maxDuration = null,
                        limit = SEARCH_LIMIT,
                        offset = 0,
                    )
                updateReady {
                    it.copy(
                        bookSearchResults = response.hits,
                        isSearchingBooks = false,
                    )
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                errorBus.emit(ErrorMapper.map(e))
                logger.error(e) { "Book search failed: ${e.message}" }
                updateReady {
                    it.copy(
                        bookSearchResults = emptyList(),
                        isSearchingBooks = false,
                    )
                }
            }
        }
    }

    /**
     * Select a book from search results or suggestions and apply the mapping.
     */
    fun selectBook(
        absItemId: String,
        bookId: String,
        title: String,
        author: String?,
        durationMs: Long?,
    ) {
        viewModelScope.launch {
            // Show loading spinner on the tapped result while state propagates
            updateReady { it.copy(loadingBookItemId = bookId) }

            // Store display info for the selected book
            val displayInfo =
                SelectedBookDisplay(
                    bookId = bookId,
                    title = title,
                    author = author,
                    durationMs = durationMs,
                )

            updateReady { s ->
                val newDisplays = s.selectedBookDisplays.toMutableMap()
                newDisplays[absItemId] = displayInfo

                val newMappings = s.bookMappings.toMutableMap()
                newMappings[absItemId] = bookId

                s.copy(
                    selectedBookDisplays = newDisplays,
                    bookMappings = newMappings,
                    // Clear search state
                    activeSearchAbsItemId = null,
                    bookSearchQuery = "",
                    bookSearchResults = emptyList(),
                    loadingBookItemId = null,
                )
            }
        }
    }

    /**
     * Clear the book mapping for an ABS item (allows re-searching).
     */
    fun clearBookMapping(absItemId: String) {
        updateReady { s ->
            val newDisplays = s.selectedBookDisplays.toMutableMap()
            newDisplays.remove(absItemId)

            val newMappings = s.bookMappings.toMutableMap()
            newMappings.remove(absItemId)

            s.copy(
                selectedBookDisplays = newDisplays,
                bookMappings = newMappings,
            )
        }
    }

    // === Import Options ===

    fun setImportSessions(value: Boolean) {
        updateReady { it.copy(importSessions = value) }
    }

    fun setImportProgress(value: Boolean) {
        updateReady { it.copy(importProgress = value) }
    }

    fun setRebuildProgress(value: Boolean) {
        updateReady { it.copy(rebuildProgress = value) }
    }

    // === Navigation ===

    fun nextStep() {
        val current = state.value as? ABSImportUiState.Ready ?: return
        when (current.step) {
            ABSImportStep.SOURCE_SELECTION -> {
                when (current.sourceType) {
                    ABSSourceType.LOCAL -> {
                        if (current.selectedLocalFile != null) {
                            uploadAndAnalyze()
                        }
                    }

                    ABSSourceType.REMOTE -> {
                        updateReady { it.copy(step = ABSImportStep.FILE_BROWSER) }
                        loadDirectory("/")
                    }

                    null -> { /* No source selected yet */ }
                }
            }

            ABSImportStep.FILE_BROWSER -> {
                // User needs to select a file path
            }

            ABSImportStep.UPLOADING -> {
                // Wait for upload to complete
            }

            ABSImportStep.ANALYZING -> {
                // Wait for analysis to complete
            }

            ABSImportStep.USER_MAPPING -> {
                // Move to book mapping or import options
                if (current.booksPending > 0) {
                    updateReady { it.copy(step = ABSImportStep.BOOK_MAPPING) }
                } else {
                    updateReady { it.copy(step = ABSImportStep.IMPORT_OPTIONS) }
                }
            }

            ABSImportStep.BOOK_MAPPING -> {
                updateReady { it.copy(step = ABSImportStep.IMPORT_OPTIONS) }
            }

            ABSImportStep.IMPORT_OPTIONS -> {
                performImport()
            }

            ABSImportStep.IMPORTING -> {
                // Wait for import to complete
            }

            ABSImportStep.RESULTS -> {
                // Done
            }
        }
    }

    fun previousStep() {
        val current = state.value as? ABSImportUiState.Ready ?: return
        when (current.step) {
            ABSImportStep.SOURCE_SELECTION -> { /* Can't go back */ }

            ABSImportStep.FILE_BROWSER -> {
                updateReady { it.copy(step = ABSImportStep.SOURCE_SELECTION) }
            }

            ABSImportStep.UPLOADING -> { /* Can't go back during upload */ }

            ABSImportStep.ANALYZING -> { /* Can't go back during analysis */ }

            ABSImportStep.USER_MAPPING -> {
                updateReady { it.copy(step = ABSImportStep.SOURCE_SELECTION) }
            }

            ABSImportStep.BOOK_MAPPING -> {
                if (current.usersPending > 0) {
                    updateReady { it.copy(step = ABSImportStep.USER_MAPPING) }
                } else {
                    updateReady { it.copy(step = ABSImportStep.SOURCE_SELECTION) }
                }
            }

            ABSImportStep.IMPORT_OPTIONS -> {
                if (current.booksPending > 0) {
                    updateReady { it.copy(step = ABSImportStep.BOOK_MAPPING) }
                } else if (current.usersPending > 0) {
                    updateReady { it.copy(step = ABSImportStep.USER_MAPPING) }
                } else {
                    updateReady { it.copy(step = ABSImportStep.SOURCE_SELECTION) }
                }
            }

            ABSImportStep.IMPORTING -> { /* Can't go back during import */ }

            ABSImportStep.RESULTS -> { /* Can't go back after complete */ }
        }
    }

    // === Import ===

    private fun performImport() {
        viewModelScope.launch {
            updateReady { it.copy(step = ABSImportStep.IMPORTING, isImporting = true, error = null) }

            val current = state.value as? ABSImportUiState.Ready ?: return@launch
            when (
                val result = backupApi.importABSBackup(
                    ImportABSRequest(
                        backupPath = current.backupPath,
                        userMappings = current.userMappings,
                        bookMappings = current.bookMappings,
                        importSessions = current.importSessions,
                        importProgress = current.importProgress,
                        rebuildProgress = current.rebuildProgress,
                    ),
                )
            ) {
                is AppResult.Success -> {
                    val importResult = result.data
                    updateReady {
                        it.copy(
                            isImporting = false,
                            step = ABSImportStep.RESULTS,
                            importResults =
                                ABSImportResults(
                                    sessionsImported = importResult.sessionsImported,
                                    sessionsSkipped = importResult.sessionsSkipped,
                                    progressImported = importResult.progressImported,
                                    progressSkipped = importResult.progressSkipped,
                                    eventsCreated = importResult.eventsCreated,
                                    affectedUsers = importResult.affectedUsers,
                                    duration = importResult.duration,
                                    warnings = importResult.warnings,
                                    errors = importResult.errors,
                                ),
                        )
                    }

                    // Refresh listening history to pull all imported events and rebuild positions
                    // This uses a full refresh (ignoring delta sync cursor) because imported
                    // events have historical timestamps that wouldn't be included in normal sync
                    logger.info { "Import complete, refreshing listening history" }
                    syncRepository.refreshListeningHistory()
                }
                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to import ABS backup: ${result.error.message}" }
                    updateReady {
                        it.copy(
                            isImporting = false,
                            step = ABSImportStep.IMPORT_OPTIONS,
                            error = userMessageFor(result.error),
                        )
                    }
                }
            }
        }
    }

    fun clearError() {
        updateReady { it.copy(error = null) }
    }

    /**
     * Apply [transform] to state only if it is currently
     * [ABSImportUiState.Ready]. No-ops otherwise.
     */
    private fun updateReady(transform: (ABSImportUiState.Ready) -> ABSImportUiState.Ready) {
        state.update { current ->
            if (current is ABSImportUiState.Ready) transform(current) else current
        }
    }
}
