package com.calypsan.listenup.client.presentation.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.core.Failure
import com.calypsan.listenup.core.Success
import com.calypsan.listenup.core.error.ErrorBus
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.domain.model.Collection
import com.calypsan.listenup.client.domain.repository.CollectionBookSummary
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import com.calypsan.listenup.client.domain.repository.CollectionShareSummary
import com.calypsan.listenup.client.domain.usecase.collection.GetUsersForSharingUseCase
import com.calypsan.listenup.client.domain.usecase.collection.LoadCollectionBooksUseCase
import com.calypsan.listenup.client.domain.usecase.collection.LoadCollectionSharesUseCase
import com.calypsan.listenup.client.domain.usecase.collection.RemoveBookFromCollectionUseCase
import com.calypsan.listenup.client.domain.usecase.collection.RemoveCollectionShareUseCase
import com.calypsan.listenup.client.domain.usecase.collection.ShareCollectionUseCase
import com.calypsan.listenup.client.domain.usecase.collection.UpdateCollectionNameUseCase
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * ViewModel for the admin collection detail screen.
 *
 * Manages viewing and editing a single collection, including:
 * - Loading collection details
 * - Editing the collection name
 * - Displaying and removing books in the collection
 * - Managing collection members (sharing)
 */
class AdminCollectionDetailViewModel(
    private val collectionId: String,
    private val collectionRepository: CollectionRepository,
    private val loadCollectionBooksUseCase: LoadCollectionBooksUseCase,
    private val loadCollectionSharesUseCase: LoadCollectionSharesUseCase,
    private val updateCollectionNameUseCase: UpdateCollectionNameUseCase,
    private val removeBookFromCollectionUseCase: RemoveBookFromCollectionUseCase,
    private val shareCollectionUseCase: ShareCollectionUseCase,
    private val removeCollectionShareUseCase: RemoveCollectionShareUseCase,
    private val getUsersForSharingUseCase: GetUsersForSharingUseCase,
    private val errorBus: ErrorBus,
) : ViewModel() {
    val state: StateFlow<AdminCollectionDetailUiState>
        field = MutableStateFlow<AdminCollectionDetailUiState>(AdminCollectionDetailUiState.Loading)

    init {
        loadCollection()
    }

    /**
     * Load the collection details, then fetch its books and shares.
     *
     * Drives the terminal Loading -> Ready | Error transition. Subsequent
     * refreshes after reaching Ready surface failures via the transient
     * `error` field on [AdminCollectionDetailUiState.Ready].
     *
     * `getById` is Room-first (plain suspend returning nullable) — a local
     * DAO exception (e.g. corrupt DB) is caught and surfaces as [AdminCollectionDetailUiState.Error].
     * `getCollectionFromServer` now returns [AppResult] and is handled with a `when` branch.
     */
    private fun loadCollection() {
        viewModelScope.launch {
            // Room-first: try local DB first (plain suspend, no AppResult);
            // catch DAO-level exceptions so a corrupt DB surfaces as Error state.
            val localCollection =
                try {
                    collectionRepository.getById(collectionId)
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errorBus.emit(
                        com.calypsan.listenup.core.error.ErrorMapper
                            .map(e),
                    )
                    logger.error(e) { "Failed to load collection from local DB: $collectionId" }
                    val message = e.message ?: "Failed to load collection"
                    state.update { current ->
                        if (current is AdminCollectionDetailUiState.Ready) {
                            current.copy(error = message)
                        } else {
                            AdminCollectionDetailUiState.Error(message)
                        }
                    }
                    return@launch
                }

            val collection =
                if (localCollection != null) {
                    localCollection
                } else {
                    // Fall back to server — now returns AppResult
                    when (val result = collectionRepository.getCollectionFromServer(collectionId)) {
                        is Success -> {
                            result.data
                        }

                        is Failure -> {
                            errorBus.emit(result.error)
                            logger.error { "Failed to load collection from server: $collectionId" }
                            val message = result.error.message
                            state.update { current ->
                                if (current is AdminCollectionDetailUiState.Ready) {
                                    current.copy(error = message)
                                } else {
                                    AdminCollectionDetailUiState.Error(message)
                                }
                            }
                            return@launch
                        }
                    }
                }

            state.update { current ->
                if (current is AdminCollectionDetailUiState.Ready) {
                    current.copy(
                        collection = collection,
                        editedName = collection.name,
                        error = null,
                    )
                } else {
                    AdminCollectionDetailUiState.Ready(
                        collection = collection,
                        editedName = collection.name,
                    )
                }
            }

            // Load books and shares sequentially (pipeline updates Ready as results arrive).
            loadBooks()
            loadShares()
        }
    }

    /**
     * Load shares for the collection via use case.
     * The use case enriches shares with user information.
     *
     * Non-fatal: failures are logged and leave shares empty rather than
     * failing the whole screen.
     */
    private suspend fun loadShares() {
        when (val result = loadCollectionSharesUseCase(collectionId)) {
            is Success -> {
                updateReady { it.copy(shares = result.data.map { share -> share.toShareItem() }) }
            }

            is Failure -> {
                logger.warn { "Failed to load shares for collection: $collectionId - ${result.message}" }
                // Don't fail the whole screen - just show empty shares
            }
        }
    }

    /**
     * Load books in the collection via use case.
     *
     * Non-fatal: failures are logged and leave books empty rather than
     * failing the whole screen.
     */
    private suspend fun loadBooks() {
        when (val result = loadCollectionBooksUseCase(collectionId)) {
            is Success -> {
                updateReady { it.copy(books = result.data.map { book -> book.toBookItem() }) }
            }

            is Failure -> {
                logger.warn { "Failed to load books for collection: $collectionId - ${result.message}" }
                // Don't fail the whole screen - just show empty books
            }
        }
    }

    /**
     * Update the edited name field.
     */
    fun updateName(name: String) {
        updateReady { it.copy(editedName = name) }
    }

    /**
     * Save the edited collection name.
     */
    fun saveName() {
        val ready = state.value as? AdminCollectionDetailUiState.Ready ?: return
        val newName = ready.editedName.trim()

        if (newName.isBlank()) {
            updateReady { it.copy(error = "Collection name cannot be empty") }
            return
        }

        if (newName == ready.collection.name) {
            // No change needed
            updateReady { it.copy(saveSuccess = true) }
            return
        }

        viewModelScope.launch {
            updateReady { it.copy(isSaving = true) }

            when (val result = updateCollectionNameUseCase(collectionId, newName)) {
                is Success -> {
                    logger.info { "Updated collection name: ${result.data.name}" }
                    // SSE will update the local database
                    updateReady {
                        it.copy(
                            isSaving = false,
                            saveSuccess = true,
                            collection = result.data,
                        )
                    }
                }

                is Failure -> {
                    logger.error { "Failed to update collection name: ${result.message}" }
                    errorBus.emit(result.error)
                    updateReady {
                        it.copy(
                            isSaving = false,
                            error = result.message,
                        )
                    }
                }
            }
        }
    }

    /**
     * Remove a book from the collection.
     */
    fun removeBook(bookId: String) {
        if (state.value !is AdminCollectionDetailUiState.Ready) return
        viewModelScope.launch {
            updateReady { it.copy(removingBookId = bookId) }

            when (val result = removeBookFromCollectionUseCase(collectionId, bookId)) {
                is Success -> {
                    logger.info { "Removed book $bookId from collection $collectionId" }

                    // Optimistically update the book list and count
                    updateReady { ready ->
                        ready.copy(
                            removingBookId = null,
                            books = ready.books.filterNot { it.id == bookId },
                            collection =
                                ready.collection.copy(
                                    bookCount = (ready.collection.bookCount - 1).coerceAtLeast(0),
                                ),
                        )
                    }
                }

                is Failure -> {
                    logger.error { "Failed to remove book from collection: ${result.message}" }
                    errorBus.emit(result.error)
                    updateReady {
                        it.copy(
                            removingBookId = null,
                            error = result.message,
                        )
                    }
                }
            }
        }
    }

    /**
     * Load users available for sharing.
     */
    fun loadUsersForSharing() {
        if (state.value !is AdminCollectionDetailUiState.Ready) return
        viewModelScope.launch {
            updateReady { it.copy(isLoadingUsers = true) }

            when (val result = getUsersForSharingUseCase(collectionId)) {
                is Success -> {
                    updateReady {
                        it.copy(
                            isLoadingUsers = false,
                            availableUsers = result.data,
                        )
                    }
                }

                is Failure -> {
                    logger.error { "Failed to load users: ${result.message}" }
                    errorBus.emit(result.error)
                    updateReady {
                        it.copy(
                            isLoadingUsers = false,
                            error = result.message,
                        )
                    }
                }
            }
        }
    }

    /**
     * Share the collection with a user.
     */
    fun shareWithUser(userId: String) {
        val ready = state.value as? AdminCollectionDetailUiState.Ready ?: return
        viewModelScope.launch {
            updateReady { it.copy(isSharing = true) }

            when (val result = shareCollectionUseCase(collectionId, userId)) {
                is Success -> {
                    logger.info { "Shared collection with user: $userId" }

                    // Get user details from the available users list to enrich the share
                    val user = ready.availableUsers.find { it.id == userId }
                    val enrichedShare =
                        result.data.copy(
                            userName =
                                user?.let {
                                    it.displayName?.takeIf { name -> name.isNotBlank() }
                                        ?: "${it.firstName ?: ""} ${it.lastName ?: ""}".trim().takeIf { name ->
                                            name.isNotBlank()
                                        }
                                        ?: it.email
                                } ?: result.data.userName,
                            userEmail = user?.email ?: result.data.userEmail,
                        )

                    // Add to shares list
                    updateReady {
                        it.copy(
                            isSharing = false,
                            shares = it.shares + enrichedShare.toShareItem(),
                            showAddMemberSheet = false,
                        )
                    }
                }

                is Failure -> {
                    logger.error { "Failed to share collection: ${result.message}" }
                    errorBus.emit(result.error)
                    updateReady {
                        it.copy(
                            isSharing = false,
                            error = result.message,
                        )
                    }
                }
            }
        }
    }

    /**
     * Remove a share (unshare with user).
     */
    fun removeShare(shareId: String) {
        if (state.value !is AdminCollectionDetailUiState.Ready) return
        viewModelScope.launch {
            updateReady { it.copy(removingShareId = shareId) }

            when (val result = removeCollectionShareUseCase(shareId)) {
                is Success -> {
                    logger.info { "Removed share: $shareId" }

                    // Remove from shares list
                    updateReady { ready ->
                        ready.copy(
                            removingShareId = null,
                            shares = ready.shares.filterNot { it.id == shareId },
                        )
                    }
                }

                is Failure -> {
                    logger.error { "Failed to remove share: ${result.message}" }
                    errorBus.emit(result.error)
                    updateReady {
                        it.copy(
                            removingShareId = null,
                            error = result.message,
                        )
                    }
                }
            }
        }
    }

    /**
     * Show the add member sheet.
     */
    fun showAddMemberSheet() {
        if (state.value !is AdminCollectionDetailUiState.Ready) return
        updateReady { it.copy(showAddMemberSheet = true) }
        loadUsersForSharing()
    }

    /**
     * Hide the add member sheet.
     */
    fun hideAddMemberSheet() {
        updateReady { it.copy(showAddMemberSheet = false) }
    }

    /**
     * Clear the error state.
     */
    fun clearError() {
        updateReady { it.copy(error = null) }
    }

    /**
     * Clear the save success flag.
     */
    fun clearSaveSuccess() {
        updateReady { it.copy(saveSuccess = false) }
    }

    /**
     * Apply [transform] to state only if it is currently
     * [AdminCollectionDetailUiState.Ready]. No-ops when state is
     * [AdminCollectionDetailUiState.Loading] or [AdminCollectionDetailUiState.Error].
     */
    private fun updateReady(transform: (AdminCollectionDetailUiState.Ready) -> AdminCollectionDetailUiState.Ready) {
        state.update { current ->
            if (current is AdminCollectionDetailUiState.Ready) transform(current) else current
        }
    }

    /**
     * Convert domain model to UI model.
     */
    private fun CollectionShareSummary.toShareItem() =
        CollectionShareItem(
            id = id,
            userId = userId,
            userName = userName,
            userEmail = userEmail,
            permission = permission,
        )

    /**
     * Convert domain model to UI model.
     */
    private fun CollectionBookSummary.toBookItem() =
        CollectionBookItem(
            id = id,
            title = title,
            authorNames = "", // Not provided by API
            coverPath = coverPath,
        )
}

/**
 * UI state for the admin collection detail screen.
 *
 * Sealed hierarchy:
 * - [Loading] before the initial `loadCollection()` completes.
 * - [Ready] once the collection has loaded; carries the server-authoritative
 *   [collection] alongside the [editedName] edit buffer (dirty-tracking is
 *   derived in the screen as `editedName != collection.name`), server-backed
 *   [books]/[shares]/[availableUsers], action overlays
 *   (`isSaving`, `removingBookId`, `isSharing`, `removingShareId`,
 *   `isLoadingUsers`, `showAddMemberSheet`), the `saveSuccess` flag driving
 *   the post-save snackbar, and a transient `error` surfaced via snackbar.
 * - [Error] terminal state when the initial load fails; refresh failures
 *   after reaching [Ready] surface via the transient `error` field instead.
 */
sealed interface AdminCollectionDetailUiState {
    data object Loading : AdminCollectionDetailUiState

    /**
     * Collection has loaded; carries the canonical [collection], the [editedName] edit
     * buffer, books and shares lists, action overlays, and a transient `error`.
     */
    data class Ready(
        val collection: Collection,
        val editedName: String,
        val isSaving: Boolean = false,
        val saveSuccess: Boolean = false,
        val books: List<CollectionBookItem> = emptyList(),
        val removingBookId: String? = null,
        val error: String? = null,
        // Sharing state
        val shares: List<CollectionShareItem> = emptyList(),
        val showAddMemberSheet: Boolean = false,
        val isSharing: Boolean = false,
        val removingShareId: String? = null,
        val isLoadingUsers: Boolean = false,
        val availableUsers: List<AdminUserInfo> = emptyList(),
    ) : AdminCollectionDetailUiState {
        /**
         * True when [editedName] (trimmed) differs from the server's canonical name.
         * Drives the Save button's enabled state.
         */
        val isDirty: Boolean
            get() = editedName.trim() != collection.name && editedName.isNotBlank()
    }

    /** Terminal state when the initial collection load fails. */
    data class Error(
        val message: String,
    ) : AdminCollectionDetailUiState
}

/**
 * A book item in a collection.
 *
 * This is a simplified representation for the collection detail screen.
 * Full book details can be fetched by navigating to the book detail screen.
 */
data class CollectionBookItem(
    val id: String,
    val title: String,
    val authorNames: String,
    val coverPath: String?,
)

/**
 * A share item representing a user who has access to the collection.
 */
data class CollectionShareItem(
    val id: String,
    val userId: String,
    val userName: String,
    val userEmail: String,
    val permission: String,
)
