package com.calypsan.listenup.web.features.match

import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.client.presentation.match.MatchReceiptUiState
import com.calypsan.listenup.client.presentation.match.MatchReceiptViewModel
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.Koin
import org.koin.core.parameter.parametersOf

/** The Match details receipt for one book or person: its state, Undo, and dismissing it. */
class MatchReceiptSession(
    val state: StateFlow<MatchReceiptUiState>,
    val undo: () -> Unit,
    val dismiss: () -> Unit,
    val close: () -> Unit,
)

/** How Book Detail or the contributor page gets the receipt session for its book or person. */
typealias OpenMatchReceipt = (subjectId: String) -> MatchReceiptSession

/** The production source: [MatchReceiptViewModel] for one book or contributor. */
fun graphMatchReceipt(koin: Koin): OpenMatchReceipt =
    { subjectId ->
        val receipt = koin.get<MatchReceiptViewModel> { parametersOf(subjectId) }
        val store = ViewModelStore().apply { put(subjectId, receipt) }
        MatchReceiptSession(
            state = receipt.state,
            undo = receipt::undo,
            dismiss = receipt::dismiss,
            close = store::clear,
        )
    }
