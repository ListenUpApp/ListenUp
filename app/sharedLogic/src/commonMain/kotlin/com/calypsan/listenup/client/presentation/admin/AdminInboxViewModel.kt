package com.calypsan.listenup.client.presentation.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.scan.ScanIssue
import com.calypsan.listenup.api.error.CollectionError
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
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/** The one way a Room-backed inbox can fail to load: the local read itself. */
private const val HELD_BOOKS_UNAVAILABLE = "Couldn't read the inbox on this device."

/** The one release failure no [com.calypsan.listenup.api.error.AppError] describes: there is no library to release into. */
private const val NO_LIBRARY = "No library available"

/** One consistent snapshot: the held ids and the hydrated rows for exactly those ids. */
private data class HeldBooks(
    val ids: List<String>,
    val books: List<InboxBookItem>,
)

/** Where the held-set read stands. */
private sealed interface HeldLoad {
    /** A Retry is re-reading after a failure. The first read needs no marker: `state` starts Loading. */
    data object Loading : HeldLoad

    /** The held ids and their hydrated rows. */
    data class Loaded(
        val held: HeldBooks,
    ) : HeldLoad

    /** The local read threw; Retry re-reads. */
    data object Failed : HeldLoad
}

/** What the admin has done on top of the inbox: the selection, and the release in flight and its outcome. */
private data class InboxOverlay(
    val selected: Set<String> = emptySet(),
    val isReleasing: Boolean = false,
    val lastReleasedCount: Int? = null,
    val lastUnreleasedCount: Int = 0,
    val error: String? = null,
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
 * [state] is derived, never written: the held books, the scan issues, the dismissed issues and the
 * admin's [InboxOverlay] combine into it, and actions only write those inputs. The selection shown is
 * the overlay's selection intersected with what is held, and a release sends only selected books that
 * are still held, so a book released elsewhere is neither shown selected nor released again here.
 *
 * Releasing is the RPC. Every inbox release is public — `releaseBooks` with an empty target list
 * moves the book into ALL_BOOKS; per-book collection assignment is book-edit's job. The book leaves
 * the list when its INBOX membership leaves Room, which [InboxRepository.releaseBooks] writes through
 * on success. A refused release goes to the [ErrorBus] — which every platform already shows — and
 * nowhere else, so it is said once. A partial release ([CollectionError.ReleaseIncomplete]) goes there
 * too; the books that did leave are confirmed as usual, with the count that stayed held beside them,
 * and the books that stayed keep their selection so Release retries exactly them.
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
    private val overlay = MutableStateFlow(InboxOverlay())
    private val dismissedIssueIds = MutableStateFlow(emptySet<String>())

    // Attempt counters, not refresh signals: each bump is the admin asking again (Retry, pull to
    // refresh), and a StateFlow replays the latest attempt to a fresh subscriber.
    private val heldReadAttempts = MutableStateFlow(0)
    private val scanIssueLoadAttempts = MutableStateFlow(0)

    private val heldLoad: Flow<HeldLoad> =
        heldReadAttempts
            .flatMapLatest {
                heldBooks()
                    // While the inbox is observed, a book that stops being held drops out of the stored
                    // selection, so if it is held again later it comes back unselected rather than
                    // pre-armed for release. Nothing prunes while nobody observes; [state]'s intersection
                    // and the release's own snapshot are what keep a stale id from being shown or sent.
                    .onEach { held -> overlay.update { it.copy(selected = it.selected.intersect(held.ids.toSet())) } }
                    .map<HeldBooks, HeldLoad> { HeldLoad.Loaded(it) }
                    // Only a Retry from Error shows Loading; a re-subscription to a Ready inbox must not flash it.
                    .onStart { if (state.value is AdminInboxUiState.Error) emit(HeldLoad.Loading) }
                    .catch { e ->
                        logger.error(e) { "Reading the inbox's held books failed" }
                        emit(HeldLoad.Failed)
                    }
            }

    /**
     * The folders the scanner could not import, or null before the first answer. Loaded when the screen
     * starts observing, on [loadScanIssues], and whenever a scan adds a book. A failed load is reported
     * and keeps the last answer: the held-books half is independently useful, and losing it — or the
     * issues already shown — because one call failed would be a worse answer than showing what we have.
     * It is a StateFlow, not a plain Flow, for exactly that: a failed reload's null is filtered out, so
     * the StateFlow keeps replaying the last answer.
     */
    private val scanIssues: StateFlow<List<ScanIssue>?> =
        merge(
            scanIssueLoadAttempts.map { },
            eventStreamRepository.adminEvents.filterIsInstance<AdminEvent.InboxBookAdded>().map { },
        ).mapLatest { fetchScanIssues() }
            .filterNotNull()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val state: StateFlow<AdminInboxUiState> =
        combine(heldLoad, scanIssues, dismissedIssueIds, overlay) { held, issues, dismissed, ov ->
            when (held) {
                HeldLoad.Loading -> {
                    AdminInboxUiState.Loading
                }

                // Error must STICK whatever the issues do: a half-populated screen would hide the
                // inbox failing to load.
                HeldLoad.Failed -> {
                    AdminInboxUiState.Error(HELD_BOOKS_UNAVAILABLE)
                }

                is HeldLoad.Loaded -> {
                    AdminInboxUiState.Ready(
                        bookIds = held.held.ids,
                        books = held.held.books,
                        selectedBookIds = ov.selected.intersect(held.held.ids.toSet()),
                        isReleasing = ov.isReleasing,
                        lastReleasedCount = ov.lastReleasedCount,
                        lastUnreleasedCount = ov.lastUnreleasedCount,
                        error = ov.error,
                        scanIssues = issues.orEmpty().filterNot { it.id in dismissed },
                    )
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AdminInboxUiState.Loading)

    /**
     * Retry. After a failed local read ([AdminInboxUiState.Error]) this re-reads the held set; the list
     * otherwise follows Room and needs no refresh. Either way it reloads the scan issues.
     */
    fun loadInboxBooks() {
        if (state.value is AdminInboxUiState.Error) heldReadAttempts.update { it + 1 }
        loadScanIssues()
    }

    /** Reloads the folders the scanner could not import. */
    fun loadScanIssues() {
        scanIssueLoadAttempts.update { it + 1 }
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

    private suspend fun fetchScanIssues(): List<ScanIssue>? =
        when (val result = inboxRepository.listScanIssues()) {
            is AppResult.Success -> {
                result.data
            }

            is AppResult.Failure -> {
                errorBus.emit(result.error)
                null
            }
        }

    /** Stops showing [issueId], and drops it from the list without a round trip. */
    fun dismissScanIssue(issueId: String) {
        viewModelScope.launch {
            when (val result = inboxRepository.dismissScanIssue(issueId)) {
                is AppResult.Success -> dismissedIssueIds.update { it + issueId }
                is AppResult.Failure -> errorBus.emit(result.error)
            }
        }
    }

    /**
     * Release the selected books to everyone (into the shared ALL_BOOKS collection).
     *
     * One [InboxRepository.releaseBooks] call maps each selected id to an empty target list. On
     * success the books leave the list through Room — the repository writes the INBOX exit through —
     * so this only clears the selection and records the count for the confirmation. A partial release
     * does the same for the books that left and keeps the rest selected. A release already
     * in flight makes this a no-op, so a double tap sends one release.
     */
    fun releaseSelected() {
        val ready = state.value as? AdminInboxUiState.Ready ?: return
        // The overlay, not [state], is read: a tap in the same frame may not have reached [state] yet.
        val current = overlay.value
        if (current.isReleasing) return
        val releasing = current.selected.intersect(ready.bookIds.toSet())
        if (releasing.isEmpty()) return
        overlay.update { it.copy(isReleasing = true) }

        viewModelScope.launch {
            val libraryId = currentLibraryId()
            if (libraryId == null) {
                overlay.update { it.copy(isReleasing = false, error = NO_LIBRARY) }
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
                    overlay.update { it.released(releasing, stayed = emptySet()) }
                }

                is AppResult.Failure -> {
                    val error = result.error
                    if (error is CollectionError.ReleaseIncomplete) {
                        overlay.update {
                            it.released(
                                releasing,
                                stayed = releasing.intersect(error.failedBookIds.toSet()),
                            )
                        }
                    } else {
                        overlay.update { it.copy(isReleasing = false) }
                    }
                    errorBus.emit(error)
                }
            }
        }
    }

    /**
     * The overlay after a release of [releasing] in which [stayed] were not released: the books that
     * left drop out of the selection and are confirmed, and the ones that stayed keep their selection
     * so Release retries them. When nothing left there is nothing to confirm.
     */
    private fun InboxOverlay.released(
        releasing: Set<String>,
        stayed: Set<String>,
    ): InboxOverlay {
        val left = releasing - stayed
        return copy(
            isReleasing = false,
            selected = selected - left,
            lastReleasedCount = left.size.takeIf { it > 0 } ?: lastReleasedCount,
            lastUnreleasedCount = if (left.isEmpty()) lastUnreleasedCount else stayed.size,
        )
    }

    /** Toggle a book's selection for batch release. */
    fun toggleBookSelection(bookId: String) {
        if (state.value !is AdminInboxUiState.Ready) return
        overlay.update { ov ->
            ov.copy(selected = if (bookId in ov.selected) ov.selected - bookId else ov.selected + bookId)
        }
    }

    /** Select every book in the inbox. */
    fun selectAll() {
        val ready = state.value as? AdminInboxUiState.Ready ?: return
        overlay.update { it.copy(selected = ready.bookIds.toSet()) }
    }

    /** Clear the selection. */
    fun clearSelection() {
        overlay.update { it.copy(selected = emptySet()) }
    }

    /** Clear the transient error state. */
    fun clearError() {
        overlay.update { it.copy(error = null) }
    }

    /** Clear the last-release-count confirmation. */
    fun clearReleaseResult() {
        overlay.update { it.copy(lastReleasedCount = null, lastUnreleasedCount = 0) }
    }

    private suspend fun currentLibraryId(): String? =
        libraryRepository
            .observeAll()
            .first()
            .firstOrNull()
            ?.id
}

/**
 * UI state for the admin inbox screen.
 *
 * Sealed hierarchy:
 * - [Loading] before the first held-set read.
 * - [Ready] once book ids have loaded; carries the book ids, the hydrated [InboxBookItem]
 *   projections, the selection set, the `isReleasing` overlay, a transient `error` (only for a
 *   failure no `AppError` describes — typed failures go to the `ErrorBus`, which every platform
 *   shows), and `lastReleasedCount` for the success confirmation.
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
        /**
         * How many books of the release [lastReleasedCount] confirms could not be released and are
         * still held — `0` when every book left. Set only alongside [lastReleasedCount], so a screen
         * says "released 2 of 3" in the same breath; a release in which nothing left confirms nothing
         * and says so through the `ErrorBus` alone.
         */
        val lastUnreleasedCount: Int = 0,
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
