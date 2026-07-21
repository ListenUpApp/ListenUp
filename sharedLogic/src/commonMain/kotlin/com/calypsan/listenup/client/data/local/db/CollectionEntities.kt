package com.calypsan.listenup.client.data.local.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for a collection (Collections — Room v34).
 *
 * Collections are admin-owned, library-scoped groupings of books with an explicit
 * per-user ACL (see [CollectionShareEntity]). The row mirrors the wire
 * [com.calypsan.listenup.api.sync.CollectionSyncPayload]; `bookCount` is **not**
 * stored — it is JOIN-derived from live [CollectionBookEntity] rows (the
 * `GenreEntity` precedent, so drift is impossible by construction).
 *
 * Carries the canonical sync substrate ([revision], [deletedAt], [updatedAt])
 * consumed by the collection sync handler for catch-up and firehose event application.
 */
@Entity(
    tableName = "collections",
    indices = [
        Index(value = ["libraryId"]),
        Index(value = ["deletedAt"]),
    ],
)
internal data class CollectionEntity(
    @PrimaryKey val id: String,
    /** The library this collection belongs to. */
    val libraryId: String,
    /** User who owns (created) this collection. */
    val ownerId: String,
    /** Display name of the collection. */
    val name: String,
    /** Whether this is the user's auto-created inbox collection (not deletable). */
    val isInbox: Boolean,
    /** Whether this is a server-managed system collection (All Books or Inbox) — not renameable/deletable. */
    @ColumnInfo(defaultValue = "0") val isSystem: Boolean = false,
    /** Monotonic server revision, advanced on every committed change. */
    val revision: Long = 0,
    /** Epoch ms tombstone; null when the collection is live. */
    val deletedAt: Long? = null,
    /** Last server update timestamp in epoch milliseconds. */
    val updatedAt: Long,
)

/**
 * Junction row linking a book to a collection (Collections — Room v24; `syncId` added Room v2,
 * SERVER-SYNC-04).
 *
 * One row per `(collectionId, bookId)` pair. Soft-deletes are tombstoned via
 * [deletedAt]; observation queries exclude tombstoned rows so removals reflect
 * reactively. Mirrors the wire
 * [com.calypsan.listenup.api.sync.CollectionBookSyncPayload].
 */
@Entity(
    tableName = "collection_books",
    primaryKeys = ["collectionId", "bookId"],
    indices = [
        Index(value = ["bookId"]),
        Index(value = ["deletedAt"]),
        Index(value = ["syncId"], unique = true),
    ],
)
internal data class CollectionBookEntity(
    val collectionId: String,
    val bookId: String,
    /**
     * Opaque wire sync identity (SERVER-SYNC-04) — matched against `SyncEvent.Deleted.id`;
     * never parsed. Client-minted for an offline-first create, server-echoed otherwise.
     */
    val syncId: String,
    /** Epoch millis when this junction row was first created. */
    val createdAt: Long,
    /** Monotonic server revision, bumped on create or soft-delete. */
    val revision: Long = 0,
    /** Epoch ms tombstone; null when the junction row is live. */
    val deletedAt: Long? = null,
)

/**
 * Room entity for a collection share grant (Collections — Room v24).
 *
 * Represents one `(collection, user, permission)` triple so a collection owner can
 * inspect and revoke shares. Soft-deletes via [deletedAt] represent revoked shares.
 * Mirrors the wire [com.calypsan.listenup.api.sync.CollectionShareSyncPayload];
 * [permission] stores the wire enum string (`"read"` | `"write"`).
 */
@Entity(
    tableName = "collection_shares",
    indices = [
        Index(value = ["collectionId"]),
        Index(value = ["deletedAt"]),
    ],
)
internal data class CollectionShareEntity(
    @PrimaryKey val id: String,
    /** The collection that was shared. */
    val collectionId: String,
    /** The user who received access. */
    val sharedWithUserId: String,
    /** The user who granted access (typically the collection owner). */
    val sharedByUserId: String,
    /** Granted permission level as the wire enum string: `"read"` or `"write"`. */
    val permission: String,
    /** Monotonic server revision, advanced on every committed change. */
    val revision: Long = 0,
    /** Epoch ms tombstone; null when the share is live. */
    val deletedAt: Long? = null,
    /** Last server update timestamp in epoch milliseconds. */
    val updatedAt: Long,
)

/**
 * Projection returned by [CollectionDao.observeAllWithBookCount]: the collection
 * row plus its current live-junction book count. Embedding keeps the SQL column
 * names aligned with [CollectionEntity] without a manual mapping (the
 * [GenreWithBookCount] precedent).
 */
internal data class CollectionWithBookCount(
    @Embedded val collection: CollectionEntity,
    val bookCount: Int,
)
