package com.calypsan.listenup.client.presentation.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.core.error.ErrorBus
import com.calypsan.listenup.client.core.error.ErrorMapper
import com.calypsan.listenup.client.core.fallbackTo
import com.calypsan.listenup.client.domain.model.ActiveSession
import com.calypsan.listenup.client.domain.model.Shelf
import com.calypsan.listenup.client.domain.model.ShelfCover
import com.calypsan.listenup.client.domain.repository.ActiveSessionRepository
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.DiscoveryBook
import com.calypsan.listenup.client.domain.repository.DiscoveryPick
import com.calypsan.listenup.client.domain.repository.Reachability
import com.calypsan.listenup.client.domain.repository.ServerReachability
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import com.calypsan.listenup.client.domain.repository.UserProfileRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.calypsan.listenup.client.presentation.WhileSubscribedForUi

private val logger = KotlinLogging.logger {}

/**
 * ViewModel for the Discover screen.
 *
 * Discovery data comes from a mix of local Room and RPC:
 * - What others are listening to: the ACL-filtered [com.calypsan.listenup.api.SocialService.currentlyListening]
 *   RPC, re-fetched on each presence nudge + firehose reconnect (via [ActiveSessionRepository]).
 *   Live listeners first, then each other person's most recently played book — the section stays
 *   useful on a server where nobody happens to be listening this minute
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
    bookRepository: BookRepository,
    activeSessionRepository: ActiveSessionRepository,
    private val authSession: AuthSession,
    private val shelfRepository: ShelfRepository,
    serverReachability: ServerReachability,
    userProfileRepository: UserProfileRepository,
    userRepository: UserRepository,
    private val errorBus: ErrorBus,
) : ViewModel() {
    // === Discover Shelves State (on-demand RPC) ===

    /**
     * Discovered shelves are an on-demand RPC read (other users' shelves never enter Room).
     * Loaded on init and refreshed via [refresh]; the latest result is held here.
     */
    val discoverShelvesState: StateFlow<DiscoverShelvesUiState>
        field = MutableStateFlow<DiscoverShelvesUiState>(DiscoverShelvesUiState.Loading)

    init {
        // Load discovered shelves on screen open (on-demand RPC, not Room-backed).
        loadDiscoverShelves()
    }

    /**
     * How many people Discover's social sections can draw on: [CommunitySize.Unknown] until the roster
     * has synced, then [CommunitySize.JustYou] or [CommunitySize.Others]. Read by Listening now, the
     * leaderboard and Shelves by others to choose their only-you treatment.
     */
    val communitySize: StateFlow<CommunitySize> =
        authSession.authState
            .flatMapLatest { auth ->
                if (auth is AuthState.Authenticated) {
                    userProfileRepository.observeRosterUserIds().map { ids -> communitySizeOf(ids, auth.userId.value) }
                } else {
                    flowOf(CommunitySize.Unknown)
                }
            }.fallbackTo { e ->
                logger.warn(e) { "Community size unavailable" }
                CommunitySize.Unknown
            }.stateIn(
                scope = viewModelScope,
                started = WhileSubscribedForUi,
                initialValue = CommunitySize.Unknown,
            )

    /** Who is looking, for the invitation's avatar and its admin-only button; null while signed out. */
    val viewer: StateFlow<DiscoverViewer?> =
        userRepository
            .observeCurrentUser()
            .map { user -> user?.let { DiscoverViewer(it.id.value, it.displayName, canInvite = it.isAdmin) } }
            .fallbackTo { e ->
                logger.warn(e) { "Viewer unavailable" }
                null
            }.stateIn(viewModelScope, WhileSubscribedForUi, initialValue = null)

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

    /**
     * Bumped by [refresh] to re-run a failed currently-listening observation.
     *
     * ⛔ The fallback sits INSIDE the [flatMapLatest], so a failure ends one run rather than the flow
     * the screen is subscribed to — see [discoverBooksDraws].
     */
    private val currentlyListeningRuns = MutableStateFlow(0)

    val currentlyListeningState: StateFlow<CurrentlyListeningUiState> =
        currentlyListeningRuns
            .flatMapLatest {
                combine(currentlyListeningFlow, serverReachability.state) { sessions, reachability ->
                    CurrentlyListeningUiState.Ready(
                        sessions = sessions.map { it.toUiModel() },
                        observedAtMs = sessions.maxOfOrNull { it.observedAtMs },
                        // Unknown (still connecting) is not stale: only a known-down server dates the roster.
                        isStale = reachability is Reachability.Unreachable,
                    ) as CurrentlyListeningUiState
                }.onStart { emit(CurrentlyListeningUiState.Loading) }
                    .fallbackTo { e ->
                        logger.error(e) { "Error observing currently listening" }
                        CurrentlyListeningUiState.Error(ErrorMapper.map(e))
                    }
            }.stateIn(
                scope = viewModelScope,
                started = WhileSubscribedForUi,
                initialValue = CurrentlyListeningUiState.Loading,
            )

    /**
     * Convert ActiveSession domain model to UI model. [ActiveSession.isLive] and
     * [ActiveSession.lastActiveAtMs] carry through untouched: the section renders "Listening now"
     * or a relative time off exactly those two, and the order they arrive in is already correct.
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
            displayName = user.displayName,
            lastActiveAt = lastActiveAtMs,
            isLive = isLive,
        )

    // === Discover Books State (Random Unstarted from Room) ===

    /**
     * Bumped by [refresh] to trigger a fresh random selection. We do not observe the
     * upstream flow reactively because the SQL uses RANDOM() — a live subscription
     * would reshuffle on every invalidation.
     *
     * ⛔ The fallback sits INSIDE the [flatMapLatest], so a failure ends one load rather than the
     * flow the screen is subscribed to. Outside it, the Error was the flow's last word: every later
     * [refresh] bumped a trigger nothing was collecting any more. `LibraryViewModel` has the same
     * shape for the same reason.
     *
     * Bumped by [shuffle] and [refresh].
     */
    private val discoverBooksDraws = MutableStateFlow(0)

    val discoverBooksState: StateFlow<DiscoverBooksUiState> =
        discoverBooksDraws
            .flatMapLatest {
                flow<DiscoverBooksUiState> {
                    val books = bookRepository.observeRandomUnstartedBooks(limit = 10).first()
                    // An empty draw means "started everything" only when the library has books at all.
                    val libraryHasBooks =
                        books.isNotEmpty() || bookRepository.observeRecentlyAddedBooks(limit = 1).first().isNotEmpty()
                    emit(
                        if (books.isEmpty() && libraryHasBooks) {
                            DiscoverBooksUiState.AllStarted
                        } else {
                            DiscoverBooksUiState.Ready(books = books.map { it.toDiscoverUiBook() })
                        },
                    )
                }.fallbackTo { e ->
                    logger.error(e) { "Error loading discover books" }
                    DiscoverBooksUiState.Error(ErrorMapper.map(e))
                }
            }.stateIn(
                scope = viewModelScope,
                started = WhileSubscribedForUi,
                initialValue = DiscoverBooksUiState.Loading,
            )

    /**
     * Draw ten different books for "Something new". Only the picks change: shelves, listeners and
     * recently added are left alone, so pressing Shuffle never costs a network call.
     */
    fun shuffle() {
        discoverBooksDraws.update { it + 1 }
    }

    /**
     * Convert a [DiscoveryPick] to its "Something new" card.
     */
    private fun DiscoveryPick.toDiscoverUiBook(): DiscoverUiBook =
        DiscoverUiBook(
            id = id,
            title = title,
            authorName = authorName,
            coverPath = coverPath,
            coverHash = coverHash,
            seriesName = seriesName,
            sequenceLabel = sequenceLabel,
            durationMs = durationMs,
        )

    // === Recently Added State (from Room) ===

    private val recentlyAddedRuns = MutableStateFlow(0)

    /**
     * Observe recently added books from Room with author info.
     * Sorted by createdAt timestamp descending.
     *
     * Restartable through [recentlyAddedRuns] for the same reason as [currentlyListeningState].
     */
    val recentlyAddedState: StateFlow<RecentlyAddedUiState> =
        recentlyAddedRuns
            .flatMapLatest {
                bookRepository
                    .observeRecentlyAddedBooks(limit = 10)
                    .map<_, RecentlyAddedUiState> { books ->
                        RecentlyAddedUiState.Ready(books = books.map { it.toRecentlyAddedUiBook() })
                    }.onStart { emit(RecentlyAddedUiState.Loading) }
                    .fallbackTo { e ->
                        logger.error(e) { "Error observing recently added books" }
                        RecentlyAddedUiState.Error(ErrorMapper.map(e))
                    }
            }.stateIn(
                scope = viewModelScope,
                started = WhileSubscribedForUi,
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
            coverHash = coverHash,
            createdAt = createdAt,
        )

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
            covers = covers,
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
                    if (result.error.isConnectivityFailure()) {
                        logger.debug { "Discover shelves unavailable offline: ${result.error.message}" }
                    } else {
                        errorBus.emit(result.error)
                        logger.error { "Failed to load discover shelves: ${result.error.message}" }
                    }
                    discoverShelvesState.value = DiscoverShelvesUiState.Error(result.error)
                }
            }
        }
    }

    /**
     * Refresh all discovery content.
     * - Shelves: re-fetched via the discover RPC
     * - Books: a new draw, as [shuffle]
     * - Sessions & recently added: live flows that update themselves, so only a FAILED one is
     *   restarted — re-subscribing a healthy one would say the same thing again
     */
    fun refresh() {
        discoverBooksDraws.update { it + 1 }
        retryListeningNow()
        retryRecentlyAdded()
        loadDiscoverShelves()
    }

    /** Try again on a failed Listening now: restarts that section only. */
    fun retryListeningNow() {
        if (currentlyListeningState.value is CurrentlyListeningUiState.Error) currentlyListeningRuns.update { it + 1 }
    }

    /** Try again on a failed Recently added: restarts that section only. */
    fun retryRecentlyAdded() {
        if (recentlyAddedState.value is RecentlyAddedUiState.Error) recentlyAddedRuns.update { it + 1 }
    }

    /** Try again on Shelves by others: shows its skeleton and refetches; nothing else moves. Picks retry is [shuffle]. */
    fun retryShelvesByOthers() {
        discoverShelvesState.value = DiscoverShelvesUiState.Loading
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

    /** The section failed; [error] says how, and a refresh retries it. */
    data class Error(
        val error: AppError,
    ) : DiscoverShelvesUiState {
        /** True when the failure is connectivity (offline, timed out): the section says it needs a connection. */
        val needsConnection: Boolean
            get() = error.isConnectivityFailure()
    }
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
 * One of another reader's public shelves on Discover.
 *
 * @property id The shelf.
 * @property name Its name.
 * @property description Its description, when it has one.
 * @property bookCount How many of its books the reader can access.
 * @property totalDurationSeconds Always 0 for a discovered shelf: its books never enter this device's
 *   mirror, so their lengths are unknown here.
 * @property covers Up to four cover tiles for its 2×2 mosaic, in shelf order; fewer when this device
 *   lacks some of the books.
 */
data class DiscoverShelfUi(
    val id: String,
    val name: String,
    val description: String?,
    val bookCount: Int,
    val totalDurationSeconds: Long,
    val covers: List<ShelfCover>,
)

/**
 * UI state for the "What Others Are Listening To" section.
 */
sealed interface CurrentlyListeningUiState {
    /** Pre-first-emission placeholder. */
    data object Loading : CurrentlyListeningUiState

    /**
     * The roster from Room: live listeners first, then each other person's most recent book.
     *
     * @property sessions The rows, in display order.
     * @property observedAtMs When the server last sent this roster (epoch ms); null when it is empty.
     * @property isStale True while the server is unreachable, so the roster may be [observedAtMs] old.
     */
    data class Ready(
        val sessions: List<CurrentlyListeningUiSession>,
        val observedAtMs: Long?,
        val isStale: Boolean,
    ) : CurrentlyListeningUiState {
        /** Whether nobody is shown at all. */
        val isEmpty: Boolean
            get() = sessions.isEmpty()

        /** People listening right now. */
        val liveCount: Int
            get() = sessions.count { it.isLive }

        /** People shown on the book they last played, within the server's recent window. */
        val recentCount: Int
            get() = sessions.count { !it.isLive }
    }

    /** The section failed; [error] says how, and a refresh retries it. */
    data class Error(
        val error: AppError,
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

    /** Every opener and standalone in the library is started: nothing left to suggest, yet the library is not empty. */
    data object AllStarted : DiscoverBooksUiState

    /** The section failed; [error] says how, and a refresh retries it. */
    data class Error(
        val error: AppError,
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

    /** The section failed; [error] says how, and a refresh retries it. */
    data class Error(
        val error: AppError,
    ) : RecentlyAddedUiState
}

// === UI Model Types ===

/**
 * One row of "What Others Are Listening To": one other person, on one book.
 *
 * [isLive] says which kind of row it is — someone listening right now, or someone shown on the book
 * they most recently played — and [lastActiveAt] is the single timestamp either kind carries. The
 * card renders "Listening now" for the former and a relative time for the latter.
 *
 * @property sessionId Stable identity for this row
 * @property userId The other user
 * @property bookId The book shown for them
 * @property bookTitle Title, enriched from the local library
 * @property authorName Author, enriched from the local library
 * @property coverPath Local cover file path, when the cover is cached
 * @property coverHash Cover version hash, for cache busting
 * @property displayName Their public display name
 * @property lastActiveAt Epoch-ms they were last active on [bookId]
 * @property isLive True when they are listening right now
 */
data class CurrentlyListeningUiSession(
    val sessionId: String,
    val userId: String,
    val bookId: String,
    val bookTitle: String,
    val authorName: String?,
    val coverPath: String?,
    val coverHash: String?,
    val displayName: String,
    val lastActiveAt: Long,
    val isLive: Boolean,
)

/**
 * A "Something new" card: a book the reader has not started, first in its series or standalone.
 *
 * @property id The book.
 * @property title Its title.
 * @property authorName Its primary author, when known.
 * @property coverPath The local cover file, or null when not downloaded here.
 * @property coverHash The cover's content version.
 * @property seriesName The series it starts; null when standalone (the card reads "Standalone").
 * @property sequenceLabel Its place in [seriesName] ("1"); null when standalone or unnumbered.
 * @property durationMs Its total length.
 */
data class DiscoverUiBook(
    val id: String,
    val title: String,
    val authorName: String?,
    val coverPath: String?,
    val coverHash: String?,
    val seriesName: String?,
    val sequenceLabel: String?,
    val durationMs: Long,
)

/**
 * Book for "Recently Added" with resolved local cover path.
 */
data class RecentlyAddedUiBook(
    val id: String,
    val title: String,
    val authorName: String?,
    val coverPath: String?,
    val coverHash: String?,
    val createdAt: Long,
)

/**
 * Connectivity faults (offline / timed-out) are expected states on a background load, not
 * snackbar-worthy errors. Matched by typed subtype — never by `message` string.
 */
internal fun AppError.isConnectivityFailure(): Boolean =
    this is TransportError.NetworkUnavailable || this is TransportError.Timeout
