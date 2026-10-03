package com.calypsan.listenup.client.presentation.bookdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.ListenerAverage
import com.calypsan.listenup.client.domain.model.RatingLabels
import com.calypsan.listenup.client.domain.repository.BookRatingRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.core.error.ErrorBus
import com.calypsan.listenup.domain.ListenerRatingLimits
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
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
    currentUserId: Flow<String?>,
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

    private val isRefreshingExternal = MutableStateFlow(false)
    private val isCheckingExternal = MutableStateFlow(false)

    /** The block's state. */
    val state: StateFlow<BookRatingsUiState> =
        combine(
            repository.observeForBook(bookId),
            currentUserId,
            repository.observeExternalForBook(bookId).combine(repository.observeCombinedScore(bookId), ::Pair),
            userRepository.observeIsAdmin(),
            isRefreshingExternal.combine(isCheckingExternal, ::Pair),
        ) { ratings, me, (external, score), isAdmin, (refreshing, checking) ->
            BookRatingsUiState.Ready(
                listeners =
                    ratings.takeIf { it.isNotEmpty() }?.let { rs ->
                        ListenerAverage(averageHalfStars = rs.map { it.halfStars }.average(), count = rs.size)
                    },
                mine = ratings.firstOrNull { it.userId == me },
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
                    .collect { isCheckingExternal.value = it }
            }
        } finally {
            isCheckingExternal.value = false
        }
    }

    /** Rate the book [halfStars] (2..10) with an optional [note]. */
    fun rate(
        halfStars: Int,
        note: String?,
    ) {
        viewModelScope.launch { report(repository.rate(bookId, halfStars, note)) }
    }

    /** Remove my rating. */
    fun clear() {
        viewModelScope.launch { report(repository.clear(bookId)) }
    }

    /**
     * Re-fetch every enabled outside source for this book now — admin only
     * ([BookRatingsUiState.Ready.canRefresh]). A tap while one is already in flight is ignored.
     */
    fun refreshExternal() {
        if (!isRefreshingExternal.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                report(repository.refreshExternal(bookId))
            } finally {
                isRefreshingExternal.value = false
            }
        }
    }

    private fun report(result: AppResult<Unit>) {
        if (result is AppResult.Failure) errorBus.emit(result.error)
    }
}

/** How long "Checking Hardcover…" may hold the score row: a backstop past the server's own 20 s bound. */
private val EXTERNAL_CHECK_TIMEOUT = 30.seconds
