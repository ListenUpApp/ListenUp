package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.api.sync.WorldEventSource
import com.calypsan.listenup.api.sync.WorldEventType

/**
 * Domain model for a single row of the Story World unified event log — library-shared, curated
 * world data namespaced under either a series or a standalone book, and optionally anchored to a
 * specific book position. Reuses the contract's [WorldEventType] / [WorldEventSource] directly —
 * both are wire concepts with no client-only semantics, the [Entity.kind] precedent.
 *
 * Events are dual-homed: exactly one of [homeSeriesId] / [homeBookId] is non-null — never both,
 * never neither. See [com.calypsan.listenup.api.sync.WorldEventSyncPayload] for the full
 * dual-home rule.
 *
 * @property id Stable, client-minted identifier (random UUID, stable across create and edit).
 * @property homeSeriesId The series this event is namespaced under — a namespacing key only.
 *   Exactly one of [homeSeriesId] / [homeBookId] is non-null.
 * @property homeBookId The standalone book this event is namespaced under — a namespacing key
 *   only. Exactly one of [homeSeriesId] / [homeBookId] is non-null.
 * @property bookId The book this event is anchored to, or null when the event carries no book
 *   anchor and is always visible, regardless of a reader's progress through any book.
 * @property positionMs Millisecond offset within [bookId]'s total duration this event is pinned
 *   to. Null exactly when [bookId] is null.
 * @property type The event's typed vocabulary slot.
 * @property text Free text describing the event; may carry inline `@entity` mention tokens
 *   (see [com.calypsan.listenup.api.core.MentionTokens]).
 * @property subjectEntityId The entity this event is principally about, if any (e.g. the
 *   character who moves).
 * @property objectEntityId A second entity this event relates [subjectEntityId] to, if any
 *   (e.g. an item transferred).
 * @property mentionIds The full set of entity ids this event mentions — the union of every
 *   inline mention token in [text] plus [subjectEntityId] and [objectEntityId], recomputed
 *   client-side by [com.calypsan.listenup.client.data.sync.domains.worldEventMentionIds].
 * @property source Whether this event was hand-written by a caller or produced by a future
 *   import pipeline.
 */
data class WorldEvent(
    val id: String,
    val homeSeriesId: String? = null,
    val homeBookId: String? = null,
    val bookId: String? = null,
    val positionMs: Long? = null,
    val type: WorldEventType,
    val text: String,
    val subjectEntityId: String? = null,
    val objectEntityId: String? = null,
    val mentionIds: List<String> = emptyList(),
    val source: WorldEventSource,
)

/**
 * The caller-supplied fields for a brand-new Story World event — the pre-id shape of a
 * [com.calypsan.listenup.api.dto.world.WorldEventUpsert], used by
 * [com.calypsan.listenup.client.domain.repository.WorldEventEditRepository.record] and
 * [com.calypsan.listenup.client.domain.repository.WorldEventEditRepository.recordBatch] before
 * the repository mints each event's id.
 *
 * @property type The event's typed vocabulary slot.
 * @property text Free text describing the event; may carry inline `@entity` mention tokens.
 * @property homeSeriesId The series to namespace the event under. Exactly one of [homeSeriesId] /
 *   [homeBookId] must be non-null.
 * @property homeBookId The standalone book to namespace the event under. Exactly one of
 *   [homeSeriesId] / [homeBookId] must be non-null.
 * @property bookId The book to anchor the event to, or null for no book anchor. When non-null,
 *   [positionMs] must also be non-null.
 * @property positionMs Millisecond offset within [bookId]'s total duration. Null exactly when
 *   [bookId] is null.
 * @property subjectEntityId The entity this event is principally about, if any.
 * @property objectEntityId A second entity this event relates [subjectEntityId] to, if any.
 */
data class NewWorldEvent(
    val type: WorldEventType,
    val text: String,
    val homeSeriesId: String? = null,
    val homeBookId: String? = null,
    val bookId: String? = null,
    val positionMs: Long? = null,
    val subjectEntityId: String? = null,
    val objectEntityId: String? = null,
)
