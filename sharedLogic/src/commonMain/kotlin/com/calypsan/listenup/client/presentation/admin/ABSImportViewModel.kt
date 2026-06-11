package com.calypsan.listenup.client.presentation.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.data.remote.ABSImportApiContract
import com.calypsan.listenup.client.data.remote.BackupApiContract
import com.calypsan.listenup.client.data.remote.SearchApiContract
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.presentation.admin.absimport.AnalysisDelegate
import com.calypsan.listenup.client.presentation.admin.absimport.BookMappingDelegate
import com.calypsan.listenup.client.presentation.admin.absimport.ImportDelegate
import com.calypsan.listenup.client.presentation.admin.absimport.SourceSelectionDelegate
import com.calypsan.listenup.client.presentation.admin.absimport.UserMappingDelegate
import com.calypsan.listenup.client.presentation.admin.absimport.updateReady
import com.calypsan.listenup.core.FileSource
import com.calypsan.listenup.core.error.ErrorBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel for ABS import flow.
 *
 * Supports two source types:
 * - LOCAL: User picks file from device, uploads to server, then analyzes
 * - REMOTE: User browses server filesystem, selects file, then analyzes
 *
 * Delegates to focused sub-classes:
 * - [AnalysisDelegate]: async backup analysis + polling
 * - [SourceSelectionDelegate]: local/remote file selection and upload
 * - [UserMappingDelegate]: inline user search and mapping
 * - [BookMappingDelegate]: inline book search and mapping
 * - [ImportDelegate]: import options + performImport execution
 */
@Suppress("TooManyFunctions")
class ABSImportViewModel(
    private val backupApi: BackupApiContract,
    private val searchApi: SearchApiContract,
    private val absImportApi: ABSImportApiContract,
    private val syncRepository: SyncRepository,
    private val errorBus: ErrorBus,
) : ViewModel() {
    val state: StateFlow<ABSImportUiState>
        field = MutableStateFlow<ABSImportUiState>(ABSImportUiState.Ready())

    private val analysisDelegate = AnalysisDelegate(viewModelScope, state, errorBus, backupApi)
    private val sourceDelegate =
        SourceSelectionDelegate(
            viewModelScope,
            state,
            errorBus,
            backupApi,
            onReadyToAnalyze = analysisDelegate::analyze,
        )
    private val userMappingDelegate = UserMappingDelegate(viewModelScope, state, errorBus, absImportApi)
    private val bookMappingDelegate = BookMappingDelegate(viewModelScope, state, errorBus, searchApi)
    private val importDelegate = ImportDelegate(viewModelScope, state, errorBus, backupApi, syncRepository)

    // === Source Selection ===

    fun selectSourceType(type: ABSSourceType) = sourceDelegate.selectSourceType(type)

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
    ) = sourceDelegate.setLocalFile(fileSource, filename, size)

    /**
     * Clear the selected local file.
     */
    fun clearLocalFile() = sourceDelegate.clearLocalFile()

    /**
     * Upload the selected local file to the server and proceed to analysis.
     *
     * Uses streaming upload to avoid loading the entire file into memory.
     */
    fun uploadAndAnalyze() = sourceDelegate.uploadAndAnalyze()

    // === Remote File Browser ===

    /**
     * Load directory contents for the file browser.
     */
    fun loadDirectory(path: String) = sourceDelegate.loadDirectory(path)

    /**
     * Navigate up to parent directory.
     */
    fun navigateUp() = sourceDelegate.navigateUp()

    /**
     * Set the remote file path and proceed to analysis.
     * User enters filename within the current directory.
     */
    fun setRemoteFilePath(filename: String) = sourceDelegate.setRemoteFilePath(filename)

    /**
     * Set the full remote path directly and proceed to analysis.
     */
    fun setFullRemotePath(path: String) = sourceDelegate.setFullRemotePath(path)

    // === Mapping ===

    fun setUserMapping(
        absUserId: String,
        listenupUserId: String?,
    ) = userMappingDelegate.setUserMapping(absUserId, listenupUserId)

    fun setBookMapping(
        absItemId: String,
        listenupBookId: String?,
    ) = bookMappingDelegate.setBookMapping(absItemId, listenupBookId)

    // === User Mapping Tab ===

    /**
     * Set the active tab in the user mapping step.
     */
    fun setUserMappingTab(tab: UserMappingTab) = userMappingDelegate.setUserMappingTab(tab)

    // === Inline User Search ===

    /**
     * Called when a user search field gains focus.
     * Activates search for that specific user and clears previous search state.
     */
    fun activateUserSearch(absUserId: String) = userMappingDelegate.activateUserSearch(absUserId)

    /**
     * Called when a user search field loses focus.
     * Clears the active search state.
     */
    fun deactivateUserSearch() = userMappingDelegate.deactivateUserSearch()

    /**
     * Update search query for the active user search field.
     */
    fun updateUserSearchQuery(query: String) = userMappingDelegate.updateUserSearchQuery(query)

    /**
     * Select a user from search results or suggestions and apply the mapping.
     */
    fun selectUser(
        absUserId: String,
        userId: String,
        email: String,
        displayName: String?,
    ) = userMappingDelegate.selectUser(absUserId, userId, email, displayName)

    /**
     * Clear the user mapping for an ABS user (allows re-searching).
     */
    fun clearUserMapping(absUserId: String) = userMappingDelegate.clearUserMapping(absUserId)

    // === Book Mapping Tab ===

    /**
     * Set the active tab in the book mapping step.
     */
    fun setBookMappingTab(tab: BookMappingTab) = bookMappingDelegate.setBookMappingTab(tab)

    // === Inline Book Search ===

    /**
     * Called when a book search field gains focus.
     * Activates search for that specific book and clears previous search state.
     */
    fun activateBookSearch(absItemId: String) = bookMappingDelegate.activateBookSearch(absItemId)

    /**
     * Called when a book search field loses focus.
     * Clears the active search state.
     */
    fun deactivateBookSearch() = bookMappingDelegate.deactivateBookSearch()

    /**
     * Update search query for the active book search field.
     */
    fun updateBookSearchQuery(query: String) = bookMappingDelegate.updateBookSearchQuery(query)

    /**
     * Select a book from search results or suggestions and apply the mapping.
     */
    fun selectBook(
        absItemId: String,
        bookId: String,
        title: String,
        author: String?,
        durationMs: Long?,
    ) = bookMappingDelegate.selectBook(absItemId, bookId, title, author, durationMs)

    /**
     * Clear the book mapping for an ABS item (allows re-searching).
     */
    fun clearBookMapping(absItemId: String) = bookMappingDelegate.clearBookMapping(absItemId)

    // === Import Options ===

    fun setImportSessions(value: Boolean) = importDelegate.setImportSessions(value)

    fun setImportProgress(value: Boolean) = importDelegate.setImportProgress(value)

    fun setRebuildProgress(value: Boolean) = importDelegate.setRebuildProgress(value)

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
                importDelegate.performImport()
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

    fun clearError() {
        state.updateReady { it.copy(error = null) }
    }
}
