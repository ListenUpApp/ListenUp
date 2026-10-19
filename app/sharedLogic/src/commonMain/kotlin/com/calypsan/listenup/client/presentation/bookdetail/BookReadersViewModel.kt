package com.calypsan.listenup.client.presentation.bookdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.domain.model.LoadedBook
import com.calypsan.listenup.client.domain.readers.BookReaders
import com.calypsan.listenup.client.domain.readers.Reader
import com.calypsan.listenup.client.domain.readers.ReaderLine
import com.calypsan.listenup.client.domain.readers.flattenToLines
import com.calypsan.listenup.client.domain.repository.LoadedBookRepository
import com.calypsan.listenup.client.core.fallbackTo
import com.calypsan.listenup.client.domain.repository.BookReadersRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.calypsan.listenup.client.presentation.WhileSubscribedForUi

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
        /** Who else is reading, for the hero's social line. Derived from [readers]; never includes you. */
        val summary: ReadersSummary = readers.summary(),
        /** Whether the player is playing this book now: the only "Listening now" we know is true is yours. */
        val youAreLive: Boolean = false,
        /** The rows every platform draws, in order, with your reading line live only when [youAreLive]. */
        val lines: List<ReaderLine> = flattenToLines(readers.readers, youAreLive),
    ) : BookReadersUiState {
        /** Whether [reader] is listening right now, as far as this device knows: you, while it plays this book. */
        fun isLive(reader: Reader): Boolean = youAreLive && reader.isYou && reader.currentProgressPct != null
    }

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
 * @param loadedBooks The player's loaded book: whether it is this one, playing, makes your own row live.
 */
class BookReadersViewModel(
    repo: BookReadersRepository,
    private val bookId: String,
    loadedBooks: LoadedBookRepository,
) : ViewModel() {
    /** Whether the player is playing this book: the one way this device knows you are listening right now. */
    private val youAreLive: Flow<Boolean> =
        loadedBooks
            .observe()
            .map { it is LoadedBook.Loaded && it.bookId == bookId && it.isPlaying }
            .distinctUntilChanged()

    /** Current UI state derived from the RPC-backed readers observation. */
    val uiState: StateFlow<BookReadersUiState> =
        combine(repo.observeReadersFor(bookId), youAreLive) { readers, live ->
            if (readers.readers.isEmpty()) {
                BookReadersUiState.NoReaders
            } else {
                BookReadersUiState.Data(readers, youAreLive = live)
            }
        }.fallbackTo {
            BookReadersUiState.Error(isRetryable = true)
        }.stateIn(
            scope = viewModelScope,
            started = WhileSubscribedForUi,
            initialValue = BookReadersUiState.Loading,
        )
}
