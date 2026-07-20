package com.calypsan.listenup.api.sync

import com.calypsan.listenup.api.dto.SharePermission
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTO for a collection synced between server and client.
 *
 * Collections are user-scoped: [ownerId] identifies the creating user and [libraryId]
 * scopes the collection to one library. The inbox ([isInbox] = `true`) is a special
 * system collection created automatically per user and is not deletable.
 *
 * Carries the canonical sync-discipline fields: [revision], [updatedAt], [deletedAt].
 */
@Serializable
@SerialName("CollectionSyncPayload")
data class CollectionSyncPayload(
    /** Stable identifier for this collection (UUIDv7). */
    @SerialName("id") override val id: String,
    /** The library this collection belongs to. */
    @SerialName("libraryId") val libraryId: String,
    /** User who owns (created) this collection. */
    @SerialName("ownerId") val ownerId: String,
    /** Display name of the collection. */
    @SerialName("name") val name: String,
    /** Whether this is the user's auto-created inbox collection. Inbox collections are not deletable. */
    @SerialName("isInbox") val isInbox: Boolean = false,
    /** Whether this is a server-managed system collection (ALL_BOOKS or INBOX). Read-only in the UI. */
    @SerialName("isSystem") val isSystem: Boolean = false,
    /** Sync revision counter — bumped on every write. */
    @SerialName("revision") override val revision: Long,
    /** Epoch millis of the last server-side write. */
    @SerialName("updatedAt") val updatedAt: Long,
    @SerialName("deletedAt") override val deletedAt: Long? = null,
) : SyncPayload

/**
 * Wire DTO for a book–collection junction synced between server and client.
 *
 * One row per `(collectionId, bookId)` pair. Soft-deletes are tombstoned via
 * [deletedAt]; a non-null [deletedAt] indicates the book was removed from the
 * collection. [createdAt] records when the junction row was first created.
 * There is no [updatedAt] — the row is either live or soft-deleted; partial
 * updates do not apply.
 *
 * [id] is an opaque per-row identity minted at creation (server-side by default;
 * client-side for offline-first creates). It deliberately encodes nothing about
 * [collectionId] or [bookId] — an ungated tombstone that shipped a composite id
 * would leak the association to a user who never had access to the row
 * (SERVER-SYNC-04).
 */
@Serializable
@SerialName("CollectionBookSyncPayload")
data class CollectionBookSyncPayload(
    /** Opaque per-row sync identity — encodes neither [collectionId] nor [bookId]. */
    @SerialName("id") override val id: String,
    /** The collection this book belongs to. */
    @SerialName("collectionId") val collectionId: String,
    /** The book added to the collection. */
    @SerialName("bookId") val bookId: String,
    /** Epoch millis when this junction row was first created. */
    @SerialName("createdAt") val createdAt: Long,
    /** Sync revision counter — bumped on every write (create or soft-delete). */
    @SerialName("revision") override val revision: Long,
    @SerialName("deletedAt") override val deletedAt: Long? = null,
) : SyncPayload

/**
 * Wire DTO for a collection share record synced between server and client.
 *
 * Represents a grant of [permission] on [collectionId] from [sharedByUserId] to
 * [sharedWithUserId]. Soft-deletes via [deletedAt] represent revoked shares.
 */
@Serializable
@SerialName("CollectionShareSyncPayload")
data class CollectionShareSyncPayload(
    /** Stable identifier for this share record (UUIDv7). */
    @SerialName("id") override val id: String,
    /** The collection being shared. */
    @SerialName("collectionId") val collectionId: String,
    /** The user receiving access. */
    @SerialName("sharedWithUserId") val sharedWithUserId: String,
    /** The user who granted access (typically the collection owner). */
    @SerialName("sharedByUserId") val sharedByUserId: String,
    /** The level of access granted. */
    @SerialName("permission") val permission: SharePermission,
    /** Sync revision counter — bumped on every write. */
    @SerialName("revision") override val revision: Long,
    /** Epoch millis of the last server-side write. */
    @SerialName("updatedAt") val updatedAt: Long,
    @SerialName("deletedAt") override val deletedAt: Long? = null,
) : SyncPayload
