package com.calypsan.listenup.client.presentation.readingorder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.ReadingOrder
import com.calypsan.listenup.client.domain.model.SeriesWithBooks
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.ReadingOrderRepository
import com.calypsan.listenup.client.domain.repository.SeriesRepository
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ReadingOrderId
import com.calypsan.listenup.core.error.ErrorBus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val STOP_TIMEOUT_MILLIS = 5_000L

/**
 * A book row inside a reading order — either a current member or an addable candidate.
 *
 * @property bookId The book's id.
 * @property title The book's title.
 * @property authorLine Comma-joined author names, or `null` when the book has no authors.
 * @property durationMs The book's total duration in milliseconds.
 * @property coverSeed Stable seed for cover resolution — the book id.
 */
data class OrderBookRowUi(
    val bookId: String,
    val title: String,
    val authorLine: String?,
    val durationMs: Long,
    val coverSeed: String,
)

/**
 * Sealed UI state for the reading-order detail screen.
 */
sealed interface ReadingOrderDetailUiState {
    /** No order selected (pre-[ReadingOrderDetailViewModel.load]). */
    data object Idle : ReadingOrderDetailUiState

    /** The `stateIn` initial value once an order is loaded — before the first emission. */
    data object Loading : ReadingOrderDetailUiState

    /** The loaded order id has no live row in the local mirror (deleted, or never existed). */
    data object NotFound : ReadingOrderDetailUiState

    /**
     * Order detail loaded and ready to render.
     *
     * @property order The reading order itself.
     * @property isOwner `true` when the caller owns [order] — gates edit/reorder/delete.
     * @property isActive `true` when [order] is the caller's active reading order for the
     *   loaded series.
     * @property books The order's member books, in the order's own sort order.
     * @property addableBooks Series books not yet in [books], in series sequence order —
     *   the candidate pool for [ReadingOrderDetailViewModel.addBooks].
     */
    data class Ready(
        val order: ReadingOrder,
        val isOwner: Boolean,
        val isActive: Boolean,
        val books: List<OrderBookRowUi>,
        val addableBooks: List<OrderBookRowUi>,
    ) : ReadingOrderDetailUiState
}

/**
 * One-shot events from [ReadingOrderDetailViewModel] the screen consumes exactly once.
 */
sealed interface ReadingOrderDetailEvent {
    /** The order was deleted; the screen should navigate back. */
    data object Deleted : ReadingOrderDetailEvent
}

/**
 * ViewModel for the reading-order detail screen — a single reading order's member books,
 * follow (active-order) state, and owner-gated editing (reorder/add/remove/delete).
 *
 * Offline-capable throughout: membership comes from the Room junction mirror
 * ([ReadingOrderRepository.observeReadingOrderBookIds]), joined against the local book
 * mirror preserving the junction's own sort order — never the book-repository's emission
 * order. Ownership is resolved from [authSession] with no RPC round-trip.
 *
 * @property readingOrderRepository Reading-order repository (Room-backed reads + writes).
 * @property bookRepository Book repository, for resolving member/addable book display fields.
 * @property seriesRepository Series repository, for the series' book roster (the addable pool).
 * @property authSession Current-user identity, for the offline-capable [ReadingOrderDetailUiState.Ready.isOwner] check.
 * @property errorBus Global error bus for command-failure snackbar emissions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadingOrderDetailViewModel(
    private val readingOrderRepository: ReadingOrderRepository,
    private val bookRepository: BookRepository,
    private val seriesRepository: SeriesRepository,
    private val authSession: AuthSession,
    private val errorBus: ErrorBus,
) : ViewModel() {
    private val keyFlow = MutableStateFlow<Key?>(null)

    private val _events = Channel<ReadingOrderDetailEvent>(Channel.BUFFERED)

    /** One-shot events (e.g. navigate back after delete). */
    val events: Flow<ReadingOrderDetailEvent> = _events.receiveAsFlow()

    /** The current screen UI state, driven by the loaded order's local mirror. */
    val state: StateFlow<ReadingOrderDetailUiState> =
        keyFlow
            .flatMapLatest { key ->
                if (key == null) {
                    flowOf(ReadingOrderDetailUiState.Idle)
                } else {
                    observeReady(key)
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = ReadingOrderDetailUiState.Idle,
            )

    /** Load [orderId]'s detail within the context of [seriesId]. Safe to call repeatedly. */
    fun load(
        orderId: ReadingOrderId,
        seriesId: String,
    ) {
        keyFlow.value = Key(orderId, seriesId)
    }

    /** Follow this order as the loaded series' active reading order. No-op before [load]. */
    fun setActive() {
        val key = keyFlow.value ?: return
        viewModelScope.launch {
            when (val result = readingOrderRepository.setActiveReadingOrder(key.seriesId, key.orderId)) {
                is AppResult.Success -> Unit
                is AppResult.Failure -> errorBus.emit(result.error)
            }
        }
    }

    /** Clear the series' active reading order, resetting to the per-book frontier floor. */
    fun clearActive() {
        val key = keyFlow.value ?: return
        viewModelScope.launch {
            when (val result = readingOrderRepository.setActiveReadingOrder(key.seriesId, null)) {
                is AppResult.Success -> Unit
                is AppResult.Failure -> errorBus.emit(result.error)
            }
        }
    }

    /** Reorder the order's member books to [orderedBookIds]. Owner-only; no-op otherwise. */
    fun reorder(orderedBookIds: List<String>) {
        val key = keyFlow.value ?: return
        val ready = state.value as? ReadingOrderDetailUiState.Ready ?: return
        if (!ready.isOwner) return
        viewModelScope.launch {
            when (val result = readingOrderRepository.reorderBooks(key.orderId, orderedBookIds.map(::BookId))) {
                is AppResult.Success -> Unit
                is AppResult.Failure -> errorBus.emit(result.error)
            }
        }
    }

    /** Add [bookIds] to the order, appended at the end. Owner-only; no-op otherwise. */
    fun addBooks(bookIds: List<String>) {
        val key = keyFlow.value ?: return
        val ready = state.value as? ReadingOrderDetailUiState.Ready ?: return
        if (!ready.isOwner) return
        viewModelScope.launch {
            when (val result = readingOrderRepository.addBooksToReadingOrder(key.orderId, bookIds.map(::BookId))) {
                is AppResult.Success -> Unit
                is AppResult.Failure -> errorBus.emit(result.error)
            }
        }
    }

    /** Remove [bookId] from the order. Owner-only; no-op otherwise. */
    fun removeBook(bookId: String) {
        val key = keyFlow.value ?: return
        val ready = state.value as? ReadingOrderDetailUiState.Ready ?: return
        if (!ready.isOwner) return
        viewModelScope.launch {
            when (val result = readingOrderRepository.removeBookFromReadingOrder(key.orderId, BookId(bookId))) {
                is AppResult.Success -> Unit
                is AppResult.Failure -> errorBus.emit(result.error)
            }
        }
    }

    /** Delete the order. On success, emits [ReadingOrderDetailEvent.Deleted]. */
    fun deleteOrder() {
        val key = keyFlow.value ?: return
        viewModelScope.launch {
            when (val result = readingOrderRepository.deleteReadingOrder(key.orderId)) {
                is AppResult.Success -> _events.trySend(ReadingOrderDetailEvent.Deleted)
                is AppResult.Failure -> errorBus.emit(result.error)
            }
        }
    }

    private fun observeReady(key: Key): Flow<ReadingOrderDetailUiState> =
        readingOrderRepository
            .observeById(key.orderId)
            .flatMapLatest { order ->
                if (order == null) {
                    flowOf(ReadingOrderDetailUiState.NotFound)
                } else {
                    observeOrderReady(order, key.seriesId)
                }
            }.onStart { emit(ReadingOrderDetailUiState.Loading) }

    private fun observeOrderReady(
        order: ReadingOrder,
        seriesId: String,
    ): Flow<ReadingOrderDetailUiState> {
        val userIdFlow = flow { emit(authSession.getUserId()) }

        return combine(
            readingOrderRepository.observeReadingOrderBookIds(order.id),
            readingOrderRepository.observeActiveReadingOrder(seriesId),
            seriesRepository.observeSeriesWithBooks(seriesId),
            userIdFlow,
        ) { bookIds, activeId, seriesWithBooks, userId ->
            Draft(order, bookIds, activeId, seriesWithBooks, userId)
        }.flatMapLatest { draft -> resolveBooks(draft) }
    }

    private fun resolveBooks(draft: Draft): Flow<ReadingOrderDetailUiState> {
        val memberIdStrings = draft.bookIds.map { it.value }
        return bookRepository.observeBookListItems(memberIdStrings).map { items -> finalize(draft, items) }
    }

    private fun finalize(
        draft: Draft,
        memberItems: List<BookListItem>,
    ): ReadingOrderDetailUiState.Ready {
        val itemsById = memberItems.associateBy { it.id.value }
        val books = draft.bookIds.mapNotNull { id -> itemsById[id.value]?.toOrderBookRowUi() }

        val memberIds = draft.bookIds.toSet()
        val addableBooks =
            draft.seriesWithBooks
                ?.booksSortedBySequence()
                .orEmpty()
                .filterNot { it.id in memberIds }
                .map { it.toOrderBookRowUi() }

        return ReadingOrderDetailUiState.Ready(
            order = draft.order,
            isOwner = draft.userId?.let { draft.order.isOwnedBy(it) } ?: false,
            isActive = draft.activeId == draft.order.id,
            books = books,
            addableBooks = addableBooks,
        )
    }

    private fun BookListItem.toOrderBookRowUi(): OrderBookRowUi =
        OrderBookRowUi(
            bookId = id.value,
            title = title,
            authorLine = authorNames.ifBlank { null },
            durationMs = duration,
            coverSeed = id.value,
        )

    /** Which order is loaded, and within which series' follow-state context. */
    private data class Key(
        val orderId: ReadingOrderId,
        val seriesId: String,
    )

    /** Everything the ready projection is derived from, pre-book-resolution. */
    private data class Draft(
        val order: ReadingOrder,
        val bookIds: List<BookId>,
        val activeId: ReadingOrderId?,
        val seriesWithBooks: SeriesWithBooks?,
        val userId: String?,
    )
}
