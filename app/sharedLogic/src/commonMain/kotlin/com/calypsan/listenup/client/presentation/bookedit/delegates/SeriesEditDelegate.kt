@file:OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.client.presentation.bookedit.delegates

import com.calypsan.listenup.client.domain.model.SeriesSearchResult
import com.calypsan.listenup.client.domain.repository.SeriesRepository
import com.calypsan.listenup.client.presentation.bookedit.BookEditUiState
import com.calypsan.listenup.client.presentation.bookedit.EditableSeries
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

private val logger = KotlinLogging.logger {}

private const val SEARCH_DEBOUNCE_MS = 300L
private const val MIN_QUERY_LENGTH = 2
private const val SEARCH_LIMIT = 10

/**
 * Delegate handling series editing operations.
 *
 * Responsibilities:
 * - Debounced series search
 * - Add/remove series
 * - Sequence editing
 *
 * @property state Shared state flow owned by ViewModel
 * @property seriesRepository Repository for series search
 * @property scope CoroutineScope for launching operations
 * @property onChangesMade Callback to notify ViewModel of changes
 */
class SeriesEditDelegate(
    private val state: MutableStateFlow<BookEditUiState>,
    private val seriesRepository: SeriesRepository,
    private val scope: CoroutineScope,
    private val onChangesMade: () -> Unit,
) {
    private val seriesQueryFlow = MutableStateFlow("")

    init {
        setupSeriesSearch()
    }

    /**
     * Internal result type for the reactive series search flow.
     */
    private sealed interface SeriesSearchFlowResult {
        data object Empty : SeriesSearchFlowResult

        data object Loading : SeriesSearchFlowResult

        /**
         * Search succeeded; [results] is filtered to exclude series already attached to the book.
         * [isOffline] is true when the results came from the local cache rather than a server hit.
         */
        data class Success(
            val results: List<SeriesSearchResult>,
            val isOffline: Boolean,
        ) : SeriesSearchFlowResult
    }

    /**
     * Update the series search query.
     */
    fun updateSearchQuery(query: String) {
        state.update { it.copy(seriesSearchQuery = query) }
        seriesQueryFlow.value = query
    }

    /**
     * Clear the series search.
     */
    fun clearSearch() {
        state.update {
            it.copy(
                seriesSearchQuery = "",
                seriesSearchResults = emptyList(),
            )
        }
        seriesQueryFlow.value = ""
    }

    /**
     * Select a series from search results.
     */
    fun selectSeries(result: SeriesSearchResult) {
        state.update { current ->
            // Check if series already exists
            val existing = current.series.find { it.id == result.id }
            if (existing != null) {
                // Already added - just clear search
                return@update current.copy(
                    seriesSearchQuery = "",
                    seriesSearchResults = emptyList(),
                )
            }

            // Add new series
            val newSeries =
                EditableSeries(
                    id = result.id,
                    name = result.name,
                    sequence = null,
                )
            current.copy(
                series = current.series + newSeries,
                seriesSearchQuery = "",
                seriesSearchResults = emptyList(),
            )
        }

        seriesQueryFlow.value = ""
        onChangesMade()
    }

    /**
     * Add a new series by name.
     */
    fun addSeries(name: String) {
        if (name.isBlank()) return

        val trimmedName = name.trim()

        state.update { current ->
            // Check if series already exists (by name)
            val existing =
                current.series.find {
                    it.name.equals(trimmedName, ignoreCase = true)
                }
            if (existing != null) {
                // Already added - just clear search
                return@update current.copy(
                    seriesSearchQuery = "",
                    seriesSearchResults = emptyList(),
                )
            }

            // Add new series (no ID = will be created on server)
            val newSeries =
                EditableSeries(
                    id = null,
                    name = trimmedName,
                    sequence = null,
                )
            current.copy(
                series = current.series + newSeries,
                seriesSearchQuery = "",
                seriesSearchResults = emptyList(),
            )
        }

        seriesQueryFlow.value = ""
        onChangesMade()
    }

    /**
     * Update the sequence for a series.
     */
    fun updateSeriesSequence(
        targetSeries: EditableSeries,
        sequence: String,
    ) {
        state.update { current ->
            current.copy(
                series =
                    current.series.map {
                        if (it == targetSeries) it.copy(sequence = sequence.ifBlank { null }) else it
                    },
            )
        }
        onChangesMade()
    }

    /**
     * Remove a series from the book.
     */
    fun removeSeries(targetSeries: EditableSeries) {
        state.update { current ->
            current.copy(series = current.series - targetSeries)
        }
        onChangesMade()
    }

    private fun setupSeriesSearch() {
        seriesQueryFlow
            .debounce(SEARCH_DEBOUNCE_MS)
            .distinctUntilChanged()
            .filter { it.length >= MIN_QUERY_LENGTH || it.isEmpty() }
            .flatMapLatest { query ->
                if (query.isBlank()) {
                    flowOf<SeriesSearchFlowResult>(SeriesSearchFlowResult.Empty)
                } else {
                    flow<SeriesSearchFlowResult> {
                        emit(SeriesSearchFlowResult.Loading)
                        emit(performSeriesSearch(query))
                    }
                }
            }.onEach { result ->
                when (result) {
                    is SeriesSearchFlowResult.Empty -> {
                        state.update {
                            it.copy(seriesSearchResults = emptyList(), seriesSearchLoading = false)
                        }
                    }

                    is SeriesSearchFlowResult.Loading -> {
                        state.update { it.copy(seriesSearchLoading = true) }
                    }

                    is SeriesSearchFlowResult.Success -> {
                        state.update {
                            it.copy(
                                seriesSearchResults = result.results,
                                seriesSearchLoading = false,
                                seriesOfflineResult = result.isOffline,
                            )
                        }
                        logger.debug { "Series search: ${result.results.size} results" }
                    }
                }
            }.launchIn(scope)
    }

    /**
     * Perform series search and return the result.
     * Called from flatMapLatest flow - cancellation is handled automatically.
     */
    private suspend fun performSeriesSearch(query: String): SeriesSearchFlowResult {
        val response = seriesRepository.searchSeries(query, limit = SEARCH_LIMIT)

        // Filter out series already added to this book
        val currentSeriesIds =
            state.value.series
                .mapNotNull { it.id }
                .toSet()
        val filteredResults = response.series.filter { it.id !in currentSeriesIds }

        return SeriesSearchFlowResult.Success(filteredResults, response.isOfflineResult)
    }
}
