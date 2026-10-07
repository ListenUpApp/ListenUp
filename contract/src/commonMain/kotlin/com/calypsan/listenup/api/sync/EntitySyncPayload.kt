package com.calypsan.listenup.api.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire row for a Story World entity: a character, location, item, group, people, event or concept.
 *
 * Library-shared, not user-owned: any member holding the Story World contribute permission may edit
 * it. Visibility follows the home: a book-homed entity is visible iff its book is; a series-homed one
 * iff at least one of the series' books is. Exactly one of [homeSeriesId] / [homeBookId] is set.
 *
 * Ids are plain strings on the wire, like every [SyncPayload]; the RPC surface types them.
 */
@Serializable
@SerialName("EntitySyncPayload")
data class EntitySyncPayload(
    /** Stable, client-minted id. */
    @SerialName("id") override val id: String,
    /** The entity's kind; [EntityKind.UNKNOWN] for a kind this build doesn't know. */
    @SerialName("kind") val kind: EntityKind,
    /**
     * The name a listener meets first. Later names are alias entries, never edits, so this field
     * itself never spoils a reveal. Blank on a tombstone.
     */
    @SerialName("name") val name: String,
    /** Free-text subtitle ("noble house", "magic system"), at most 60 characters. */
    @SerialName("descriptor") val descriptor: String? = null,
    /** The containing entity — LOCATION within LOCATION or GROUP within GROUP only. */
    @SerialName("parentId") val parentId: String? = null,
    /** The series this entity lives under; exclusive with [homeBookId]. */
    @SerialName("homeSeriesId") val homeSeriesId: String? = null,
    /** The book this entity lives under; exclusive with [homeSeriesId]. */
    @SerialName("homeBookId") val homeBookId: String? = null,
    /** Reserved for a portrait; always null until image support ships. */
    @SerialName("imageRef") val imageRef: String? = null,
    /** Who created it; null for a row written by the server itself. */
    @SerialName("createdBy") val createdBy: String? = null,
    /** Who last changed it; null for a server-written change (e.g. a book removal). */
    @SerialName("updatedBy") val updatedBy: String? = null,
    /** Sync revision. */
    @SerialName("revision") override val revision: Long,
    /** Epoch ms of the last write, stamped by the server's clock. */
    @SerialName("updatedAt") val updatedAt: Long,
    /** Epoch ms of creation. */
    @SerialName("createdAt") val createdAt: Long,
    /** Tombstone instant, else null. */
    @SerialName("deletedAt") override val deletedAt: Long? = null,
) : SyncPayload
