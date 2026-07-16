package com.calypsan.listenup.client.presentation.storyworld

import com.calypsan.listenup.api.sync.WorldEventType
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.client.domain.model.WorldEvent

/**
 * The book-scope and viewer-frontier a [WorldProjection.project] fold runs against.
 *
 * Determines both which events are in scope for a fold (spec §2 v1 simplification: events
 * anchored to a book outside the active scope are excluded from this projection — they still
 * surface on the entity's own page via a separate, unscoped query) and the position-based
 * ordering key events sort by within that scope.
 */
internal sealed interface FoldClock {
    /** The viewer's per-book listening frontiers — the same map [FrontierGate.isVisible] reads. */
    val frontiers: Map<String, PlaybackPosition>

    /**
     * Scopes the fold to a reading order: events anchored to a book in [orderedBookIds], or
     * baseline events with no book anchor, ordered first by each book's index in the list.
     */
    data class OrderedClock(
        val orderedBookIds: List<String>,
        override val frontiers: Map<String, PlaybackPosition>,
    ) : FoldClock

    /**
     * Scopes the fold to a single book: events anchored to [bookId], or baseline events with no
     * book anchor.
     */
    data class PerBookClock(
        val bookId: String,
        override val frontiers: Map<String, PlaybackPosition>,
    ) : FoldClock
}

/**
 * An entity's last known whereabouts as folded from the safe portion of the world-event log.
 */
internal sealed interface LocationFact {
    /** The entity's last known location is the entity identified by [locationEntityId]. */
    data class Known(
        val locationEntityId: String,
    ) : LocationFact

    /**
     * The entity has departed its last known location. [fromEntityId] carries the location it
     * departed from when the authoring event recorded one; server-validated to usually be
     * present, but not required.
     */
    data class EnRoute(
        val fromEntityId: String?,
    ) : LocationFact

    /** No location has ever been established for this entity by a safe, folded event. */
    data object Unknown : LocationFact
}

/**
 * One entity's world state as folded from the safe, ordered portion of the event log — the
 * spoiler-safe "what do we know about this entity right now" projection.
 *
 * @property entityId The entity this state describes.
 * @property inScene Whether the entity is currently on-scene, per the latest safe
 *   `ENTERS_SCENE`/`EXITS_SCENE` event naming it as subject.
 * @property location The entity's last known whereabouts.
 * @property statusNote The latest safe `NOTE` event mentioning this entity, by fold order.
 * @property lastSeen The latest safe event mentioning this entity, by fold order — regardless of
 *   type.
 * @property eventCount How many safe events mention this entity.
 */
internal data class EntityWorldState(
    val entityId: String,
    val inScene: Boolean = false,
    val location: LocationFact = LocationFact.Unknown,
    val statusNote: WorldEvent? = null,
    val lastSeen: WorldEvent? = null,
    val eventCount: Int = 0,
)

/**
 * The full spoiler-safe world state produced by folding an event log through a [FoldClock].
 *
 * @property entities Per-entity world state, keyed by entity id. An entity never mentioned by any
 *   safe event in scope simply has no entry.
 * @property foldedEventCount How many events actually contributed to this projection — after
 *   scope filtering (spec §2) and frontier filtering ([FrontierGate]). Surfaced so callers can
 *   distinguish "nothing has happened yet" from "everything is still hidden".
 */
internal data class WorldState(
    val entities: Map<String, EntityWorldState>,
    val foldedEventCount: Int,
)

/**
 * Pure fold from a Story World event log to spoiler-safe [WorldState] (spec §2/§6).
 *
 * The fold is deliberately pure and total: same events + same clock always yields the same
 * state, in any input order, because the events are re-sorted onto their own deterministic clock
 * before folding. This is what lets a caller preview a hypothetical listening position via
 * [withPlayheadRaised] without touching persisted playback state.
 */
internal object WorldProjection {
    /**
     * Folds [events] into a [WorldState], scoped and ordered by [clock].
     *
     * Three passes, in order:
     * 1. **Scope filter** — keep only events in [clock]'s book scope (or baseline events with no
     *    book anchor); see [FoldClock] for what "in scope" means per clock variant.
     * 2. **Safe filter** — drop events beyond the viewer's listening frontier, per
     *    [FrontierGate.isVisible].
     * 3. **Deterministic sort + reduce** — sort the remaining events by `(orderIndex, positionMs,
     *    id)` ascending, then fold left to right, mutating each mentioned entity's
     *    [EntityWorldState].
     */
    fun project(
        events: List<WorldEvent>,
        clock: FoldClock,
    ): WorldState {
        val scoped = events.mapNotNull { event -> clock.orderIndexOf(event.bookId)?.let { ScopedEvent(event, it) } }
        val safe = scoped.filter { FrontierGate.isVisible(it.event.bookId, it.event.positionMs, clock.frontiers) }
        val ordered = safe.sortedWith(compareBy({ it.orderIndex }, { it.event.positionMs ?: 0L }, { it.event.id }))

        val entities = mutableMapOf<String, EntityWorldState>()
        ordered.forEach { applyEvent(entities, it.event) }
        return WorldState(entities = entities, foldedEventCount = ordered.size)
    }

    /**
     * Returns [clock] with [bookId]'s frontier widened to cover [playheadMs] — a session-only
     * "preview as of here" reveal, never persisted.
     *
     * When [bookId] has no stored frontier yet (the book was never started), synthesizes a
     * minimal [PlaybackPosition] with [PlaybackPosition.startedAtMs] set, so
     * [FrontierGate.isVisible]'s "book was started" rule also opens up for this book's
     * null-`positionMs` events.
     */
    fun withPlayheadRaised(
        clock: FoldClock,
        bookId: String,
        playheadMs: Long,
    ): FoldClock {
        val raisedFrontiers = clock.frontiers + (bookId to raisePosition(clock.frontiers[bookId], bookId, playheadMs))
        return when (clock) {
            is FoldClock.OrderedClock -> clock.copy(frontiers = raisedFrontiers)
            is FoldClock.PerBookClock -> clock.copy(frontiers = raisedFrontiers)
        }
    }

    /** An event paired with its precomputed scope-ordering key, so it's computed exactly once. */
    private data class ScopedEvent(
        val event: WorldEvent,
        val orderIndex: Int,
    )

    /**
     * This clock's ordering key for [bookId], or null when [bookId] is out of scope for this
     * clock. Baseline events (`bookId == null`) always sort first, at index -1.
     */
    private fun FoldClock.orderIndexOf(bookId: String?): Int? =
        when (this) {
            is FoldClock.OrderedClock -> {
                when (bookId) {
                    null -> -1
                    else -> orderedBookIds.indexOf(bookId).takeIf { it >= 0 }
                }
            }

            is FoldClock.PerBookClock -> {
                val scopeBookId = this.bookId
                when (bookId) {
                    null -> -1
                    scopeBookId -> 0
                    else -> null
                }
            }
        }

    /** Folds a single event into [entities]: mention bookkeeping, then a typed transition. */
    private fun applyEvent(
        entities: MutableMap<String, EntityWorldState>,
        event: WorldEvent,
    ) {
        event.mentionIds.forEach { entityId -> applyMention(entities, entityId, event) }
        applyTransition(entities, event)
    }

    /** Bumps [entityId]'s eventCount/lastSeen, and its statusNote when [event] is a `NOTE`. */
    private fun applyMention(
        entities: MutableMap<String, EntityWorldState>,
        entityId: String,
        event: WorldEvent,
    ) {
        val current = entities[entityId] ?: EntityWorldState(entityId = entityId)
        entities[entityId] =
            current.copy(
                lastSeen = event,
                eventCount = current.eventCount + 1,
                statusNote = if (event.type == WorldEventType.NOTE) event else current.statusNote,
            )
    }

    /**
     * Applies [event]'s typed fact transition to its subject, when it has one. The full
     * [WorldEventType] vocabulary is listed explicitly — reserved types are accepted-and-stored
     * but deliberately reduce to no fact change (see the `ReducerIgnored` arms below).
     */
    private fun applyTransition(
        entities: MutableMap<String, EntityWorldState>,
        event: WorldEvent,
    ) {
        val subjectId = event.subjectEntityId ?: return
        val subject = entities[subjectId] ?: return
        when (event.type) {
            WorldEventType.ENTERS_SCENE -> {
                entities[subjectId] = subject.copy(inScene = true)
            }

            WorldEventType.EXITS_SCENE -> {
                entities[subjectId] = subject.copy(inScene = false)
            }

            WorldEventType.MOVES_TO -> {
                applyMovesTo(entities, subjectId, subject, event.objectEntityId)
            }

            WorldEventType.DEPARTS -> {
                entities[subjectId] = subject.copy(location = LocationFact.EnRoute(fromEntityId = event.objectEntityId))
            }

            WorldEventType.NOTE -> {
                Unit
            }

            // No fact change; statusNote is already handled in applyMention.
            WorldEventType.ALIAS -> {
                Unit
            }

            // ReducerIgnored: reserved type — accepted-and-stored, reduced post-v1 (spec §2)
            WorldEventType.BORN -> {
                Unit
            }

            // ReducerIgnored: reserved type — accepted-and-stored, reduced post-v1 (spec §2)
            WorldEventType.DIES -> {
                Unit
            }

            // ReducerIgnored: reserved type — accepted-and-stored, reduced post-v1 (spec §2)
            WorldEventType.ITEM_TRANSFER -> {
                Unit
            }

            // ReducerIgnored: reserved type — accepted-and-stored, reduced post-v1 (spec §2)
            WorldEventType.RELATIONSHIP_CHANGE -> {
                Unit
            } // ReducerIgnored: reserved type — accepted-and-stored, reduced post-v1 (spec §2)
        }
    }

    /**
     * Sets [subjectId]'s location to `Known(objectEntityId)`. `MOVES_TO` is server-validated to
     * always carry an object, but the reducer stays defensive: a null object skips the location
     * change rather than throwing, since a malformed event is never worth crashing a fold over.
     */
    private fun applyMovesTo(
        entities: MutableMap<String, EntityWorldState>,
        subjectId: String,
        subject: EntityWorldState,
        objectEntityId: String?,
    ) {
        if (objectEntityId == null) return
        entities[subjectId] = subject.copy(location = LocationFact.Known(objectEntityId))
    }

    /** Widens [existing]'s frontier to cover [playheadMs], synthesizing a fresh row when absent. */
    private fun raisePosition(
        existing: PlaybackPosition?,
        bookId: String,
        playheadMs: Long,
    ): PlaybackPosition {
        val maxPositionMs = maxOf(existing?.maxPositionMs ?: 0L, playheadMs)
        return existing?.copy(maxPositionMs = maxPositionMs) ?: newSyntheticPosition(bookId, maxPositionMs)
    }

    /** A minimal "started, never actually played" position, used only as a session-local frontier. */
    private fun newSyntheticPosition(
        bookId: String,
        maxPositionMs: Long,
    ): PlaybackPosition =
        PlaybackPosition(
            bookId = bookId,
            positionMs = 0L,
            maxPositionMs = maxPositionMs,
            playbackSpeed = 1.0f,
            hasCustomSpeed = false,
            updatedAtMs = 0L,
            syncedAtMs = null,
            lastPlayedAtMs = null,
            isFinished = false,
            finishedAtMs = null,
            startedAtMs = 0L,
        )
}
