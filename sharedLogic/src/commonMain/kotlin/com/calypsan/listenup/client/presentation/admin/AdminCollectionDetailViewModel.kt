package com.calypsan.listenup.client.presentation.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.toListItem
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.domain.model.Collection
import com.calypsan.listenup.client.domain.model.CollectionBookItem
import com.calypsan.listenup.client.domain.model.CollectionShare
import com.calypsan.listenup.client.core.error.ErrorMapper
import com.calypsan.listenup.client.domain.model.SearchHit
import com.calypsan.listenup.client.domain.model.SearchHitType
import com.calypsan.listenup.client.domain.repository.AdminRepository
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.SearchRepository
import com.calypsan.listenup.client.domain.repository.UserProfileRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

private const val BOOK_SEARCH_DEBOUNCE_MS = 300L
private const val BOOK_SEARCH_LIMIT = 20

/**
 * ViewModel for the admin collection detail screen.
 *
 * Reads the collection, its books, and its shares reactively from the local Room
 * mirror via the [CollectionRepository] observation surface (the sync engine keeps
 * Room current). Rename / remove-book / share / revoke-share dispatch to the
 * repository, which forwards to the `CollectionService` RPC; the SSE echo updates
 * Room, so the screen refreshes itself — there are no optimistic UI mutations.
 *
 * "Available users to share with" is fetched on demand from [AdminRepository] and
 * filtered against the already-shared recipients and the current user.
 */
@Suppress("LongParameterList")
class AdminCollectionDetailViewModel internal constructor(
    private val collectionId: String,
    private val collectionRepository: CollectionRepository,
    private val adminRepository: AdminRepository,
    private val userRepository: UserRepository,
    private val userProfileRepository: UserProfileRepository,
    private val bookDao: BookDao,
    private val searchRepository: SearchRepository,
    private val imageStorage: ImageStorage,
    private val errorBus: ErrorBus,
) : ViewModel() {
    val state: StateFlow<AdminCollectionDetailUiState>
        field = MutableStateFlow<AdminCollectionDetailUiState>(AdminCollectionDetailUiState.Loading)

    // Tracks the in-flight Room hydration so a new collection book-id-set replaces the prior observation.
    private var hydrationJob: Job? = null

    // The id-set the live hydration is currently observing; guards against redundant re-subscription
    // when an unrelated combine emission (rename, share change) arrives with an unchanged id-set.
    private var lastHydratedIds: List<String>? = null

    init {
        observeCollection()
    }

    /**
     * Observe the collection, its book ids, and its shares together; transition
     * Loading → Ready on the first combined emission. If no collection with
     * [collectionId] is present in Room, surface a terminal Error.
     */
    private fun observeCollection() {
        viewModelScope.launch {
            try {
                combine(
                    collectionRepository.observeCollections(),
                    collectionRepository.observeCollectionBooks(collectionId),
                    collectionRepository.observeShares(collectionId),
                ) { collections, bookIds, shares ->
                    Triple(collections.firstOrNull { it.id == collectionId }, bookIds, shares)
                }.collect { (collection, bookIds, shares) ->
                    if (collection == null) {
                        state.update { current ->
                            if (current is AdminCollectionDetailUiState.Ready) {
                                current
                            } else {
                                AdminCollectionDetailUiState.Error("Collection not found")
                            }
                        }
                        return@collect
                    }
                    val shareItems = shares.map { it.toShareItem(resolveShareName(it.sharedWithUserId)) }
                    state.update { current ->
                        if (current is AdminCollectionDetailUiState.Ready) {
                            current.copy(
                                collection = collection,
                                shares = shareItems,
                            )
                        } else {
                            AdminCollectionDetailUiState.Ready(
                                collection = collection,
                                editedName = collection.name,
                                shares = shareItems,
                            )
                        }
                    }
                    hydrate(bookIds)
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Failed to observe collection: $collectionId" }
                state.value = AdminCollectionDetailUiState.Error(e.message ?: "Failed to load collection")
            }
        }
    }

    /**
     * Observe the Room projections for [ids] and fold them into [AdminCollectionDetailUiState.Ready.books].
     *
     * Books are emitted in collection-order so the list is stable regardless of Room's row order,
     * and ids with no Room row yet are simply omitted until they sync in. A new call cancels the
     * prior observation so the live set tracks the latest collection book-id-set.
     */
    private fun hydrate(ids: List<String>) {
        if (ids == lastHydratedIds) return
        lastHydratedIds = ids
        hydrationJob?.cancel()
        if (ids.isEmpty()) {
            updateReady { it.copy(books = emptyList()) }
            return
        }
        hydrationJob =
            viewModelScope.launch {
                bookDao.observeByIdsWithContributors(ids.map { BookId(it) }).collect { rows ->
                    val byId = rows.associateBy { it.book.id.value }
                    val books =
                        ids.mapNotNull { id ->
                            byId[id]?.toListItem(imageStorage)?.let { item ->
                                CollectionBookItem(
                                    id = item.id.value,
                                    title = item.title,
                                    author = item.authors.firstOrNull()?.name,
                                    coverPath = item.coverPath,
                                    durationMs = item.duration,
                                    coverHash = item.coverHash,
                                )
                            }
                        }
                    updateReady { it.copy(books = books) }
                }
            }
    }

    /** Update the edited-name buffer. */
    fun updateName(name: String) {
        updateReady { it.copy(editedName = name) }
    }

    /** Save the edited collection name via RPC. */
    fun saveName() {
        val ready = state.value as? AdminCollectionDetailUiState.Ready ?: return
        val newName = ready.editedName.trim()

        if (newName.isBlank()) {
            updateReady { it.copy(error = "Collection name cannot be empty") }
            return
        }
        if (newName == ready.collection.name) {
            updateReady { it.copy(saveSuccess = true) }
            return
        }

        viewModelScope.launch {
            updateReady { it.copy(isSaving = true) }
            when (val result = collectionRepository.rename(collectionId, newName)) {
                is AppResult.Success -> {
                    updateReady { it.copy(isSaving = false, saveSuccess = true) }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    updateReady { it.copy(isSaving = false, error = result.error.message) }
                }
            }
        }
    }

    /** Remove a book from the collection via RPC. */
    fun removeBook(bookId: String) {
        if (state.value !is AdminCollectionDetailUiState.Ready) return
        viewModelScope.launch {
            updateReady { it.copy(removingBookId = bookId) }
            when (val result = collectionRepository.removeBook(collectionId, bookId)) {
                is AppResult.Success -> {
                    updateReady { it.copy(removingBookId = null) }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    updateReady { it.copy(removingBookId = null, error = result.error.message) }
                }
            }
        }
    }

    private var bookSearchJob: Job? = null

    /** Open the "add books" search sheet. No-op for system collections (their membership is implicit). */
    fun openAddBooks() {
        val ready = state.value as? AdminCollectionDetailUiState.Ready ?: return
        if (ready.collection.isSystem) return
        updateReady { it.copy(showAddBooks = true) }
    }

    /** Close the "add books" search sheet and reset all search state. */
    fun closeAddBooks() {
        bookSearchJob?.cancel()
        updateReady {
            it.copy(
                showAddBooks = false,
                bookQuery = "",
                bookResults = emptyList(),
                isSearchingBooks = false,
            )
        }
    }

    /**
     * Debounced book-title search; excludes books already in this collection.
     *
     * Uses an imperative `Job + delay + cancel-previous` debounce deliberately: this VM mutates a
     * [MutableStateFlow] imperatively via [updateReady] (unlike [SearchViewModel]'s `stateIn` +
     * `.debounce()` flow pipeline), so the debounce lives in the launched job, not in the state flow.
     * A backend search failure is surfaced (logged + emitted to [errorBus]) rather than swallowed, so
     * it isn't indistinguishable from "no results"; results then collapse to empty.
     */
    fun onBookQueryChange(query: String) {
        updateReady { it.copy(bookQuery = query) }
        bookSearchJob?.cancel()
        if (query.isBlank()) {
            updateReady { it.copy(bookResults = emptyList(), isSearchingBooks = false) }
            return
        }
        bookSearchJob =
            viewModelScope.launch {
                delay(BOOK_SEARCH_DEBOUNCE_MS)
                updateReady { it.copy(isSearchingBooks = true) }
                val memberIds =
                    (state.value as? AdminCollectionDetailUiState.Ready)
                        ?.books
                        ?.map { it.id }
                        ?.toSet()
                        .orEmpty()
                val hits =
                    try {
                        searchRepository
                            .search(query = query, types = listOf(SearchHitType.BOOK), limit = BOOK_SEARCH_LIMIT)
                            .hits
                            .filter { it.id !in memberIds }
                    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                        throw e
                    } catch (
                        @Suppress("TooGenericExceptionCaught") e: Exception,
                    ) {
                        logger.error { "Book search failed for '$query'" }
                        @Suppress("DEPRECATION")
                        errorBus.emit(ErrorMapper.map(e))
                        emptyList()
                    }
                updateReady { it.copy(bookResults = hits, isSearchingBooks = false) }
            }
    }

    /** Add the searched book to this collection (additive — never touches All Books). */
    fun addBookFromSearch(bookId: String) {
        viewModelScope.launch {
            when (val result = collectionRepository.addBook(collectionId, bookId)) {
                is AppResult.Success -> {
                    updateReady { it.copy(bookResults = it.bookResults.filterNot { h -> h.id == bookId }) }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    updateReady { it.copy(error = result.error.message) }
                }
            }
        }
    }

    /** Load users available to share with: all users minus existing recipients and the current user. */
    fun loadUsersForSharing() {
        val ready = state.value as? AdminCollectionDetailUiState.Ready ?: return
        viewModelScope.launch {
            updateReady { it.copy(isLoadingUsers = true) }
            when (val result = adminRepository.getUsers()) {
                is AppResult.Success -> {
                    val sharedUserIds = ready.shares.map { it.userId }.toSet()
                    val currentUserId = userRepository.getCurrentUser()?.id?.value
                    val available =
                        result.data.filter { it.id !in sharedUserIds && it.id != currentUserId }
                    updateReady { it.copy(isLoadingUsers = false, availableUsers = available) }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    updateReady { it.copy(isLoadingUsers = false, error = result.error.message) }
                }
            }
        }
    }

    /** Share the collection with [userId] at read permission via RPC. */
    fun shareWithUser(userId: String) {
        if (state.value !is AdminCollectionDetailUiState.Ready) return
        viewModelScope.launch {
            updateReady { it.copy(isSharing = true) }
            when (val result = collectionRepository.share(collectionId, userId, SharePermission.Read)) {
                is AppResult.Success -> {
                    updateReady { it.copy(isSharing = false, showAddMemberSheet = false) }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    updateReady { it.copy(isSharing = false, error = result.error.message) }
                }
            }
        }
    }

    /** Revoke a share (by recipient user id) via RPC. */
    fun revokeShare(userId: String) {
        if (state.value !is AdminCollectionDetailUiState.Ready) return
        viewModelScope.launch {
            updateReady { it.copy(removingShareUserId = userId) }
            when (val result = collectionRepository.revokeShare(collectionId, userId)) {
                is AppResult.Success -> {
                    updateReady { it.copy(removingShareUserId = null) }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    updateReady { it.copy(removingShareUserId = null, error = result.error.message) }
                }
            }
        }
    }

    /** Show the add-member sheet and load candidate users. */
    fun showAddMemberSheet() {
        if (state.value !is AdminCollectionDetailUiState.Ready) return
        updateReady { it.copy(showAddMemberSheet = true) }
        loadUsersForSharing()
    }

    /** Hide the add-member sheet. */
    fun hideAddMemberSheet() {
        updateReady { it.copy(showAddMemberSheet = false) }
    }

    /** Clear the transient error state. */
    fun clearError() {
        updateReady { it.copy(error = null) }
    }

    /** Clear the save-success flag. */
    fun clearSaveSuccess() {
        updateReady { it.copy(saveSuccess = false) }
    }

    private fun updateReady(transform: (AdminCollectionDetailUiState.Ready) -> AdminCollectionDetailUiState.Ready) {
        state.update { current ->
            if (current is AdminCollectionDetailUiState.Ready) transform(current) else current
        }
    }

    private fun CollectionShare.toShareItem(displayName: String) =
        CollectionShareItem(
            id = id,
            userId = sharedWithUserId,
            displayName = displayName,
            permission = permission.name.lowercase(),
        )

    /**
     * Resolve a shared-with user's display name from the globally-synced `public_profiles` mirror,
     * falling back to the raw user id only until that row syncs (or if the name is blank). A share
     * record carries only the user id — names live in the separate `public_profiles` domain — so this
     * resolution is what stops the UI from showing a bare UUID for a collection member.
     */
    private suspend fun resolveShareName(userId: String): String =
        userProfileRepository
            .observeProfile(userId)
            .first()
            ?.displayName
            ?.ifBlank { null } ?: userId
}

/**
 * UI state for the admin collection detail screen.
 *
 * Sealed hierarchy:
 * - [Loading] before the first combined Room emission.
 * - [Ready] once the collection has loaded; carries the Room-authoritative
 *   [collection], the [editedName] edit buffer (`isDirty` derived), the book ids
 *   ([books]) and [shares], action overlays (`isSaving`, `removingBookId`,
 *   `isSharing`, `removingShareUserId`, `isLoadingUsers`, `showAddMemberSheet`),
 *   the `saveSuccess` flag, and a transient `error`.
 * - [Error] terminal state when the collection cannot be found or the pipeline fails.
 */
sealed interface AdminCollectionDetailUiState {
    data object Loading : AdminCollectionDetailUiState

    /** Collection loaded; carries the collection, edit buffer, books and shares, overlays, and a transient `error`. */
    data class Ready(
        val collection: Collection,
        val editedName: String,
        val isSaving: Boolean = false,
        val saveSuccess: Boolean = false,
        val books: List<CollectionBookItem> = emptyList(),
        val removingBookId: String? = null,
        val error: String? = null,
        val shares: List<CollectionShareItem> = emptyList(),
        val showAddMemberSheet: Boolean = false,
        val isSharing: Boolean = false,
        val removingShareUserId: String? = null,
        val isLoadingUsers: Boolean = false,
        val availableUsers: List<AdminUserInfo> = emptyList(),
        val showAddBooks: Boolean = false,
        val bookQuery: String = "",
        val bookResults: List<SearchHit> = emptyList(),
        val isSearchingBooks: Boolean = false,
    ) : AdminCollectionDetailUiState {
        /** True when [editedName] (trimmed) differs from the collection's canonical name. */
        val isDirty: Boolean
            get() = editedName.trim() != collection.name && editedName.isNotBlank()
    }

    /** Terminal state when the collection cannot be loaded. */
    data class Error(
        val message: String,
    ) : AdminCollectionDetailUiState
}

/**
 * A share item representing a user who has access to the collection.
 *
 * @property id The share record id.
 * @property userId The recipient user id (used to revoke the share).
 * @property permission The granted permission as a lower-case wire string.
 */
data class CollectionShareItem(
    val id: String,
    val userId: String,
    /** Resolved display name of the shared-with user; falls back to [userId] until the profile syncs. */
    val displayName: String,
    val permission: String,
)
