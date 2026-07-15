package com.calypsan.listenup.client.data.local.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.calypsan.listenup.api.sync.EntityKind

/**
 * Room entity for a Story World entity — character, location, or item (Story World Stage 2).
 *
 * Entities are library-shared, curated world data namespaced under either a series or a
 * standalone book — unlike [ReadingOrderEntity], there is no per-user ownership: the local
 * mirror holds every entity the caller can see (Books-B1-style curation, the same access model
 * as [SeriesEntity]). The row mirrors the wire [com.calypsan.listenup.api.sync.EntitySyncPayload]
 * one-for-one: entities are dual-homed, exactly one of [homeSeriesId] / [homeBookId] is non-null.
 *
 * Carries the canonical sync substrate ([revision], [deletedAt], [updatedAt], [createdAt])
 * consumed by the entity sync domain for catch-up and SSE event application.
 */
@Entity(
    tableName = "entities",
    indices = [
        Index(value = ["homeSeriesId"]),
        Index(value = ["homeBookId"]),
        Index(value = ["deletedAt"]),
    ],
)
internal data class EntityEntity(
    @PrimaryKey val id: String,
    /** The entity's taxonomy — character, location, or item. */
    val kind: EntityKind,
    /** The entity's first-introduced (pre-reveal) name. */
    val name: String,
    /**
     * The series this entity is namespaced under — a namespacing key only. Exactly one of
     * [homeSeriesId] / [homeBookId] is non-null.
     */
    val homeSeriesId: String? = null,
    /**
     * The standalone book this entity is namespaced under — a namespacing key only. Exactly one
     * of [homeSeriesId] / [homeBookId] is non-null.
     */
    val homeBookId: String? = null,
    /** Reserved reference to an entity portrait/image; null until image support ships. */
    val imageRef: String? = null,
    /** Monotonic server revision, advanced on every committed change. */
    val revision: Long = 0,
    /** Epoch ms tombstone; null when the entity is live. */
    val deletedAt: Long? = null,
    /** Epoch millis when this entity was first created. */
    val createdAt: Long,
    /** Last server update timestamp in epoch milliseconds. */
    val updatedAt: Long,
)
