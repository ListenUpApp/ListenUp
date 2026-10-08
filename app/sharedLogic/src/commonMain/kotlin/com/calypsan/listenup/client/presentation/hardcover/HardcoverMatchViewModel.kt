package com.calypsan.listenup.client.presentation.hardcover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.hardcover.HardcoverBookMatch
import com.calypsan.listenup.api.dto.hardcover.HardcoverMatchMethod
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.getOrNull
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.HardcoverRepository
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.error.ErrorBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

/** How long after a link Undo still puts it back: longer than any snackbar or toast offering it stays up. */
private val UNDO_WINDOW = 30.seconds

/** What a Hardcover catalog search is showing. */
sealed interface HardcoverSearchState {
    /** Asking Hardcover. */
    data object Searching : HardcoverSearchState

    /** What Hardcover found, ranked: candidates sharing the book's author first. */
    data class Results(
        val rows: List<HardcoverCandidateRow>,
    ) : HardcoverSearchState {
        /** The results by the book's own author, shown first under "By {author}". */
        val byAuthor: List<HardcoverCandidateRow> get() = rows.filter { it.sharesAuthor }

        /** Everything else, shown quieter under "Other results" — or alone, as "Results", when [byAuthor] is empty. */
        val others: List<HardcoverCandidateRow> get() = rows.filterNot { it.sharesAuthor }
    }

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
     * [suggestions] are other searches worth one tap ([HardcoverMatchViewModel.searchFor]): the title and
     * the author apart when nothing was found, or together when nothing found is by the book's author.
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
        val suggestions: List<String> = emptyList(),
    ) : HardcoverMatchUiState
}

/** One-shot effects of Find on Hardcover. */
sealed interface HardcoverMatchEvent {
    /**
     * [picked] is linked: close the screen, saying what it is matched to, with Undo. [replaced] is the
     * match it took the place of, or null for a book that had none — what [HardcoverMatchViewModel.undoLink] restores.
     */
    data class Linked(
        val picked: HardcoverCandidateRow,
        val replaced: HardcoverMatchedBook?,
    ) : HardcoverMatchEvent

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
 *
 * A pick links in one tap, with Undo offered after the screen has closed. That is why [undoLink] runs
 * on [appScope] and reports a failure to [errorBus]: by the time it is pressed, this screen and its
 * viewModelScope are usually gone.
 */
class HardcoverMatchViewModel(
    private val bookId: String,
    private val repository: HardcoverRepository,
    private val bookRepository: BookRepository,
    private val appScope: CoroutineScope,
    private val errorBus: ErrorBus,
    private val clock: Clock = Clock.System,
) : ViewModel() {
    private val subject = MutableStateFlow<MatchSubject>(MatchSubject.Loading)
    private val query = MutableStateFlow("")
    private val search = MutableStateFlow<HardcoverSearchState>(HardcoverSearchState.Searching)
    private val pending = MutableStateFlow<PendingAction>(PendingAction.None)
    private val eventChannel = Channel<HardcoverMatchEvent>(Channel.BUFFERED)
    private var searchJob: Job? = null
    private val lastLink = MutableStateFlow<LastLink?>(null)

    /** One-shot effects — close on success, or show an error. */
    val events: Flow<HardcoverMatchEvent> = eventChannel.receiveAsFlow()

    /** The screen's state. */
    val uiState: StateFlow<HardcoverMatchUiState> =
        combine(flow = subject, flow2 = query, flow3 = search, flow4 = pending) { subject, query, search, pending ->
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
                        suggestions = suggestionsFor(subject.book, query, search),
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

    /** Searches Hardcover for [text], as if typed and submitted — a suggestion tapped. */
    fun searchFor(text: String) {
        query.value = text
        search()
    }

    /** Links the book to search result [hcBookId], replacing any current match. Ignored while another action is in flight. */
    fun link(hcBookId: Long) {
        val row =
            (search.value as? HardcoverSearchState.Results)?.run { rows.firstOrNull { it.hcBookId == hcBookId } }
                ?: return
        if (!pending.compareAndSet(PendingAction.None, PendingAction.Linking(hcBookId))) return
        val replaced = (subject.value as? MatchSubject.Found)?.currentMatch
        viewModelScope.launch {
            try {
                val event =
                    when (val result = repository.linkBook(BookId(bookId), row.hcBookId, row.hcEditionId)) {
                        is AppResult.Success -> {
                            lastLink.value = LastLink(replaced, clock.now())
                            subject.update { current ->
                                (current as? MatchSubject.Found)?.copy(
                                    currentMatch = row.toMatchedBook(),
                                ) ?: current
                            }
                            HardcoverMatchEvent.Linked(row, replaced)
                        }

                        is AppResult.Failure -> {
                            HardcoverMatchEvent.ShowError(result.error)
                        }
                    }
                eventChannel.send(event)
            } finally {
                pending.value = PendingAction.None
            }
        }
    }

    /**
     * Puts back what the last link replaced — the previous book and edition, made the way it was made (an
     * ASIN match stays one, not the user's pick), or no match at all for a book that had none. A match
     * from a server that doesn't say how it was made is linked again as a pick. It works once, within [UNDO_WINDOW] of the link, and only while that link is still the
     * last thing this screen did; otherwise it does nothing.
     */
    fun undoLink() {
        val link = lastLink.getAndUpdate { null } ?: return
        if (clock.now() - link.at > UNDO_WINDOW) return
        appScope.launch {
            val previous = link.replaced
            val method = previous?.method
            val result =
                when {
                    previous == null -> {
                        repository.unlinkBook(BookId(bookId))
                    }

                    method != null -> {
                        repository.restoreMatch(
                            bookId = BookId(bookId),
                            hcBookId = previous.hcBookId,
                            hcEditionId = previous.hcEditionId,
                            method = method,
                        )
                    }

                    else -> {
                        repository.linkBook(BookId(bookId), previous.hcBookId, previous.hcEditionId)
                    }
                }
            when (result) {
                is AppResult.Success -> {
                    subject.update { current ->
                        (current as? MatchSubject.Found)?.copy(currentMatch = previous)
                            ?: current
                    }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                }
            }
        }
    }

    /** Removes the book's match: it then needs one, and its pushes wait. Ignored while another action is in flight. */
    fun removeMatch() {
        if (!pending.compareAndSet(PendingAction.None, PendingAction.Removing)) return
        lastLink.value = null
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

/**
 * Other searches worth offering after [search] for [query] found nothing useful for [book]: its title
 * and first author apart when nothing was found, both together when nothing found is by that author.
 * The search just made is never offered again.
 */
private fun suggestionsFor(
    book: BookListItem,
    query: String,
    search: HardcoverSearchState,
): List<String> {
    val author =
        book.authors
            .firstOrNull()
            ?.run { name.takeIf { it.isNotBlank() } }
    val candidates =
        when {
            search == HardcoverSearchState.NoResults -> {
                listOfNotNull(book.title, author)
            }

            search is HardcoverSearchState.Results && search.byAuthor.isEmpty() && author != null -> {
                listOf(
                    "${book.title} $author",
                )
            }

            else -> {
                emptyList()
            }
        }
    return candidates.filter { it.isNotBlank() && !it.equals(query.trim(), ignoreCase = true) }.distinct()
}

/** [HardcoverBookMatch.Linked] as the clients show it. */
internal fun HardcoverBookMatch.Linked.toMatchedBook() =
    HardcoverMatchedBook(
        hcBookId = hcBookId,
        title = title,
        authors = authors,
        releaseYear = releaseYear,
        chosenByYou = chosenByYou,
        hcEditionId = hcEditionId,
        method = method,
    )

/** A search result the user just picked, as the book's match now reads. */
private fun HardcoverCandidateRow.toMatchedBook() =
    HardcoverMatchedBook(
        hcBookId = hcBookId,
        title = title,
        authors = authors,
        releaseYear = releaseYear,
        chosenByYou = true,
        hcEditionId = hcEditionId,
        method = HardcoverMatchMethod.MANUAL,
    )

/** The last link this screen made: what it [replaced] (null when the book had no match), and when. */
private data class LastLink(
    val replaced: HardcoverMatchedBook?,
    val at: Instant,
)

/** What the screen is matching: the library's answer about the book, once it has one. */
private sealed interface MatchSubject {
    /** The book hasn't been read from the library yet. */
    data object Loading : MatchSubject

    /** The book is no longer in the library on this device. */
    data object Missing : MatchSubject

    /** The book, as the library has it, and its match today ([currentMatch], or null). */
    data class Found(
        val book: BookListItem,
        val currentMatch: HardcoverMatchedBook?,
    ) : MatchSubject
}

/** The one action in flight, so a second tap waits for the first. */
private sealed interface PendingAction {
    /** Nothing in flight. */
    data object None : PendingAction

    /** Linking the book to Hardcover book [hcBookId]. */
    data class Linking(
        val hcBookId: Long,
    ) : PendingAction

    /** Removing the book's match. */
    data object Removing : PendingAction
}
