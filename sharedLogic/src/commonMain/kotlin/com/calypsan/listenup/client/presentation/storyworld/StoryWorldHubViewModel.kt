package com.calypsan.listenup.client.presentation.storyworld

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.core.MentionTokens
import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.api.sync.WorldEventType
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.model.Entity
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.client.domain.model.WorldEvent
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.EntityEditRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.domain.repository.SeriesRepository
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
 * ViewModel for the Story World hub — the entry point into a series' or standalone book's Story
 * World: entity-kind tiles, a recently-mentioned strip, and the latest log entries.
 *
 * State is derived reactively via nested `combine(...).stateIn(WhileSubscribed)` over the
 * world's entities, events, playback positions, and book roster; log entries are additionally
 * gated through [FrontierGate] so a spoiler beyond the viewer's listening frontier never
 * surfaces, unless [showHidden] was called this session.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StoryWorldHubViewModel(
    private val entityEditRepository: EntityEditRepository,
    private val worldEventEditRepository: WorldEventEditRepository,
    private val seriesRepository: SeriesRepository,
    private val bookRepository: BookRepository,
    private val playbackPositionRepository: PlaybackPositionRepository,
) : ViewModel() {
    private val worldFlow = MutableStateFlow<WorldRef?>(null)
    private val revealFlow = MutableStateFlow(false)
    private val searchQueryFlow = MutableStateFlow("")

    val state: StateFlow<StoryWorldHubUiState> =
        worldFlow
            .flatMapLatest { world ->
                if (world == null) {
                    flowOf(StoryWorldHubUiState.Idle)
                } else {
                    observeReady(world)
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = StoryWorldHubUiState.Idle,
            )

    /** Set the world to observe. Safe to call repeatedly with the same ref. */
    fun load(world: WorldRef) {
        worldFlow.value = world
    }

    /** Reveal every gated log entry for this session. Session-scoped only — never persisted. */
    fun showHidden() {
        revealFlow.value = true
    }

    /** Update the in-hub entity search query. Blank clears [StoryWorldHubUiState.Ready.searchResults]. */
    fun setSearchQuery(query: String) {
        searchQueryFlow.value = query
    }

    private fun observeReady(world: WorldRef): Flow<StoryWorldHubUiState> {
        val entitiesFlow =
            world.seriesId?.let { entityEditRepository.observeEntitiesForSeries(it) }
                ?: entityEditRepository.observeEntitiesForBook(world.bookId!!)
        val eventsFlow = worldEventEditRepository.observeForWorld(world.seriesId, world.bookId)
        val positionsFlow =
            playbackPositionRepository.observeAll().map { positions -> positions.mapKeys { (id, _) -> id.value } }
        val worldBooksFlow = observeWorldBooks(world)

        return combine(
            combine(
                entitiesFlow,
                eventsFlow,
                positionsFlow,
                worldBooksFlow,
                revealFlow,
            ) { entities, events, positions, worldBooks, reveal ->
                Snapshot(entities, events, positions, worldBooks, reveal)
            },
            searchQueryFlow,
        ) { snapshot, query -> buildDraft(snapshot, query) }
            .flatMapLatest { draft -> resolveChapters(draft) }
            .onStart { emit(StoryWorldHubUiState.Loading) }
    }

    private fun observeWorldBooks(world: WorldRef): Flow<WorldBooksSnapshot> =
        if (world.seriesId != null) {
            seriesRepository.observeSeriesWithBooks(world.seriesId).map { seriesWithBooks ->
                if (seriesWithBooks == null) {
                    WorldBooksSnapshot(title = "", books = emptyList())
                } else {
                    WorldBooksSnapshot(
                        title = seriesWithBooks.series.name,
                        books =
                            seriesWithBooks.booksSortedBySequence().map { book ->
                                WorldBook(
                                    id = book.id.value,
                                    title = book.title,
                                    sequenceLabel = seriesWithBooks.sequenceFor(book.id.value),
                                )
                            },
                    )
                }
            }
        } else {
            bookRepository.observeBookListItems(listOf(world.bookId!!)).map { books ->
                val book = books.firstOrNull()
                WorldBooksSnapshot(
                    title = book?.title.orEmpty(),
                    books =
                        listOfNotNull(
                            book?.let { WorldBook(id = it.id.value, title = it.title, sequenceLabel = null) },
                        ),
                )
            }
        }

    private fun buildDraft(
        snapshot: Snapshot,
        searchQuery: String,
    ): Draft {
        val kindGroups =
            listOf(EntityKind.CHARACTER, EntityKind.LOCATION, EntityKind.ITEM).map { kind ->
                val ofKind = snapshot.entities.filter { it.kind == kind }
                KindGroup(
                    kind = kind,
                    count = ofKind.size,
                    preview = ofKind.take(KIND_GROUP_PREVIEW_SIZE).map { it.toCard() },
                )
            }

        val gatedEvents =
            FrontierGate.gate(snapshot.events, snapshot.positions, snapshot.reveal) { it.bookId to it.positionMs }

        val entityById = snapshot.entities.associateBy { it.id }

        val recentEntities = mutableListOf<Entity>()
        val seenEntityIds = mutableSetOf<String>()
        outer@ for (event in gatedEvents.visible) {
            for (mentionId in event.mentionIds) {
                if (recentEntities.size >= RECENT_ENTITIES_LIMIT) break@outer
                if (seenEntityIds.add(mentionId)) {
                    entityById[mentionId]?.let { recentEntities.add(it) }
                }
            }
        }

        val topEvents = gatedEvents.visible.take(LATEST_EVENTS_LIMIT)
        val worldBooksById = snapshot.worldBooks.books.associateBy { it.id }

        val unstartedBooksBanner =
            snapshot.worldBooks.books.any { book ->
                val position = snapshot.positions[book.id]
                position == null || (position.startedAtMs == null && position.maxPositionMs == 0L)
            }

        val searchResults =
            if (searchQuery.isBlank()) {
                emptyList()
            } else {
                snapshot.entities.filter { it.name.contains(searchQuery, ignoreCase = true) }.map { it.toCard() }
            }

        return Draft(
            worldTitle = snapshot.worldBooks.title,
            kindGroups = kindGroups,
            recentEntities = recentEntities.map { it.toCard() },
            topEvents = topEvents,
            entityById = entityById,
            worldBooksById = worldBooksById,
            hiddenEventCount = gatedEvents.hiddenCount,
            revealed = snapshot.reveal,
            unstartedBooksBanner = unstartedBooksBanner,
            searchQuery = searchQuery,
            searchResults = searchResults,
            isEmpty = snapshot.entities.isEmpty() && snapshot.events.isEmpty(),
        )
    }

    private fun resolveChapters(draft: Draft): Flow<StoryWorldHubUiState> {
        val neededBookIds = draft.topEvents.mapNotNull { it.bookId }.distinct()
        return if (neededBookIds.isEmpty()) {
            flowOf(finalize(draft, emptyMap()))
        } else {
            combine(
                neededBookIds.map { bookId ->
                    bookRepository.observeChapters(bookId).map { chapters -> bookId to chapters }
                },
            ) { pairs -> finalize(draft, pairs.toMap()) }
        }
    }

    private fun finalize(
        draft: Draft,
        chaptersByBookId: Map<String, List<Chapter>>,
    ): StoryWorldHubUiState.Ready {
        val latestEvents =
            draft.topEvents.map { event ->
                val bookLabel =
                    event.bookId?.let { bookId -> draft.worldBooksById[bookId]?.let { it.sequenceLabel ?: it.title } }
                val chapters = event.bookId?.let { chaptersByBookId[it] }.orEmpty()
                EventRow(
                    id = event.id,
                    type = event.type,
                    renderedText = MentionTokens.render(event.text) { id -> draft.entityById[id]?.name },
                    anchor = AnchorLabeler.label(bookLabel, chapters, event.positionMs),
                )
            }

        return StoryWorldHubUiState.Ready(
            worldTitle = draft.worldTitle,
            kindGroups = draft.kindGroups,
            recentEntities = draft.recentEntities,
            latestEvents = latestEvents,
            hiddenEventCount = draft.hiddenEventCount,
            revealed = draft.revealed,
            unstartedBooksBanner = draft.unstartedBooksBanner,
            searchQuery = draft.searchQuery,
            searchResults = draft.searchResults,
            isEmpty = draft.isEmpty,
        )
    }

    private fun Entity.toCard(): EntityCard = EntityCard(id = id, name = name, kind = kind)

    /** Raw upstream tick — one flow per data source, pre-gating, pre-search. */
    private data class Snapshot(
        val entities: List<Entity>,
        val events: List<WorldEvent>,
        val positions: Map<String, PlaybackPosition>,
        val worldBooks: WorldBooksSnapshot,
        val reveal: Boolean,
    )

    /** This world's title and book roster, keyed for [AnchorLabeler] book-label lookups. */
    private data class WorldBooksSnapshot(
        val title: String,
        val books: List<WorldBook>,
    )

    /** One of this world's books — [sequenceLabel] is null for a standalone-book world. */
    private data class WorldBook(
        val id: String,
        val title: String,
        val sequenceLabel: String?,
    )

    /** Everything computed from a [Snapshot] except the chapter-dependent [EventRow.anchor] values. */
    private data class Draft(
        val worldTitle: String,
        val kindGroups: List<KindGroup>,
        val recentEntities: List<EntityCard>,
        val topEvents: List<WorldEvent>,
        val entityById: Map<String, Entity>,
        val worldBooksById: Map<String, WorldBook>,
        val hiddenEventCount: Int,
        val revealed: Boolean,
        val unstartedBooksBanner: Boolean,
        val searchQuery: String,
        val searchResults: List<EntityCard>,
        val isEmpty: Boolean,
    )

    private companion object {
        /** Entities shown per [KindGroup.preview] tile. */
        const val KIND_GROUP_PREVIEW_SIZE = 3

        /** Max entities surfaced in [StoryWorldHubUiState.Ready.recentEntities]. */
        const val RECENT_ENTITIES_LIMIT = 5

        /** Max entries surfaced in [StoryWorldHubUiState.Ready.latestEvents]. */
        const val LATEST_EVENTS_LIMIT = 3
    }
}

/** UI state for the Story World hub screen. */
sealed interface StoryWorldHubUiState {
    /** No world selected (pre-[StoryWorldHubViewModel.load]). */
    data object Idle : StoryWorldHubUiState

    /** Upstream has not yet produced data for the selected world. */
    data object Loading : StoryWorldHubUiState

    /** World data loaded. */
    data class Ready(
        /** The series name, or the standalone book's title. */
        val worldTitle: String,
        /** Always exactly 3 groups, in CHARACTER/LOCATION/ITEM order — zero-count groups included. */
        val kindGroups: List<KindGroup>,
        /** Up to 5 entities first mentioned walking the visible (gated) log, most-recent-first. */
        val recentEntities: List<EntityCard>,
        /** Up to 3 most-recent visible (gated) log entries. */
        val latestEvents: List<EventRow>,
        /** How many log entries [FrontierGate] hid from [latestEvents]/[recentEntities]. */
        val hiddenEventCount: Int,
        /** Whether the viewer chose to reveal every gated entry this session. */
        val revealed: Boolean,
        /** True when at least one of this world's books has never been started. */
        val unstartedBooksBanner: Boolean,
        val searchQuery: String,
        /** Entities matching [searchQuery] (case-insensitive name-contains); empty when [searchQuery] is blank. */
        val searchResults: List<EntityCard>,
        /** True when this world has no entities and no events at all, pre-gating. */
        val isEmpty: Boolean,
    ) : StoryWorldHubUiState
}

/** A single entity-kind tile on the hub: how many entities of [kind] exist, and a preview. */
data class KindGroup(
    val kind: EntityKind,
    val count: Int,
    /** First 3 entities of [kind], alphabetically (the upstream entity flow is already name-sorted). */
    val preview: List<EntityCard>,
)

/** A lightweight entity projection for list/preview surfaces. */
data class EntityCard(
    val id: String,
    val name: String,
    val kind: EntityKind,
)

/** A single rendered Story World log entry: its typed slot, mention-resolved text, and its anchor. */
data class EventRow(
    val id: String,
    val type: WorldEventType,
    /** [WorldEvent.text] with every mention token resolved to a live (or cached-fallback) entity name. */
    val renderedText: String,
    val anchor: AnchorLabel,
)
