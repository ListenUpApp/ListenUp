package com.calypsan.listenup.client.presentation.storyworld

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.client.domain.model.Entity
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.client.domain.model.WorldEvent
import com.calypsan.listenup.client.domain.repository.EntityEditRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.domain.repository.WorldEventEditRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/**
 * ViewModel for the Story World entity list screen — every entity in a world, grouped by kind,
 * with each row's visible-log entry count.
 *
 * Unlike [StoryWorldHubViewModel], this screen has no reveal toggle: [FrontierGate] always runs
 * with `reveal = false`, so [EntityListRow.entryCount] never leaks a spoiler count. Kinds with no
 * entities are omitted entirely — unlike the hub's always-3-tiles shape, an empty group here is
 * simply not shown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StoryWorldEntityListViewModel(
    private val entityEditRepository: EntityEditRepository,
    private val worldEventEditRepository: WorldEventEditRepository,
    private val playbackPositionRepository: PlaybackPositionRepository,
) : ViewModel() {
    private val paramsFlow = MutableStateFlow<LoadParams?>(null)

    val state: StateFlow<StoryWorldEntityListUiState> =
        paramsFlow
            .flatMapLatest { params ->
                if (params == null) {
                    flowOf(StoryWorldEntityListUiState.Idle)
                } else {
                    observeReady(params)
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = StoryWorldEntityListUiState.Idle,
            )

    /** Set the world (and optional kind filter) to observe. Safe to call repeatedly. */
    fun load(
        world: WorldRef,
        kindFilter: EntityKind? = null,
    ) {
        paramsFlow.value = LoadParams(world, kindFilter)
    }

    private fun observeReady(params: LoadParams): Flow<StoryWorldEntityListUiState> {
        val world = params.world
        val entitiesFlow =
            world.seriesId?.let { entityEditRepository.observeEntitiesForSeries(it) }
                ?: entityEditRepository.observeEntitiesForBook(world.bookId!!)
        val eventsFlow = worldEventEditRepository.observeForWorld(world.seriesId, world.bookId)
        val positionsFlow =
            playbackPositionRepository.observeAll().map { positions -> positions.mapKeys { (id, _) -> id.value } }

        val ready: Flow<StoryWorldEntityListUiState> =
            combine(entitiesFlow, eventsFlow, positionsFlow) { entities, events, positions ->
                buildReady(entities, events, positions, params.kindFilter)
            }
        return ready.onStart { emit(StoryWorldEntityListUiState.Loading) }
    }

    private fun buildReady(
        entities: List<Entity>,
        events: List<WorldEvent>,
        positions: Map<String, PlaybackPosition>,
        kindFilter: EntityKind?,
    ): StoryWorldEntityListUiState.Ready {
        val visibleEvents = FrontierGate.gate(events, positions, reveal = false) { it.bookId to it.positionMs }.visible

        val kinds = kindFilter?.let { listOf(it) } ?: listOf(EntityKind.CHARACTER, EntityKind.LOCATION, EntityKind.ITEM)
        val groups =
            kinds.mapNotNull { kind ->
                val ofKind = entities.filter { it.kind == kind }
                if (ofKind.isEmpty()) {
                    null
                } else {
                    EntityListGroup(
                        kind = kind,
                        rows =
                            ofKind.map { entity ->
                                EntityListRow(
                                    id = entity.id,
                                    name = entity.name,
                                    kind = entity.kind,
                                    entryCount = visibleEvents.count { entity.id in it.mentionIds },
                                )
                            },
                    )
                }
            }

        return StoryWorldEntityListUiState.Ready(groups)
    }

    private data class LoadParams(
        val world: WorldRef,
        val kindFilter: EntityKind?,
    )
}

/** UI state for the Story World entity list screen. */
sealed interface StoryWorldEntityListUiState {
    /** No world selected (pre-[StoryWorldEntityListViewModel.load]). */
    data object Idle : StoryWorldEntityListUiState

    /** Upstream has not yet produced data for the selected world. */
    data object Loading : StoryWorldEntityListUiState

    /** Entities loaded, grouped by kind. Kinds with no entities are omitted. */
    data class Ready(
        val groups: List<EntityListGroup>,
    ) : StoryWorldEntityListUiState
}

/** One kind's worth of entities on the entity list screen. */
data class EntityListGroup(
    val kind: EntityKind,
    val rows: List<EntityListRow>,
)

/** A single entity row on the entity list screen. */
data class EntityListRow(
    val id: String,
    val name: String,
    val kind: EntityKind,
    /** Count of currently-visible (frontier-gated, never revealed) log entries mentioning this entity. */
    val entryCount: Int,
)
