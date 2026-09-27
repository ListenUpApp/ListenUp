package com.calypsan.listenup.client.presentation.bookdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.ListenerAverage
import com.calypsan.listenup.client.domain.model.ListenerRating
import com.calypsan.listenup.client.domain.repository.BookRatingRepository
import com.calypsan.listenup.core.error.ErrorBus
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
     */
    data class Ready(
        val listeners: ListenerAverage?,
        val mine: ListenerRating?,
    ) : BookRatingsUiState
}

/**
 * State and actions for rating one book. Everything is read from Room through [repository], so the
 * block works offline and updates the instant a rating syncs in from another device.
 */
class BookRatingsViewModel(
    private val bookId: String,
    private val repository: BookRatingRepository,
    currentUserId: Flow<String?>,
    private val errorBus: ErrorBus,
) : ViewModel() {
    /** The block's state. */
    val state: StateFlow<BookRatingsUiState> =
        combine(repository.observeForBook(bookId), currentUserId) { ratings, me ->
            BookRatingsUiState.Ready(
                listeners =
                    ratings.takeIf { it.isNotEmpty() }?.let { rs ->
                        ListenerAverage(averageHalfStars = rs.map { it.halfStars }.average(), count = rs.size)
                    },
                mine = ratings.firstOrNull { it.userId == me },
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

    private fun report(result: AppResult<Unit>) {
        if (result is AppResult.Failure) errorBus.emit(result.error)
    }
}
