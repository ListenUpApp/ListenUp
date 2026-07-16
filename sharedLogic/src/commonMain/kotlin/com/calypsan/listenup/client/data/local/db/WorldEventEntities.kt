package com.calypsan.listenup.client.data.local.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.calypsan.listenup.api.sync.WorldEventSource
import com.calypsan.listenup.api.sync.WorldEventType

/**
 * Room entity for a single row of the Story World unified event log — library-shared, curated
 * world data (the same access model as [EntityEntity]), dual-homed under exactly one of a series
 * or a standalone book, and optionally anchored to a specific book position.
 *
 * The row mirrors the wire [com.calypsan.listenup.api.sync.WorldEventSyncPayload] one-for-one,
 * with one deliberate exception: [com.calypsan.listenup.api.sync.WorldEventSyncPayload.mentionIds]
 * is NOT a column here — it lives in the separate [WorldEventMentionEntity] junction table,
 * mirroring the server's own `world_events` / `world_event_mentions` split (see
 * [com.calypsan.listenup.server.sync.WorldEventRepository]).
 *
 * Carries the canonical sync substrate ([revision], [deletedAt], [updatedAt], [createdAt])
 * consumed by the world-events sync domain for catch-up and SSE event application.
 */
@Entity(
    tableName = "world_events",
    indices = [
        Index(value = ["bookId"]),
        Index(value = ["homeSeriesId"]),
        Index(value = ["homeBookId"]),
        Index(value = ["deletedAt"]),
    ],
)
internal data class WorldEventEntity(
    @PrimaryKey val id: String,
    /**
     * The series this event is scoped under — a namespacing key only. Exactly one of
     * [homeSeriesId] / [homeBookId] is non-null.
     */
    val homeSeriesId: String? = null,
    /**
     * The standalone book this event is scoped under — a namespacing key only. Exactly one of
     * [homeSeriesId] / [homeBookId] is non-null.
     */
    val homeBookId: String? = null,
    /** The book this event is anchored to, or null when the event carries no book anchor. */
    val bookId: String? = null,
    /** Millisecond offset within [bookId]'s total duration. Null exactly when [bookId] is null. */
    val positionMs: Long? = null,
    /** The event's typed vocabulary slot. */
    val type: WorldEventType,
    /** Free text describing the event; may carry inline `@entity` mention tokens. */
    val text: String,
    /** The entity this event is principally about, if any (e.g. the character who moves). */
    val subjectEntityId: String? = null,
    /** A second entity this event relates [subjectEntityId] to, if any (e.g. an item transferred). */
    val objectEntityId: String? = null,
    /** Whether this event was hand-written by a caller or produced by a future import pipeline. */
    val source: WorldEventSource,
    /** Reserved: the world-track this event was imported from. Always null until track import ships. */
    val trackId: String? = null,
    /** Reserved: the world-track version this event was imported at. Always null until track import ships. */
    val trackVersion: Long? = null,
    /** Monotonic server revision, advanced on every committed change. */
    val revision: Long = 0,
    /** Epoch ms tombstone; null when the event is live. */
    val deletedAt: Long? = null,
    /** Epoch millis when this event was first created. */
    val createdAt: Long,
    /** Last server update timestamp in epoch milliseconds. */
    val updatedAt: Long,
)

/**
 * Junction row linking a [WorldEventEntity] to an entity it mentions — the client mirror of the
 * server's `world_event_mentions` table.
 *
 * Never client-authored as an independent write: the mentioning entity-id set is always the
 * recomputed union `MentionTokens.extractMentionIds(text) ∪ {subjectEntityId, objectEntityId}`
 * (see [com.calypsan.listenup.api.core.MentionTokens] and
 * [com.calypsan.listenup.client.data.sync.domains.worldEventMentionIds]), replaced wholesale
 * (delete-then-insert) alongside its parent event's own row write — see [WorldEventDao.replaceMentions].
 * Carries no sync-discipline columns of its own; it rides the parent [WorldEventEntity] row's
 * [WorldEventEntity.revision] / [WorldEventEntity.deletedAt] entirely.
 */
@Entity(
    tableName = "world_event_mentions",
    primaryKeys = ["eventId", "entityId"],
    indices = [
        Index(value = ["entityId"]),
    ],
)
internal data class WorldEventMentionEntity(
    val eventId: String,
    val entityId: String,
)

/**
 * Read projection composing a [WorldEventEntity] with its mentioned-entity-id set from the
 * [WorldEventMentionEntity] junction, in one query — the [ContributorWithAliases] precedent
 * (a single-column `@Relation` projection avoids an N+1 lookup per event).
 */
internal data class WorldEventWithMentions(
    @Embedded val event: WorldEventEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "eventId",
        entity = WorldEventMentionEntity::class,
        projection = ["entityId"],
    )
    val mentionIds: List<String>,
)
