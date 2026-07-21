package com.calypsan.listenup.client.data.local.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for a shelf (Shelves — Room v26).
 *
 * Shelves are user-owned, ordered lists of books for personal curation and
 * social discovery. The local mirror holds only the authenticated user's own
 * shelves — the sync substrate scopes pull/firehose queries to the caller's
 * rows, so no `userId` column is stored (the [PlaybackPositionEntity] own-data
 * precedent). The row mirrors the wire
 * [com.calypsan.listenup.api.sync.ShelfSyncPayload]; `bookCount` is **not**
 * stored — it is JOIN-derived from live [ShelfBookEntity] rows (the
 * [CollectionEntity] precedent, so drift is impossible by construction).
 *
 * Carries the canonical sync substrate ([revision], [deletedAt], [updatedAt],
 * [createdAt]) consumed by the shelf sync handler for catch-up and firehose event
 * application.
 */
@Entity(
    tableName = "shelves",
    indices = [
        Index(value = ["deletedAt"]),
    ],
)
internal data class ShelfEntity(
    @PrimaryKey val id: String,
    /** Display name of the shelf. */
    val name: String,
    /** Optional description of the shelf's theme or purpose. */
    val description: String,
    /** Whether this shelf is visible only to the owner. */
    val isPrivate: Boolean,
    /** Monotonic server revision, advanced on every committed change. */
    val revision: Long = 0,
    /** Epoch ms tombstone; null when the shelf is live. */
    val deletedAt: Long? = null,
    /** Last server update timestamp in epoch milliseconds. */
    val updatedAt: Long,
    /** Epoch millis when this shelf was first created. */
    val createdAt: Long,
)

/**
 * Junction row linking a book to a shelf (Shelves — Room v26).
 *
 * One row per `(shelfId, bookId)` pair, identified by the synthetic stable id
 * `"$shelfId:$bookId"` the server uses on the wire. [sortOrder] determines
 * display ordering within the shelf. Soft-deletes are tombstoned via
 * [deletedAt]; observation queries exclude tombstoned rows so removals reflect
 * reactively. Mirrors the wire
 * [com.calypsan.listenup.api.sync.ShelfBookSyncPayload].
 */
@Entity(
    tableName = "shelf_books",
    indices = [
        Index(value = ["shelfId"]),
        Index(value = ["bookId"]),
        Index(value = ["deletedAt"]),
    ],
)
internal data class ShelfBookEntity(
    @PrimaryKey val id: String,
    /** The shelf this book belongs to. */
    val shelfId: String,
    /** The book added to the shelf. */
    val bookId: String,
    /** Display ordering within the shelf — lower values appear first. */
    val sortOrder: Int,
    /** Monotonic server revision, bumped on create, reorder, or soft-delete. */
    val revision: Long = 0,
    /** Epoch ms tombstone; null when the junction row is live. */
    val deletedAt: Long? = null,
    /** Last server update timestamp in epoch milliseconds. */
    val updatedAt: Long,
    /** Epoch millis when this junction row was first created. */
    val createdAt: Long,
)

/**
 * Projection returned by [ShelfDao.observeMyShelvesWithBookCount]: the shelf row
 * plus its current live-junction book count. Embedding keeps the SQL column names
 * aligned with [ShelfEntity] without a manual mapping (the
 * [CollectionWithBookCount] precedent).
 */
internal data class ShelfWithBookCount(
    @Embedded val shelf: ShelfEntity,
    val bookCount: Int,
)

/**
 * Projection returned by [ShelfDao.coverHashesByBookFor]: one row per live shelf book that
 * exists in the local `books` mirror, pairing the book id with its cover content hash.
 *
 * The shelf-detail RPC view carries no cover information, so the hash is resolved client-side
 * from Room. A non-null [coverHash] lets the UI version its image-cache key so a re-imaged
 * cover invalidates the stale cached bitmap.
 */
internal data class ShelfBookCoverHash(
    val bookId: String,
    val coverHash: String?,
)
