package com.calypsan.listenup.client.presentation.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.core.error.ErrorBus
import com.calypsan.listenup.client.core.error.ErrorMapper
import com.calypsan.listenup.client.domain.model.SearchHit
import com.calypsan.listenup.client.domain.model.SearchHitType
import com.calypsan.listenup.client.domain.model.SearchResult
import com.calypsan.listenup.client.domain.repository.SearchRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

private val logger = KotlinLogging.logger {}

/**
 * UI state for the federated search overlay.
 *
 * Every variant carries the live [query] and [selectedTypes] so the search bar
 * and filter chips render consistently across loading/results/error transitions.
 */
sealed interface SearchUiState {
    val query: String
    val selectedTypes: Set<SearchHitType>

    /** No query entered, or query too short to trigger a search. */
    data class Idle(
        override val query: String = "",
        override val selectedTypes: Set<SearchHitType> = emptySet(),
    ) : SearchUiState

    /** A search is in flight for the current [query] and [selectedTypes]. */
    data class Searching(
        override val query: String,
        override val selectedTypes: Set<SearchHitType>,
    ) : SearchUiState

    /** Latest search returned [result]. */
    data class Results(
        override val query: String,
        override val selectedTypes: Set<SearchHitType>,
        val result: SearchResult,
    ) : SearchUiState

    /** Latest search failed; error surface message in [message]. */
    data class Error(
        override val query: String,
        override val selectedTypes: Set<SearchHitType>,
        val message: String,
    ) : SearchUiState
}

/**
 * Navigation actions emitted when a search hit is clicked.
 */
sealed interface SearchNavAction {
    /** User picked a book hit; navigate to the book detail screen. */
    data class NavigateToBook(
        val bookId: String,
    ) : SearchNavAction

    /** User picked a contributor hit; navigate to the contributor detail screen. */
    data class NavigateToContributor(
        val contributorId: String,
    ) : SearchNavAction

    /** User picked a series hit; navigate to the series detail screen. */
    data class NavigateToSeries(
        val seriesId: String,
    ) : SearchNavAction

    /** User picked a tag hit; navigate to the tag facet-browse screen. */
    data class NavigateToTag(
        val tagId: String,
        val tagName: String,
    ) : SearchNavAction
}

/**
 * ViewModel for the federated search overlay.
 *
 * Debounced-then-flat-map-latest pipeline driven by [queryFlow] + [typesFlow];
 * toggling a type filter re-issues the search immediately because the combine
 * propagates the new types through `flatMapLatest` without debounce.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(
    private val searchRepository: SearchRepository,
    private val errorBus: ErrorBus,
) : ViewModel() {
    private val queryFlow = MutableStateFlow("")
    private val typesFlow = MutableStateFlow<Set<SearchHitType>>(emptySet())

    private val navChannel = Channel<SearchNavAction>(Channel.BUFFERED)
    val navActions: Flow<SearchNavAction> = navChannel.receiveAsFlow()

    val state: StateFlow<SearchUiState> =
        combine(queryFlow, typesFlow, phaseFlow()) { query, types, phase ->
            when (phase) {
                is Phase.Idle -> SearchUiState.Idle(query, types)
                is Phase.Searching -> SearchUiState.Searching(query, types)
                is Phase.Results -> SearchUiState.Results(query, types, phase.data)
                is Phase.Error -> SearchUiState.Error(query, types, phase.message)
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            initialValue = SearchUiState.Idle(),
        )

    private fun phaseFlow(): Flow<Phase> =
        combine(
            queryFlow.debounce { q -> if (q.isBlank()) 0L else SEARCH_DEBOUNCE_MS },
            typesFlow,
        ) { query, types -> query to types }
            .distinctUntilChanged()
            .flatMapLatest { (query, types) ->
                flow {
                    when {
                        query.isBlank() -> emit(Phase.Idle)
                        query.length < MIN_QUERY_LENGTH -> Unit
                        else -> emitSearch(query, types)
                    }
                }
            }

    private suspend fun FlowCollector<Phase>.emitSearch(
        query: String,
        types: Set<SearchHitType>,
    ) {
        emit(Phase.Searching)
        try {
            val typesList = types.takeIf { it.isNotEmpty() }?.toList()
            val result =
                searchRepository.search(
                    query = query,
                    types = typesList,
                    limit = DEFAULT_RESULT_LIMIT,
                )
            logger.info {
                "Search completed: ${result.total} results for '$query' (offline=${result.isOfflineResult})"
            }
            emit(Phase.Results(result))
        } catch (cancel: kotlin.coroutines.cancellation.CancellationException) {
            throw cancel
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            logger.error { "Search failed for '$query'" }
            @Suppress("DEPRECATION")
            errorBus.emit(ErrorMapper.map(e))
            emit(Phase.Error("Search unavailable. Please try again."))
        }
    }

    fun onQueryChanged(query: String) {
        queryFlow.value = query
    }

    fun clearQuery() {
        queryFlow.value = ""
    }

    fun toggleTypeFilter(type: SearchHitType) {
        typesFlow.update { current ->
            if (type in current) current - type else current + type
        }
    }

    /** Reset all type filters, returning the overlay to its "All" (unfiltered) scope. */
    fun clearTypeFilters() {
        typesFlow.value = emptySet()
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

    /** Private relay between the debounced pipeline and the public [state]. */
    private sealed interface Phase {
        data object Idle : Phase

        data object Searching : Phase

        /** Search call completed successfully; carries the raw result for the public state. */
        data class Results(
            val data: SearchResult,
        ) : Phase

        /** Search call failed; carries the user-facing message for the public state. */
        data class Error(
            val message: String,
        ) : Phase
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 300L
        private const val MIN_QUERY_LENGTH = 2
        private const val DEFAULT_RESULT_LIMIT = 30
        private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}
