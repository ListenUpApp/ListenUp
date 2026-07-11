package com.calypsan.listenup.client.data.local.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for a reading order (Reading Orders — Room v3).
 *
 * Reading orders are user-owned, named, ordered, attributed lists of books —
 * the near-exact sibling of [ShelfEntity], plus [attribution]. The local mirror
 * holds only the authenticated user's own reading orders — the sync substrate
 * scopes pull/firehose queries to the caller's rows, so no `userId` column is
 * stored. The row mirrors the wire
 * [com.calypsan.listenup.api.sync.ReadingOrderSyncPayload]; `bookCount` is **not**
 * stored — it is JOIN-derived from live [ReadingOrderBookEntity] rows.
 *
 * Carries the canonical sync substrate ([revision], [deletedAt], [updatedAt],
 * [createdAt]) consumed by the reading-order sync domain for catch-up and SSE
 * event application.
 */
@Entity(
    tableName = "reading_orders",
    indices = [
        Index(value = ["deletedAt"]),
    ],
)
internal data class ReadingOrderEntity(
    @PrimaryKey val id: String,
    /** Display name of the reading order. */
    val name: String,
    /** Optional description of the reading order's theme or purpose. */
    val description: String,
    /** Free text — who recommends this order / why. */
    val attribution: String,
    /** Whether this reading order is visible only to the owner. */
    val isPrivate: Boolean,
    /** Monotonic server revision, advanced on every committed change. */
    val revision: Long = 0,
    /** Epoch ms tombstone; null when the reading order is live. */
    val deletedAt: Long? = null,
    /** Last server update timestamp in epoch milliseconds. */
    val updatedAt: Long,
    /** Epoch millis when this reading order was first created. */
    val createdAt: Long,
)

/**
 * Junction row linking a book to a reading order (Reading Orders — Room v3).
 *
 * One row per `(readingOrderId, bookId)` pair, identified by the synthetic stable
 * id `"$readingOrderId:$bookId"` the server uses on the wire. [sortOrder]
 * determines display ordering within the reading order. Soft-deletes are
 * tombstoned via [deletedAt]; observation queries exclude tombstoned rows so
 * removals reflect reactively. Mirrors the wire
 * [com.calypsan.listenup.api.sync.ReadingOrderBookSyncPayload].
 */
@Entity(
    tableName = "reading_order_books",
    indices = [
        Index(value = ["readingOrderId"]),
        Index(value = ["bookId"]),
        Index(value = ["deletedAt"]),
    ],
)
internal data class ReadingOrderBookEntity(
    @PrimaryKey val id: String,
    /** The reading order this book belongs to. */
    val readingOrderId: String,
    /** The book added to the reading order. */
    val bookId: String,
    /** Display ordering within the reading order — lower values appear first. */
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
 * Per-user, per-series follow-state row (Reading Orders — Room v3): which reading
 * order (if any) is the active spoiler clock for a series — Integration
 * Foundations §5.4. Mirrors the wire
 * [com.calypsan.listenup.api.sync.ReadingOrderFollowSyncPayload].
 *
 * [id] is the deterministic synthetic key `"$userId:$seriesId"` shared with the
 * server; [seriesId] is the local lookup key (the mirror holds only the caller's
 * own rows, so it is unique per row in practice).
 */
@Entity(
    tableName = "reading_order_follows",
    indices = [
        Index(value = ["seriesId"]),
        Index(value = ["deletedAt"]),
    ],
)
internal data class ReadingOrderFollowEntity(
    @PrimaryKey val id: String,
    /** The series this follow-state applies to. */
    val seriesId: String,
    /** The active reading order for the series, or null for the per-book frontier floor. */
    val activeReadingOrderId: String? = null,
    /** Monotonic server revision, advanced on every committed change. */
    val revision: Long = 0,
    /** Epoch ms tombstone; null when the follow row is live. */
    val deletedAt: Long? = null,
    /** Last server update timestamp in epoch milliseconds. */
    val updatedAt: Long,
    /** Epoch millis when this follow row was first created. */
    val createdAt: Long,
)

/**
 * Projection returned by [ReadingOrderDao.observeMyReadingOrdersWithBookCount]:
 * the reading-order row plus its current live-junction book count. Embedding
 * keeps the SQL column names aligned with [ReadingOrderEntity] without a manual
 * mapping (the [ShelfWithBookCount] precedent).
 */
internal data class ReadingOrderWithBookCount(
    @Embedded val readingOrder: ReadingOrderEntity,
    val bookCount: Int,
)
