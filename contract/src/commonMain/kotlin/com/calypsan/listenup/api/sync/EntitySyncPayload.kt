package com.calypsan.listenup.api.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTO for a Story World entity (character, location, or item) synced between
 * server and client.
 *
 * Entities are library-shared, not user-scoped: they are curated world data behind
 * the metadata-edit gate, visible to every caller who can see the owning series or
 * standalone book — there is no per-user ownership or privacy flag the way
 * [ReadingOrderSyncPayload] has one.
 *
 * Entities are dual-homed: every entity is scoped under exactly one of
 * [homeSeriesId] (a series) or [homeBookId] (a standalone book) — never both, never
 * neither. A character who crosses over between series or books is not modeled by
 * loosening this rule; crossover is curation (series membership, reading orders),
 * not schema.
 *
 * Carries the canonical sync-discipline fields: [revision], [updatedAt],
 * [createdAt], [deletedAt].
 */
@Serializable
@SerialName("EntitySyncPayload")
data class EntitySyncPayload(
    /** Stable, client-minted identifier for this entity (random UUID, stable across export/import). */
    @SerialName("id") override val id: String,
    /** The entity's taxonomy — character, location, or item. */
    @SerialName("kind") val kind: EntityKind,
    /**
     * The entity's first-introduced name — the name a reader encounters first, not
     * a later-revealed alias or true identity. Authoring convention: this field
     * itself must never leak a spoiler, so pick the pre-reveal name even when a
     * twist later renames or unmasks the entity.
     */
    @SerialName("name") val name: String,
    /**
     * The series this entity is scoped under — a namespacing key only. It does
     * NOT mean the entity appears in every book of the series, or in any book at
     * all yet. Which books/events an entity actually appears in is modeled by
     * Stage 3's event-sourced book-entity links, not by this field. Exactly one
     * of [homeSeriesId] / [homeBookId] is non-null.
     */
    @SerialName("homeSeriesId") val homeSeriesId: String? = null,
    /**
     * The standalone book this entity is scoped under — a namespacing key only,
     * for entities that belong to a book with no series. Exactly one of
     * [homeSeriesId] / [homeBookId] is non-null.
     */
    @SerialName("homeBookId") val homeBookId: String? = null,
    /**
     * Reserved reference to an entity portrait/image. No upload path exists yet —
     * always null until image support ships in a later stage.
     */
    @SerialName("imageRef") val imageRef: String? = null,
    /** Sync revision counter — bumped on every write. */
    @SerialName("revision") override val revision: Long,
    /** Epoch millis of the last server-side write. */
    @SerialName("updatedAt") val updatedAt: Long,
    /** Epoch millis when this entity was first created. */
    @SerialName("createdAt") val createdAt: Long,
    @SerialName("deletedAt") override val deletedAt: Long? = null,
) : SyncPayload

/** The taxonomy of a Story World entity. */
@Serializable
enum class EntityKind {
    CHARACTER,
    LOCATION,
    ITEM,
}
