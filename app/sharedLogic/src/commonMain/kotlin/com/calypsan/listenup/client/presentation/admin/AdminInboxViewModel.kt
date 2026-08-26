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
import com.calypsan.listenup.api.dto.scan.ScanIssue
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.error.ErrorBus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for the admin inbox screen.
 *
 * The inbox holds freshly-ingested books awaiting admin triage. The authoritative id set
 * comes from [InboxRepository.listInbox] (the admin `CollectionService` RPC returns ids only); the
 * VM then hydrates each id into an [InboxBookItem] (cover/title/author/duration) by observing
 * [BookDao.observeByIdsWithContributors] so the review-and-release queue shows real book detail
 * rather than raw ids. The admin selects books and **releases** them: every inbox release is
 * public — `releaseBooks` with an empty target list moves the book into the shared `ALL_BOOKS`
 * collection every member can see — so the single `releaseBooks(libraryId, assignments)` call
 * maps each selected id to an empty target-collection list. Per-book collection assignment is
 * book-edit's job, not the inbox's.
 *
 * Subscribes to admin events from the server event stream for real-time inbox add/release updates.
 */
class AdminInboxViewModel internal constructor(
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
        loadScanIssues()
        observeAdminEvents()
    }

    private fun observeAdminEvents() {
        viewModelScope.launch {
            eventStreamRepository.adminEvents.collect { event ->
                when (event) {
                    is AdminEvent.InboxBookAdded -> {
                        loadInboxBooks()
                        // A scan that just added a book may equally have fixed or raised an issue.
                        loadScanIssues()
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
                )
            } else {
                ready
            }
        }
    }

    /**
     * Loads the folders the scanner could not import.
     *
     * Failure here is reported but never downgrades the screen to [AdminInboxUiState.Error]: the
     * held-books half is independently useful, and losing the whole inbox because one call failed
     * would be a worse answer than showing what we do have.
     */
    fun loadScanIssues() {
        viewModelScope.launch {
            when (val result = inboxRepository.listScanIssues()) {
                is AppResult.Success -> {
                    state.update { current ->
                        when (current) {
                            is AdminInboxUiState.Ready -> current.copy(scanIssues = result.data)

                            // Loading has nothing to lose; the books load fills in the rest.
                            is AdminInboxUiState.Loading -> AdminInboxUiState.Ready(scanIssues = result.data)

                            // Error must STICK. Promoting it to Ready because a different call
                            // happened to succeed would hide the inbox failing to load behind a
                            // half-populated screen — the failure the user needs to see, silenced
                            // by the very surface built to stop silencing failures.
                            is AdminInboxUiState.Error -> current
                        }
                    }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                }
            }
        }
    }

    /** Stops showing [issueId], and drops it from the list without a round trip. */
    fun dismissScanIssue(issueId: String) {
        viewModelScope.launch {
            when (val result = inboxRepository.dismissScanIssue(issueId)) {
                is AppResult.Success -> {
                    state.update { current ->
                        if (current is AdminInboxUiState.Ready) {
                            current.copy(scanIssues = current.scanIssues.filterNot { it.id == issueId })
                        } else {
                            current
                        }
                    }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                }
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
                                    coverHash = item.coverHash,
                                )
                            }
                        }
                    // Filter against the CURRENT id-set, not the one this collector was started
                    // with. Releasing a book prunes `bookIds` without restarting hydration, and a
                    // released book is not deleted from Room — so the next Room invalidation (a
                    // cover download, a position write, any sync) would otherwise recompute over
                    // the stale list and put released books straight back in the grid, disagreeing
                    // with the header count and with what `selectAll` selects.
                    updateReady { ready -> ready.copy(books = books.filter { it.id in ready.bookIds }) }
                }
            }
    }

    /**
     * Release the selected books from the inbox as publicly visible (moved into the shared
     * `ALL_BOOKS` collection every member can see).
     *
     * Every inbox release is public — per-book collection assignment is book-edit's job,
     * not the inbox's — so each selected id maps to an empty target-collection list in the
     * single [InboxRepository.releaseBooks] call. Released books leave the list via the firehose
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
            val assignments = ready.selectedBookIds.associateWith { emptyList<String>() }

            when (val result = inboxRepository.releaseBooks(libraryId, assignments)) {
                is AppResult.Success -> {
                    updateReady { current ->
                        current.copy(
                            isReleasing = false,
                            bookIds = current.bookIds.filterNot { it in current.selectedBookIds },
                            books = current.books.filterNot { it.id in current.selectedBookIds },
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
 *   projections, the selection set, the `isReleasing` overlay, a transient `error`, and
 *   `lastReleasedCount` for the success confirmation.
 * - [Error] terminal state when the initial inbox fetch fails.
 */
sealed interface AdminInboxUiState {
    data object Loading : AdminInboxUiState

    /**
     * Inbox loaded. [bookIds] is the authoritative inbox id-set (selection key); [books] is the
     * hydrated, inbox-ordered projection used by the queue UI (it may lag [bookIds] until rows
     * sync into Room). Also carries selection, the release overlay, and a transient `error`.
     */
    data class Ready(
        val bookIds: List<String> = emptyList(),
        val books: List<InboxBookItem> = emptyList(),
        val selectedBookIds: Set<String> = emptySet(),
        val isReleasing: Boolean = false,
        val lastReleasedCount: Int? = null,
        val error: String? = null,
        /**
         * Folders the scanner could not import. Independent of [bookIds]: an issue is not a book
         * awaiting a decision, it is a thing that went wrong and produced no book at all — so the
         * inbox has content even when nothing is being held for review.
         */
        val scanIssues: List<ScanIssue> = emptyList(),
    ) : AdminInboxUiState {
        val hasBooks: Boolean get() = bookIds.isNotEmpty()
        val hasIssues: Boolean get() = scanIssues.isNotEmpty()
        val isEmpty: Boolean get() = bookIds.isEmpty() && scanIssues.isEmpty()
        val hasSelection: Boolean get() = selectedBookIds.isNotEmpty()
        val selectedCount: Int get() = selectedBookIds.size
        val allSelected: Boolean get() = selectedBookIds.size == bookIds.size && bookIds.isNotEmpty()
    }

    /** Terminal state when the initial inbox load fails. */
    data class Error(
        val message: String,
    ) : AdminInboxUiState
}
