package com.calypsan.listenup.client.presentation.hardcover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.hardcover.HardcoverConnection
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.getOrNull
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.HardcoverRepository
import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

/** How long a book that synced again stays out of the list waiting for the server's list to drop it. */
private const val SYNC_AGAIN_HANDOFF_MS = 2_000L

/** One book kept off Hardcover, as the list shows it: from the library on this device, so it has its cover. */
data class KeptOffBook(
    val bookId: String,
    val title: String,
    val authorNames: String,
    val coverPath: String?,
    val coverHash: String?,
)

/** What the Kept off Hardcover screen shows. */
sealed interface KeptOffBooksUiState {
    /** Waiting for the server's first answer. */
    data object Loading : KeptOffBooksUiState

    /** The kept-off books, by title. Empty once the last one syncs again; [KeptOffBooksEvent.SyncingAgain] closes the screen. */
    data class Loaded(
        val books: List<KeptOffBook>,
    ) : KeptOffBooksUiState

    /** The server couldn't say which books are kept off: nothing is claimed, least of all an empty list. */
    data object Unavailable : KeptOffBooksUiState
}

/** One-shot effects the Kept off Hardcover screen performs. */
sealed interface KeptOffBooksEvent {
    /** [title] syncs with Hardcover again: say so. [wasLast] when no kept-off book is left, so the screen closes. */
    data class SyncingAgain(
        val title: String,
        val wasLast: Boolean,
    ) : KeptOffBooksEvent

    /** Sync again was refused: show [error]; the book is back in the list. */
    data class ShowError(
        val error: AppError,
    ) : KeptOffBooksEvent
}

/**
 * Backs Settings → Account → Hardcover → Kept off Hardcover (#1541): the books the listener keeps off Hardcover,
 * named from the library on this device and ordered by title, each with Sync again.
 *
 * The list is server state, re-read whenever the connection's kept-off count moves and whenever this client
 * keeps a book off or syncs one again. Sync again is optimistic: the book leaves at once, and stays gone until
 * the server's list drops it (or [SYNC_AGAIN_HANDOFF_MS] passes); a refusal puts it back and says why.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeptOffBooksViewModel(
    private val repository: HardcoverRepository,
    private val bookRepository: BookRepository,
) : ViewModel() {
    /** Books whose Sync again is saving or has just landed: kept out of the list until the server agrees. */
    private val leaving = MutableStateFlow<Set<String>>(emptySet())
    private val eventChannel = Channel<KeptOffBooksEvent>(Channel.BUFFERED)

    /** One-shot effects — say a book syncs again, or show an error. Each is delivered once. */
    val events: Flow<KeptOffBooksEvent> = eventChannel.receiveAsFlow()

    /** The server's list, named and ordered; null when it couldn't be read or there is no connection. */
    private val serverBooks: SharedFlow<List<KeptOffBook>?> =
        combine(
            repository
                .observeConnection()
                .map { (it as? HardcoverConnection.Connected)?.keptOffBookCount }
                .distinctUntilChanged(),
            repository.matchChanges.map { }.onStart { emit(Unit) },
        ) { count, _ -> count }
            .flatMapLatest { count ->
                if (count == null) {
                    flowOf<List<KeptOffBook>?>(null)
                } else {
                    flow { emit(repository.keptOffBooks().getOrNull()?.map { it.value }) }
                        .flatMapLatest { ids -> if (ids == null) flowOf(null) else booksNamed(ids) }
                }
            }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), replay = 1)

    /** The screen's state: [KeptOffBooksUiState.Loading] until the server first answers. */
    val uiState: StateFlow<KeptOffBooksUiState> =
        combine(serverBooks, leaving) { books, gone ->
            if (books == null) {
                KeptOffBooksUiState.Unavailable
            } else {
                KeptOffBooksUiState.Loaded(books.filterNot { it.bookId in gone })
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), KeptOffBooksUiState.Loading)

    /** Syncs [bookId] with Hardcover again. Ignored for a book not in the list, and while its Sync again saves. */
    fun syncAgain(bookId: String) {
        // [leaving] itself, not [uiState]: the shown list catches up with it only once the combine runs.
        if (bookId in leaving.value) return
        val shown = uiState.value as? KeptOffBooksUiState.Loaded ?: return
        val book = shown.books.firstOrNull { it.bookId == bookId } ?: return
        val wasLast = shown.books.size == 1
        leaving.update { it + bookId }
        viewModelScope.launch {
            when (val result = repository.setBookSynced(BookId(bookId), synced = true)) {
                is AppResult.Success -> {
                    eventChannel.send(KeptOffBooksEvent.SyncingAgain(book.title, wasLast))
                    withTimeoutOrNull(SYNC_AGAIN_HANDOFF_MS) {
                        serverBooks.first { books -> books == null || books.none { it.bookId == bookId } }
                    }
                    leaving.update { it - bookId }
                }

                is AppResult.Failure -> {
                    leaving.update { it - bookId }
                    eventChannel.send(KeptOffBooksEvent.ShowError(result.error))
                }
            }
        }
    }

    private fun booksNamed(ids: List<String>): Flow<List<KeptOffBook>> =
        if (ids.isEmpty()) {
            flowOf(emptyList())
        } else {
            bookRepository.observeBookListItems(ids).map { items ->
                items.map { it.toKeptOffBook() }.sortedBy { it.title.lowercase() }
            }
        }
}

private fun BookListItem.toKeptOffBook() =
    KeptOffBook(
        bookId = id.value,
        title = title,
        authorNames = authors.joinToString(", ") { it.name },
        coverPath = coverPath,
        coverHash = coverHash,
    )
