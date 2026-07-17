package com.calypsan.listenup.client.presentation.storyworld

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.core.MentionTokens
import com.calypsan.listenup.api.result.AppResult
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
import com.calypsan.listenup.core.error.ErrorBus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for the Story World entity detail screen — a single entity's chronological log:
 * every Story World event that mentions it, ordered by the world's own internal clock (book
 * publication order, then in-book position), not by write recency.
 *
 * State is derived reactively via nested `combine(...).stateIn(WhileSubscribed)` over the
 * entity itself, its home world's entities (for mention-name resolution), the events that
 * mention it, playback positions, and the world's book roster; entries are additionally gated
 * through [FrontierGate] so a spoiler beyond the viewer's listening frontier never surfaces,
 * unless [showHidden] was called this session. A changed entity id (via [load]) and external
 * deletion (the observed entity going tombstoned/absent) both flow through the same pipeline —
 * the latter surfaces as [EntityDetailUiState.NotFound].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EntityDetailViewModel(
    private val entityEditRepository: EntityEditRepository,
    private val worldEventEditRepository: WorldEventEditRepository,
    private val seriesRepository: SeriesRepository,
    private val bookRepository: BookRepository,
    private val playbackPositionRepository: PlaybackPositionRepository,
    private val errorBus: ErrorBus,
) : ViewModel() {
    private val entityIdFlow = MutableStateFlow<String?>(null)
    private val revealFlow = MutableStateFlow(false)

    private val _events = Channel<EntityDetailEvent>(Channel.BUFFERED)

    /**
     * One-shot events the screen consumes exactly once (e.g. navigate back after a delete).
     * Uses a [Channel] per the one-shot-events rubric rule so re-collection never replays one.
     */
    val events: Flow<EntityDetailEvent> = _events.receiveAsFlow()

    val state: StateFlow<EntityDetailUiState> =
        entityIdFlow
            .flatMapLatest { id ->
                if (id == null) {
                    flowOf(EntityDetailUiState.Idle)
                } else {
                    observeReady(id)
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = EntityDetailUiState.Idle,
            )

    /** Set the entity to observe. Safe to call repeatedly with the same id. */
    fun load(entityId: String) {
        entityIdFlow.value = entityId
    }

    /** Reveal every gated entry for this session. Session-scoped only — never persisted. */
    fun showHidden() {
        revealFlow.value = true
    }

    /** Rename the current entity, preserving its existing image reference. No-op before [load]. */
    fun rename(newName: String) {
        val id = entityIdFlow.value ?: return
        viewModelScope.launch {
            val entity = entityEditRepository.observeEntity(id).first() ?: return@launch
            when (val result = entityEditRepository.updateCore(id, newName, entity.imageRef)) {
                is AppResult.Success -> Unit
                is AppResult.Failure -> errorBus.emit(result.error)
            }
        }
    }

    /**
     * Delete the current entity. On success, emits [EntityDetailEvent.EntityDeleted] so the
     * screen can navigate back. No-op before [load].
     */
    fun deleteEntity() {
        val id = entityIdFlow.value ?: return
        viewModelScope.launch {
            when (val result = entityEditRepository.deleteEntity(id)) {
                is AppResult.Success -> _events.trySend(EntityDetailEvent.EntityDeleted)
                is AppResult.Failure -> errorBus.emit(result.error)
            }
        }
    }

    /** Delete a single log entry from this entity's chronological log. */
    fun deleteEntry(eventId: String) {
        viewModelScope.launch {
            when (val result = worldEventEditRepository.delete(eventId)) {
                is AppResult.Success -> Unit
                is AppResult.Failure -> errorBus.emit(result.error)
            }
        }
    }

    private fun observeReady(entityId: String): Flow<EntityDetailUiState> =
        entityEditRepository
            .observeEntity(entityId)
            .flatMapLatest { entity ->
                if (entity == null) {
                    flowOf(EntityDetailUiState.NotFound)
                } else {
                    observeEntityReady(entity)
                }
            }.onStart { emit(EntityDetailUiState.Loading) }

    private fun observeEntityReady(entity: Entity): Flow<EntityDetailUiState> {
        val world = WorldRef(seriesId = entity.homeSeriesId, bookId = entity.homeBookId)
        val entitiesFlow =
            world.seriesId?.let { entityEditRepository.observeEntitiesForSeries(it) }
                ?: entityEditRepository.observeEntitiesForBook(world.bookId!!)
        val eventsFlow = worldEventEditRepository.observeForEntity(entity.id)
        val positionsFlow =
            playbackPositionRepository.observeAll().map { positions -> positions.mapKeys { (id, _) -> id.value } }
        val worldBooksFlow = observeWorldBooks(world)

        return combine(
            entitiesFlow,
            eventsFlow,
            positionsFlow,
            worldBooksFlow,
            revealFlow,
        ) { entities, events, positions, worldBooks, reveal ->
            buildDraft(entity, entities, events, positions, worldBooks, reveal)
        }.flatMapLatest { draft -> resolveChapters(draft) }
    }

    private fun observeWorldBooks(world: WorldRef): Flow<WorldBooksSnapshot> =
        if (world.seriesId != null) {
            seriesRepository.observeSeriesWithBooks(world.seriesId).map { seriesWithBooks ->
                if (seriesWithBooks == null) {
                    WorldBooksSnapshot(orderedBookIds = emptyList(), booksById = emptyMap())
                } else {
                    val ordered = seriesWithBooks.booksSortedBySequence()
                    WorldBooksSnapshot(
                        orderedBookIds = ordered.map { it.id.value },
                        booksById =
                            ordered.associate { book ->
                                book.id.value to
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
                if (book == null) {
                    WorldBooksSnapshot(orderedBookIds = emptyList(), booksById = emptyMap())
                } else {
                    WorldBooksSnapshot(
                        orderedBookIds = listOf(book.id.value),
                        booksById =
                            mapOf(
                                book.id.value to
                                    WorldBook(id = book.id.value, title = book.title, sequenceLabel = null),
                            ),
                    )
                }
            }
        }

    private fun buildDraft(
        entity: Entity,
        entities: List<Entity>,
        events: List<WorldEvent>,
        positions: Map<String, PlaybackPosition>,
        worldBooks: WorldBooksSnapshot,
        reveal: Boolean,
    ): Draft {
        val entityById = entities.associateBy { it.id }
        val chronological = chronologicalOrder(events, worldBooks.orderedBookIds)
        val gated = FrontierGate.gate(chronological, positions, reveal) { it.bookId to it.positionMs }

        val unstartedBooksBanner =
            worldBooks.orderedBookIds.any { bookId ->
                val position = positions[bookId]
                position == null || (position.startedAtMs == null && position.maxPositionMs == 0L)
            }

        // Evolution's frontier split is independent of the session `showHidden()` toggle — it
        // always gates as if reveal were false, so the divider stays the honest boundary.
        val (evolutionRevealed, evolutionHidden) =
            chronological.partition { event -> FrontierGate.isVisible(event.bookId, event.positionMs, positions) }

        return Draft(
            entityCard = entity.toCard(),
            world = WorldRef(seriesId = entity.homeSeriesId, bookId = entity.homeBookId),
            visibleEvents = gated.visible,
            hiddenCount = gated.hiddenCount,
            revealed = reveal,
            entityById = entityById,
            worldBooksById = worldBooks.booksById,
            unstartedBooksBanner = unstartedBooksBanner,
            evolutionRevealed = evolutionRevealed,
            evolutionHidden = evolutionHidden,
        )
    }

    /**
     * Orders [events] by the world's own internal clock — baseline entries (no book anchor)
     * first (stable by id), then anchored entries by (this world's book sequence, in-book
     * position, id). A bookId absent from [orderedBookIds] sorts last.
     */
    private fun chronologicalOrder(
        events: List<WorldEvent>,
        orderedBookIds: List<String>,
    ): List<WorldEvent> {
        val baseline = events.filter { it.bookId == null }.sortedBy { it.id }
        val anchored =
            events
                .filter { it.bookId != null }
                .sortedWith(
                    compareBy(
                        { event: WorldEvent -> bookRank(event.bookId, orderedBookIds) },
                        { event: WorldEvent -> event.positionMs ?: 0L },
                        { event: WorldEvent -> event.id },
                    ),
                )
        return baseline + anchored
    }

    /** This world's sequence position for [bookId], or [Int.MAX_VALUE] when [bookId] is unknown to this world. */
    private fun bookRank(
        bookId: String?,
        orderedBookIds: List<String>,
    ): Int {
        val index = bookId?.let { orderedBookIds.indexOf(it) } ?: -1
        return if (index >= 0) index else Int.MAX_VALUE
    }

    private fun resolveChapters(draft: Draft): Flow<EntityDetailUiState> {
        // Widened beyond visibleEvents' books: hidden Evolution rows still need a labeled anchor
        // (existence-only, not text), so their books' chapters must resolve too.
        val neededBookIds =
            (draft.visibleEvents + draft.evolutionHidden).mapNotNull { it.bookId }.distinct()
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
    ): EntityDetailUiState.Ready {
        val entries =
            draft.visibleEvents.map { event ->
                EntityEntryRow(
                    id = event.id,
                    type = event.type,
                    renderedText = MentionTokens.render(event.text) { id -> draft.entityById[id]?.name },
                    anchor = resolveAnchor(event, draft.worldBooksById, chaptersByBookId),
                )
            }

        return EntityDetailUiState.Ready(
            entity = draft.entityCard,
            world = draft.world,
            entries = entries,
            hiddenCount = draft.hiddenCount,
            revealed = draft.revealed,
            unstartedBooksBanner = draft.unstartedBooksBanner,
            evolution = buildEvolution(draft, chaptersByBookId),
        )
    }

    /** Anchor label for [event] — shared by the entries tab and Evolution's revealed/hidden rows. */
    private fun resolveAnchor(
        event: WorldEvent,
        worldBooksById: Map<String, WorldBook>,
        chaptersByBookId: Map<String, List<Chapter>>,
    ): AnchorLabel {
        val worldBook = event.bookId?.let { worldBooksById[it] }
        val bookLabel = worldBook?.let { it.sequenceLabel ?: it.title }
        val chapters = event.bookId?.let { chaptersByBookId[it] }.orEmpty()
        return AnchorLabeler.label(bookLabel, chapters, event.positionMs)
    }

    /**
     * Builds the Evolution tab's frontier-divided timeline from [Draft.evolutionRevealed] /
     * [Draft.evolutionHidden] — see [EvolutionUi] KDoc for the frontier-label rule.
     */
    private fun buildEvolution(
        draft: Draft,
        chaptersByBookId: Map<String, List<Chapter>>,
    ): EvolutionUi {
        val revealedRows =
            draft.evolutionRevealed.mapIndexed { index, event ->
                EvolutionRow(
                    eventId = event.id,
                    renderedText = MentionTokens.render(event.text) { id -> draft.entityById[id]?.name },
                    anchor = resolveAnchor(event, draft.worldBooksById, chaptersByBookId),
                    isLatest = index == draft.evolutionRevealed.lastIndex,
                )
            }
        val hiddenRows =
            draft.evolutionHidden.map { event ->
                EvolutionRow(
                    eventId = event.id,
                    renderedText = null,
                    anchor = resolveAnchor(event, draft.worldBooksById, chaptersByBookId),
                    isLatest = false,
                )
            }

        val frontierLabel =
            if (hiddenRows.isEmpty()) {
                null
            } else {
                revealedRows.lastOrNull { it.anchor !is AnchorLabel.AlwaysVisible }?.anchor
                    ?: hiddenRows.first().anchor
            }

        return EvolutionUi(revealed = revealedRows, hidden = hiddenRows, frontierLabel = frontierLabel)
    }

    private fun Entity.toCard(): EntityCard = EntityCard(id = id, name = name, kind = kind)

    /** This world's book roster, keyed for [AnchorLabeler] book-label lookups and clock ordering. */
    private data class WorldBooksSnapshot(
        val orderedBookIds: List<String>,
        val booksById: Map<String, WorldBook>,
    )

    /** One of this world's books — [sequenceLabel] is null for a standalone-book world. */
    private data class WorldBook(
        val id: String,
        val title: String,
        val sequenceLabel: String?,
    )

    /** Everything computed pre-gating/pre-chapter-resolution except the anchor-dependent entries. */
    private data class Draft(
        val entityCard: EntityCard,
        val world: WorldRef,
        val visibleEvents: List<WorldEvent>,
        val hiddenCount: Int,
        val revealed: Boolean,
        val entityById: Map<String, Entity>,
        val worldBooksById: Map<String, WorldBook>,
        val unstartedBooksBanner: Boolean,
        /** Chronologically-ordered events before the frontier, gated as if `reveal` were false. */
        val evolutionRevealed: List<WorldEvent>,
        /** Chronologically-ordered events beyond the frontier, gated as if `reveal` were false. */
        val evolutionHidden: List<WorldEvent>,
    )
}

/** UI state for the Story World entity detail screen. */
sealed interface EntityDetailUiState {
    /** No entity selected (pre-[EntityDetailViewModel.load]). */
    data object Idle : EntityDetailUiState

    /** Upstream has not yet produced data for the selected entity. */
    data object Loading : EntityDetailUiState

    /** The entity was absent or has been tombstoned (deleted elsewhere, or a bad id). */
    data object NotFound : EntityDetailUiState

    /** Entity data loaded. */
    data class Ready(
        /** The entity being viewed. */
        val entity: EntityCard,
        /** This entity's home world — feeds the composer sheet when adding/editing an entry here. */
        val world: WorldRef,
        /** This entity's chronological log — the world's own internal clock, gated for spoilers. */
        val entries: List<EntityEntryRow>,
        /** How many log entries [FrontierGate] hid from [entries]. */
        val hiddenCount: Int,
        /** Whether the viewer chose to reveal every gated entry this session. */
        val revealed: Boolean,
        /** True when at least one of this world's books has never been started. */
        val unstartedBooksBanner: Boolean,
        /** This entity's Evolution-tab timeline — frontier-divided independent of [revealed]. */
        val evolution: EvolutionUi,
    ) : EntityDetailUiState
}

/** A single rendered entry in an entity's chronological log. */
data class EntityEntryRow(
    val id: String,
    val type: WorldEventType,
    /** [WorldEvent.text] with every mention token resolved to a live (or cached-fallback) entity name. */
    val renderedText: String,
    val anchor: AnchorLabel,
)

/**
 * The Evolution tab's frontier-divided timeline — built from the same chronologically-ordered
 * event list [EntityDetailUiState.Ready.entries] draws from, but split with the frontier gate
 * pinned to `reveal = false` always: the session-scoped [EntityDetailViewModel.showHidden]
 * reveal never leaks event text into this tab, so the frontier divider stays an honest boundary
 * regardless of what the entries tab is currently showing.
 *
 * @property revealed Rows before the frontier, in chronological order, with rendered text.
 * @property hidden Rows beyond the frontier, in chronological order — existence only: the
 *   anchor is still labeled, but [EvolutionRow.renderedText] is null (spec's accepted trade-off).
 * @property frontierLabel The "through here" divider label: the anchor of the last [revealed]
 *   row that carries a book anchor (baseline rows with no anchor are skipped), or — when no
 *   revealed row has a book anchor — the anchor of the first [hidden] row. Null when [hidden]
 *   is empty, since there's nothing to divide.
 */
data class EvolutionUi(
    val revealed: List<EvolutionRow>,
    val hidden: List<EvolutionRow>,
    val frontierLabel: AnchorLabel?,
)

/**
 * One row in [EvolutionUi]'s frontier-divided timeline.
 *
 * @property eventId The underlying [WorldEvent.id].
 * @property renderedText The mention-rendered event text, or null for a row beyond the
 *   frontier — no spoiler text crosses the divide, only the anchor.
 * @property anchor Where this event sits in the book. Resolved the same way for revealed and
 *   hidden rows alike, so even a hidden row can be labeled (e.g. by chapter title).
 * @property isLatest True on the last row of [EvolutionUi.revealed] only; always false for a
 *   [EvolutionUi.hidden] row.
 */
data class EvolutionRow(
    val eventId: String,
    val renderedText: String?,
    val anchor: AnchorLabel,
    val isLatest: Boolean,
)

/** One-shot events emitted by [EntityDetailViewModel] for the screen to consume exactly once. */
sealed interface EntityDetailEvent {
    /** The entity was deleted; the screen should navigate back. */
    data object EntityDeleted : EntityDetailEvent
}
