package com.calypsan.listenup.client.presentation.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.FileSource
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.core.error.ErrorBus
import com.calypsan.listenup.client.data.remote.ABSImportApiContract
import com.calypsan.listenup.client.data.remote.ABSImportBook
import com.calypsan.listenup.client.data.remote.ABSImportResponse
import com.calypsan.listenup.client.data.remote.ABSImportSummary
import com.calypsan.listenup.client.data.remote.ABSImportUser
import com.calypsan.listenup.client.data.remote.ABSSessionsResponse
import com.calypsan.listenup.client.data.remote.ImportSessionsResult
import com.calypsan.listenup.client.data.remote.MappingFilter
import com.calypsan.listenup.client.data.remote.SearchApiContract
import com.calypsan.listenup.client.data.remote.SearchHitResponse
import com.calypsan.listenup.client.data.remote.SessionStatusFilter
import com.calypsan.listenup.client.data.remote.UserSearchResult
import com.calypsan.listenup.client.domain.repository.SyncRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

private const val IMPORT_STATUS_ANALYZING = "analyzing"
private const val ANALYSIS_POLL_INTERVAL_MS = 3_000L
private const val CREATE_IMPORT_FAILED_PREFIX = "Failed to create import"
private const val SEARCH_DEBOUNCE_MS = 300L
private const val MIN_SEARCH_QUERY_LEN = 2
private const val SEARCH_LIMIT = 10

private fun createImportFailureDetail(message: String): String = "$CREATE_IMPORT_FAILED_PREFIX: $message"

/**
 * Tab in the import hub detail view.
 */
enum class ImportHubTab {
    OVERVIEW,
    USERS,
    BOOKS,
    SESSIONS,
}

/**
 * UI state for the ABS import list screen.
 *
 * Sealed hierarchy:
 * - [Loading] before the first `listImports()` emission.
 * - [Ready] once the list has loaded; carries `imports`, `isCreating` overlay, and a
 *   transient `error` for mutation failures surfaced as a snackbar.
 * - [Error] terminal state when the initial load fails. Refresh failures after we've
 *   reached [Ready] surface via the transient `error` field on [Ready] instead.
 */
sealed interface ABSImportListUiState {
    data object Loading : ABSImportListUiState

    /**
     * List has loaded; [isCreating] overlays a create-in-flight state and [error] surfaces transient
     * mutation failures (snackbar). Refresh failures while in [Ready] also flow through [error] —
     * we don't fall back to [Error] once the list has rendered.
     */
    data class Ready(
        val imports: List<ABSImportSummary> = emptyList(),
        val isCreating: Boolean = false,
        val error: String? = null,
    ) : ABSImportListUiState

    /** Initial load failed before the list rendered; terminal state. */
    data class Error(
        val message: String,
    ) : ABSImportListUiState
}

/**
 * UI state for a single import's detail/hub view.
 *
 * Sealed hierarchy:
 * - [Loading] before the first `openImport()` completes.
 * - [Ready] once the import has been fetched; carries the import and every tab's
 *   data, plus action overlays (`isLoadingUsers`, `isLoadingBooks`,
 *   `isLoadingSessions`, `isImportingSessions`, `isSearchingUsers`,
 *   `isSearchingBooks`, `mappingInFlightUsers`, `mappingInFlightBooks`) and intent
 *   fields (filters, active search ids, search queries, results, selected tab).
 *   A transient `error` field surfaces mutation failures as snackbars.
 * - [Error] terminal state when `openImport` fails.
 *
 * W5 minimal-flatten note: the overlay booleans and intent-in-state fields remain
 * flat on [Ready] — decomposition into sub-records is deferred to W6.
 */
sealed interface ABSImportHubUiState {
    data object Loading : ABSImportHubUiState

    /**
     * Import has loaded; carries every tab's data plus action overlays and intent fields.
     * Transient mutation failures surface via [error] (snackbar); per-tab loading flags
     * (`isLoading*`, `isSearching*`, `mappingInFlight*`) overlay the data without replacing it.
     */
    @Suppress("LongParameterList")
    data class Ready(
        val importId: String,
        val import: ABSImportResponse,
        val activeTab: ImportHubTab = ImportHubTab.OVERVIEW,
        // Users tab
        val users: List<ABSImportUser> = emptyList(),
        val usersFilter: MappingFilter = MappingFilter.ALL,
        val isLoadingUsers: Boolean = false,
        val activeSearchAbsUserId: String? = null,
        val userSearchQuery: String = "",
        val userSearchResults: List<UserSearchResult> = emptyList(),
        val isSearchingUsers: Boolean = false,
        // Books tab
        val books: List<ABSImportBook> = emptyList(),
        val booksFilter: MappingFilter = MappingFilter.ALL,
        val isLoadingBooks: Boolean = false,
        val activeSearchAbsMediaId: String? = null,
        val bookSearchQuery: String = "",
        val bookSearchResults: List<SearchHitResponse> = emptyList(),
        val isSearchingBooks: Boolean = false,
        // In-flight mapping tracking (#140)
        val mappingInFlightUsers: Set<String> = emptySet(),
        val mappingInFlightBooks: Set<String> = emptySet(),
        // Sessions tab
        val sessionsResponse: ABSSessionsResponse? = null,
        val sessionsFilter: SessionStatusFilter = SessionStatusFilter.ALL,
        val isLoadingSessions: Boolean = false,
        val isImportingSessions: Boolean = false,
        val importResult: ImportSessionsResult? = null,
        // Transient mutation-failure error (snackbar).
        val error: String? = null,
    ) : ABSImportHubUiState

    /** `openImport` failed before the detail rendered; terminal state. */
    data class Error(
        val message: String,
    ) : ABSImportHubUiState
}

/**
 * ViewModel for the persistent ABS import hub.
 *
 * Manages:
 * - List of all imports (resumable)
 * - Creating new imports
 * - Detail view for a specific import
 * - User/book mapping within an import
 * - Session import
 */
class ABSImportHubViewModel(
    private val absImportApi: ABSImportApiContract,
    private val searchApi: SearchApiContract,
    private val syncRepository: SyncRepository,
) : ViewModel() {
    val listState: StateFlow<ABSImportListUiState>
        field = MutableStateFlow<ABSImportListUiState>(ABSImportListUiState.Loading)

    val hubState: StateFlow<ABSImportHubUiState>
        field = MutableStateFlow<ABSImportHubUiState>(ABSImportHubUiState.Loading)

    private var userSearchJob: Job? = null
    private var bookSearchJob: Job? = null
    private var analysisPollingJob: Job? = null

    init {
        loadImports()
    }

    // === Import List ===

    fun loadImports() {
        viewModelScope.launch {
            when (val result = absImportApi.listImports()) {
                is Success -> {
                    listState.update { current ->
                        if (current is ABSImportListUiState.Ready) {
                            current.copy(imports = result.data, error = null)
                        } else {
                            ABSImportListUiState.Ready(imports = result.data)
                        }
                    }
                }

                is Failure -> {
                    logger.error { "Failed to load imports: ${result.message}" }
                    val message = "Failed to load imports"
                    listState.update { current ->
                        if (current is ABSImportListUiState.Ready) {
                            current.copy(error = message)
                        } else {
                            ABSImportListUiState.Error(message)
                        }
                    }
                }
            }
        }
    }

    fun createImport(
        fileSource: FileSource,
        name: String,
    ) {
        viewModelScope.launch {
            updateListReady { it.copy(isCreating = true, error = null) }
            when (val result = absImportApi.createImport(fileSource, name)) {
                is Success -> {
                    updateListReady { it.copy(isCreating = false) }
                    loadImports() // Refresh list
                    if (result.data.status == IMPORT_STATUS_ANALYZING) {
                        startAnalysisPolling(result.data.id)
                    }
                }

                is Failure -> {
                    val detail = createImportFailureDetail(result.message)
                    logger.error { detail }
                    updateListReady { it.copy(isCreating = false, error = detail) }
                }
            }
        }
    }

    /**
     * Create an import and return the import ID.
     * Used by the upload sheet to navigate to the import hub after upload.
     */
    suspend fun createImportAndGetId(
        fileSource: FileSource,
        name: String,
    ): AppResult<String> =
        when (val result = absImportApi.createImport(fileSource, name)) {
            is Success -> {
                loadImports() // Refresh list in background
                if (result.data.status == IMPORT_STATUS_ANALYZING) {
                    startAnalysisPolling(result.data.id)
                }
                Success(result.data.id)
            }

            is Failure -> {
                val detail = createImportFailureDetail(result.message)
                logger.error { detail }
                result
            }
        }

    fun createImportFromPath(
        backupPath: String,
        name: String,
    ) {
        viewModelScope.launch {
            updateListReady { it.copy(isCreating = true, error = null) }
            when (val result = absImportApi.createImportFromPath(backupPath, name)) {
                is Success -> {
                    updateListReady { it.copy(isCreating = false) }
                    loadImports() // Refresh list
                    if (result.data.status == IMPORT_STATUS_ANALYZING) {
                        startAnalysisPolling(result.data.id)
                    }
                }

                is Failure -> {
                    val detail = createImportFailureDetail(result.message)
                    logger.error { detail }
                    updateListReady { it.copy(isCreating = false, error = detail) }
                }
            }
        }
    }

    fun deleteImport(importId: String) {
        viewModelScope.launch {
            when (val result = absImportApi.deleteImport(importId)) {
                is Success -> {
                    loadImports()
                }

                is Failure -> {
                    logger.error { "Failed to delete import: ${result.message}" }
                    updateListReady { it.copy(error = "Failed to delete import") }
                }
            }
        }
    }

    // === Import Hub (Detail View) ===

    fun openImport(importId: String) {
        // Re-enter Loading for the new import (covers switching between imports).
        hubState.value = ABSImportHubUiState.Loading
        viewModelScope.launch {
            when (val result = absImportApi.getImport(importId)) {
                is Success -> {
                    hubState.value =
                        ABSImportHubUiState.Ready(
                            importId = importId,
                            import = result.data,
                        )
                    // Load initial data for the active tab
                    loadTabData(ImportHubTab.OVERVIEW)
                    // Start polling if analysis is still in progress (covers WorkManager upload flow)
                    if (result.data.status == IMPORT_STATUS_ANALYZING) {
                        startAnalysisPolling(importId)
                    }
                    // If this import isn't in the list yet (newly created via background upload),
                    // reload the list so the user sees it when they navigate back
                    val listReady = listState.value as? ABSImportListUiState.Ready
                    if (listReady == null || listReady.imports.none { it.id == importId }) {
                        loadImports()
                    }
                }

                is Failure -> {
                    logger.error { "Failed to load import: ${result.message}" }
                    hubState.value = ABSImportHubUiState.Error("Failed to load import")
                }
            }
        }
    }

    fun setActiveTab(tab: ImportHubTab) {
        updateHubReady { it.copy(activeTab = tab) }
        loadTabData(tab)
    }

    private fun loadTabData(tab: ImportHubTab) {
        when (tab) {
            ImportHubTab.OVERVIEW -> refreshImport()
            ImportHubTab.USERS -> loadUsers()
            ImportHubTab.BOOKS -> loadBooks()
            ImportHubTab.SESSIONS -> loadSessions()
        }
    }

    private fun refreshImport() {
        val ready = hubState.value as? ABSImportHubUiState.Ready ?: return
        val importId = ready.importId
        if (importId.isEmpty()) return

        viewModelScope.launch {
            when (val result = absImportApi.getImport(importId)) {
                is Success -> {
                    updateHubReady { it.copy(import = result.data) }
                }

                is Failure -> {
                    logger.error { "Failed to refresh import: ${result.message}" }
                }
            }
        }
    }

    // === Users Tab ===

    fun setUsersFilter(filter: MappingFilter) {
        updateHubReady { it.copy(usersFilter = filter) }
        loadUsers()
    }

    private fun loadUsers() {
        val ready = hubState.value as? ABSImportHubUiState.Ready ?: return
        val importId = ready.importId
        if (importId.isEmpty()) return

        viewModelScope.launch {
            updateHubReady { it.copy(isLoadingUsers = true) }
            when (val result = absImportApi.listImportUsers(importId, ready.usersFilter)) {
                is Success -> {
                    updateHubReady { it.copy(users = result.data, isLoadingUsers = false) }
                }

                is Failure -> {
                    logger.error { "Failed to load users: ${result.message}" }
                    updateHubReady { it.copy(isLoadingUsers = false, error = "Failed to load users") }
                }
            }
        }
    }

    fun activateUserSearch(absUserId: String) {
        updateHubReady {
            it.copy(
                activeSearchAbsUserId = absUserId,
                userSearchQuery = "",
                userSearchResults = emptyList(),
                isSearchingUsers = false,
            )
        }
    }

    fun deactivateUserSearch() {
        userSearchJob?.cancel()
        updateHubReady {
            it.copy(
                activeSearchAbsUserId = null,
                userSearchQuery = "",
                userSearchResults = emptyList(),
                isSearchingUsers = false,
            )
        }
    }

    fun updateUserSearchQuery(query: String) {
        updateHubReady { it.copy(userSearchQuery = query) }
        userSearchJob?.cancel()

        if (query.length < MIN_SEARCH_QUERY_LEN) {
            updateHubReady { it.copy(userSearchResults = emptyList(), isSearchingUsers = false) }
            return
        }

        userSearchJob =
            viewModelScope.launch {
                delay(SEARCH_DEBOUNCE_MS)
                updateHubReady { it.copy(isSearchingUsers = true) }
                when (val result = absImportApi.searchUsers(query, limit = SEARCH_LIMIT)) {
                    is Success -> {
                        updateHubReady {
                            it.copy(userSearchResults = result.data, isSearchingUsers = false)
                        }
                    }

                    is Failure -> {
                        logger.error { "User search failed: ${result.message}" }
                        updateHubReady {
                            it.copy(userSearchResults = emptyList(), isSearchingUsers = false)
                        }
                    }
                }
            }
    }

    fun mapUser(
        absUserId: String,
        listenUpId: String,
    ) {
        val ready = hubState.value as? ABSImportHubUiState.Ready ?: return
        val importId = ready.importId
        if (importId.isEmpty()) return

        updateHubReady { it.copy(mappingInFlightUsers = it.mappingInFlightUsers + absUserId) }
        viewModelScope.launch {
            when (val result = absImportApi.mapUser(importId, absUserId, listenUpId)) {
                is Success -> {
                    deactivateUserSearch()
                    updateHubReady { state ->
                        state.copy(
                            users =
                                state.users.map { user ->
                                    if (user.absUserId == absUserId) result.data else user
                                },
                            mappingInFlightUsers = state.mappingInFlightUsers - absUserId,
                        )
                    }
                    refreshImport()
                }

                is Failure -> {
                    logger.error { "Failed to map user: ${result.message}" }
                    updateHubReady {
                        it.copy(
                            error = "Failed to map user",
                            mappingInFlightUsers = it.mappingInFlightUsers - absUserId,
                        )
                    }
                }
            }
        }
    }

    fun clearUserMapping(absUserId: String) {
        val ready = hubState.value as? ABSImportHubUiState.Ready ?: return
        val importId = ready.importId
        if (importId.isEmpty()) return

        viewModelScope.launch {
            when (val result = absImportApi.clearUserMapping(importId, absUserId)) {
                is Success -> {
                    updateHubReady { state ->
                        state.copy(
                            users =
                                state.users.map { user ->
                                    if (user.absUserId == absUserId) result.data else user
                                },
                        )
                    }
                    refreshImport()
                }

                is Failure -> {
                    logger.error { "Failed to clear user mapping: ${result.message}" }
                    updateHubReady { it.copy(error = "Failed to clear mapping") }
                }
            }
        }
    }

    // === Books Tab ===

    fun setBooksFilter(filter: MappingFilter) {
        updateHubReady { it.copy(booksFilter = filter) }
        loadBooks()
    }

    private fun loadBooks() {
        val ready = hubState.value as? ABSImportHubUiState.Ready ?: return
        val importId = ready.importId
        if (importId.isEmpty()) return

        viewModelScope.launch {
            updateHubReady { it.copy(isLoadingBooks = true) }
            when (val result = absImportApi.listImportBooks(importId, ready.booksFilter)) {
                is Success -> {
                    updateHubReady { it.copy(books = result.data, isLoadingBooks = false) }
                }

                is Failure -> {
                    logger.error { "Failed to load books: ${result.message}" }
                    updateHubReady { it.copy(isLoadingBooks = false, error = "Failed to load books") }
                }
            }
        }
    }

    fun activateBookSearch(absMediaId: String) {
        updateHubReady {
            it.copy(
                activeSearchAbsMediaId = absMediaId,
                bookSearchQuery = "",
                bookSearchResults = emptyList(),
                isSearchingBooks = false,
            )
        }
    }

    fun deactivateBookSearch() {
        bookSearchJob?.cancel()
        updateHubReady {
            it.copy(
                activeSearchAbsMediaId = null,
                bookSearchQuery = "",
                bookSearchResults = emptyList(),
                isSearchingBooks = false,
            )
        }
    }

    fun updateBookSearchQuery(query: String) {
        updateHubReady { it.copy(bookSearchQuery = query) }
        bookSearchJob?.cancel()

        if (query.length < MIN_SEARCH_QUERY_LEN) {
            updateHubReady { it.copy(bookSearchResults = emptyList(), isSearchingBooks = false) }
            return
        }

        bookSearchJob =
            viewModelScope.launch {
                delay(SEARCH_DEBOUNCE_MS)
                updateHubReady { it.copy(isSearchingBooks = true) }
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
                    updateHubReady {
                        it.copy(bookSearchResults = response.hits, isSearchingBooks = false)
                    }
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ErrorBus.emit(e)
                    logger.error(e) { "Book search failed" }
                    updateHubReady {
                        it.copy(bookSearchResults = emptyList(), isSearchingBooks = false)
                    }
                }
            }
    }

    fun mapBook(
        absMediaId: String,
        listenUpId: String,
    ) {
        val ready = hubState.value as? ABSImportHubUiState.Ready ?: return
        val importId = ready.importId
        if (importId.isEmpty()) return

        updateHubReady { it.copy(mappingInFlightBooks = it.mappingInFlightBooks + absMediaId) }
        viewModelScope.launch {
            when (val result = absImportApi.mapBook(importId, absMediaId, listenUpId)) {
                is Success -> {
                    deactivateBookSearch()
                    updateHubReady { state ->
                        state.copy(
                            books =
                                state.books.map { book ->
                                    if (book.absMediaId == absMediaId) result.data else book
                                },
                            mappingInFlightBooks = state.mappingInFlightBooks - absMediaId,
                        )
                    }
                    refreshImport()
                }

                is Failure -> {
                    logger.error { "Failed to map book: ${result.message}" }
                    updateHubReady {
                        it.copy(
                            error = "Failed to map book",
                            mappingInFlightBooks = it.mappingInFlightBooks - absMediaId,
                        )
                    }
                }
            }
        }
    }

    fun clearBookMapping(absMediaId: String) {
        val ready = hubState.value as? ABSImportHubUiState.Ready ?: return
        val importId = ready.importId
        if (importId.isEmpty()) return

        viewModelScope.launch {
            when (val result = absImportApi.clearBookMapping(importId, absMediaId)) {
                is Success -> {
                    updateHubReady { state ->
                        state.copy(
                            books =
                                state.books.map { book ->
                                    if (book.absMediaId == absMediaId) result.data else book
                                },
                        )
                    }
                    refreshImport()
                }

                is Failure -> {
                    logger.error { "Failed to clear book mapping: ${result.message}" }
                    updateHubReady { it.copy(error = "Failed to clear mapping") }
                }
            }
        }
    }

    // === Sessions Tab ===

    fun setSessionsFilter(filter: SessionStatusFilter) {
        updateHubReady { it.copy(sessionsFilter = filter) }
        loadSessions()
    }

    private fun loadSessions() {
        val ready = hubState.value as? ABSImportHubUiState.Ready ?: return
        val importId = ready.importId
        if (importId.isEmpty()) return

        viewModelScope.launch {
            updateHubReady { it.copy(isLoadingSessions = true) }
            when (val result = absImportApi.listSessions(importId, ready.sessionsFilter)) {
                is Success -> {
                    updateHubReady { it.copy(sessionsResponse = result.data, isLoadingSessions = false) }
                }

                is Failure -> {
                    logger.error { "Failed to load sessions: ${result.message}" }
                    updateHubReady { it.copy(isLoadingSessions = false, error = "Failed to load sessions") }
                }
            }
        }
    }

    fun importReadySessions() {
        val ready = hubState.value as? ABSImportHubUiState.Ready ?: return
        val importId = ready.importId
        if (importId.isEmpty()) return

        viewModelScope.launch {
            updateHubReady { it.copy(isImportingSessions = true, importResult = null) }
            when (val result = absImportApi.importReadySessions(importId)) {
                is Success -> {
                    updateHubReady {
                        it.copy(isImportingSessions = false, importResult = result.data)
                    }
                    loadSessions()
                    refreshImport()
                    // Refresh listening history to pull imported events
                    syncRepository.refreshListeningHistory()
                }

                is Failure -> {
                    logger.error { "Failed to import sessions: ${result.message}" }
                    updateHubReady {
                        it.copy(isImportingSessions = false, error = "Failed to import sessions")
                    }
                }
            }
        }
    }

    fun skipSession(
        sessionId: String,
        reason: String? = null,
    ) {
        val ready = hubState.value as? ABSImportHubUiState.Ready ?: return
        val importId = ready.importId
        if (importId.isEmpty()) return

        viewModelScope.launch {
            when (val result = absImportApi.skipSession(importId, sessionId, reason)) {
                is Success -> {
                    loadSessions()
                    refreshImport()
                }

                is Failure -> {
                    logger.error { "Failed to skip session: ${result.message}" }
                    updateHubReady { it.copy(error = "Failed to skip session") }
                }
            }
        }
    }

    fun clearError() {
        updateListReady { it.copy(error = null) }
        updateHubReady { it.copy(error = null) }
    }

    fun clearImportResult() {
        updateHubReady { it.copy(importResult = null) }
    }

    private fun startAnalysisPolling(importId: String) {
        analysisPollingJob?.cancel()
        analysisPollingJob =
            viewModelScope.launch {
                while (true) {
                    delay(ANALYSIS_POLL_INTERVAL_MS)
                    when (val result = absImportApi.getImport(importId)) {
                        is Success -> {
                            val imp = result.data
                            // Update hub state if we're viewing this import
                            val currentHub = hubState.value
                            if (currentHub is ABSImportHubUiState.Ready && currentHub.importId == importId) {
                                updateHubReady { it.copy(import = imp) }
                            }
                            // Update list state
                            updateListReady { state ->
                                state.copy(
                                    imports =
                                        state.imports.map { summary ->
                                            if (summary.id == importId) {
                                                ABSImportSummary(
                                                    id = imp.id,
                                                    name = imp.name,
                                                    status = imp.status,
                                                    createdAt = imp.createdAt,
                                                    updatedAt = imp.updatedAt,
                                                    totalUsers = imp.totalUsers,
                                                    totalBooks = imp.totalBooks,
                                                    totalSessions = imp.totalSessions,
                                                    usersMapped = imp.usersMapped,
                                                    booksMapped = imp.booksMapped,
                                                    sessionsImported = imp.sessionsImported,
                                                )
                                            } else {
                                                summary
                                            }
                                        },
                                )
                            }
                            if (imp.status != IMPORT_STATUS_ANALYZING) {
                                logger.info { "Import $importId analysis complete: ${imp.status}" }
                                break
                            }
                        }

                        is Failure -> {
                            logger.error { "Failed to poll import status: ${result.message}" }
                            break
                        }
                    }
                }
            }
    }

    override fun onCleared() {
        super.onCleared()
        analysisPollingJob?.cancel()
    }

    /**
     * Apply [transform] to list state only if it is currently
     * [ABSImportListUiState.Ready]. No-ops otherwise.
     */
    private fun updateListReady(transform: (ABSImportListUiState.Ready) -> ABSImportListUiState.Ready) {
        listState.update { current ->
            if (current is ABSImportListUiState.Ready) transform(current) else current
        }
    }

    /**
     * Apply [transform] to hub state only if it is currently
     * [ABSImportHubUiState.Ready]. No-ops otherwise.
     */
    private fun updateHubReady(transform: (ABSImportHubUiState.Ready) -> ABSImportHubUiState.Ready) {
        hubState.update { current ->
            if (current is ABSImportHubUiState.Ready) transform(current) else current
        }
    }
}
