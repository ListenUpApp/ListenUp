package com.calypsan.listenup.api.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTO for a single row of the Story World unified event log, synced between server and
 * client.
 *
 * The unified log is the one place every world happening lives — a manual note, a scene entry,
 * a departure, an imported world-track beat — regardless of [type]. Stage 1 ships the log itself;
 * only [WorldEventType.NOTE] carries no reducer today, and even the reserved types below are
 * accepted-and-stored, never reduced into character/location state until Arc 3.
 *
 * Like [EntitySyncPayload], events are dual-homed: every event is scoped under exactly one of
 * [homeSeriesId] (a series) or [homeBookId] (a standalone book) — never both, never neither. This
 * is a namespacing key only, mirroring the dual-home rule on [EntitySyncPayload].
 *
 * An event is optionally anchored to a specific book position via [bookId] + [positionMs]. A null
 * [bookId] means the event has no book anchor and is always visible, regardless of a reader's
 * progress through any book — e.g. a note about the overall cast. When [bookId] is non-null,
 * [positionMs] is the millisecond offset within that book's total duration the event is pinned
 * to, and it is this offset — not any external spoiler-boundary computation — that a spoiler-aware
 * reader-progress gate compares against.
 *
 * Carries the canonical sync-discipline fields: [revision], [updatedAt], [createdAt],
 * [deletedAt].
 */
@Serializable
@SerialName("WorldEventSyncPayload")
data class WorldEventSyncPayload(
    /** Stable, client-minted identifier for this event (random UUID, stable across export/import). */
    @SerialName("id") override val id: String,
    /**
     * The series this event is scoped under — a namespacing key only, mirroring
     * [EntitySyncPayload.homeSeriesId]. Exactly one of [homeSeriesId] / [homeBookId] is non-null.
     */
    @SerialName("homeSeriesId") val homeSeriesId: String? = null,
    /**
     * The standalone book this event is scoped under — a namespacing key only, for events that
     * belong to a book with no series. Exactly one of [homeSeriesId] / [homeBookId] is non-null.
     */
    @SerialName("homeBookId") val homeBookId: String? = null,
    /**
     * The book this event is anchored to, or null when the event carries no book anchor and is
     * always visible. When non-null, [positionMs] must also be non-null.
     */
    @SerialName("bookId") val bookId: String? = null,
    /**
     * Millisecond offset within [bookId]'s total duration this event is anchored to. Null exactly
     * when [bookId] is null.
     */
    @SerialName("positionMs") val positionMs: Long? = null,
    /** The event's typed vocabulary slot — see [WorldEventType] for which are reduced today. */
    @SerialName("type") val type: WorldEventType,
    /**
     * Free text describing the event. May carry
     * [com.calypsan.listenup.api.core.MentionTokens]-encoded `@entity` mentions inline. May be
     * empty only when a typed assertion ([subjectEntityId] / [objectEntityId] / a reserved [type])
     * carries the event's meaning on its own.
     */
    @SerialName("text") val text: String,
    /** The entity this event is principally about, if any (e.g. the character who moves). */
    @SerialName("subjectEntityId") val subjectEntityId: String? = null,
    /** A second entity this event relates [subjectEntityId] to, if any (e.g. an item transferred). */
    @SerialName("objectEntityId") val objectEntityId: String? = null,
    /**
     * The full set of entity ids this event mentions — the union of every
     * [com.calypsan.listenup.api.core.MentionTokens] token found in [text] plus
     * [subjectEntityId] and [objectEntityId]. This is server-recomputed on every write; a client
     * does not author it authoritatively and any value it sends is superseded by the server's
     * recomputation.
     */
    @SerialName("mentionIds") val mentionIds: List<String> = emptyList(),
    /** Whether this event was hand-written by a caller or produced by a future import pipeline. */
    @SerialName("source") val source: WorldEventSource,
    /** Reserved: the world-track this event was imported from. Always null until track import ships. */
    @SerialName("trackId") val trackId: String? = null,
    /** Reserved: the world-track version this event was imported at. Always null until track import ships. */
    @SerialName("trackVersion") val trackVersion: Long? = null,
    /** Sync revision counter — bumped on every write. */
    @SerialName("revision") override val revision: Long,
    /** Epoch millis of the last server-side write. */
    @SerialName("updatedAt") val updatedAt: Long,
    /** Epoch millis when this event was first created. */
    @SerialName("createdAt") val createdAt: Long,
    @SerialName("deletedAt") override val deletedAt: Long? = null,
) : SyncPayload

/**
 * The typed vocabulary slot of a world event.
 *
 * None of these are reduced into character/location state in Stage 1 — reducers (the code that
 * turns, say, a [MOVES_TO] event into "this character is now at this location" queryable state)
 * arrive in Arc 3. [NOTE] is the only type with no further shape expectations. The remaining
 * values ([ALIAS], [BORN], [DIES], [ITEM_TRANSFER], [RELATIONSHIP_CHANGE]) are reserved: the
 * server accepts and stores them today, validated only for the payload shape every event shares
 * (dual-home, anchor), never for type-specific semantics.
 */
@Serializable
enum class WorldEventType {
    /** A free-text note with no further typed meaning. */
    NOTE,

    /** [WorldEventSyncPayload.subjectEntityId] enters the scene at this event's anchor. */
    ENTERS_SCENE,

    /** [WorldEventSyncPayload.subjectEntityId] exits the scene at this event's anchor. */
    EXITS_SCENE,

    /** [WorldEventSyncPayload.subjectEntityId] moves to [WorldEventSyncPayload.objectEntityId] (a location). */
    MOVES_TO,

    /** [WorldEventSyncPayload.subjectEntityId] departs from its current location. */
    DEPARTS,

    /** Reserved: [WorldEventSyncPayload.subjectEntityId] takes on a new alias. Not validated beyond shape. */
    ALIAS,

    /** Reserved: [WorldEventSyncPayload.subjectEntityId] is born. Not validated beyond shape. */
    BORN,

    /** Reserved: [WorldEventSyncPayload.subjectEntityId] dies. Not validated beyond shape. */
    DIES,

    /** Reserved: an item transfers from [WorldEventSyncPayload.subjectEntityId] to [WorldEventSyncPayload.objectEntityId]. Not validated beyond shape. */
    ITEM_TRANSFER,

    /** Reserved: the relationship between [WorldEventSyncPayload.subjectEntityId] and [WorldEventSyncPayload.objectEntityId] changes. Not validated beyond shape. */
    RELATIONSHIP_CHANGE,
}

/** How a world event came to exist. */
@Serializable
enum class WorldEventSource {
    /** Hand-written by a caller through the composer. */
    MANUAL,

    /** Reserved: produced by a future world-track import pipeline, not by a caller. */
    IMPORTED,
}
