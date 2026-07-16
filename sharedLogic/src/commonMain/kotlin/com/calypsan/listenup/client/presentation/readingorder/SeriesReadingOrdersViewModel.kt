package com.calypsan.listenup.client.presentation.readingorder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.ReadingOrder
import com.calypsan.listenup.client.domain.repository.ReadingOrderRepository
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
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val STOP_TIMEOUT_MILLIS = 5_000L

/**
 * One reading-order row, as surfaced on the "Reading Orders for this series" screen.
 *
 * @property order The reading order itself.
 * @property isActive `true` when [order] is the caller's active reading order for the
 *   loaded series — the per-series spoiler clock, not a global "featured" flag.
 */
data class OrderRowUi(
    val order: ReadingOrder,
    val isActive: Boolean,
)

/**
 * Sealed UI state for the per-series reading-orders screen.
 */
sealed interface SeriesReadingOrdersUiState {
    /** No series selected (pre-[SeriesReadingOrdersViewModel.load]). */
    data object Idle : SeriesReadingOrdersUiState

    /** The `stateIn` initial value once a series is loaded — before the first emission. */
    data object Loading : SeriesReadingOrdersUiState

    /**
     * Reading orders loaded and ready to render.
     *
     * @property owned The caller's own reading orders for this series.
     * @property discovered Other users' public reading orders for this series, excluding
     *   any already present in [owned]. Empty when [discoverFailed] is `true`.
     * @property discoverFailed `true` when the on-demand discovery RPC failed — the
     *   section shows its own retry affordance rather than a global error toast.
     */
    data class Ready(
        val owned: List<OrderRowUi>,
        val discovered: List<OrderRowUi>,
        val discoverFailed: Boolean,
    ) : SeriesReadingOrdersUiState
}

/**
 * One-shot events from [SeriesReadingOrdersViewModel] the screen consumes exactly once.
 */
sealed interface SeriesReadingOrdersEvent {
    /** A new reading order was created; [orderId] navigates the screen to its detail. */
    data class Created(
        val orderId: String,
    ) : SeriesReadingOrdersEvent
}

/**
 * ViewModel for the "Reading Orders for this series" screen — the caller's own reading
 * orders for the series plus other users' discoverable ones, with the ability to follow
 * one as the active (spoiler-clock) order or create a new one.
 *
 * [owned] resolves reactively from the local Room mirror (offline-first). [discovered] is
 * an on-demand RPC read, re-fired via [retryDiscover] — its failure is a passive,
 * section-scoped condition ([SeriesReadingOrdersUiState.Ready.discoverFailed]), not routed
 * through [errorBus], since discovery is supplementary to the owned list.
 *
 * @property readingOrderRepository Reading-order repository (Room-backed reads + RPC writes).
 * @property errorBus Global error bus for command-failure snackbar emissions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SeriesReadingOrdersViewModel(
    private val readingOrderRepository: ReadingOrderRepository,
    private val errorBus: ErrorBus,
) : ViewModel() {
    private val seriesIdFlow = MutableStateFlow<String?>(null)
    private val refreshTrigger = MutableStateFlow(0)
    private var createInFlight = false

    private val _events = Channel<SeriesReadingOrdersEvent>(Channel.BUFFERED)

    /** One-shot events (e.g. navigate to a newly created order). */
    val events: Flow<SeriesReadingOrdersEvent> = _events.receiveAsFlow()

    /** The current screen UI state, driven by the loaded series' reading orders. */
    val state: StateFlow<SeriesReadingOrdersUiState> =
        seriesIdFlow
            .flatMapLatest { seriesId ->
                if (seriesId == null) {
                    flowOf(SeriesReadingOrdersUiState.Idle)
                } else {
                    observeReady(seriesId)
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = SeriesReadingOrdersUiState.Idle,
            )

    /** Load reading orders for [seriesId]. Safe to call repeatedly with the same id. */
    fun load(seriesId: String) {
        seriesIdFlow.value = seriesId
    }

    /** Re-fire the discovery RPC after a failure. */
    fun retryDiscover() {
        refreshTrigger.update { it + 1 }
    }

    /**
     * Follow [orderId] as the active reading order for the loaded series, or clear the
     * follow with `null`. No-op before [load].
     */
    fun setActive(orderId: ReadingOrderId?) {
        val seriesId = seriesIdFlow.value ?: return
        viewModelScope.launch {
            when (val result = readingOrderRepository.setActiveReadingOrder(seriesId, orderId)) {
                is AppResult.Success -> Unit
                is AppResult.Failure -> errorBus.emit(result.error)
            }
        }
    }

    /**
     * Create a new reading order. When [setActive] is `true`, the newly created order is
     * also followed for the loaded series. Emits [SeriesReadingOrdersEvent.Created] on
     * success. Double-taps while a create is already in flight are ignored.
     */
    fun createOrder(
        name: String,
        attribution: String?,
        isPrivate: Boolean,
        setActive: Boolean,
    ) {
        if (createInFlight) return
        val seriesId = seriesIdFlow.value ?: return
        createInFlight = true
        viewModelScope.launch {
            try {
                when (
                    val result =
                        readingOrderRepository.createReadingOrder(
                            name = name,
                            description = null,
                            attribution = attribution,
                            isPrivate = isPrivate,
                        )
                ) {
                    is AppResult.Success -> {
                        val order = result.data
                        if (setActive) {
                            when (val activeResult = readingOrderRepository.setActiveReadingOrder(seriesId, order.id)) {
                                is AppResult.Success -> Unit
                                is AppResult.Failure -> errorBus.emit(activeResult.error)
                            }
                        }
                        _events.trySend(SeriesReadingOrdersEvent.Created(order.idString))
                    }

                    is AppResult.Failure -> {
                        errorBus.emit(result.error)
                    }
                }
            } finally {
                createInFlight = false
            }
        }
    }

    private fun observeReady(seriesId: String): Flow<SeriesReadingOrdersUiState> {
        val discoverResultFlow =
            refreshTrigger.flatMapLatest {
                flow {
                    emit(
                        readingOrderRepository.discoverReadingOrders(),
                    )
                }
            }

        val ready: Flow<SeriesReadingOrdersUiState> =
            combine(
                readingOrderRepository.observeMyReadingOrders(),
                readingOrderRepository.observeActiveReadingOrder(seriesId),
                discoverResultFlow,
            ) { owned, activeId, discoverResult ->
                val ownedRows = owned.map { OrderRowUi(order = it, isActive = it.id == activeId) }
                val ownedIds = owned.map { it.id }.toSet()

                when (discoverResult) {
                    is AppResult.Success -> {
                        val discoveredRows =
                            discoverResult.data
                                .filterNot { it.id in ownedIds }
                                .map { OrderRowUi(order = it, isActive = it.id == activeId) }
                        SeriesReadingOrdersUiState.Ready(
                            owned = ownedRows,
                            discovered = discoveredRows,
                            discoverFailed = false,
                        )
                    }

                    is AppResult.Failure -> {
                        SeriesReadingOrdersUiState.Ready(
                            owned = ownedRows,
                            discovered = emptyList(),
                            discoverFailed = true,
                        )
                    }
                }
            }
        return ready.onStart { emit(SeriesReadingOrdersUiState.Loading) }
    }
}
