package com.calypsan.listenup.client.presentation.readingorder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.domain.model.ReadingOrder
import com.calypsan.listenup.client.domain.repository.ReadingOrderRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private const val STOP_TIMEOUT_MILLIS = 5_000L

/**
 * Sealed UI state for the "My Reading Orders" list.
 *
 * [Loading] is the `stateIn` initial value — emitted before the first Room query
 * completes. [Empty] is reached when the caller owns no reading orders. [Loaded]
 * carries the orders, most-recently-updated first.
 */
sealed interface ReadingOrderListUiState {
    /** Pre-first-emission placeholder — the `stateIn` initial value. */
    data object Loading : ReadingOrderListUiState

    /** The caller owns no reading orders. */
    data object Empty : ReadingOrderListUiState

    /**
     * Reading orders loaded and ready to render.
     *
     * @property readingOrders The caller's reading orders, most-recently-updated first.
     */
    data class Loaded(
        val readingOrders: List<ReadingOrder>,
    ) : ReadingOrderListUiState
}

/**
 * ViewModel for the "My Reading Orders" list.
 *
 * Stream-driven: observes the local Room mirror reactively (offline-first — the
 * sync engine keeps the mirror live), mapping each emission to the sealed
 * [ReadingOrderListUiState]. State is produced via
 * `stateIn(WhileSubscribed)` per the ViewModel rubric — no `init { collect }`.
 *
 * @property repository Reading-order repository (Room-backed reads).
 */
class ReadingOrderListViewModel(
    repository: ReadingOrderRepository,
) : ViewModel() {
    /** The current list UI state, driven by the local Room mirror. */
    val state: StateFlow<ReadingOrderListUiState> =
        repository
            .observeMyReadingOrders()
            .map { orders ->
                if (orders.isEmpty()) {
                    ReadingOrderListUiState.Empty
                } else {
                    ReadingOrderListUiState.Loaded(orders)
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = ReadingOrderListUiState.Loading,
            )
}
