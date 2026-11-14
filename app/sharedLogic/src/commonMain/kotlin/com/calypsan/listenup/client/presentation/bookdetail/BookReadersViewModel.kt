package com.calypsan.listenup.client.presentation.bookdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.core.fallbackTo
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.model.LoadedBook
import com.calypsan.listenup.client.domain.readers.BookReaders
import com.calypsan.listenup.client.domain.readers.Reader
import com.calypsan.listenup.client.domain.readers.ReaderLine
import com.calypsan.listenup.client.domain.readers.flattenToLines
import com.calypsan.listenup.client.domain.repository.ActiveSessionRepository
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.BookReadersRepository
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.LoadedBookRepository
import com.calypsan.listenup.client.domain.repository.Reachability
import com.calypsan.listenup.client.domain.repository.ServerReachability
import com.calypsan.listenup.client.presentation.WhileSubscribedForUi
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

private val logger = KotlinLogging.logger {}

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
        /** Whether the player is playing this book now: the one way this device knows you are listening. */
        val youAreLive: Boolean = false,
        /**
         * The other readers listening to this book right now, by user id: fresh presence only, so empty while the
         * server is known to be unreachable. Never includes you; [youAreLive] says that.
         */
        val liveUserIds: Set<String> = emptySet(),
        /** The rows every platform draws, in order, each reading line live per [isLive]. */
        val lines: List<ReaderLine> = flattenToLines(readers.readers, youAreLive, liveUserIds),
        /** Where everyone else is in the book, for the chapter list's avatars. */
        val chapterReaders: ChapterReadership = ChapterReadership.None,
    ) : BookReadersUiState {
        /**
         * Whether [reader] is listening right now: you, while the player plays this book; anyone else, while fresh
         * presence says so. The Readers section's "Listening now" and the chapter list's ring read the same answer.
         */
        fun isLive(reader: Reader): Boolean =
            reader.currentProgressPct != null && if (reader.isYou) youAreLive else reader.userId in liveUserIds
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
 * It also places every other reader on the chapter they are in ([BookReadersUiState.Data.chapterReaders]), and says
 * who is listening right now, from the same presence roster Discover's Listening now reads.
 *
 * @param repo The readers repository, backed by the server's bookReadership RPC.
 * @param bookId The book whose readers this ViewModel tracks.
 * @param loadedBooks The player's loaded book: whether it is this one, playing, makes your own row live.
 * @param bookRepository The book's chapters, which the readers are placed on.
 * @param activeSessions The presence roster: who else is listening, and to what.
 * @param authSession Who is signed in, for the presence roster.
 * @param serverReachability Whether presence is fresh: a known-down server leaves nobody live.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookReadersViewModel(
    repo: BookReadersRepository,
    private val bookId: String,
    loadedBooks: LoadedBookRepository,
    bookRepository: BookRepository,
    activeSessions: ActiveSessionRepository,
    authSession: AuthSession,
    serverReachability: ServerReachability,
) : ViewModel() {
    /** Whether the player is playing this book: the one way this device knows you are listening right now. */
    private val youAreLive: Flow<Boolean> =
        loadedBooks
            .observe()
            .map { it is LoadedBook.Loaded && it.bookId == bookId && it.isPlaying }
            .distinctUntilChanged()

    /**
     * Who else is listening to this book right now. Presence is decoration on the readers, never a reason to withhold
     * them: nobody is live until the roster answers, and nobody is if it fails or the server is known to be down.
     */
    private val othersLive: Flow<Set<String>> =
        combine(
            authSession.authState
                .flatMapLatest { auth ->
                    if (auth is AuthState.Authenticated) {
                        activeSessions.observeActiveSessions(auth.userId.value)
                    } else {
                        flowOf(emptyList())
                    }
                }.map { sessions ->
                    sessions.filter { it.isLive && it.bookId == bookId }.map { it.userId }.toSet()
                }.onStart { emit(emptySet()) }
                .fallbackTo { e ->
                    logger.warn(e) { "Presence unavailable; showing readers with nobody live" }
                    emptySet()
                },
            serverReachability.state,
        ) { live, reachability ->
            // Unknown (still connecting) is not stale: only a known-down server dates the roster, as on Discover.
            if (reachability is Reachability.Unreachable) emptySet() else live
        }.distinctUntilChanged()

    /** Current UI state derived from the RPC-backed readers observation. */
    val uiState: StateFlow<BookReadersUiState> =
        combine(
            flow = repo.observeReadersFor(bookId),
            flow2 = youAreLive,
            flow3 = othersLive,
            flow4 = bookRepository.observeChapters(bookId),
        ) { readers, live, others, chapters ->
            if (readers.readers.isEmpty()) {
                BookReadersUiState.NoReaders
            } else {
                BookReadersUiState.Data(
                    readers = readers,
                    youAreLive = live,
                    liveUserIds = others,
                    chapterReaders = chapterReadership(readers.readers, chapters, others),
                )
            }
        }.fallbackTo {
            BookReadersUiState.Error(isRetryable = true)
        }.stateIn(
            scope = viewModelScope,
            started = WhileSubscribedForUi,
            initialValue = BookReadersUiState.Loading,
        )
}
