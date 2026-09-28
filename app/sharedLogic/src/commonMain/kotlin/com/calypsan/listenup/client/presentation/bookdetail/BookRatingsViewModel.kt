package com.calypsan.listenup.client.presentation.bookdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.CombinedScore
import com.calypsan.listenup.client.domain.model.ExternalRating
import com.calypsan.listenup.client.domain.model.ListenerAverage
import com.calypsan.listenup.client.domain.model.ListenerRating
import com.calypsan.listenup.client.domain.model.combineExternalRatings
import com.calypsan.listenup.client.domain.repository.BookRatingRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.core.error.ErrorBus
import com.calypsan.listenup.domain.ListenerRatingLimits
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The rating block on Book Detail. */
sealed interface BookRatingsUiState {
    /** Not read yet. */
    data object Loading : BookRatingsUiState

    /**
     * @property listeners your listeners' average, or null when nobody has rated the book.
     * @property mine the signed-in listener's rating, or null when they haven't rated it.
     * @property external the outside-world headline score, or null when no enabled source has
     *   rated the book yet.
     * @property breakdown the per-source outside ratings backing [external], highest rating count
     *   first — the sheet one tap away from the headline.
     * @property canRefresh whether the signed-in listener may trigger [BookRatingsViewModel.refreshExternal]
     *   (admin or root).
     */
    data class Ready(
        val listeners: ListenerAverage?,
        val mine: ListenerRating?,
        val external: CombinedScore?,
        val breakdown: List<ExternalRating>,
        val canRefresh: Boolean,
    ) : BookRatingsUiState
}

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

    /** The block's state. */
    val state: StateFlow<BookRatingsUiState> =
        combine(
            repository.observeForBook(bookId),
            currentUserId,
            repository.observeExternalForBook(bookId),
            userRepository.observeIsAdmin(),
        ) { ratings, me, external, isAdmin ->
            BookRatingsUiState.Ready(
                listeners =
                    ratings.takeIf { it.isNotEmpty() }?.let { rs ->
                        ListenerAverage(averageHalfStars = rs.map { it.halfStars }.average(), count = rs.size)
                    },
                mine = ratings.firstOrNull { it.userId == me },
                external = combineExternalRatings(external),
                breakdown = external,
                canRefresh = isAdmin,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookRatingsUiState.Loading)

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

    /** Re-fetch every enabled outside source for this book now — admin only ([BookRatingsUiState.Ready.canRefresh]). */
    fun refreshExternal() {
        viewModelScope.launch { report(repository.refreshExternal(bookId)) }
    }

    private fun report(result: AppResult<Unit>) {
        if (result is AppResult.Failure) errorBus.emit(result.error)
    }
}
