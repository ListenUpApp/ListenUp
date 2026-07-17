package com.calypsan.listenup.client.presentation.storyworld

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.core.MentionTokens
import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.model.Entity
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.client.domain.model.WorldEvent
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.EntityEditRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.domain.repository.ReadingOrderRepository
import com.calypsan.listenup.client.domain.repository.SeriesRepository
import com.calypsan.listenup.client.domain.repository.WorldEventEditRepository
import com.calypsan.listenup.client.playback.PlaybackManager
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
 * ViewModel for the Story So Far screen — a single book's "what do I need to remember" recap,
 * folded from the Story World event log up to the viewer's own listening frontier (or, opted in
 * via [setAsOfHere], as of the exact position currently playing).
 *
 * Mirrors [StoryWorldHubViewModel]'s `combine(...).stateIn(WhileSubscribed)` shape. The key
 * addition is [FoldClock] selection: when the loaded book belongs to a series with an active
 * reading order ([ReadingOrderRepository.observeActiveReadingOrder]), the fold spans every book
 * in that order ([FoldClock.OrderedClock]); otherwise it falls back to [FoldClock.PerBookClock],
 * scoped to the loaded book alone — a series with more than one book and no active order surfaces
 * that fallback as [StorySoFarUiState.Ready.noOrderFloor], a CTA to follow an order.
 *
 * Pragmatic bound: chapter-precise anchor labels ([AnchorLabeler]) are only resolved for the
 * loaded book itself. An anchor on another book in the same reading order still renders — just as
 * a book-label + elapsed-time anchor ([AnchorLabel.AtTime]/[AnchorLabel.BookOnly]) rather than a
 * chapter title, since fetching every ordered book's chapters purely to label a handful of
 * cross-book rows isn't worth the extra subscriptions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StorySoFarViewModel(
    private val worldEventEditRepository: WorldEventEditRepository,
    private val entityEditRepository: EntityEditRepository,
    private val seriesRepository: SeriesRepository,
    private val bookRepository: BookRepository,
    private val playbackPositionRepository: PlaybackPositionRepository,
    private val readingOrderRepository: ReadingOrderRepository,
    private val playbackManager: PlaybackManager,
) : ViewModel() {
    private val bookIdFlow = MutableStateFlow<String?>(null)
    private val asOfHereFlow = MutableStateFlow(false)

    val state: StateFlow<StorySoFarUiState> =
        bookIdFlow
            .flatMapLatest { bookId ->
                if (bookId == null) {
                    flowOf(StorySoFarUiState.Idle)
                } else {
                    observeReady(bookId)
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = StorySoFarUiState.Idle,
            )

    /** Set the book to observe. Safe to call repeatedly with the same id. */
    fun load(bookId: String) {
        bookIdFlow.value = bookId
    }

    /** Session-only "preview as of here" toggle — never persisted, resets on a fresh instance. */
    fun setAsOfHere(enabled: Boolean) {
        asOfHereFlow.value = enabled
    }

    private fun observeReady(bookId: String): Flow<StorySoFarUiState> =
        seriesRepository.observeByBookId(bookId).flatMapLatest { series ->
            val seriesId = series?.id?.value
            val entitiesFlow =
                seriesId?.let { entityEditRepository.observeEntitiesForSeries(it) }
                    ?: entityEditRepository.observeEntitiesForBook(bookId)
            val eventsFlow =
                worldEventEditRepository.observeForWorld(
                    homeSeriesId = seriesId,
                    homeBookId = if (seriesId == null) bookId else null,
                )
            val worldBooksFlow = observeWorldBooks(seriesId, bookId)
            val clockInputFlow = observeClockInput(seriesId)
            val positionsFlow =
                playbackPositionRepository.observeAll().map { positions -> positions.mapKeys { (id, _) -> id.value } }
            val chaptersFlow = bookRepository.observeChapters(bookId)

            combine(
                combine(
                    entitiesFlow,
                    eventsFlow,
                    worldBooksFlow,
                    clockInputFlow,
                    positionsFlow,
                ) { entities, events, worldBooks, clockInput, positions ->
                    Snapshot(entities, events, worldBooks, clockInput, positions)
                },
                asOfHereFlow,
                playbackManager.currentBookId,
                playbackManager.currentPositionMs,
                chaptersFlow,
            ) { snapshot, asOfHere, currentBookId, currentPositionMs, chapters ->
                val context = PlaybackContext(asOfHere, currentBookId?.value, currentPositionMs, chapters)
                buildState(bookId, seriesId, snapshot, context)
            }
        }.onStart { emit(StorySoFarUiState.Loading) }

    private fun observeWorldBooks(
        seriesId: String?,
        bookId: String,
    ): Flow<WorldBooksSnapshot> =
        if (seriesId != null) {
            seriesRepository.observeSeriesWithBooks(seriesId).map { seriesWithBooks ->
                if (seriesWithBooks == null) {
                    WorldBooksSnapshot(books = emptyList())
                } else {
                    WorldBooksSnapshot(
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
            bookRepository.observeBookListItems(listOf(bookId)).map { books ->
                val book = books.firstOrNull()
                WorldBooksSnapshot(
                    books =
                        listOfNotNull(
                            book?.let { WorldBook(id = it.id.value, title = it.title, sequenceLabel = null) },
                        ),
                )
            }
        }

    private fun observeClockInput(seriesId: String?): Flow<ClockInput> =
        if (seriesId == null) {
            flowOf(ClockInput(orderedBookIds = null, orderName = null))
        } else {
            readingOrderRepository.observeActiveReadingOrder(seriesId).flatMapLatest { readingOrderId ->
                if (readingOrderId == null) {
                    flowOf(ClockInput(orderedBookIds = null, orderName = null))
                } else {
                    combine(
                        readingOrderRepository.observeReadingOrderBookIds(readingOrderId),
                        readingOrderRepository.observeById(readingOrderId),
                    ) { bookIds, order ->
                        ClockInput(orderedBookIds = bookIds.map { it.value }, orderName = order?.name)
                    }
                }
            }
        }

    private fun buildState(
        bookId: String,
        seriesId: String?,
        snapshot: Snapshot,
        context: PlaybackContext,
    ): StorySoFarUiState {
        val entityById = snapshot.entities.associateBy { it.id }
        val worldBooksById = snapshot.worldBooks.books.associateBy { it.id }
        val bookTitle = worldBooksById[bookId]?.title.orEmpty()
        val bookLabel = worldBooksById[bookId]?.let { it.sequenceLabel ?: it.title }
        val chapters = context.chapters

        val orderedBookIds = snapshot.clockInput.orderedBookIds
        val clock: FoldClock =
            if (orderedBookIds != null) {
                FoldClock.OrderedClock(orderedBookIds = orderedBookIds, frontiers = snapshot.positions)
            } else {
                FoldClock.PerBookClock(bookId = bookId, frontiers = snapshot.positions)
            }
        val noOrderFloor = seriesId != null && orderedBookIds == null && snapshot.worldBooks.books.size > 1

        val asOfHereAvailable = context.currentPlayingBookId == bookId
        val safeState = WorldProjection.project(snapshot.events, clock)
        val usedState =
            if (context.asOfHere && asOfHereAvailable) {
                WorldProjection.project(
                    snapshot.events,
                    WorldProjection.withPlayheadRaised(clock, bookId, context.currentPositionMs),
                )
            } else {
                safeState
            }

        if (usedState.foldedEventCount == 0) {
            return StorySoFarUiState.EmptyFloor(bookTitle = bookTitle, seriesId = seriesId, bookId = bookId)
        }

        fun buildRow(
            state: EntityWorldState,
            entity: Entity,
            isNew: Boolean,
        ): StandRowUi {
            val location = state.location
            val locationName = (location as? LocationFact.Known)?.let { entityById[it.locationEntityId]?.name }
            val enRoute = location is LocationFact.EnRoute
            val enRouteFrom = (location as? LocationFact.EnRoute)?.fromEntityId?.let { entityById[it]?.name }
            val statusLine =
                state.statusNote?.text?.let { text -> MentionTokens.render(text) { id -> entityById[id]?.name } }
            val lastSeenAnchor =
                state.lastSeen?.let { event ->
                    val label = event.bookId?.let { id -> worldBooksById[id]?.let { book -> book.sequenceLabel ?: book.title } }
                    val eventChapters = if (event.bookId == bookId) chapters else emptyList()
                    AnchorLabeler.label(label, eventChapters, event.positionMs)
                }
            return StandRowUi(
                entity = entity.toCard(),
                locationName = locationName,
                enRouteFrom = enRouteFrom,
                enRoute = enRoute,
                statusLine = statusLine,
                lastSeenAnchor = lastSeenAnchor,
                isNew = isNew,
            )
        }

        val standRows =
            KIND_ORDER.flatMap { kind ->
                snapshot.entities
                    .filter { it.kind == kind }
                    .mapNotNull { entity -> usedState.entities[entity.id]?.let { state -> entity to state } }
                    .sortedWith(recencyComparator(clock))
            }.map { (entity, state) ->
                val isNew = (safeState.entities[state.entityId]?.eventCount ?: 0) == 0 && state.eventCount > 0
                buildRow(state, entity, isNew)
            }

        val inScene =
            KIND_ORDER.flatMap { kind ->
                snapshot.entities.filter { it.kind == kind && usedState.entities[it.id]?.inScene == true }
            }.map { it.toCard() }

        val frontierLabel = AnchorLabeler.label(bookLabel, chapters, snapshot.positions[bookId]?.maxPositionMs ?: 0L)
        val hereLabel =
            if (asOfHereAvailable) AnchorLabeler.label(bookLabel, chapters, context.currentPositionMs) else null

        return StorySoFarUiState.Ready(
            bookId = bookId,
            bookTitle = bookTitle,
            orderName = snapshot.clockInput.orderName,
            frontierLabel = frontierLabel,
            hereLabel = hereLabel,
            asOfHere = context.asOfHere,
            asOfHereAvailable = asOfHereAvailable,
            inScene = inScene,
            standRows = standRows,
            noOrderFloor = noOrderFloor,
            seriesId = seriesId,
        )
    }

    private fun recencyComparator(clock: FoldClock): Comparator<Pair<Entity, EntityWorldState>> =
        compareByDescending<Pair<Entity, EntityWorldState>> { (_, state) -> clock.orderIndexFor(state.lastSeen?.bookId) }
            .thenByDescending { (_, state) -> state.lastSeen?.positionMs ?: Long.MIN_VALUE }
            .thenByDescending { (_, state) -> state.lastSeen?.id }

    /** This clock's ordering key for [bookId] — mirrors [WorldProjection]'s private scope-ordering rule, for sorting only. */
    private fun FoldClock.orderIndexFor(bookId: String?): Int =
        when (this) {
            is FoldClock.OrderedClock -> if (bookId == null) -1 else orderedBookIds.indexOf(bookId)
            is FoldClock.PerBookClock -> if (bookId == null) -1 else 0
        }

    private fun Entity.toCard(): EntityCard = EntityCard(id = id, name = name, kind = kind)

    /** Fold-clock scope + the active reading order's name, resolved before the fold runs. */
    private data class ClockInput(
        val orderedBookIds: List<String>?,
        val orderName: String?,
    )

    /** One of this world's books — [sequenceLabel] is null for a standalone-book world. */
    private data class WorldBook(
        val id: String,
        val title: String,
        val sequenceLabel: String?,
    )

    /** This world's book roster, keyed for [AnchorLabeler] book-label lookups. */
    private data class WorldBooksSnapshot(
        val books: List<WorldBook>,
    )

    /** Raw upstream tick — one flow per data source, pre-fold. */
    private data class Snapshot(
        val entities: List<Entity>,
        val events: List<WorldEvent>,
        val worldBooks: WorldBooksSnapshot,
        val clockInput: ClockInput,
        val positions: Map<String, PlaybackPosition>,
    )

    /** Playback-derived inputs to the fold, resolved alongside [Snapshot]. */
    private data class PlaybackContext(
        val asOfHere: Boolean,
        val currentPlayingBookId: String?,
        val currentPositionMs: Long,
        val chapters: List<Chapter>,
    )

    private companion object {
        /** Row/card grouping order — every [EntityKind] value, character-first. */
        val KIND_ORDER = listOf(EntityKind.CHARACTER, EntityKind.LOCATION, EntityKind.ITEM)
    }
}

/** UI state for the Story So Far screen. */
sealed interface StorySoFarUiState {
    /** No book selected (pre-[StorySoFarViewModel.load]). */
    data object Idle : StorySoFarUiState

    /** Upstream has not yet produced data for the selected book. */
    data object Loading : StorySoFarUiState

    /** World has zero frontier-safe events — the empty floor. */
    data class EmptyFloor(
        val bookTitle: String,
        val seriesId: String?,
        val bookId: String,
    ) : StorySoFarUiState

    /** World data loaded, with at least one safe (or "as of here"-widened) event folded in. */
    data class Ready(
        val bookId: String,
        val bookTitle: String,
        /** The active reading order's name; null on floors/standalone. */
        val orderName: String?,
        /** The current book's frontier position label. */
        val frontierLabel: AnchorLabel,
        /** The playhead label — populated whenever [asOfHereAvailable], regardless of [asOfHere]. */
        val hereLabel: AnchorLabel?,
        val asOfHere: Boolean,
        /** True only when the loaded book is the one actively playing right now. */
        val asOfHereAvailable: Boolean,
        val inScene: List<EntityCard>,
        val standRows: List<StandRowUi>,
        /** True for a series world with more than one book and no followed reading order. */
        val noOrderFloor: Boolean,
        /** CTA target for [noOrderFloor]; null for a standalone-book world. */
        val seriesId: String?,
    ) : StorySoFarUiState
}

/** One "Where things stand" row on the Story So Far screen. */
data class StandRowUi(
    val entity: EntityCard,
    /** Resolved entity name for [LocationFact.Known]; null when unknown or [enRoute]. */
    val locationName: String?,
    /** Resolved entity name for [LocationFact.EnRoute.fromEntityId]; may be null even when [enRoute] is true. */
    val enRouteFrom: String?,
    val enRoute: Boolean,
    /** The entity's latest status note, mention-tokens resolved to live names; null when it has none. */
    val statusLine: String?,
    val lastSeenAnchor: AnchorLabel?,
    /** True when this entity only appears once the "as of here" preview widens the fold. */
    val isNew: Boolean,
)
