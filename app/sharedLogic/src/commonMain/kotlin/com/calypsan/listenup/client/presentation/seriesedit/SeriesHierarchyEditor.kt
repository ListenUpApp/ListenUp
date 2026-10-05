package com.calypsan.listenup.client.presentation.seriesedit

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.SeriesHierarchy
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

/** Idle timeout before stopping the picker's tree. */
private const val STOP_TIMEOUT_MS = 5_000L

/**
 * The hierarchy slice of the series editor: where the series sits (its parent and its sub-series),
 * the "Move into…" picker, the "New parent series" dialog, and the writes that move it.
 *
 * It keeps its fields on the editor's own [state] — `parentId`, `parentName`, `childSeries`,
 * `parentPickerVisible`, `parentQuery`, `newParent`, `hierarchyBusy` — so the screen reads one state
 * object.
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
    private val hierarchy: Flow<SeriesHierarchy>,
    private val createSeries: suspend (String, SeriesId?) -> AppResult<SeriesId>,
    private val setParent: suspend (SeriesId, SeriesId?) -> AppResult<Unit>,
    private val reorderChildren: suspend (SeriesId, List<SeriesId>) -> AppResult<Unit>,
) {
    private val expanded = MutableStateFlow<Set<String>>(emptySet())
    private var latest: SeriesHierarchy = SeriesHierarchy.Empty

    /**
     * The "Move into…" rows: the tree, with the series itself, everything inside it and its current
     * parent disabled, each with its reason; or, while [SeriesEditUiState.parentQuery] is set, every
     * match. Computed only while the picker is visible.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val parentPickerRows: StateFlow<List<ParentPickerRow>> =
        state
            .map { it.parentPickerVisible }
            .distinctUntilChanged()
            .flatMapLatest { pickerVisible ->
                if (!pickerVisible) {
                    flowOf(emptyList())
                } else {
                    combine(
                        state.map { it.seriesId to it.parentQuery }.distinctUntilChanged(),
                        expanded,
                        hierarchy,
                    ) { (currentId, query), open, tree ->
                        latest = tree
                        parentPickerRows(tree, currentId, query, open)
                    }
                }
            }.stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private var placementJob: Job? = null

    /** Keeps the parent and sub-series — and the tree the picker checks against — in step with Room. */
    fun observePlacement(seriesId: String) {
        placementJob?.cancel()
        placementJob =
            scope.launch {
                launch { hierarchy.collect { latest = it } }
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
                                        bookCount = child.bookIds.size,
                                    )
                                },
                        )
                    }
                }
            }
    }

    /** Opens the parent picker on the series' own place in the tree. */
    fun openParentPicker() {
        expanded.value = initiallyExpanded(latest, state.value.seriesId)
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

    /** Expands or collapses [seriesId]'s sub-series in the picker. */
    fun toggleNode(seriesId: String) {
        expanded.update { if (seriesId in it) it - seriesId else it + seriesId }
    }

    /**
     * Places the series under [parentId], or makes it a root when null. A parent the picker shows
     * disabled — the series itself, anything inside it, the parent it already has — is ignored, as
     * is "top level" for a series already there.
     */
    fun changeParent(parentId: SeriesId?) {
        val currentId = state.value.seriesId
        val unchanged =
            if (parentId == null) {
                state.value.parentId == null
            } else {
                parentDisabledReason(latest, currentId, parentId.value) != null
            }
        if (unchanged) return
        change { id -> setParent(id, parentId) }
    }

    /** Rewrites the sub-series' sibling order to [orderedChildIds]. */
    fun reorderChildSeries(orderedChildIds: List<SeriesId>) = change { id -> reorderChildren(id, orderedChildIds) }

    /** Opens the "New parent series" dialog; the picker closes behind it. */
    fun startNewParent() {
        state.update {
            it.copy(newParent = NewSeriesDraft(name = ""), parentPickerVisible = false, parentQuery = "")
        }
    }

    /** The new parent's name changed: a name already in use offers that series instead. */
    fun changeNewParentName(name: String) {
        val currentId = state.value.seriesId
        val existing =
            latest.findByName(name)?.let { series ->
                ExistingSeriesMatch(
                    id = series.id.value,
                    name = series.name,
                    isSelectable = parentDisabledReason(latest, currentId, series.id.value) == null,
                )
            }
        state.update { it.copy(newParent = NewSeriesDraft(name = name, existing = existing)) }
    }

    /** Closes the "New parent series" dialog. */
    fun dismissNewParent() {
        state.update { it.copy(newParent = null) }
    }

    /**
     * Creates the new parent where the series sits now — under its current parent, or at the top
     * level — then moves the series into it. Two server writes; the result arrives through sync.
     */
    fun createParent() {
        val draft = state.value.newParent ?: return
        val name = draft.name.trim()
        if (name.isEmpty() || latest.findByName(name) != null) return
        val slot = state.value.parentId?.let(::SeriesId)
        state.update { it.copy(newParent = null) }
        change { id ->
            when (val created = createSeries(name, slot)) {
                is AppResult.Success -> setParent(id, created.data)
                is AppResult.Failure -> created
            }
        }
    }

    /**
     * Sends one hierarchy change to the server; the result reaches the screen through Room. One at
     * a time: a change asked for while another is still on its way is dropped, because it was
     * chosen against a tree the first one is about to rearrange.
     */
    private fun change(write: suspend (SeriesId) -> AppResult<Unit>) {
        val seriesId = state.value.seriesId
        if (seriesId.isBlank()) {
            logger.error { "Cannot change hierarchy: series ID is empty" }
            return
        }
        if (state.value.hierarchyBusy) return
        // Marked busy before the launch, so a second change in the same frame is dropped too.
        state.update {
            it.copy(hierarchyBusy = true, parentPickerVisible = false, parentQuery = "", error = null)
        }

        scope.launch {
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
