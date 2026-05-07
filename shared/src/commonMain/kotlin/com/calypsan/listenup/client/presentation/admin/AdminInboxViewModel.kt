package com.calypsan.listenup.client.presentation.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.domain.model.AdminEvent
import com.calypsan.listenup.client.domain.model.InboxBook
import com.calypsan.listenup.client.domain.repository.EventStreamRepository
import com.calypsan.listenup.client.domain.usecase.admin.LoadInboxBooksUseCase
import com.calypsan.listenup.client.domain.usecase.admin.ReleaseBooksUseCase
import com.calypsan.listenup.client.domain.usecase.admin.StageCollectionUseCase
import com.calypsan.listenup.client.domain.usecase.admin.UnstageCollectionUseCase
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * ViewModel for the admin inbox screen.
 *
 * Manages the inbox staging workflow where newly scanned books
 * land for admin review before becoming visible to users.
 *
 * Subscribes to SSE events for real-time updates when books are
 * added to or released from the inbox.
 */
class AdminInboxViewModel(
    private val loadInboxBooksUseCase: LoadInboxBooksUseCase,
    private val releaseBooksUseCase: ReleaseBooksUseCase,
    private val stageCollectionUseCase: StageCollectionUseCase,
    private val unstageCollectionUseCase: UnstageCollectionUseCase,
    private val eventStreamRepository: EventStreamRepository,
) : ViewModel() {
    val state: StateFlow<AdminInboxUiState>
        field = MutableStateFlow<AdminInboxUiState>(AdminInboxUiState.Loading)

    init {
        loadInboxBooks()
        observeSSEEvents()
    }

    /**
     * Observe admin events for real-time inbox updates.
     */
    private fun observeSSEEvents() {
        viewModelScope.launch {
            eventStreamRepository.adminEvents.collect { event ->
                when (event) {
                    is AdminEvent.InboxBookAdded -> {
                        handleInboxBookAdded(event)
                    }

                    is AdminEvent.InboxBookReleased -> {
                        handleInboxBookReleased(event.bookId)
                    }

                    else -> { /* Other admin events handled elsewhere */ }
                }
            }
        }
    }

    private fun handleInboxBookAdded(event: AdminEvent.InboxBookAdded) {
        logger.debug { "SSE: Inbox book added - ${event.bookId}" }
        // Reload to get full book details
        loadInboxBooks()
    }

    private fun handleInboxBookReleased(bookId: String) {
        logger.debug { "SSE: Inbox book released - $bookId" }
        // Remove the book from local state
        updateReady { ready ->
            if (ready.books.any { it.id == bookId }) {
                ready.copy(books = ready.books.filter { it.id != bookId })
            } else {
                ready
            }
        }
    }

    fun loadInboxBooks() {
        viewModelScope.launch {
            when (val result = loadInboxBooksUseCase()) {
                is Success -> {
                    state.update { current ->
                        if (current is AdminInboxUiState.Ready) {
                            current.copy(books = result.data, error = null)
                        } else {
                            // First emission (from Loading) or recovering from Error:
                            // transition to Ready with fresh data and default UI fields.
                            AdminInboxUiState.Ready(books = result.data)
                        }
                    }
                }

                is Failure -> {
                    logger.error { "Failed to load inbox books: ${result.message}" }
                    state.update { current ->
                        if (current is AdminInboxUiState.Ready) {
                            // Transient refresh failure once already loaded: keep books and
                            // surface error to the snackbar.
                            current.copy(error = result.message)
                        } else {
                            // Initial load (or post-Error retry) failed: terminal Error state.
                            AdminInboxUiState.Error(result.message)
                        }
                    }
                }
            }
        }
    }

    /**
     * Release selected books from inbox.
     *
     * If any books have no staged collections, shows a confirmation
     * warning that they will become publicly visible.
     */
    fun releaseBooks(bookIds: List<String>) {
        if (bookIds.isEmpty()) return

        viewModelScope.launch {
            updateReady { it.copy(isReleasing = true, releasingBookIds = bookIds.toSet()) }

            when (val result = releaseBooksUseCase(bookIds)) {
                is Success -> {
                    val releaseResult = result.data
                    logger.info {
                        "Released ${releaseResult.released} books " +
                            "(${releaseResult.publicCount} public, ${releaseResult.toCollections} to collections)"
                    }

                    // Remove released books from local state and clear selection
                    val releasedSet = bookIds.toSet()
                    updateReady { ready ->
                        ready.copy(
                            isReleasing = false,
                            releasingBookIds = emptySet(),
                            selectedBookIds = emptySet(), // Clear selection after successful release
                            books = ready.books.filter { it.id !in releasedSet },
                            lastReleaseResult =
                                ReleaseResult(
                                    released = releaseResult.released,
                                    publicCount = releaseResult.publicCount,
                                    toCollections = releaseResult.toCollections,
                                ),
                        )
                    }
                }

                is Failure -> {
                    logger.error { "Failed to release books: ${result.message}" }
                    updateReady {
                        it.copy(
                            isReleasing = false,
                            releasingBookIds = emptySet(),
                            error = result.message,
                        )
                    }
                }
            }
        }
    }

    /**
     * Stage a collection assignment for an inbox book.
     */
    fun stageCollection(
        bookId: String,
        collectionId: String,
    ) {
        viewModelScope.launch {
            updateReady { it.copy(stagingBookId = bookId) }
            when (val result = stageCollectionUseCase(bookId, collectionId)) {
                is Success -> {
                    // Reload to get updated staged collections
                    loadInboxBooks()
                }

                is Failure -> {
                    logger.error { "Failed to stage collection: ${result.message}" }
                    updateReady { it.copy(error = result.message) }
                }
            }
            updateReady { it.copy(stagingBookId = null) }
        }
    }

    /**
     * Remove a staged collection from an inbox book.
     */
    fun unstageCollection(
        bookId: String,
        collectionId: String,
    ) {
        viewModelScope.launch {
            updateReady { it.copy(stagingBookId = bookId) }
            when (val result = unstageCollectionUseCase(bookId, collectionId)) {
                is Success -> {
                    // Reload to get updated staged collections
                    loadInboxBooks()
                }

                is Failure -> {
                    logger.error { "Failed to unstage collection: ${result.message}" }
                    updateReady { it.copy(error = result.message) }
                }
            }
            updateReady { it.copy(stagingBookId = null) }
        }
    }

    /**
     * Toggle selection of a book for batch operations.
     */
    fun toggleBookSelection(bookId: String) {
        updateReady { ready ->
            val newSelection =
                if (bookId in ready.selectedBookIds) {
                    ready.selectedBookIds - bookId
                } else {
                    ready.selectedBookIds + bookId
                }
            ready.copy(selectedBookIds = newSelection)
        }
    }

    /**
     * Select all books.
     */
    fun selectAll() {
        updateReady { ready ->
            ready.copy(
                selectedBookIds =
                    ready.books
                        .map { it.id }
                        .toSet(),
            )
        }
    }

    /**
     * Clear all selections.
     */
    fun clearSelection() {
        updateReady { it.copy(selectedBookIds = emptySet()) }
    }

    /**
     * Check if any selected books have no staged collections.
     * Used to show public visibility warning before release.
     */
    fun hasSelectedBooksWithoutCollections(): Boolean {
        val ready = state.value as? AdminInboxUiState.Ready ?: return false
        val selectedIds = ready.selectedBookIds
        return ready.books
            .filter { it.id in selectedIds }
            .any { it.stagedCollectionIds.isEmpty() }
    }

    fun clearError() {
        updateReady { it.copy(error = null) }
    }

    fun clearReleaseResult() {
        updateReady { it.copy(lastReleaseResult = null) }
    }

    /**
     * Apply [transform] to state only if it is currently [AdminInboxUiState.Ready].
     * No-ops when state is [AdminInboxUiState.Loading] or [AdminInboxUiState.Error].
     */
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
 * - [Loading] before the first `loadInboxBooksUseCase` emission.
 * - [Ready] once books have loaded; carries books, selection state, action
 *   overlays (`isReleasing`, `releasingBookIds`, `stagingBookId`), a transient
 *   `error` surfaced as a snackbar, and the `lastReleaseResult` for success
 *   confirmation.
 * - [Error] terminal state when the initial load (or a retry from [Error])
 *   fails. Refresh failures after we've reached [Ready] surface via the
 *   transient `error` field on [Ready] instead.
 */
sealed interface AdminInboxUiState {
    data object Loading : AdminInboxUiState

    /**
     * Inbox books have loaded; carries the books, selection state, action overlays,
     * a transient `error`, and the [lastReleaseResult] for success confirmation.
     */
    data class Ready(
        val books: List<InboxBook> = emptyList(),
        val selectedBookIds: Set<String> = emptySet(),
        val isReleasing: Boolean = false,
        val releasingBookIds: Set<String> = emptySet(),
        val stagingBookId: String? = null,
        val lastReleaseResult: ReleaseResult? = null,
        val error: String? = null,
    ) : AdminInboxUiState {
        val hasBooks: Boolean get() = books.isNotEmpty()
        val hasSelection: Boolean get() = selectedBookIds.isNotEmpty()
        val selectedCount: Int get() = selectedBookIds.size
        val allSelected: Boolean get() = selectedBookIds.size == books.size && books.isNotEmpty()

        /** Check if a specific book is currently being staged/unstaged */
        fun isBookStaging(bookId: String): Boolean = stagingBookId == bookId
    }

    /** Terminal state when the initial inbox load fails. */
    data class Error(
        val message: String,
    ) : AdminInboxUiState
}

/**
 * Result of releasing books from inbox.
 */
data class ReleaseResult(
    val released: Int,
    val publicCount: Int,
    val toCollections: Int,
)
