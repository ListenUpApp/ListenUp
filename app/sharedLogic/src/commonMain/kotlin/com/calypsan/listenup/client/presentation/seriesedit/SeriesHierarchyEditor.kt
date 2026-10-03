package com.calypsan.listenup.client.presentation.seriesedit

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.SeriesEntity
import com.calypsan.listenup.client.domain.model.SeriesLineage
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/** Idle timeout before stopping parent-candidate collection. */
private const val STOP_TIMEOUT_MS = 5_000L

/**
 * The hierarchy slice of the series editor: where the series sits (its parent and its sub-series),
 * the parent picker, and the writes that move it.
 *
 * It keeps its six fields on the editor's own [state] — `parentId`, `parentName`, `childSeries`,
 * `parentPickerVisible`, `parentQuery`, `hierarchyBusy` — so the screen reads one state object.
 *
 * Placement is read from Room through [observeLineage] and never written here: a hierarchy change
 * goes to the server, and its answer arrives through sync and lands in [state] on its own. A
 * refused change goes to the [errorBus] and into `state.error`.
 */
internal class SeriesHierarchyEditor(
    private val scope: CoroutineScope,
    private val errorBus: ErrorBus,
    private val state: MutableStateFlow<SeriesEditUiState>,
    private val observeLineage: (seriesId: String) -> Flow<SeriesLineage>,
    observeAllSeries: () -> Flow<List<SeriesEntity>>,
    private val setParent: suspend (SeriesId, SeriesId?) -> AppResult<Unit>,
    private val reorderChildren: suspend (SeriesId, List<SeriesId>) -> AppResult<Unit>,
) {
    /**
     * Candidates for the parent picker — every live series this one may sit under (never itself
     * or its own sub-series), filtered by [SeriesEditUiState.parentQuery]. Computed only while the
     * picker is visible: hidden emits an empty list without ever reading the series table.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val parentCandidates: StateFlow<List<SeriesCandidate>> =
        state
            .map { it.parentPickerVisible }
            .distinctUntilChanged()
            .flatMapLatest { pickerVisible ->
                if (!pickerVisible) {
                    flowOf(emptyList())
                } else {
                    combine(
                        state.map { it.seriesId to it.parentQuery }.distinctUntilChanged(),
                        observeAllSeries(),
                    ) { (currentId, query), allSeries -> parentCandidates(allSeries, currentId, query) }
                }
            }.stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private var placementJob: Job? = null

    /** Keeps the parent and sub-series in step with Room for [seriesId]. */
    fun observePlacement(seriesId: String) {
        placementJob?.cancel()
        placementJob =
            scope.launch {
                observeLineage(seriesId).collect { lineage ->
                    val parent = lineage.ancestors.lastOrNull()
                    state.update {
                        it.copy(
                            parentId = parent?.id?.value,
                            parentName = parent?.name,
                            childSeries =
                                lineage.children.map { child ->
                                    SeriesCandidate(
                                        id = child.series.id,
                                        displayName = child.series.name,
                                        bookCount = 0,
                                    )
                                },
                        )
                    }
                }
            }
    }

    /** Opens the parent picker; candidate computation starts. */
    fun openParentPicker() {
        state.update { it.copy(parentPickerVisible = true) }
    }

    /** Closes the parent picker without choosing, and clears its query. */
    fun dismissParentPicker() {
        state.update { it.copy(parentPickerVisible = false, parentQuery = "") }
    }

    /** Updates the parent picker's search text. */
    fun changeParentQuery(query: String) {
        state.update { it.copy(parentQuery = query) }
    }

    /** Places the series under [parentId], or makes it a root when null. */
    fun changeParent(parentId: SeriesId?) = change { id -> setParent(id, parentId) }

    /** Rewrites the sub-series' sibling order to [orderedChildIds]. */
    fun reorderChildSeries(orderedChildIds: List<SeriesId>) = change { id -> reorderChildren(id, orderedChildIds) }

    /** Sends one hierarchy change to the server; the result reaches the screen through Room. */
    private fun change(write: suspend (SeriesId) -> AppResult<Unit>) {
        val seriesId = state.value.seriesId
        if (seriesId.isBlank()) {
            logger.error { "Cannot change hierarchy: series ID is empty" }
            return
        }

        scope.launch {
            state.update {
                it.copy(hierarchyBusy = true, parentPickerVisible = false, parentQuery = "", error = null)
            }
            when (val result = write(SeriesId(seriesId))) {
                is AppResult.Success -> {
                    state.update { it.copy(hierarchyBusy = false) }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to change series hierarchy: ${result.message}" }
                    state.update { it.copy(hierarchyBusy = false, error = result.error.message) }
                }
            }
        }
    }
}
