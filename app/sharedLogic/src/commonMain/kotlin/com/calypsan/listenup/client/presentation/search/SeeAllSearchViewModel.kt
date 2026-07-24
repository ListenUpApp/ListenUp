package com.calypsan.listenup.client.presentation.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.core.error.ErrorMapper
import com.calypsan.listenup.client.domain.model.SearchHit
import com.calypsan.listenup.client.domain.model.SearchHitType
import com.calypsan.listenup.client.domain.repository.SearchRepository
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn

private val logger = KotlinLogging.logger {}

/**
 * UI state for the single-type "See all" search page.
 *
 * The page shows the full, uncapped list of hits for one [SearchHitType] (e.g. every Book match),
 * reached from the main results overlay when a group's hits exceed its display cap.
 */
sealed interface SeeAllSearchUiState {
    /** No request loaded yet. */
    data object Idle : SeeAllSearchUiState

    /** A search is in flight for the requested type. */
    data object Loading : SeeAllSearchUiState

    /** The full single-type search completed; [hits] are all of [type] for [query]. */
    data class Results(
        val type: SearchHitType,
        val query: String,
        val hits: List<SearchHit>,
    ) : SeeAllSearchUiState

    /** The search failed; [message] is the user-facing error text. */
    data class Error(
        val message: String,
    ) : SeeAllSearchUiState
}

/**
 * ViewModel for the single-type "See all" search page.
 *
 * Driven by a single settled [request] (the query and type are already chosen on the main results
 * view, so there is nothing to debounce). [load] sets the request; the public [state] re-runs the
 * search with a high per-type [SEE_ALL_LIMIT] whenever the request changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SeeAllSearchViewModel(
    private val searchRepository: SearchRepository,
    private val errorBus: ErrorBus,
) : ViewModel() {
    private val request = MutableStateFlow<Request?>(null)

    private val navChannel = Channel<SearchNavAction>(Channel.BUFFERED)
    val navActions: Flow<SearchNavAction> = navChannel.receiveAsFlow()

    val state: StateFlow<SeeAllSearchUiState> =
        request
            .flatMapLatest { current ->
                flow {
                    if (current == null) {
                        emit(SeeAllSearchUiState.Idle)
                    } else {
                        emitSearch(current.query, current.type)
                    }
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
                initialValue = SeeAllSearchUiState.Idle,
            )

    private suspend fun FlowCollector<SeeAllSearchUiState>.emitSearch(
        query: String,
        type: SearchHitType,
    ) {
        emit(SeeAllSearchUiState.Loading)
        try {
            val result =
                searchRepository.search(
                    query = query,
                    types = listOf(type),
                    limit = SEE_ALL_LIMIT,
                )
            val hits = result.hits.filter { it.type == type }.distinctBy { it.id }
            logger.info { "See-all search completed: ${hits.size} $type hits for '$query'" }
            emit(SeeAllSearchUiState.Results(type = type, query = query, hits = hits))
        } catch (cancel: kotlin.coroutines.cancellation.CancellationException) {
            throw cancel
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            logger.error { "See-all search failed for '$query' ($type)" }
            @Suppress("DEPRECATION")
            errorBus.emit(ErrorMapper.map(e))
            emit(SeeAllSearchUiState.Error("Search unavailable. Please try again."))
        }
    }

    /** Load the full list of [type] hits for [query]. Idempotent for an unchanged request. */
    fun load(
        query: String,
        type: SearchHitType,
    ) {
        request.value = Request(query = query, type = type)
    }

    fun onResultClicked(hit: SearchHit) = onResultSelected(hit.id, hit.type, hit.name)

    /**
     * Navigate to the entity identified by [id] + [type] (+ [name], used only for the Tag
     * destination's immediate hero label). The iOS native-row search path calls this directly —
     * its `SearchRow` carries no Kotlin `SearchHit` — and [onResultClicked] delegates here, so
     * the id+type+name → [SearchNavAction] mapping lives in exactly one place.
     */
    fun onResultSelected(
        id: String,
        type: SearchHitType,
        name: String,
    ) {
        val action =
            when (type) {
                SearchHitType.BOOK -> SearchNavAction.NavigateToBook(id)
                SearchHitType.CONTRIBUTOR -> SearchNavAction.NavigateToContributor(id)
                SearchHitType.SERIES -> SearchNavAction.NavigateToSeries(id)
                SearchHitType.TAG -> SearchNavAction.NavigateToTag(id, name)
            }
        navChannel.trySend(action)
    }

    /** A settled See-all request: the query and the single type to expand. */
    private data class Request(
        val query: String,
        val type: SearchHitType,
    )

    companion object {
        private const val SEE_ALL_LIMIT = 100
        private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}
