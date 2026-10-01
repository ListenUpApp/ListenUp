package com.calypsan.listenup.client.presentation.hardcover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.hardcover.HardcoverBookMatch
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.getOrNull
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.HardcoverRepository
import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

/** What a Hardcover catalog search is showing. */
sealed interface HardcoverSearchState {
    /** Asking Hardcover. */
    data object Searching : HardcoverSearchState

    /** What Hardcover found, ranked: candidates sharing the book's author first. */
    data class Results(
        val rows: List<HardcoverCandidateRow>,
    ) : HardcoverSearchState

    /** Hardcover found nothing for the query. */
    data object NoResults : HardcoverSearchState

    /** The search failed with [error]; the screen offers to search again. */
    data class Failed(
        val error: AppError,
    ) : HardcoverSearchState
}

/** What Find on Hardcover shows. */
sealed interface HardcoverMatchUiState {
    /** Reading the book from the library. */
    data object Loading : HardcoverMatchUiState

    /** The book is no longer in the library on this device; there is nothing to match. */
    data object BookMissing : HardcoverMatchUiState

    /**
     * Matching [bookTitle] by [bookAuthors] (cover [coverPath] / [coverHash] for book [bookId]).
     * [query] is the search box; [search] what it found. [currentMatch] is the book's match today, or null.
     * [linkingId] is the candidate whose link is in flight; [isRemoving] while Remove match is in flight.
     */
    data class Ready(
        val bookId: String,
        val bookTitle: String,
        val bookAuthors: String,
        val coverPath: String?,
        val coverHash: String?,
        val query: String,
        val search: HardcoverSearchState,
        val currentMatch: HardcoverMatchedBook?,
        val linkingId: Long?,
        val isRemoving: Boolean,
    ) : HardcoverMatchUiState
}

/** One-shot effects of Find on Hardcover. */
sealed interface HardcoverMatchEvent {
    /** The pick is linked: close the screen. */
    data object Linked : HardcoverMatchEvent

    /** The match is removed: close the screen; the book now needs a match. */
    data object MatchRemoved : HardcoverMatchEvent

    /** Show [error]. */
    data class ShowError(
        val error: AppError,
    ) : HardcoverMatchEvent
}

/**
 * Backs Find on Hardcover for one book: opens with a search for its title, ranks what Hardcover finds
 * so the book's own author comes first ([rankCandidates]), links the user's pick, and removes a match.
 *
 * Searching costs Hardcover's per-user budget, so it runs on open and when the user submits — never
 * per keystroke. Linking replaces any current match in one step ("Change match"); removing it leaves
 * the book needing a match, its pushes parked, which is why it is its own explicit action.
 */
class HardcoverMatchViewModel(
    private val bookId: String,
    private val repository: HardcoverRepository,
    private val bookRepository: BookRepository,
) : ViewModel() {
    private val subject = MutableStateFlow<MatchSubject>(MatchSubject.Loading)
    private val query = MutableStateFlow("")
    private val search = MutableStateFlow<HardcoverSearchState>(HardcoverSearchState.Searching)
    private val pending = MutableStateFlow<PendingAction>(PendingAction.None)
    private val eventChannel = Channel<HardcoverMatchEvent>(Channel.BUFFERED)
    private var searchJob: Job? = null

    /** One-shot effects — close on success, or show an error. */
    val events: Flow<HardcoverMatchEvent> = eventChannel.receiveAsFlow()

    /** The screen's state. */
    val uiState: StateFlow<HardcoverMatchUiState> =
        combine(subject, query, search, pending) { subject, query, search, pending ->
            when (subject) {
                MatchSubject.Loading -> {
                    HardcoverMatchUiState.Loading
                }

                MatchSubject.Missing -> {
                    HardcoverMatchUiState.BookMissing
                }

                is MatchSubject.Found -> {
                    HardcoverMatchUiState.Ready(
                        bookId = bookId,
                        bookTitle = subject.book.title,
                        bookAuthors = subject.book.authors.joinToString(", ") { it.name },
                        coverPath = subject.book.coverPath,
                        coverHash = subject.book.coverHash,
                        query = query,
                        search = search,
                        currentMatch = subject.currentMatch,
                        linkingId = (pending as? PendingAction.Linking)?.hcBookId,
                        isRemoving = pending == PendingAction.Removing,
                    )
                }
            }
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            HardcoverMatchUiState.Loading,
        )

    init {
        // A one-shot load, not a collection: the book and its current match are read once on open.
        viewModelScope.launch { load() }
    }

    /** The search box changed. Nothing is searched until [search]. */
    fun onQueryChange(text: String) {
        query.value = text
    }

    /** Searches Hardcover for the query as typed. A blank query searches nothing. */
    fun search() {
        val book = (subject.value as? MatchSubject.Found)?.book ?: return
        val text = query.value.trim()
        if (text.isEmpty()) return
        runSearch(text, book)
    }

    /** Links the book to search result [hcBookId], replacing any current match. Ignored while another action is in flight. */
    fun link(hcBookId: Long) {
        val row =
            (search.value as? HardcoverSearchState.Results)?.rows?.firstOrNull { it.hcBookId == hcBookId } ?: return
        if (!pending.compareAndSet(PendingAction.None, PendingAction.Linking(hcBookId))) return
        viewModelScope.launch {
            try {
                val event =
                    when (val result = repository.linkBook(BookId(bookId), row.hcBookId, row.hcEditionId)) {
                        is AppResult.Success -> HardcoverMatchEvent.Linked
                        is AppResult.Failure -> HardcoverMatchEvent.ShowError(result.error)
                    }
                eventChannel.send(event)
            } finally {
                pending.value = PendingAction.None
            }
        }
    }

    /** Removes the book's match: it then needs one, and its pushes wait. Ignored while another action is in flight. */
    fun removeMatch() {
        if (!pending.compareAndSet(PendingAction.None, PendingAction.Removing)) return
        viewModelScope.launch {
            try {
                val event =
                    when (val result = repository.unlinkBook(BookId(bookId))) {
                        is AppResult.Success -> HardcoverMatchEvent.MatchRemoved
                        is AppResult.Failure -> HardcoverMatchEvent.ShowError(result.error)
                    }
                eventChannel.send(event)
            } finally {
                pending.value = PendingAction.None
            }
        }
    }

    private suspend fun load() {
        val book = bookRepository.getBookListItem(bookId)
        if (book == null) {
            subject.value = MatchSubject.Missing
            return
        }
        val current = repository.bookMatch(BookId(bookId)).getOrNull() as? HardcoverBookMatch.Linked
        subject.value = MatchSubject.Found(book, current?.toMatchedBook())
        query.value = book.title
        runSearch(book.title, book)
    }

    private fun runSearch(
        text: String,
        book: BookListItem,
    ) {
        searchJob?.cancel()
        searchJob =
            viewModelScope.launch {
                search.value = HardcoverSearchState.Searching
                search.value =
                    when (val result = repository.searchCatalog(text)) {
                        is AppResult.Success -> {
                            if (result.data.isEmpty()) {
                                HardcoverSearchState.NoResults
                            } else {
                                HardcoverSearchState.Results(rankCandidates(result.data, book.authors.map { it.name }))
                            }
                        }

                        is AppResult.Failure -> {
                            HardcoverSearchState.Failed(result.error)
                        }
                    }
            }
    }
}

/** [HardcoverBookMatch.Linked] as the clients show it. */
internal fun HardcoverBookMatch.Linked.toMatchedBook() =
    HardcoverMatchedBook(
        hcBookId = hcBookId,
        title = title,
        authors = authors,
        releaseYear = releaseYear,
        chosenByYou = chosenByYou,
    )

private sealed interface MatchSubject {
    data object Loading : MatchSubject

    data object Missing : MatchSubject

    data class Found(
        val book: BookListItem,
        val currentMatch: HardcoverMatchedBook?,
    ) : MatchSubject
}

private sealed interface PendingAction {
    data object None : PendingAction

    data class Linking(
        val hcBookId: Long,
    ) : PendingAction

    data object Removing : PendingAction
}
