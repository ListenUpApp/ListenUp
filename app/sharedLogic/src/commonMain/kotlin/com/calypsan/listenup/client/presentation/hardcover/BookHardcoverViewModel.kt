package com.calypsan.listenup.client.presentation.hardcover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.hardcover.HardcoverBookMatch
import com.calypsan.listenup.api.dto.hardcover.HardcoverBookSync
import com.calypsan.listenup.api.dto.hardcover.HardcoverConnection
import com.calypsan.listenup.api.result.getOrNull
import com.calypsan.listenup.client.domain.repository.HardcoverRepository
import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

/** The Hardcover row on Book Detail. */
sealed interface BookHardcoverUiState {
    /** No row: not connected (or broken), never matched, or Hardcover's answer isn't known. */
    data object Hidden : BookHardcoverUiState

    /** ListenUp couldn't tell which Hardcover book this is: offer Find on Hardcover. */
    data object NeedsMatch : BookHardcoverUiState

    /** Matched to [match]; [sync] says where it stands. Offers Change match. */
    data class Linked(
        val match: HardcoverMatchedBook,
        val sync: HardcoverBookSync,
    ) : BookHardcoverUiState
}

/**
 * Backs the Hardcover row on Book Detail for [bookId] (spec B5). The row exists only for a connected
 * user whose book is matched or needs a match; everything else — including a failed read — shows no
 * row, because the settings screen is where a connection's trouble is explained.
 *
 * The match is live server state, re-read whenever the connection syncs and whenever this client
 * changes this book's match. Offline, the connection stream doesn't answer, so the row stays hidden.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookHardcoverViewModel(
    private val bookId: String,
    private val repository: HardcoverRepository,
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
                    flow { emit(repository.bookMatch(BookId(bookId)).getOrNull().toUiState()) }
                }
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
                BookHardcoverUiState.Hidden,
            )
}

private fun HardcoverBookMatch?.toUiState(): BookHardcoverUiState =
    when (this) {
        null, HardcoverBookMatch.Unmatched -> BookHardcoverUiState.Hidden
        HardcoverBookMatch.NeedsMatch -> BookHardcoverUiState.NeedsMatch
        is HardcoverBookMatch.Linked -> BookHardcoverUiState.Linked(toMatchedBook(), sync)
    }
