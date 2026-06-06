package com.calypsan.listenup.client.presentation.bookdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.domain.readers.BookReaders
import com.calypsan.listenup.client.domain.repository.BookReadersRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * UI state for the Book Detail Readers section.
 *
 * Sealed hierarchy prevents illegal state combinations (e.g. "loaded but also errored").
 */
sealed interface BookReadersUiState {
    /** Initial state while the first Room emission is in flight. */
    data object Loading : BookReadersUiState

    /** Neither the current user nor anyone else is reading or has finished this book. */
    data object NoReaders : BookReadersUiState

    /** At least one listener or completion is present. */
    data class Data(
        val readers: BookReaders,
    ) : BookReadersUiState

    /**
     * The Room observation failed unexpectedly (e.g. database corruption).
     *
     * @property isRetryable Always true — the caller may recreate the ViewModel to retry.
     */
    data class Error(
        val isRetryable: Boolean,
    ) : BookReadersUiState
}

/**
 * Backs the Book Detail "Readers" section.
 *
 * Observes [BookReadersRepository.observeReadersFor] for the given [bookId] and maps each
 * emission to a sealed [BookReadersUiState]. The repository combines the current user's own reading
 * state (local, offline-capable) with other live listeners from the server, so the section shows
 * whenever the current user — or anyone else — is reading or has finished the book.
 *
 * @param repo The readers repository (current user + live others), refreshed on presence pings.
 * @param bookId The book whose readers this ViewModel tracks.
 */
class BookReadersViewModel(
    private val repo: BookReadersRepository,
    private val bookId: String,
) : ViewModel() {
    /** Current UI state derived from the RPC-backed readers observation. */
    val uiState: StateFlow<BookReadersUiState> =
        repo
            .observeReadersFor(bookId)
            .map { readers ->
                if (readers.readers.isEmpty()) {
                    BookReadersUiState.NoReaders
                } else {
                    BookReadersUiState.Data(readers)
                }
            }.catch { e ->
                if (e is CancellationException) throw e
                emit(BookReadersUiState.Error(isRetryable = true))
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = BookReadersUiState.Loading,
            )
}
