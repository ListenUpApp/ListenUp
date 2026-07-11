package com.calypsan.listenup.client.presentation.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.campfire.OpenCampfireSummary
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.core.error.ErrorBus
import com.calypsan.listenup.client.core.fallbackTo
import com.calypsan.listenup.client.domain.model.ActiveSession
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.Shelf
import com.calypsan.listenup.client.domain.repository.ActiveSessionRepository
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.DiscoveryBook
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

/**
 * ViewModel for the Discover screen.
 *
 * Discovery data comes from a mix of local Room and RPC:
 * - What others are listening to: the ACL-filtered [com.calypsan.listenup.api.SocialService.currentlyListening]
 *   RPC, re-fetched on each presence nudge + firehose reconnect (via [ActiveSessionRepository])
 * - Discover something new: random unstarted books from books table
 * - Recently added: newest books from books table
 * - Shelves from other users: fetched on demand via [ShelfRepository.discoverShelves] RPC
 *   (other users' shelves never enter Room; each [refresh] issues a fresh network call)
 *
 * Discover-new and recently-added are offline-first — they work instantly after initial sync.
 * Currently-listening and discover-shelves require connectivity for each load.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverViewModel(
    private val bookRepository: BookRepository,
    private val activeSessionRepository: ActiveSessionRepository,
    private val authSession: AuthSession,
    private val shelfRepository: ShelfRepository,
    private val errorBus: ErrorBus,
    private val openCampfires: Flow<List<OpenCampfireSummary>>,
) : ViewModel() {
    init {
        // Load discovered shelves on screen open (on-demand RPC, not Room-backed).
        loadDiscoverShelves()
    }

    // === Currently Listening State (from SocialService RPC) ===

    /**
     * Observe active sessions via [ActiveSessionRepository], which fetches the ACL-filtered
     * [com.calypsan.listenup.api.SocialService.currentlyListening] RPC (the server excludes the
     * caller). Re-fetched on every presence nudge ([com.calypsan.listenup.client.data.sync.PresenceRefreshSignal])
     * and firehose reconnect.
     */
    private val currentlyListeningFlow =
        authSession.authState.flatMapLatest { authState ->
            if (authState is AuthState.Authenticated) {
                activeSessionRepository.observeActiveSessions(authState.userId.value)
            } else {
                flowOf(emptyList())
            }
        }

    val currentlyListeningState: StateFlow<CurrentlyListeningUiState> =
        currentlyListeningFlow
            .map<_, CurrentlyListeningUiState> { sessions ->
                CurrentlyListeningUiState.Ready(sessions = sessions.map { it.toUiModel() })
            }.onStart { emit(CurrentlyListeningUiState.Loading) }
            .fallbackTo { e ->
                logger.error(e) { "Error observing currently listening" }
                CurrentlyListeningUiState.Error("Failed to load currently listening")
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
                initialValue = CurrentlyListeningUiState.Loading,
            )

    /**
     * Convert ActiveSession domain model to UI model.
     */
    private fun ActiveSession.toUiModel(): CurrentlyListeningUiSession =
        CurrentlyListeningUiSession(
            sessionId = sessionId,
            userId = userId,
            bookId = bookId,
            bookTitle = book.title,
            authorName = book.authorName,
            coverPath = book.coverPath,
            coverHash = book.coverHash,
            coverBlurHash = book.coverBlurHash,
            displayName = user.displayName,
            startedAt = startedAtMs,
        )

    // === Live Campfires State ("Live now" row, from CampfireDiscoveryRepository) ===

    /**
     * Open campfires (co-listening sessions) the caller may currently discover — the Discover
     * "Live now" row. Backed by `CampfireDiscoveryRepository.observeOpenSessions` (in-memory, no
     * Room — see that repository's KDoc): fetches on subscribe and re-fetches on every
     * `CampfireRefreshSignal` ping (the server's `CampfiresChanged` nudge).
     */
    val liveCampfiresState: StateFlow<List<OpenCampfireSummary>> =
        openCampfires.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            initialValue = emptyList(),
        )

    /**
     * [liveCampfiresState] enriched with each session's book (cover, title, authors) for the
     * Discover "Live now" row — [OpenCampfireSummary] itself carries only a bare [OpenCampfireSummary.bookId]
     * (deliberately lean, see its KDoc), so this pairs each summary with a [BookRepository]
     * lookup. Every book here is already synced to Room (the caller has access to it, or it would
     * not have appeared in [liveCampfiresState] at all), so the join is a pure local read — no
     * extra network round-trip.
     */
    val liveCampfiresUiState: StateFlow<List<LiveCampfireUiModel>> =
        liveCampfiresState
            .flatMapLatest { summaries ->
                if (summaries.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    bookRepository.observeBookListItems(summaries.map { it.bookId }).map { books ->
                        val booksById = books.associateBy { it.id.value }
                        summaries.mapNotNull { summary ->
                            booksById[summary.bookId]?.let { book -> LiveCampfireUiModel(summary, book) }
                        }
                    }
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
                initialValue = emptyList(),
            )

    // === Discover Books State (Random Unstarted from Room) ===

    /**
     * Bumped by [refresh] to trigger a fresh random selection. We do not observe the
     * upstream flow reactively because the SQL uses RANDOM() — a live subscription
     * would reshuffle on every invalidation.
     */
    private val discoverBooksRefreshTrigger = MutableStateFlow(0)

    val discoverBooksState: StateFlow<DiscoverBooksUiState> =
        discoverBooksRefreshTrigger
            .flatMapLatest {
                flow {
                    emit(DiscoverBooksUiState.Loading as DiscoverBooksUiState)
                    val books = bookRepository.observeRandomUnstartedBooks(limit = 10).first()
                    emit(DiscoverBooksUiState.Ready(books = books.map { it.toDiscoverUiBook() }))
                }
            }.fallbackTo { e ->
                logger.error(e) { "Error loading discover books" }
                DiscoverBooksUiState.Error("Failed to load discover books")
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
                initialValue = DiscoverBooksUiState.Loading,
            )

    /**
     * Convert DiscoveryBook domain model to DiscoverUiBook.
     */
    private fun DiscoveryBook.toDiscoverUiBook(): DiscoverUiBook =
        DiscoverUiBook(
            id = id,
            title = title,
            authorName = authorName,
            coverPath = coverPath,
            coverBlurHash = coverBlurHash,
            coverHash = coverHash,
            seriesName = null, // Series comes from book_series relation
        )

    // === Recently Added State (from Room) ===

    /**
     * Observe recently added books from Room with author info.
     * Sorted by createdAt timestamp descending.
     */
    val recentlyAddedState: StateFlow<RecentlyAddedUiState> =
        bookRepository
            .observeRecentlyAddedBooks(limit = 10)
            .map<_, RecentlyAddedUiState> { books ->
                RecentlyAddedUiState.Ready(books = books.map { it.toRecentlyAddedUiBook() })
            }.onStart { emit(RecentlyAddedUiState.Loading) }
            .fallbackTo { e ->
                logger.error(e) { "Error observing recently added books" }
                RecentlyAddedUiState.Error("Failed to load recently added")
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
                initialValue = RecentlyAddedUiState.Loading,
            )

    /**
     * Convert DiscoveryBook domain model to RecentlyAddedUiBook.
     */
    private fun DiscoveryBook.toRecentlyAddedUiBook(): RecentlyAddedUiBook =
        RecentlyAddedUiBook(
            id = id,
            title = title,
            authorName = authorName,
            coverPath = coverPath,
            coverBlurHash = coverBlurHash,
            coverHash = coverHash,
            createdAt = createdAt,
        )

    // === Discover Shelves State (on-demand RPC) ===

    /**
     * Discovered shelves are an on-demand RPC read (other users' shelves never enter Room).
     * Loaded on init and refreshed via [refresh]; the latest result is held here.
     */
    val discoverShelvesState: StateFlow<DiscoverShelvesUiState>
        field = MutableStateFlow<DiscoverShelvesUiState>(DiscoverShelvesUiState.Loading)

    /**
     * Convert Shelf domain model to UI model.
     */
    private fun Shelf.toUiModel(): DiscoverShelfUi =
        DiscoverShelfUi(
            id = id.value,
            name = name,
            description = description,
            bookCount = bookCount,
            totalDurationSeconds = totalDurationSeconds,
        )

    /**
     * Fetch discovered shelves from the server and publish the grouped UI state.
     * Groups by owner so the screen can render each user's shelves together.
     */
    private fun loadDiscoverShelves() {
        viewModelScope.launch {
            if (authSession.authState.value !is AuthState.Authenticated) {
                logger.debug { "Not authenticated, skipping shelf fetch" }
                return@launch
            }

            when (val result = shelfRepository.discoverShelves()) {
                is AppResult.Success -> {
                    val userShelves =
                        result.data
                            .groupBy { it.ownerId }
                            .map { (ownerId, ownerShelves) ->
                                DiscoverUserShelves(
                                    user =
                                        DiscoverShelfOwner(
                                            id = ownerId,
                                            displayName = ownerShelves.first().ownerDisplayName,
                                        ),
                                    shelves = ownerShelves.map { it.toUiModel() },
                                )
                            }
                    discoverShelvesState.value = DiscoverShelvesUiState.Ready(users = userShelves)
                }

                is AppResult.Failure -> {
                    // Offline is an expected state on this background load, not a snackbar-worthy fault —
                    // the section degrades to its local empty/error state. Only genuine (non-connectivity)
                    // errors reach the global bus and the error log; connectivity misses are logged quietly.
                    if (result.error.isConnectivityError()) {
                        logger.debug { "Discover shelves unavailable offline: ${result.error.message}" }
                    } else {
                        errorBus.emit(result.error)
                        logger.error { "Failed to load discover shelves: ${result.error.message}" }
                    }
                    discoverShelvesState.value = DiscoverShelvesUiState.Error("Failed to load discover shelves")
                }
            }
        }
    }

    /**
     * Refresh all discovery content.
     * - Shelves: re-fetched via the discover RPC
     * - Books: new RANDOM() selection via refresh trigger
     * - Sessions & recently added: automatically updated via Room flows
     */
    fun refresh() {
        discoverBooksRefreshTrigger.update { it + 1 }
        loadDiscoverShelves()
    }
}

/**
 * UI state for the Discover shelves section.
 */
sealed interface DiscoverShelvesUiState {
    /** Pre-first-emission placeholder. */
    data object Loading : DiscoverShelvesUiState

    /** Shelves grouped by owner, loaded via RPC. */
    data class Ready(
        val users: List<DiscoverUserShelves>,
    ) : DiscoverShelvesUiState {
        val isEmpty: Boolean
            get() = users.isEmpty()

        val totalShelfCount: Int
            get() = users.sumOf { it.shelves.size }
    }

    /** Upstream failure — section renders the message in error styling. */
    data class Error(
        val message: String,
    ) : DiscoverShelvesUiState
}

/**
 * User with their shelves for Discover screen.
 */
data class DiscoverUserShelves(
    val user: DiscoverShelfOwner,
    val shelves: List<DiscoverShelfUi>,
)

/**
 * Shelf owner info for display.
 *
 * Avatar color is derived from [id] at the UI layer — the discovery contract
 * supplies owner identity but no avatar color.
 */
data class DiscoverShelfOwner(
    val id: String,
    val displayName: String,
)

/**
 * Shelf UI model for Discover screen.
 */
data class DiscoverShelfUi(
    val id: String,
    val name: String,
    val description: String?,
    val bookCount: Int,
    val totalDurationSeconds: Long,
)

/**
 * UI state for the "What Others Are Listening To" section.
 */
sealed interface CurrentlyListeningUiState {
    /** Pre-first-emission placeholder. */
    data object Loading : CurrentlyListeningUiState

    /** Active sessions loaded from Room. */
    data class Ready(
        val sessions: List<CurrentlyListeningUiSession>,
    ) : CurrentlyListeningUiState {
        val isEmpty: Boolean
            get() = sessions.isEmpty()
    }

    /** Upstream failure — section renders the message in error styling. */
    data class Error(
        val message: String,
    ) : CurrentlyListeningUiState
}

/**
 * UI state for the "Discover Something New" section.
 */
sealed interface DiscoverBooksUiState {
    /** Pre-first-emission placeholder. */
    data object Loading : DiscoverBooksUiState

    /** Random unstarted books from Room. */
    data class Ready(
        val books: List<DiscoverUiBook>,
    ) : DiscoverBooksUiState {
        val isEmpty: Boolean
            get() = books.isEmpty()
    }

    /** Upstream failure — section renders the message in error styling. */
    data class Error(
        val message: String,
    ) : DiscoverBooksUiState
}

/**
 * UI state for the "Recently Added" section.
 */
sealed interface RecentlyAddedUiState {
    /** Pre-first-emission placeholder. */
    data object Loading : RecentlyAddedUiState

    /** Newly added books loaded from Room. */
    data class Ready(
        val books: List<RecentlyAddedUiBook>,
    ) : RecentlyAddedUiState {
        val isEmpty: Boolean
            get() = books.isEmpty()
    }

    /** Upstream failure — section renders the message in error styling. */
    data class Error(
        val message: String,
    ) : RecentlyAddedUiState
}

// === UI Model Types ===

/**
 * Active session for "What Others Are Listening To".
 * Represents a single user listening to a single book.
 */
data class CurrentlyListeningUiSession(
    val sessionId: String,
    val userId: String,
    val bookId: String,
    val bookTitle: String,
    val authorName: String?,
    val coverPath: String?,
    val coverHash: String?,
    val coverBlurHash: String?,
    val displayName: String,
    val startedAt: Long,
)

/**
 * Book for "Discover Something New" with resolved local cover path.
 */
data class DiscoverUiBook(
    val id: String,
    val title: String,
    val authorName: String?,
    val coverPath: String?,
    val coverBlurHash: String?,
    val coverHash: String?,
    val seriesName: String?,
)

/**
 * Book for "Recently Added" with resolved local cover path.
 */
data class RecentlyAddedUiBook(
    val id: String,
    val title: String,
    val authorName: String?,
    val coverPath: String?,
    val coverBlurHash: String?,
    val coverHash: String?,
    val createdAt: Long,
)

/**
 * One entry in the Discover "Live now" row — an [OpenCampfireSummary] paired with its [book] for
 * cover/title rendering (see [DiscoverViewModel.liveCampfiresUiState]'s KDoc for why the join
 * happens here rather than server-side).
 */
data class LiveCampfireUiModel(
    val summary: OpenCampfireSummary,
    val book: BookListItem,
)

/**
 * Connectivity faults (offline / timed-out) are expected states on a background load, not
 * snackbar-worthy errors. Matched by typed subtype — never by `message` string.
 */
private fun AppError.isConnectivityError(): Boolean =
    this is TransportError.NetworkUnavailable || this is TransportError.Timeout
