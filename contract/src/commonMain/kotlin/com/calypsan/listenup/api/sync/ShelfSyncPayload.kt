package com.calypsan.listenup.api.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTO for a shelf synced between server and client.
 *
 * Shelves are user-scoped: each shelf is owned by a single user. `userId` is
 * stamped server-side at write time and is NOT transmitted on the wire — the
 * sync substrate scopes pull/firehose queries to the authenticated user's rows.
 *
 * Carries the canonical sync-discipline fields: [revision], [updatedAt],
 * [createdAt], [deletedAt].
 */
@Serializable
@SerialName("ShelfSyncPayload")
data class ShelfSyncPayload(
    /** Stable identifier for this shelf (UUIDv7). */
    @SerialName("id") override val id: String,
    /** Display name of the shelf. */
    @SerialName("name") val name: String,
    /** Optional description for the shelf. */
    @SerialName("description") val description: String = "",
    /** Whether this shelf is private (visible only to the owner). */
    @SerialName("isPrivate") val isPrivate: Boolean = false,
    /** Sync revision counter — bumped on every write. */
    @SerialName("revision") override val revision: Long,
    /** Epoch millis of the last server-side write. */
    @SerialName("updatedAt") val updatedAt: Long,
    /** Epoch millis when this shelf was first created. */
    @SerialName("createdAt") val createdAt: Long,
    @SerialName("deletedAt") override val deletedAt: Long? = null,
) : SyncPayload

/**
 * Wire DTO for a shelf–book junction synced between server and client.
 *
 * One row per `(shelfId, bookId)` pair. [sortOrder] determines display ordering
 * within the shelf. Soft-deletes are tombstoned via [deletedAt]; a non-null
 * [deletedAt] indicates the book was removed from the shelf. [id] is a synthetic
 * stable identifier (`"$shelfId:$bookId"`) used as the sync-cursor identity.
 *
 * `userId` is NOT on the wire — it is scoped server-side via `UserScopedSyncableTable`.
 */
@Serializable
@SerialName("ShelfBookSyncPayload")
data class ShelfBookSyncPayload(
    /** Synthetic stable id for sync-cursor identity (`"$shelfId:$bookId"`). */
    @SerialName("id") override val id: String,
    /** The shelf this book belongs to. */
    @SerialName("shelfId") val shelfId: String,
    /** The book added to the shelf. */
    @SerialName("bookId") val bookId: String,
    /** Display ordering within the shelf — lower values appear first. */
    @SerialName("sortOrder") val sortOrder: Int,
    /** Sync revision counter — bumped on every write. */
    @SerialName("revision") override val revision: Long,
    /** Epoch millis of the last server-side write. */
    @SerialName("updatedAt") val updatedAt: Long,
    /** Epoch millis when this junction row was first created. */
    @SerialName("createdAt") val createdAt: Long,
    @SerialName("deletedAt") override val deletedAt: Long? = null,
) : SyncPayload
