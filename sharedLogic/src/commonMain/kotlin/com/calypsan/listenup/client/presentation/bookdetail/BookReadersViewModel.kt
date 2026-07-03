package com.calypsan.listenup.client.presentation.bookdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.domain.readers.BookReaders
import com.calypsan.listenup.client.core.fallbackTo
import com.calypsan.listenup.client.domain.repository.BookReadersRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
 * emission to a sealed [BookReadersUiState]. The repository observes the server's `bookReadership`
 * RPC, which returns every reader of this book — including the current user — each with their
 * current progress and dated finish history. The repository is offline-first: Room's
 * `book_readership` mirror is the read source, refreshed on presence pings, and is left intact on
 * RPC failure — so the Readers section keeps rendering the last-known (possibly stale) readership
 * when the server is unreachable.
 *
 * @param repo The readers repository, backed by the server's bookReadership RPC.
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
            }.fallbackTo {
                BookReadersUiState.Error(isRetryable = true)
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = BookReadersUiState.Loading,
            )
}
