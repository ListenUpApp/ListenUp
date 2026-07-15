package com.calypsan.listenup.client.data.local.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.calypsan.listenup.api.sync.BioEntryMode
import com.calypsan.listenup.api.sync.EntityKind

/**
 * Room entity for a Story World entity — character, location, or item (Story World Stage 2).
 *
 * Entities are library-shared, curated world data namespaced under a series — unlike
 * [ReadingOrderEntity], there is no per-user ownership: the local mirror holds every entity the
 * caller can see (Books-B1-style curation, the same access model as [SeriesEntity]). The row
 * mirrors the wire [com.calypsan.listenup.api.sync.EntitySyncPayload]. The whole-aggregate
 * bio-entry child collection lives in the separate [BioEntryEntity] table, replaced wholesale
 * on every apply — mirrors how [ChapterEntity] rides along with [BookEntity].
 *
 * Carries the canonical sync substrate ([revision], [deletedAt], [updatedAt], [createdAt])
 * consumed by the entity sync domain for catch-up and SSE event application.
 */
@Entity(
    tableName = "entities",
    indices = [
        Index(value = ["homeSeriesId"]),
        Index(value = ["deletedAt"]),
    ],
)
internal data class EntityEntity(
    @PrimaryKey val id: String,
    /** The entity's taxonomy — character, location, or item. */
    val kind: EntityKind,
    /** The entity's first-introduced (pre-reveal) name. */
    val name: String,
    /** The series this entity is namespaced under — a namespacing key only. */
    val homeSeriesId: String,
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

/**
 * Room entity for one spoiler-anchored biography entry on an [EntityEntity] (Story World Stage 2).
 *
 * Bio entries do not sync independently — they ride along with their parent [EntityEntity] and
 * are replaced wholesale (delete-for-entity + insert-all) on every apply, mirroring how
 * [ChapterEntity] rides along with [BookEntity]. No revision/tombstone columns of their own;
 * they inherit the parent entity's revision lifecycle. Mirrors the wire
 * [com.calypsan.listenup.api.sync.BioEntryPayload].
 */
@Entity(
    tableName = "entity_bio_entries",
    indices = [
        Index(value = ["entityId"]),
    ],
)
internal data class BioEntryEntity(
    @PrimaryKey val id: String,
    /** The entity this bio entry belongs to. */
    val entityId: String,
    /** The spoiler anchor's book; null means this entry is always visible. */
    val bookId: String? = null,
    /** The spoiler anchor's position within [bookId], in milliseconds. */
    val positionMs: Long? = null,
    /** How this entry combines with earlier-anchored entries when folded. */
    val mode: BioEntryMode,
    /** The entry's body text. */
    val text: String,
    /** Ordering among entries that share the same anchor — lower values fold first. */
    val sortKey: Int,
)
