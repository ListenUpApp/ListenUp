package com.calypsan.listenup.api.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTO for a Story World entity (character, location, or item) synced between
 * server and client.
 *
 * Entities are library-shared, not user-scoped: they are curated world data behind
 * the metadata-edit gate, visible to every caller who can see the owning series —
 * there is no per-user ownership or privacy flag the way [ReadingOrderSyncPayload]
 * has one. Unlike [BookSyncPayload]'s children, [bioEntries] is not yet consulted
 * for spoiler gating by anything upstream of Stage 3 — this payload only carries the
 * shape; the fold that walks a reader's position against [BioEntryPayload] anchors
 * ships in Stage 3.
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
     * twist later renames or unmasks the entity; the reveal lives in a
     * [BioEntryPayload] anchored past the point it happens.
     */
    @SerialName("name") val name: String,
    /**
     * The series this entity is scoped under — a namespacing key only. It does
     * NOT mean the entity appears in every book of the series, or in any book at
     * all yet. Which books/events an entity actually appears in is modeled by
     * Stage 3's event-sourced book-entity links, not by this field.
     */
    @SerialName("homeSeriesId") val homeSeriesId: String,
    /**
     * Reserved reference to an entity portrait/image. No upload path exists yet —
     * always null until image support ships in a later stage.
     */
    @SerialName("imageRef") val imageRef: String? = null,
    /**
     * The entity's spoiler-anchored biography entries, carried inline as a
     * whole-aggregate snapshot — clients don't issue a follow-up fetch per entry
     * (mirrors [BookSyncPayload]'s child-collection pattern).
     */
    @SerialName("bioEntries") val bioEntries: List<BioEntryPayload> = emptyList(),
    /** Sync revision counter — bumped on every write. */
    @SerialName("revision") override val revision: Long,
    /** Epoch millis of the last server-side write. */
    @SerialName("updatedAt") val updatedAt: Long,
    /** Epoch millis when this entity was first created. */
    @SerialName("createdAt") val createdAt: Long,
    @SerialName("deletedAt") override val deletedAt: Long? = null,
) : SyncPayload

/**
 * One spoiler-anchored biography entry on an [EntitySyncPayload].
 *
 * The anchor ([bookId], [positionMs]) is nullable and present from day one: Stage 3
 * is the first consumer that folds entries against a reader's position, and no
 * migration or backfill is planned when it lands — anchors are simply consulted
 * starting then.
 */
@Serializable
@SerialName("BioEntryPayload")
data class BioEntryPayload(
    /** Stable identifier for this bio entry. */
    @SerialName("id") val id: String,
    /**
     * The spoiler anchor's book — null means this entry is always visible (the
     * pre-frontier baseline every reader sees regardless of progress).
     */
    @SerialName("bookId") val bookId: String? = null,
    /**
     * The spoiler anchor's position within [bookId], in milliseconds. Null with a
     * non-null [bookId] means the entry becomes visible as soon as that book is
     * started or known, with no finer-grained position gate.
     */
    @SerialName("positionMs") val positionMs: Long? = null,
    /**
     * How this entry combines with earlier-anchored entries when Stage 3 folds the
     * bio for a reader's position — [BioEntryMode.APPEND] concatenates onto the
     * running bio, [BioEntryMode.REPLACE] resets it at this anchor.
     */
    @SerialName("mode") val mode: BioEntryMode,
    /** The entry's body text. */
    @SerialName("text") val text: String,
    /** Ordering among entries that share the same anchor — lower values fold first. */
    @SerialName("sortKey") val sortKey: Int,
)

/** The taxonomy of a Story World entity. */
@Serializable
enum class EntityKind {
    CHARACTER,
    LOCATION,
    ITEM,
}

/**
 * How a [BioEntryPayload] combines with earlier-anchored entries for the same
 * entity when Stage 3 folds a reader-position-scoped bio.
 *
 * [APPEND] concatenates this entry's [BioEntryPayload.text] onto the bio already
 * accumulated from earlier anchors. [REPLACE] discards everything accumulated so
 * far and resets the bio to this entry's text — for a reveal that supersedes
 * (rather than adds to) what came before.
 */
@Serializable
enum class BioEntryMode {
    APPEND,
    REPLACE,
}
