package com.calypsan.listenup.client.presentation.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.toListItem
import com.calypsan.listenup.client.domain.model.AdminEvent
import com.calypsan.listenup.client.domain.model.InboxBookItem
import com.calypsan.listenup.client.domain.repository.EventStreamRepository
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.InboxRepository
import com.calypsan.listenup.client.domain.repository.LibraryRepository
import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * ViewModel for the admin inbox screen.
 *
 * The inbox holds freshly-ingested books awaiting admin triage. The authoritative id set
 * comes from [InboxRepository.listInbox] (the 1b admin REST surface returns ids only); the
 * VM then hydrates each id into an [InboxBookItem] (cover/title/author/duration) by observing
 * [BookDao.observeByIdsWithContributors] so the review-and-release queue shows real book detail
 * rather than raw ids. The admin selects target collections per book, then **releases**: the 1b
 * `releaseBooks(libraryId, assignments)` call takes the per-book target-collection map directly,
 * so the legacy stage / unstage round-trips are gone — assignment is local UI state collapsed
 * into one release call (an empty target list releases a book as publicly visible).
 *
 * Subscribes to admin SSE events for real-time inbox add/release updates.
 */
class AdminInboxViewModel(
    private val inboxRepository: InboxRepository,
    private val libraryRepository: LibraryRepository,
    private val eventStreamRepository: EventStreamRepository,
    private val bookDao: BookDao,
    private val imageStorage: ImageStorage,
    private val errorBus: ErrorBus,
) : ViewModel() {
    val state: StateFlow<AdminInboxUiState>
        field = MutableStateFlow<AdminInboxUiState>(AdminInboxUiState.Loading)

    // Tracks the in-flight Room hydration so a new inbox id-set replaces the prior observation.
    private var hydrationJob: Job? = null

    init {
        loadInboxBooks()
        observeSSEEvents()
    }

    private fun observeSSEEvents() {
        viewModelScope.launch {
            eventStreamRepository.adminEvents.collect { event ->
                when (event) {
                    is AdminEvent.InboxBookAdded -> {
                        loadInboxBooks()
                    }

                    is AdminEvent.InboxBookReleased -> {
                        handleInboxBookReleased(event.bookId)
                    }

                    else -> { /* Other admin events handled elsewhere */ }
                }
            }
        }
    }

    private fun handleInboxBookReleased(bookId: String) {
        updateReady { ready ->
            if (ready.bookIds.contains(bookId)) {
                ready.copy(
                    bookIds = ready.bookIds.filterNot { it == bookId },
                    books = ready.books.filterNot { it.id == bookId },
                    selectedBookIds = ready.selectedBookIds - bookId,
                    stagedAssignments = ready.stagedAssignments - bookId,
                )
            } else {
                ready
            }
        }
    }

    /** Load inbox book ids for the admin's library. */
    fun loadInboxBooks() {
        viewModelScope.launch {
            val libraryId = currentLibraryId()
            if (libraryId == null) {
                state.value = AdminInboxUiState.Error("No library available")
                return@launch
            }
            when (val result = inboxRepository.listInbox(libraryId)) {
                is AppResult.Success -> {
                    state.update { current ->
                        if (current is AdminInboxUiState.Ready) {
                            current.copy(bookIds = result.data, error = null)
                        } else {
                            AdminInboxUiState.Ready(bookIds = result.data)
                        }
                    }
                    hydrate(result.data)
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    state.update { current ->
                        if (current is AdminInboxUiState.Ready) {
                            current.copy(error = result.error.message)
                        } else {
                            AdminInboxUiState.Error(result.error.message)
                        }
                    }
                }
            }
        }
    }

    /**
     * Observe the Room projections for [ids] and fold them into [AdminInboxUiState.Ready.books].
     *
     * Books are emitted in inbox-id order so the triage list is stable regardless of Room's
     * row order, and ids with no Room row yet are simply omitted until they sync in. A new
     * call cancels the prior observation so the live set tracks the latest inbox id-set.
     */
    private fun hydrate(ids: List<String>) {
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
                                InboxBookItem(
                                    id = item.id.value,
                                    title = item.title,
                                    author = item.authors.firstOrNull()?.name,
                                    coverPath = item.coverPath,
                                    durationMs = item.duration,
                                )
                            }
                        }
                    updateReady { it.copy(books = books) }
                }
            }
    }

    /**
     * Release the selected books from the inbox.
     *
     * Builds the per-book assignment map from each selected book's staged collections
     * (an unstaged selected book is released as public) and dispatches a single
     * [InboxRepository.releaseBooks] call. Released books leave the list via the SSE
     * echo, but we also clear them locally so the UI converges immediately.
     */
    fun releaseSelected() {
        val ready = state.value as? AdminInboxUiState.Ready ?: return
        if (ready.selectedBookIds.isEmpty()) return

        viewModelScope.launch {
            updateReady { it.copy(isReleasing = true) }
            val libraryId = currentLibraryId()
            if (libraryId == null) {
                updateReady { it.copy(isReleasing = false, error = "No library available") }
                return@launch
            }
            val assignments =
                ready.selectedBookIds.associateWith { bookId ->
                    ready.stagedAssignments[bookId] ?: emptyList()
                }

            when (val result = inboxRepository.releaseBooks(libraryId, assignments)) {
                is AppResult.Success -> {
                    updateReady { current ->
                        current.copy(
                            isReleasing = false,
                            bookIds = current.bookIds.filterNot { it in current.selectedBookIds },
                            books = current.books.filterNot { it.id in current.selectedBookIds },
                            stagedAssignments = current.stagedAssignments - current.selectedBookIds,
                            selectedBookIds = emptySet(),
                            lastReleasedCount = current.selectedBookIds.size,
                        )
                    }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    updateReady { it.copy(isReleasing = false, error = result.error.message) }
                }
            }
        }
    }

    /** Stage a target collection for a book (local UI state, applied on release). */
    fun stageCollection(
        bookId: String,
        collectionId: String,
    ) {
        updateReady { ready ->
            val current = ready.stagedAssignments[bookId].orEmpty()
            if (collectionId in current) {
                ready
            } else {
                ready.copy(stagedAssignments = ready.stagedAssignments + (bookId to current + collectionId))
            }
        }
    }

    /** Remove a staged target collection from a book (local UI state). */
    fun unstageCollection(
        bookId: String,
        collectionId: String,
    ) {
        updateReady { ready ->
            val current = ready.stagedAssignments[bookId].orEmpty()
            ready.copy(
                stagedAssignments =
                    ready.stagedAssignments + (bookId to current.filterNot { it == collectionId }),
            )
        }
    }

    /** Toggle a book's selection for batch release. */
    fun toggleBookSelection(bookId: String) {
        updateReady { ready ->
            val newSelection =
                if (bookId in ready.selectedBookIds) ready.selectedBookIds - bookId else ready.selectedBookIds + bookId
            ready.copy(selectedBookIds = newSelection)
        }
    }

    /** Select every book in the inbox. */
    fun selectAll() {
        updateReady { ready -> ready.copy(selectedBookIds = ready.bookIds.toSet()) }
    }

    /** Clear the selection. */
    fun clearSelection() {
        updateReady { it.copy(selectedBookIds = emptySet()) }
    }

    /** True when any selected book has no staged target collections (will be released public). */
    fun hasSelectedBooksWithoutCollections(): Boolean {
        val ready = state.value as? AdminInboxUiState.Ready ?: return false
        return ready.selectedBookIds.any { ready.stagedAssignments[it].isNullOrEmpty() }
    }

    /** Clear the transient error state. */
    fun clearError() {
        updateReady { it.copy(error = null) }
    }

    /** Clear the last-release-count confirmation. */
    fun clearReleaseResult() {
        updateReady { it.copy(lastReleasedCount = null) }
    }

    private suspend fun currentLibraryId(): String? =
        libraryRepository
            .observeAll()
            .first()
            .firstOrNull()
            ?.id

    private fun updateReady(transform: (AdminInboxUiState.Ready) -> AdminInboxUiState.Ready) {
        state.update { current ->
            if (current is AdminInboxUiState.Ready) transform(current) else current
        }
    }
}

/**
 * UI state for the admin inbox screen.
 *
 * Sealed hierarchy:
 * - [Loading] before the first inbox fetch.
 * - [Ready] once book ids have loaded; carries the book ids, the hydrated [InboxBookItem]
 *   projections, the per-book staged target-collection assignments, the selection set, the
 *   `isReleasing` overlay, a transient `error`, and `lastReleasedCount` for the success
 *   confirmation.
 * - [Error] terminal state when the initial inbox fetch fails.
 */
sealed interface AdminInboxUiState {
    data object Loading : AdminInboxUiState

    /**
     * Inbox loaded. [bookIds] is the authoritative inbox id-set (selection key); [books] is the
     * hydrated, inbox-ordered projection used by the queue UI (it may lag [bookIds] until rows
     * sync into Room). Also carries selection, staged assignments, the release overlay, and a
     * transient `error`.
     */
    data class Ready(
        val bookIds: List<String> = emptyList(),
        val books: List<InboxBookItem> = emptyList(),
        val stagedAssignments: Map<String, List<String>> = emptyMap(),
        val selectedBookIds: Set<String> = emptySet(),
        val isReleasing: Boolean = false,
        val lastReleasedCount: Int? = null,
        val error: String? = null,
    ) : AdminInboxUiState {
        val hasBooks: Boolean get() = bookIds.isNotEmpty()
        val hasSelection: Boolean get() = selectedBookIds.isNotEmpty()
        val selectedCount: Int get() = selectedBookIds.size
        val allSelected: Boolean get() = selectedBookIds.size == bookIds.size && bookIds.isNotEmpty()
    }

    /** Terminal state when the initial inbox load fails. */
    data class Error(
        val message: String,
    ) : AdminInboxUiState
}
