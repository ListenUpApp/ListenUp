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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

/** How long a fresh match reads "Matched just now" — the same minute "Last synced just now" uses. */
private val JUST_MATCHED_WINDOW = 1.minutes

/** How long a flipped switch holds on screen waiting for the server's answer to carry it back. */
private const val SYNC_SWITCH_HANDOFF_MS = 2_000L

/** The Hardcover row on Book Detail. */
sealed interface BookHardcoverUiState {
    /** No row: not connected (or broken), or Hardcover's answer isn't known. */
    data object Hidden : BookHardcoverUiState

    /**
     * Connected, but never matched (#1541, decision 1): the row is only Sync with Hardcover, on, so a book can be
     * kept off before its first listen. Switching it off never asks first: nothing would leave.
     */
    data object Unmatched : BookHardcoverUiState

    /** ListenUp couldn't tell which Hardcover book this is: offer Find on Hardcover, beside Sync with Hardcover. */
    data object NeedsMatch : BookHardcoverUiState

    /**
     * Matched to [match]; [sync] says where it stands. Offers Change match and Remove match, beside Sync with
     * Hardcover. [justMatched] is true for the first minute after this device made the match: the row then says
     * "Matched just now" in place of [sync]. [keepOffRemoves] is what switching it off would take out of ListenUp,
     * so the platforms ask first; null when nothing visible would leave, and it switches off at once.
     */
    data class Linked(
        val match: HardcoverMatchedBook,
        val sync: HardcoverBookSync,
        val justMatched: Boolean = false,
        val keepOffRemoves: KeepOffRemoves? = null,
    ) : BookHardcoverUiState

    /**
     * Kept off Hardcover (#1541): nothing about the book is shared or brought in. [isResuming] while switching it
     * back on saves: the switch already reads on, until the server says which row comes back.
     */
    data class KeptOff(
        val isResuming: Boolean = false,
    ) : BookHardcoverUiState
}

/** What keeping a book off Hardcover takes out of ListenUp: the confirmation names exactly this. */
enum class KeepOffRemoves {
    /** Its Hardcover reads leave Readers. */
    READS,

    /** It comes off the To Read shelf Hardcover's Want to Read put it on. */
    TO_READ,

    /** Both. */
    READS_AND_TO_READ,
}

/**
 * Backs the Hardcover row on Book Detail for [bookId] (spec B5, #1541). The row exists for every book of a
 * connected user: never matched (the switch alone), needing a match, matched, or kept off Hardcover. Without a
 * connection, or on a failed read, there is no row, because the settings screen is where a connection's trouble
 * is explained.
 *
 * The match is live server state, re-read whenever the connection syncs and whenever this client changes
 * this book's match or keeps it off. Offline, the connection stream doesn't answer, so the row stays hidden.
 *
 * "Matched just now" reads [HardcoverRepository.linkedAt] on every re-read rather than listening for
 * the link itself: Book Detail is usually off screen (and unsubscribed) while Find on Hardcover makes
 * the link, so an event would be missed and a timestamp is not.
 *
 * [setSynced] is optimistic: the switch moves at once and holds until the server's answer carries it back
 * (or [SYNC_SWITCH_HANDOFF_MS] passes); a refusal goes back to what the server holds and reaches [errorBus].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookHardcoverViewModel(
    private val bookId: String,
    private val repository: HardcoverRepository,
    private val errorBus: ErrorBus,
    private val clock: Clock = Clock.System,
) : ViewModel() {
    /** The switch just flipped, shown until the server's answer carries it back; null when nothing is saving. */
    private val pendingSynced = MutableStateFlow<Boolean?>(null)

    /** The row as the server answers it, shared by [uiState] and the switch's handoff. */
    private val serverRow: SharedFlow<BookHardcoverUiState> =
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
            }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), replay = 1)

    /** The row's state: [BookHardcoverUiState.Hidden] until it is known. */
    val uiState: StateFlow<BookHardcoverUiState> =
        combine(serverRow, pendingSynced) { row, pending -> row.showingSynced(pending) }
            .stateIn(
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

    /**
     * Sync with Hardcover: off keeps the book off Hardcover, on syncs it again (#1541). The platforms ask first,
     * when [BookHardcoverUiState.Linked.keepOffRemoves] says something visible would leave. Ignored with no row,
     * when the switch already says so, and while a flip saves.
     */
    fun setSynced(synced: Boolean) {
        val shown = uiState.value
        if (shown == BookHardcoverUiState.Hidden || shown.isSyncOn == synced) return
        if (!pendingSynced.compareAndSet(expect = null, update = synced)) return
        viewModelScope.launch {
            try {
                when (val result = repository.setBookSynced(BookId(bookId), synced)) {
                    is AppResult.Success -> {
                        withTimeoutOrNull(SYNC_SWITCH_HANDOFF_MS) { serverRow.first { it.isSyncOn == synced } }
                    }

                    is AppResult.Failure -> {
                        errorBus.emit(result.error)
                    }
                }
            } finally {
                pendingSynced.value = null
            }
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

/** Whether the row's Sync with Hardcover switch reads on. */
private val BookHardcoverUiState.isSyncOn: Boolean
    get() = this !is BookHardcoverUiState.KeptOff || isResuming

/** [this] as a flip that is saving shows it: off collapses the row at once; on reads on at once. */
private fun BookHardcoverUiState.showingSynced(pending: Boolean?): BookHardcoverUiState =
    when {
        pending == false && this != BookHardcoverUiState.Hidden && this !is BookHardcoverUiState.KeptOff -> {
            BookHardcoverUiState.KeptOff()
        }

        pending == true && this is BookHardcoverUiState.KeptOff -> {
            BookHardcoverUiState.KeptOff(isResuming = true)
        }

        else -> {
            this
        }
    }

private fun HardcoverBookMatch?.toUiState(): BookHardcoverUiState =
    when (this) {
        null -> BookHardcoverUiState.Hidden
        HardcoverBookMatch.Unmatched -> BookHardcoverUiState.Unmatched
        HardcoverBookMatch.NeedsMatch -> BookHardcoverUiState.NeedsMatch
        HardcoverBookMatch.KeptOff -> BookHardcoverUiState.KeptOff()
        is HardcoverBookMatch.Linked -> BookHardcoverUiState.Linked(toMatchedBook(), sync, keepOffRemoves = keepOffRemoves())
    }

private fun HardcoverBookMatch.Linked.keepOffRemoves(): KeepOffRemoves? =
    when {
        readsInReaders && onToReadFromHardcover -> KeepOffRemoves.READS_AND_TO_READ
        readsInReaders -> KeepOffRemoves.READS
        onToReadFromHardcover -> KeepOffRemoves.TO_READ
        else -> null
    }
