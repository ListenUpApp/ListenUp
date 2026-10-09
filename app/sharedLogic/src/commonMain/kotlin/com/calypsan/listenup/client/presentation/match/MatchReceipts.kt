package com.calypsan.listenup.client.presentation.match

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.match.MatchReceipt
import com.calypsan.listenup.api.dto.match.UndoResult
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.MatchingRepository
import com.calypsan.listenup.core.error.ErrorBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Hands a fresh receipt from Match details to the page it returns to — Book Detail for a book, the contributor
 * page for a person. Match details is its own screen with its own ViewModel and returns to that page after Apply
 * on every layout, so the receipt crosses here: held in memory per subject (a book or contributor id; both are
 * UUIDs) until the page's receipt is dismissed, undone or expires.
 */
internal class MatchReceiptStore {
    private val receipts = MutableStateFlow<Map<String, MatchReceipt>>(emptyMap())

    /** The receipt waiting for each subject's page, by subject id. */
    val all: StateFlow<Map<String, MatchReceipt>> = receipts.asStateFlow()

    fun put(
        subjectId: String,
        receipt: MatchReceipt,
    ) = receipts.update { it + (subjectId to receipt) }

    fun clear(subjectId: String) = receipts.update { it - subjectId }
}

/**
 * Undoes one match. The receipt's Undo and Book Detail's "Undo last match" both call this, so the two can't behave
 * differently. Public only because [com.calypsan.listenup.client.presentation.bookdetail.BookDetailViewModel]'s
 * constructor is, which keeps that ViewModel inside `module.verify()`.
 */
interface UndoMatch {
    /** Undoes [receiptId]; the restored book or person reaches Room before this returns. */
    suspend operator fun invoke(receiptId: String): AppResult<UndoResult>
}

/** [UndoMatch] over the matching RPC. */
internal fun UndoMatch(matchingRepository: MatchingRepository): UndoMatch =
    object : UndoMatch {
        override suspend fun invoke(receiptId: String): AppResult<UndoResult> = matchingRepository.undoMatch(receiptId)
    }

/** The receipt on Book Detail, or on the contributor page, after Apply. */
sealed interface MatchReceiptUiState {
    /** No receipt to show. */
    data object None : MatchReceiptUiState

    /**
     * "Changed 5 fields, cover from Hardcover, 16 chapter names" with Undo and See what changed. It stays until
     * dismissed while a screen reader runs; otherwise the platform dismisses it after its usual snackbar time.
     */
    data class Shown(
        val receipt: MatchReceiptUi,
        val undoing: Boolean,
        val undoError: AppError?,
    ) : MatchReceiptUiState

    /** Undo restored everything the match changed. */
    data object Undone : MatchReceiptUiState

    /** The book or person changed since, so the match can't be undone; the receipt is gone. */
    data object Expired : MatchReceiptUiState
}

/**
 * The receipt for [subjectId] — a book on Book Detail, a person on the contributor page. Reads the receipt Match
 * details left in [MatchReceiptStore]; Undo goes through [UndoMatch]. [dismiss] clears the receipt (or the
 * Undone / Expired confirmation that replaced it).
 */
class MatchReceiptViewModel internal constructor(
    private val subjectId: String,
    private val receiptStore: MatchReceiptStore,
    private val undoMatch: UndoMatch,
    private val errorBus: ErrorBus,
) : ViewModel() {
    private val outcome = MutableStateFlow<Outcome>(Outcome.Idle)

    /** The receipt surface for this book. */
    val state: StateFlow<MatchReceiptUiState> =
        combine(receiptStore.all, outcome) { receipts, outcome ->
            when (outcome) {
                Outcome.Undone -> {
                    MatchReceiptUiState.Undone
                }

                Outcome.Expired -> {
                    MatchReceiptUiState.Expired
                }

                is Outcome.Idle, is Outcome.Undoing, is Outcome.UndoFailed -> {
                    receipts[subjectId]?.let { receipt ->
                        MatchReceiptUiState.Shown(
                            receipt = receipt.toUi(),
                            undoing = outcome is Outcome.Undoing,
                            undoError = (outcome as? Outcome.UndoFailed)?.error,
                        )
                    } ?: MatchReceiptUiState.None
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MatchReceiptUiState.None)

    /** Undoes the receipt's match; the restored book or person reaches Room before the state moves to Undone. */
    fun undo() {
        val receipt = receiptStore.all.value[subjectId] ?: return
        if (outcome.value is Outcome.Undoing) return
        outcome.value = Outcome.Undoing
        viewModelScope.launch {
            when (val result = undoMatch(receipt.receiptId)) {
                is AppResult.Success -> {
                    receiptStore.clear(subjectId)
                    outcome.value = Outcome.Undone
                }

                is AppResult.Failure -> {
                    if (result.error is MetadataError.UndoExpired) {
                        receiptStore.clear(subjectId)
                        outcome.value = Outcome.Expired
                    } else {
                        errorBus.emit(result.error)
                        outcome.value = Outcome.UndoFailed(result.error)
                    }
                }
            }
        }
    }

    /** Clears the receipt, or the confirmation shown after Undo. */
    fun dismiss() {
        if (outcome.value is Outcome.Undoing) return
        receiptStore.clear(subjectId)
        outcome.value = Outcome.Idle
    }

    private var closed = false

    /**
     * Cancels this ViewModel's coroutines — an in-flight Undo included. Idempotent. Android reaches it via
     * [onCleared]; iOS has no `ViewModelStore`, so its observer calls this from an `isolated deinit`; web clears
     * the session's store, which runs [onCleared].
     */
    fun close() {
        if (closed) return
        closed = true
        viewModelScope.cancel()
    }

    override fun onCleared() {
        close()
        super.onCleared()
    }

    private sealed interface Outcome {
        data object Idle : Outcome

        data object Undoing : Outcome

        /** Undo failed for a reason other than expiry; the receipt stays. */
        data class UndoFailed(
            val error: AppError,
        ) : Outcome

        data object Undone : Outcome

        data object Expired : Outcome
    }
}
