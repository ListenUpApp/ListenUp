package com.calypsan.listenup.client.presentation.bookdetail

import com.calypsan.listenup.api.dto.auth.Permission
import com.calypsan.listenup.api.dto.match.LastMatch
import com.calypsan.listenup.api.dto.match.MatchReceipt
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.BookMatchRecord
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.PermissionsRepository
import com.calypsan.listenup.client.domain.repository.UserProfileRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.presentation.match.MatchReceiptUi
import com.calypsan.listenup.client.presentation.match.UndoMatch
import com.calypsan.listenup.client.presentation.match.toUi
import com.calypsan.listenup.core.error.ErrorBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Book Detail's last-match row: "Details matched <relative time> · See what changed · Undo last match". Present
 * only while the book is still at the revision the match left it at, and only for someone who may undo it.
 *
 * [receipt] is the match as the receipt shows it, so See what changed lists the same lines the receipt does.
 * [matchedBy] names whoever matched the book when it wasn't you; null when you did, or when their profile has not
 * synced yet. [showingChanges] is See what changed open; [undoing] is Undo in flight; [undoError] is why the last
 * Undo failed, the row staying so it can be tried again.
 */
data class LastMatchUi(
    val receipt: MatchReceiptUi,
    val appliedAtMs: Long,
    val matchedBy: String?,
    val showingChanges: Boolean,
    val undoing: Boolean,
    val undoError: AppError?,
)

/** How "Undo last match" ended, said once. Success needs no state: the restored book retires the row. */
sealed interface LastMatchEvent {
    /** Everything the match changed is back. */
    data object Undone : LastMatchEvent

    /** The book changed since, so the match can't be undone; the row is gone. */
    data object Expired : LastMatchEvent
}

/** The row's transient state for one receipt: See what changed, Undo in flight, its error, or retired. */
internal data class LastMatchOverlay(
    val receiptId: String? = null,
    val showingChanges: Boolean = false,
    val undoing: Boolean = false,
    val undoError: AppError? = null,
    val retired: Boolean = false,
)

/** [this] match as the row shows it; null once [overlay] has retired it. */
internal fun LastMatch.toUi(
    matchedBy: String?,
    overlay: LastMatchOverlay,
): LastMatchUi? {
    val mine = overlay.takeIf { it.receiptId == receiptId } ?: LastMatchOverlay()
    if (mine.retired) return null
    return LastMatchUi(
        receipt =
            MatchReceipt(
                receiptId = receiptId,
                appliedAt = appliedAt,
                changes = changes,
                undoable = true,
            ).toUi(),
        appliedAtMs = appliedAt,
        matchedBy = matchedBy,
        showingChanges = mine.showingChanges,
        undoing = mine.undoing,
        undoError = mine.undoError,
    )
}

/**
 * Book Detail's last-match row, behind [BookDetailViewModel]'s `lastMatch`, `lastMatchEvents`, `seeWhatChanged`,
 * `closeWhatChanged` and `undoLastMatch`. Kept out of the ViewModel so the row's rules read in one place.
 *
 * The row is derived from Room ([BookRepository.observeMatchRecord]) — offline included — and shown only while
 * [BookMatchRecord.liveMatch] holds and the user has Edit metadata: the server refuses `undoMatch` without it,
 * exactly as it refuses Match details. Undo goes through the shared [UndoMatch], so the row and the receipt
 * behave alike: `UndoExpired` retires the row and says why; any other failure goes to [errorBus] and stays on the
 * row so it can be tried again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class LastMatchRow(
    bookIds: Flow<String>,
    private val scope: CoroutineScope,
    bookRepository: BookRepository,
    permissionsRepository: PermissionsRepository,
    userRepository: UserRepository,
    private val userProfileRepository: UserProfileRepository,
    private val undoMatch: UndoMatch,
    private val errorBus: ErrorBus,
) {
    private val overlay = MutableStateFlow(LastMatchOverlay())
    private val outcomes = Channel<LastMatchEvent>(Channel.BUFFERED)
    private val currentUserId = userRepository.observeCurrentUser().map { it?.idString }.distinctUntilChanged()

    /** One-shot: how the last Undo ended. */
    val events: Flow<LastMatchEvent> = outcomes.receiveAsFlow()

    /** The row, or null when there is no match to undo here (or no right to undo it). */
    val state: StateFlow<LastMatchUi?> =
        bookIds
            .flatMapLatest { bookId ->
                combine(
                    bookRepository.observeMatchRecord(bookId).map { it?.liveMatch }.distinctUntilChanged(),
                    permissionsRepository.observeCan(Permission.EDIT_METADATA),
                ) { match, canUndo -> match.takeIf { canUndo } }
            }.flatMapLatest { match ->
                if (match == null) {
                    flowOf(null)
                } else {
                    combine(matchedBy(match.appliedBy), overlay) { name, overlay -> match.toUi(name, overlay) }
                }
            }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    fun seeWhatChanged() {
        val receiptId = state.value?.receipt?.receiptId ?: return
        overlay.update { it.forReceipt(receiptId).copy(showingChanges = true) }
    }

    fun closeWhatChanged() {
        overlay.update { it.copy(showingChanges = false) }
    }

    fun undo() {
        val row = state.value ?: return
        if (row.undoing) return
        val receiptId = row.receipt.receiptId
        // Busy before the launch: a second press in the same frame must already see it.
        overlay.value = LastMatchOverlay(receiptId = receiptId, undoing = true)
        scope.launch {
            when (val result = undoMatch(receiptId)) {
                is AppResult.Success -> {
                    overlay.value = LastMatchOverlay(receiptId = receiptId, retired = true)
                    outcomes.send(LastMatchEvent.Undone)
                }

                is AppResult.Failure -> {
                    if (result.error is MetadataError.UndoExpired) {
                        overlay.value = LastMatchOverlay(receiptId = receiptId, retired = true)
                        outcomes.send(LastMatchEvent.Expired)
                    } else {
                        errorBus.emit(result.error)
                        overlay.value = LastMatchOverlay(receiptId = receiptId, undoError = result.error)
                    }
                }
            }
        }
    }

    /** Whoever matched the book, by display name — null when it was you, or their profile hasn't synced. */
    private fun matchedBy(userId: String): Flow<String?> =
        combine(currentUserId, userProfileRepository.observeProfile(userId)) { me, profile ->
            profile?.displayName?.takeUnless { me == userId }
        }.distinctUntilChanged()

    private fun LastMatchOverlay.forReceipt(receiptId: String): LastMatchOverlay =
        if (this.receiptId == receiptId) this else LastMatchOverlay(receiptId = receiptId)
}
