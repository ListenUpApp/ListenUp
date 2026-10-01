package com.calypsan.listenup.client.presentation.hardcover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.hardcover.HardcoverBookMatch
import com.calypsan.listenup.api.dto.hardcover.HardcoverBookSync
import com.calypsan.listenup.api.dto.hardcover.HardcoverConnection
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.getOrNull
import com.calypsan.listenup.client.domain.repository.HardcoverRepository
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.error.ErrorBus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

/** How long a fresh match reads "Matched just now" — the same minute "Last synced just now" uses. */
private val JUST_MATCHED_WINDOW = 1.minutes

/** The Hardcover row on Book Detail. */
sealed interface BookHardcoverUiState {
    /** No row: not connected (or broken), never matched, or Hardcover's answer isn't known. */
    data object Hidden : BookHardcoverUiState

    /** ListenUp couldn't tell which Hardcover book this is: offer Find on Hardcover. */
    data object NeedsMatch : BookHardcoverUiState

    /**
     * Matched to [match]; [sync] says where it stands. Offers Change match and Remove match.
     * [justMatched] is true for the first minute after this device made the match: the row then says
     * "Matched just now" in place of [sync].
     */
    data class Linked(
        val match: HardcoverMatchedBook,
        val sync: HardcoverBookSync,
        val justMatched: Boolean = false,
    ) : BookHardcoverUiState
}

/**
 * Backs the Hardcover row on Book Detail for [bookId] (spec B5). The row exists only for a connected
 * user whose book is matched or needs a match; everything else — including a failed read — shows no
 * row, because the settings screen is where a connection's trouble is explained.
 *
 * The match is live server state, re-read whenever the connection syncs and whenever this client
 * changes this book's match. Offline, the connection stream doesn't answer, so the row stays hidden.
 *
 * "Matched just now" reads [HardcoverRepository.linkedAt] on every re-read rather than listening for
 * the link itself: Book Detail is usually off screen (and unsubscribed) while Find on Hardcover makes
 * the link, so an event would be missed and a timestamp is not.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookHardcoverViewModel(
    private val bookId: String,
    private val repository: HardcoverRepository,
    private val errorBus: ErrorBus,
    private val clock: Clock = Clock.System,
) : ViewModel() {
    /** The row's state: [BookHardcoverUiState.Hidden] until it is known. */
    val uiState: StateFlow<BookHardcoverUiState> =
        combine(
            repository
                .observeConnection()
                .map { (it as? HardcoverConnection.Connected)?.let { connected -> connected.lastSyncedAt ?: 0L } }
                .distinctUntilChanged(),
            repository.matchChanges
                .filter { it.value == bookId }
                .map { }
                .onStart { emit(Unit) },
        ) { syncMark, _ -> syncMark }
            .flatMapLatest { syncMark ->
                if (syncMark == null) {
                    flowOf<BookHardcoverUiState>(BookHardcoverUiState.Hidden)
                } else {
                    flow { emitRow(repository.bookMatch(BookId(bookId)).getOrNull().toUiState()) }
                }
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
                BookHardcoverUiState.Hidden,
            )

    /** Removes the book's match: it then needs one, and the row offers Find on Hardcover. A refusal goes to [errorBus]. */
    fun removeMatch() {
        viewModelScope.launch {
            val result = repository.unlinkBook(BookId(bookId))
            if (result is AppResult.Failure) errorBus.emit(result.error)
        }
    }

    /** Emits [row]; a match this device made under a minute ago first reads as just made, until the minute is up. */
    private suspend fun FlowCollector<BookHardcoverUiState>.emitRow(row: BookHardcoverUiState) {
        val linkedAt = repository.linkedAt(BookId(bookId))
        val sinceLinked = linkedAt?.let { clock.now() - it }
        if (row !is BookHardcoverUiState.Linked || sinceLinked == null || sinceLinked >= JUST_MATCHED_WINDOW) {
            emit(row)
            return
        }
        emit(row.copy(justMatched = true))
        delay(JUST_MATCHED_WINDOW - sinceLinked)
        emit(row)
    }
}

private fun HardcoverBookMatch?.toUiState(): BookHardcoverUiState =
    when (this) {
        null, HardcoverBookMatch.Unmatched -> BookHardcoverUiState.Hidden
        HardcoverBookMatch.NeedsMatch -> BookHardcoverUiState.NeedsMatch
        is HardcoverBookMatch.Linked -> BookHardcoverUiState.Linked(toMatchedBook(), sync)
    }
