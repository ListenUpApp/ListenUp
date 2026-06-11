package com.calypsan.listenup.client.presentation.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.error.ErrorMapper
import com.calypsan.listenup.client.data.remote.ABSImportApiContract
import com.calypsan.listenup.client.data.remote.BackupApiContract
import com.calypsan.listenup.client.data.remote.SearchApiContract
import com.calypsan.listenup.client.data.remote.model.AnalyzeABSRequest
import com.calypsan.listenup.client.data.remote.model.AnalyzeABSResponse
import com.calypsan.listenup.client.data.remote.model.ImportABSRequest
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.presentation.admin.absimport.ready
import com.calypsan.listenup.client.presentation.admin.absimport.updateReady
import com.calypsan.listenup.client.presentation.error.userMessageFor
import com.calypsan.listenup.core.FileSource
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

@Suppress("MagicNumber")
private const val ANALYSIS_POLL_INTERVAL_MS = 1_500L

private const val MIN_SEARCH_QUERY_LEN = 2
private const val SEARCH_LIMIT = 10

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
        state.updateReady { it.copy(sourceType = type, error = null) }
        when (type) {
            ABSSourceType.LOCAL -> {
                // Stay on source selection, user will pick file via UI
            }

            ABSSourceType.REMOTE -> {
                // Navigate to file browser and load root
                state.updateReady { it.copy(step = ABSImportStep.FILE_BROWSER) }
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
        state.updateReady {
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
        state.updateReady { it.copy(selectedLocalFile = null) }
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
            state.updateReady { it.copy(step = ABSImportStep.UPLOADING, isUploading = true, error = null) }

            when (val result = backupApi.uploadABSBackup(file.fileSource)) {
                is AppResult.Success -> {
                    val uploadResult = result.data
                    // Proceed to analysis with the returned path
                    state.updateReady {
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
                    state.updateReady {
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
            state.updateReady { it.copy(isLoadingDirectories = true, error = null) }

            when (val result = backupApi.browseFilesystem(path)) {
                is AppResult.Success -> {
                    val browseResult = result.data
                    state.updateReady {
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
                    state.updateReady {
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

        state.updateReady { it.copy(selectedRemotePath = fullPath, backupPath = fullPath) }
        analyzeBackup(fullPath)
    }

    /**
     * Set the full remote path directly and proceed to analysis.
     */
    fun setFullRemotePath(path: String) {
        state.updateReady { it.copy(selectedRemotePath = path, backupPath = path) }
        analyzeBackup(path)
    }

    // === Analysis ===

    @Suppress("CyclomaticComplexMethod")
    private fun analyzeBackup(path: String) {
        viewModelScope.launch {
            state.updateReady {
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
            val asyncResult =
                backupApi.analyzeABSBackupAsync(
                    AnalyzeABSRequest(
                        backupPath = path,
                        matchByEmail = true,
                        matchByPath = true,
                        fuzzyMatchBooks = true,
                        fuzzyThreshold = 0.85,
                    ),
                )

            val asyncResponse =
                when (asyncResult) {
                    is AppResult.Success -> {
                        asyncResult.data
                    }

                    is AppResult.Failure -> {
                        errorBus.emit(asyncResult.error)
                        logger.error { "Failed to analyze ABS backup: ${asyncResult.error.message}" }
                        state.updateReady {
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
                        is AppResult.Success -> {
                            result.data
                        }

                        is AppResult.Failure -> {
                            errorBus.emit(result.error)
                            logger.error { "Failed to poll analysis status: ${result.error.message}" }
                            state.updateReady {
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
                state.updateReady {
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
                state.updateReady {
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

            state.updateReady {
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
        state.updateReady { current ->
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
        state.updateReady { current ->
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
        state.updateReady { it.copy(userMappingTab = tab) }
    }

    // === Inline User Search ===

    /**
     * Called when a user search field gains focus.
     * Activates search for that specific user and clears previous search state.
     */
    fun activateUserSearch(absUserId: String) {
        state.updateReady {
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
        state.updateReady {
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
        state.updateReady { it.copy(userSearchQuery = query) }

        if (query.length < MIN_SEARCH_QUERY_LEN) {
            state.updateReady { it.copy(userSearchResults = emptyList(), isSearchingUsers = false) }
            return
        }

        viewModelScope.launch {
            state.updateReady { it.copy(isSearchingUsers = true) }
            try {
                when (val result = absImportApi.searchUsers(query, limit = SEARCH_LIMIT)) {
                    is AppResult.Success -> {
                        state.updateReady {
                            it.copy(
                                userSearchResults = result.data,
                                isSearchingUsers = false,
                            )
                        }
                    }

                    is AppResult.Failure -> {
                        logger.error { "User search failed: ${null as Exception?}" }
                        state.updateReady {
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
                state.updateReady {
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
            state.updateReady { it.copy(loadingUserItemId = userId) }

            // Store display info for the selected user
            val displayInfo =
                SelectedUserDisplay(
                    userId = userId,
                    email = email,
                    displayName = displayName,
                )

            state.updateReady { s ->
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
        state.updateReady { s ->
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
        state.updateReady { it.copy(bookMappingTab = tab) }
    }

    // === Inline Book Search ===

    /**
     * Called when a book search field gains focus.
     * Activates search for that specific book and clears previous search state.
     */
    fun activateBookSearch(absItemId: String) {
        state.updateReady {
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
        state.updateReady {
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
        state.updateReady { it.copy(bookSearchQuery = query) }

        if (query.length < MIN_SEARCH_QUERY_LEN) {
            state.updateReady { it.copy(bookSearchResults = emptyList(), isSearchingBooks = false) }
            return
        }

        viewModelScope.launch {
            state.updateReady { it.copy(isSearchingBooks = true) }
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
                state.updateReady {
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
                state.updateReady {
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
            state.updateReady { it.copy(loadingBookItemId = bookId) }

            // Store display info for the selected book
            val displayInfo =
                SelectedBookDisplay(
                    bookId = bookId,
                    title = title,
                    author = author,
                    durationMs = durationMs,
                )

            state.updateReady { s ->
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
        state.updateReady { s ->
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
        state.updateReady { it.copy(importSessions = value) }
    }

    fun setImportProgress(value: Boolean) {
        state.updateReady { it.copy(importProgress = value) }
    }

    fun setRebuildProgress(value: Boolean) {
        state.updateReady { it.copy(rebuildProgress = value) }
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
                        state.updateReady { it.copy(step = ABSImportStep.FILE_BROWSER) }
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
                    state.updateReady { it.copy(step = ABSImportStep.BOOK_MAPPING) }
                } else {
                    state.updateReady { it.copy(step = ABSImportStep.IMPORT_OPTIONS) }
                }
            }

            ABSImportStep.BOOK_MAPPING -> {
                state.updateReady { it.copy(step = ABSImportStep.IMPORT_OPTIONS) }
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
                state.updateReady { it.copy(step = ABSImportStep.SOURCE_SELECTION) }
            }

            ABSImportStep.UPLOADING -> { /* Can't go back during upload */ }

            ABSImportStep.ANALYZING -> { /* Can't go back during analysis */ }

            ABSImportStep.USER_MAPPING -> {
                state.updateReady { it.copy(step = ABSImportStep.SOURCE_SELECTION) }
            }

            ABSImportStep.BOOK_MAPPING -> {
                if (current.usersPending > 0) {
                    state.updateReady { it.copy(step = ABSImportStep.USER_MAPPING) }
                } else {
                    state.updateReady { it.copy(step = ABSImportStep.SOURCE_SELECTION) }
                }
            }

            ABSImportStep.IMPORT_OPTIONS -> {
                if (current.booksPending > 0) {
                    state.updateReady { it.copy(step = ABSImportStep.BOOK_MAPPING) }
                } else if (current.usersPending > 0) {
                    state.updateReady { it.copy(step = ABSImportStep.USER_MAPPING) }
                } else {
                    state.updateReady { it.copy(step = ABSImportStep.SOURCE_SELECTION) }
                }
            }

            ABSImportStep.IMPORTING -> { /* Can't go back during import */ }

            ABSImportStep.RESULTS -> { /* Can't go back after complete */ }
        }
    }

    // === Import ===

    private fun performImport() {
        viewModelScope.launch {
            state.updateReady { it.copy(step = ABSImportStep.IMPORTING, isImporting = true, error = null) }

            val current = state.value as? ABSImportUiState.Ready ?: return@launch
            when (
                val result =
                    backupApi.importABSBackup(
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
                    state.updateReady {
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
                    state.updateReady {
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
        state.updateReady { it.copy(error = null) }
    }
}
