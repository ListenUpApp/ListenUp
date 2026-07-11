package com.calypsan.listenup.api.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTO for a reading order synced between server and client.
 *
 * Reading orders are user-scoped: each is owned by a single user. `userId` is
 * stamped server-side at write time and is NOT transmitted on the wire — the
 * sync substrate scopes pull/firehose queries to the authenticated user's rows.
 *
 * Carries the canonical sync-discipline fields: [revision], [updatedAt],
 * [createdAt], [deletedAt]. [attribution] is the sole net-new field vs the
 * near-identical [ShelfSyncPayload] — free text naming who recommends the order
 * or why it exists.
 */
@Serializable
@SerialName("ReadingOrderSyncPayload")
data class ReadingOrderSyncPayload(
    /** Stable identifier for this reading order (UUIDv7). */
    @SerialName("id") override val id: String,
    /** Display name of the reading order. */
    @SerialName("name") val name: String,
    /** Optional description of the reading order's theme or purpose. */
    @SerialName("description") val description: String = "",
    /** Free text — who recommends this order / why. */
    @SerialName("attribution") val attribution: String = "",
    /** Whether this reading order is private (visible only to the owner). */
    @SerialName("isPrivate") val isPrivate: Boolean = false,
    /** Sync revision counter — bumped on every write. */
    @SerialName("revision") override val revision: Long,
    /** Epoch millis of the last server-side write. */
    @SerialName("updatedAt") val updatedAt: Long,
    /** Epoch millis when this reading order was first created. */
    @SerialName("createdAt") val createdAt: Long,
    @SerialName("deletedAt") override val deletedAt: Long? = null,
) : SyncPayload

/**
 * Wire DTO for a reading-order–book junction synced between server and client.
 *
 * One row per `(readingOrderId, bookId)` pair. [sortOrder] determines display
 * ordering within the reading order. Soft-deletes are tombstoned via [deletedAt];
 * a non-null [deletedAt] indicates the book was removed from the reading order.
 * [id] is a synthetic stable identifier (`"$readingOrderId:$bookId"`) used as the
 * sync-cursor identity.
 *
 * `userId` is NOT on the wire — it is scoped server-side via `UserScopedSyncableTable`.
 */
@Serializable
@SerialName("ReadingOrderBookSyncPayload")
data class ReadingOrderBookSyncPayload(
    /** Synthetic stable id for sync-cursor identity (`"$readingOrderId:$bookId"`). */
    @SerialName("id") override val id: String,
    /** The reading order this book belongs to. */
    @SerialName("readingOrderId") val readingOrderId: String,
    /** The book added to the reading order. */
    @SerialName("bookId") val bookId: String,
    /** Display ordering within the reading order — lower values appear first. */
    @SerialName("sortOrder") val sortOrder: Int,
    /** Sync revision counter — bumped on every write. */
    @SerialName("revision") override val revision: Long,
    /** Epoch millis of the last server-side write. */
    @SerialName("updatedAt") val updatedAt: Long,
    /** Epoch millis when this junction row was first created. */
    @SerialName("createdAt") val createdAt: Long,
    @SerialName("deletedAt") override val deletedAt: Long? = null,
) : SyncPayload
