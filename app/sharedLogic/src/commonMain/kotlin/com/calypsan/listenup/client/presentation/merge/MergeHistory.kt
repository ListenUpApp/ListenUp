package com.calypsan.listenup.client.presentation.merge

import com.calypsan.listenup.api.dto.MergeReceipt
import com.calypsan.listenup.api.dto.MergeUndoResult
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.MergeReceiptId
import com.calypsan.listenup.core.error.ErrorBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The "Merged into this" list for one series or genre, and its Undo (#1061).
 *
 * One implementation for both, on every platform: the series editor and the genre admin each own
 * one, pointed at their own repository's [load] and [undo]. Receipts live on the server, so the list
 * needs a connection; a failed load is [MergeHistoryState.Unavailable], never an empty list that
 * would look like "nothing was ever merged here".
 *
 * An undo runs once per receipt at a time, reports what moved back as [MergeUndoOutcome], and then
 * re-reads the list — the undone merge leaves it. A refused undo goes to the [errorBus] (its typed
 * message says why) and also re-reads, because the usual reason is that someone else already undid it.
 */
class MergeHistory internal constructor(
    private val scope: CoroutineScope,
    private val errorBus: ErrorBus,
    private val load: suspend () -> AppResult<List<MergeReceipt>>,
    private val undo: suspend (MergeReceiptId) -> AppResult<MergeUndoResult>,
) {
    private val mutableState = MutableStateFlow<MergeHistoryState>(MergeHistoryState.Loading)

    /** What to draw. */
    val state: StateFlow<MergeHistoryState> = mutableState.asStateFlow()

    /** Reads the list from the server — on open, and as the retry after [MergeHistoryState.Unavailable]. */
    fun refresh() {
        scope.launch { reload(outcome = (mutableState.value as? MergeHistoryState.Ready)?.outcome) }
    }

    /** Undoes the merge [receiptId]. Ignored while an undo is already running. */
    fun undo(receiptId: MergeReceiptId) {
        val ready = mutableState.value as? MergeHistoryState.Ready ?: return
        if (ready.undoingId != null) return
        val receipt = ready.receipts.firstOrNull { it.id == receiptId } ?: return
        mutableState.value = ready.copy(undoingId = receiptId, outcome = null)
        scope.launch {
            when (val result = undo.invoke(receiptId)) {
                is AppResult.Success -> {
                    reload(outcome = result.data.toOutcome(receipt.sourceName))
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    reload(outcome = null)
                }
            }
        }
    }

    private suspend fun reload(outcome: MergeUndoOutcome?) {
        mutableState.value =
            when (val result = load()) {
                is AppResult.Success -> MergeHistoryState.Ready(receipts = result.data, outcome = outcome)
                is AppResult.Failure -> MergeHistoryState.Unavailable(result.error)
            }
    }
}

/** What the "Merged into this" section shows. */
sealed interface MergeHistoryState {
    /** Reading the list from the server. */
    data object Loading : MergeHistoryState

    /** The list could not be read — usually no connection. [error]'s message says which. */
    data class Unavailable(
        val error: AppError,
    ) : MergeHistoryState

    /**
     * The merges that can still be undone, newest first; empty when there are none.
     *
     * @property undoingId the receipt whose undo is running, if any.
     * @property outcome what the last undo put back, until the next one starts.
     */
    data class Ready(
        val receipts: List<MergeReceipt>,
        val undoingId: MergeReceiptId? = null,
        val outcome: MergeUndoOutcome? = null,
    ) : MergeHistoryState
}

/**
 * What an undo put back, named so the screen can say it plainly: "Stormlite is back. 3 books moved
 * back; 1 had changed since and stayed where it was."
 *
 * @property restoredAtTopLevel a genre came back at the top of the tree because its old parent is gone.
 */
data class MergeUndoOutcome(
    val sourceName: String,
    val booksRestored: Int,
    val booksSkipped: Int,
    val restoredAtTopLevel: Boolean,
)

private fun MergeUndoResult.toOutcome(sourceName: String) =
    MergeUndoOutcome(
        sourceName = sourceName,
        booksRestored = booksRestored,
        booksSkipped = booksSkipped,
        restoredAtTopLevel = restoredAtTopLevel,
    )
