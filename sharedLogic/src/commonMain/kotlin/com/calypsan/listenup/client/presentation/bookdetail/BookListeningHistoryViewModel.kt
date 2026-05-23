package com.calypsan.listenup.client.presentation.bookdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.domain.history.BookListeningHistory
import com.calypsan.listenup.client.domain.repository.BookListeningHistoryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Sealed UI state for the BookDetail "Your listening history" section.
 *
 * Four distinct surfaces prevent illegal state combinations (e.g. "loaded but also errored"):
 * [Loading] while the first Room emission is in flight, [Empty] when no events have been
 * recorded for this book, [Data] when at least one day bucket exists, and [Error] on
 * unexpected failures.
 */
sealed interface BookListeningHistoryUiState {
    /** Initial state while the first Room emission is in flight. */
    data object Loading : BookListeningHistoryUiState

    /** No listening events recorded for this book yet. */
    data object Empty : BookListeningHistoryUiState

    /** At least one day bucket of listening history is present. */
    data class Data(
        val history: BookListeningHistory,
    ) : BookListeningHistoryUiState

    /**
     * The Room observation failed unexpectedly (e.g. database corruption).
     *
     * @property isRetryable Always true — the caller may recreate the ViewModel to retry.
     */
    data class Error(
        val isRetryable: Boolean,
    ) : BookListeningHistoryUiState
}

/**
 * Backs the per-book listening-history section on BookDetail. Observes
 * [BookListeningHistoryRepository.observeFor] and maps emissions to a sealed
 * [BookListeningHistoryUiState]. Follows the canonical reactive-state pattern
 * pinned by `ViewModelUsesStateInWhileSubscribedRule`.
 *
 * @param repo The history repository providing reactive Room observation.
 * @param bookId The book whose listening history this ViewModel tracks.
 */
class BookListeningHistoryViewModel(
    private val repo: BookListeningHistoryRepository,
    private val bookId: String,
) : ViewModel() {
    /** Current UI state derived from the Room observation. */
    val uiState: StateFlow<BookListeningHistoryUiState> =
        repo
            .observeFor(bookId)
            .map { history ->
                if (history.daily.isEmpty()) {
                    BookListeningHistoryUiState.Empty
                } else {
                    BookListeningHistoryUiState.Data(history)
                }
            }.catch { e ->
                if (e is CancellationException) throw e
                emit(BookListeningHistoryUiState.Error(isRetryable = true))
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = BookListeningHistoryUiState.Loading,
            )
}
