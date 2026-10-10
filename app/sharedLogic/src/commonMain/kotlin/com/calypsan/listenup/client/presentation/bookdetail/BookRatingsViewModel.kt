package com.calypsan.listenup.client.presentation.bookdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.ListenerAverage
import com.calypsan.listenup.client.domain.model.ListenerRating
import com.calypsan.listenup.client.domain.model.RatingLabels
import com.calypsan.listenup.client.domain.repository.BookRatingRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.core.error.ErrorBus
import com.calypsan.listenup.domain.ListenerRatingLimits
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * State and actions for rating one book. Everything is read from Room through [repository], so the
 * block works offline and updates the instant a rating syncs in from another device — both your
 * listeners' rating and the outside world's.
 *
 * `currentUserId` is who "me" is; the block stays [BookRatingsUiState.Loading] until it first
 * emits, so production passes the auth session's `signedInUserId()` — the identity the repository
 * writes under — which waits out startup instead of naming nobody for a frame.
 */
class BookRatingsViewModel(
    private val bookId: String,
    private val repository: BookRatingRepository,
    private val currentUserId: Flow<String?>,
    private val errorBus: ErrorBus,
    userRepository: UserRepository,
) : ViewModel() {
    /**
     * The rating's shape: the half-star range, the note cap and the stars label every platform
     * speaks. Exposed so native clients (Swift Export reaches only what a public signature names)
     * read the one definition instead of mirroring it.
     */
    val limits: ListenerRatingLimits = ListenerRatingLimits

    /**
     * Formats headline averages and compact counts the same way every platform does. Exposed here
     * because Swift Export only reaches a `:contract` top-level function when something in
     * `:app:sharedLogic`'s own surface uses it — see [RatingLabels].
     */
    val ratingLabels: RatingLabels = RatingLabels

    private val refreshingExternal = MutableStateFlow(false)
    private val checkingExternal = MutableStateFlow(false)
    private val pendingStars = MutableStateFlow<Int?>(null)

    private val eventChannel = Channel<BookRatingsEvent>(Channel.BUFFERED)

    /** One-shot effects: [BookRatingsEvent.RatingRemoved] after a [clear], for an Undo. */
    val events: Flow<BookRatingsEvent> = eventChannel.receiveAsFlow()

    /** What the last [clear] removed, until [undoClear] puts it back or another clear replaces it. */
    private var lastRemoved: ListenerRating? = null

    /** The block's state. */
    val state: StateFlow<BookRatingsUiState> =
        combine(
            flow = repository.observeForBook(bookId).combine(pendingStars, ::Pair),
            flow2 = currentUserId,
            flow3 = repository.observeExternalForBook(bookId).combine(repository.observeCombinedScore(bookId), ::Pair),
            flow4 = userRepository.observeIsAdmin(),
            flow5 = refreshingExternal.combine(checkingExternal, ::Pair),
        ) { (ratings, pending), me, (external, score), isAdmin, (refreshing, checking) ->
            BookRatingsUiState.Ready(
                listeners =
                    ratings.takeIf { it.isNotEmpty() }?.let { rs ->
                        ListenerAverage(averageHalfStars = rs.map { it.halfStars }.average(), count = rs.size)
                    },
                mine = ratings.firstOrNull { it.userId == me }.withPendingStars(pending, bookId, me),
                external = score,
                breakdown = external,
                canRefresh = isAdmin,
                isRefreshingExternal = refreshing,
                isCheckingExternal = checking,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookRatingsUiState.Loading)

    init {
        // Ratings on open (#1542): ask the server to fetch this book's Hardcover rating if it is missing or
        // stale, and hold the score row's space while it does. Every platform's Book Detail builds this
        // ViewModel per book, so this one call covers Android, iOS and web.
        viewModelScope.launch { followExternalCheck() }
    }

    /**
     * [BookRatingsUiState.Ready.isCheckingExternal] for as long as the server's check says it runs. It
     * clears when the check ends, fails, or outlasts [EXTERNAL_CHECK_TIMEOUT]; a failure is never shown,
     * because the rating arrives (or doesn't) through sync either way.
     */
    private suspend fun followExternalCheck() {
        try {
            withTimeoutOrNull(EXTERNAL_CHECK_TIMEOUT) {
                repository
                    .observeExternalCheck(bookId)
                    .catch { emit(false) }
                    .collect { checkingExternal.value = it }
            }
        } finally {
            checkingExternal.value = false
        }
    }

    /** Rate the book [halfStars] (2..10) with an optional [note]. */
    fun rate(
        halfStars: Int,
        note: String?,
    ) {
        viewModelScope.launch { report(repository.rate(bookId, halfStars, note)) }
    }

    /**
     * Rate the book [halfStars] (2..10) straight from the stars, keeping any note: what a tap, the end of a
     * drag, a key step or an accessibility adjustment commits. The stars show it at once; a refused save
     * puts them back and reports the error.
     */
    fun setStars(halfStars: Int) {
        pendingStars.value = halfStars
        viewModelScope.launch {
            when (val result = repository.rate(bookId, halfStars, myRating()?.note)) {
                is AppResult.Success -> {
                    settle(halfStars)
                }

                is AppResult.Failure -> {
                    pendingStars.compareAndSet(expect = halfStars, update = null)
                    errorBus.emit(result.error)
                }
            }
        }
    }

    /**
     * The signed-in listener's rating as Room holds it now, pending stars aside. Read from the repository,
     * never from [state]: with no subscriber, [state] is still [BookRatingsUiState.Loading], and a note
     * read from it would be lost.
     */
    private suspend fun myRating(): ListenerRating? {
        val me = currentUserId.first()
        return repository.observeForBook(bookId).first().firstOrNull { it.userId == me }
    }

    /**
     * Drops the pending [halfStars] once Room shows them, so the stars never flick back to the old value
     * for a frame. Bounded by [SETTLE_TIMEOUT]; a newer pick is never dropped by an older one's settle.
     */
    private suspend fun settle(halfStars: Int) {
        withTimeoutOrNull(SETTLE_TIMEOUT) {
            val me = currentUserId.first()
            repository.observeForBook(bookId).first { rows ->
                rows.firstOrNull { it.userId == me }?.halfStars ==
                    halfStars
            }
        }
        pendingStars.compareAndSet(expect = halfStars, update = null)
    }

    /**
     * Remove my rating, note and all. Android and web then offer Undo on [BookRatingsEvent.RatingRemoved];
     * iOS asks first when a note would be lost.
     */
    fun clear() {
        viewModelScope.launch {
            val removed = myRating()
            pendingStars.value = null
            when (val result = repository.clear(bookId)) {
                is AppResult.Success -> {
                    if (removed != null) {
                        lastRemoved = removed
                        eventChannel.send(BookRatingsEvent.RatingRemoved)
                    }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                }
            }
        }
    }

    /** Put back the rating, stars and note, that the last [clear] removed. A second Undo does nothing. */
    fun undoClear() {
        val removed = lastRemoved ?: return
        lastRemoved = null
        viewModelScope.launch { report(repository.rate(bookId, removed.halfStars, removed.note)) }
    }

    /**
     * Re-fetch every enabled outside source for this book now — admin only
     * ([BookRatingsUiState.Ready.canRefresh]). A tap while one is already in flight is ignored.
     */
    fun refreshExternal() {
        if (!refreshingExternal.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                report(repository.refreshExternal(bookId))
            } finally {
                refreshingExternal.value = false
            }
        }
    }

    private fun report(result: AppResult<Unit>) {
        if (result is AppResult.Failure) errorBus.emit(result.error)
    }
}

/** How long "Checking Hardcover…" may hold the score row: a backstop past the server's own 20 s bound. */
private val EXTERNAL_CHECK_TIMEOUT = 30.seconds

/** How long stars you just set may wait for Room to echo them before the overlay lets go. */
private val SETTLE_TIMEOUT = 2.seconds

/** This rating with [pending] stars over it, or a fresh rating of them when there was none. */
private fun ListenerRating?.withPendingStars(
    pending: Int?,
    bookId: String,
    me: String?,
): ListenerRating? =
    when {
        pending == null -> this
        this != null -> copy(halfStars = pending, fromHardcover = false)
        else -> ListenerRating(bookId = bookId, userId = me.orEmpty(), halfStars = pending, note = null, ratedAtMs = 0L)
    }
