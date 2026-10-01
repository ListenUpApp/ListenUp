package com.calypsan.listenup.client.presentation.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.BookWithContributors
import com.calypsan.listenup.client.data.local.db.toListItem
import com.calypsan.listenup.client.domain.model.AdminEvent
import com.calypsan.listenup.client.domain.model.InboxBookItem
import com.calypsan.listenup.client.domain.repository.EventStreamRepository
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.InboxRepository
import com.calypsan.listenup.client.domain.repository.LibraryRepository
import com.calypsan.listenup.api.dto.scan.ScanIssue
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

private val logger = KotlinLogging.logger {}

/** The one way a Room-backed inbox can fail to load: the local read itself. */
private const val HELD_BOOKS_UNAVAILABLE = "Couldn't read the inbox on this device."

/** One consistent snapshot: the held ids and the hydrated rows for exactly those ids. */
private data class HeldBooks(
    val ids: List<String>,
    val books: List<InboxBookItem>,
)

/**
 * ViewModel for the admin inbox screen.
 *
 * The held set is Room's: [InboxRepository.observeHeldBookIds] reads the live INBOX memberships the
 * sync engine mirrors for admins — the same definition that hides held books from the library and
 * counts the badge — so the inbox page, the badge and the library cannot disagree, and the inbox
 * works offline. Each id is hydrated into an [InboxBookItem] by observing
 * [BookDao.observeByIdsWithContributors]; an id whose book row has not synced yet counts in
 * [AdminInboxUiState.Ready.bookIds] and joins [AdminInboxUiState.Ready.books] when it lands. Ids and
 * rows travel together, so a released book can never reappear from a stale hydration.
 *
 * Releasing is the RPC. Every inbox release is public — `releaseBooks` with an empty target list
 * moves the book into ALL_BOOKS; per-book collection assignment is book-edit's job. The book leaves
 * the list when its INBOX membership leaves Room, which [InboxRepository.releaseBooks] writes through
 * on success.
 *
 * Scan issues are not mirrored and stay on their RPC. The admin event stream is kept for exactly one
 * reason: [AdminEvent.InboxBookAdded] means a scan just ran, which may have raised or cleared an issue.
 */
@OptIn(ExperimentalCoroutinesApi::class)
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

    // The live held-books observation; restarted by [loadInboxBooks] after a failed read.
    private var heldBooksJob: Job? = null

    init {
        observeHeldBooks()
        loadScanIssues()
        observeScanTriggers()
    }

    private fun observeScanTriggers() {
        viewModelScope.launch {
            eventStreamRepository.adminEvents.collect { event ->
                if (event is AdminEvent.InboxBookAdded) loadScanIssues()
            }
        }
    }

    /**
     * Retry: restart the held-books observation and reload the scan issues. The list itself needs no
     * refresh — it follows Room — so this exists for [AdminInboxUiState.Error], whose only cause is a
     * failed local read.
     */
    fun loadInboxBooks() {
        state.update { current -> if (current is AdminInboxUiState.Error) AdminInboxUiState.Loading else current }
        observeHeldBooks()
        loadScanIssues()
    }

    private fun observeHeldBooks() {
        heldBooksJob?.cancel()
        heldBooksJob =
            viewModelScope.launch {
                try {
                    heldBooks().collect(::applyHeldBooks)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error(e) { "Reading the inbox's held books failed" }
                    state.value = AdminInboxUiState.Error(HELD_BOOKS_UNAVAILABLE)
                }
            }
    }

    private fun heldBooks(): Flow<HeldBooks> =
        inboxRepository
            .observeHeldBookIds()
            .map { held -> held.map { it.value } }
            .distinctUntilChanged()
            .flatMapLatest { ids ->
                if (ids.isEmpty()) {
                    flowOf(HeldBooks(ids, emptyList()))
                } else {
                    bookDao
                        .observeByIdsWithContributors(ids.map { BookId(it) })
                        .map { rows -> HeldBooks(ids, rows.toInboxItems(ids)) }
                }
            }

    /** Rows as [InboxBookItem]s in held order; ids with no row yet are simply absent. */
    private fun List<BookWithContributors>.toInboxItems(ids: List<String>): List<InboxBookItem> {
        val byId = associateBy { it.book.id.value }
        return ids.mapNotNull { id ->
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
    }

    private fun applyHeldBooks(held: HeldBooks) {
        state.update { current ->
            if (current is AdminInboxUiState.Ready) {
                current.copy(
                    bookIds = held.ids,
                    books = held.books,
                    // A book released elsewhere cannot stay selected for release here.
                    selectedBookIds = current.selectedBookIds.intersect(held.ids.toSet()),
                )
            } else {
                AdminInboxUiState.Ready(bookIds = held.ids, books = held.books)
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

                            // Loading has nothing to lose; the held books fill in the rest.
                            is AdminInboxUiState.Loading -> AdminInboxUiState.Ready(scanIssues = result.data)

                            // Error must STICK. Promoting it to Ready because a different call
                            // happened to succeed would hide the inbox failing to load behind a
                            // half-populated screen.
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
                    updateReady { it.copy(scanIssues = it.scanIssues.filterNot { issue -> issue.id == issueId }) }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                }
            }
        }
    }

    /**
     * Release the selected books to everyone (into the shared ALL_BOOKS collection).
     *
     * One [InboxRepository.releaseBooks] call maps each selected id to an empty target list. On
     * success the books leave the list through Room — the repository writes the INBOX exit through —
     * so this only clears the selection and records the count for the confirmation.
     */
    fun releaseSelected() {
        val ready = state.value as? AdminInboxUiState.Ready ?: return
        val releasing = ready.selectedBookIds
        if (releasing.isEmpty()) return

        viewModelScope.launch {
            updateReady { it.copy(isReleasing = true) }
            val libraryId = currentLibraryId()
            if (libraryId == null) {
                updateReady { it.copy(isReleasing = false, error = "No library available") }
                return@launch
            }
            when (
                val result =
                    inboxRepository.releaseBooks(
                        libraryId,
                        releasing.associateWith { emptyList<String>() },
                    )
            ) {
                is AppResult.Success -> {
                    updateReady { current ->
                        current.copy(
                            isReleasing = false,
                            selectedBookIds = current.selectedBookIds - releasing,
                            lastReleasedCount = releasing.size,
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
 * - [Loading] before the first held-set read.
 * - [Ready] once book ids have loaded; carries the book ids, the hydrated [InboxBookItem]
 *   projections, the selection set, the `isReleasing` overlay, a transient `error`, and
 *   `lastReleasedCount` for the success confirmation.
 * - [Error] when the local held-set read fails; [AdminInboxViewModel.loadInboxBooks] retries.
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

    /** The local held-set read failed. Retry with [AdminInboxViewModel.loadInboxBooks]. */
    data class Error(
        val message: String,
    ) : AdminInboxUiState
}
