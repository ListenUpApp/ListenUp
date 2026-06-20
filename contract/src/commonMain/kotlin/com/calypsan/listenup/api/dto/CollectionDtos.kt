package com.calypsan.listenup.api.dto

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.core.CollectionId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Maximum length, in characters, of a collection display name. */
private const val MAX_COLLECTION_NAME_LENGTH = 200

/**
 * Permission level granted to a user for a shared collection.
 *
 * [Read] allows viewing the collection and its books. [Write] additionally allows
 * adding and removing books. Owners always have implicit write access regardless of
 * this enum — the enum is only meaningful for share recipients.
 */
@Serializable
@SerialName("SharePermission")
enum class SharePermission {
    /** Allows viewing the collection and its books. */
    @SerialName("read")
    Read,

    /** Allows viewing and modifying (add/remove books) the collection. */
    @SerialName("write")
    Write,
    ;

    /** Returns `true` for all permission levels (every recipient can read). */
    fun canRead(): Boolean = true

    /** Returns `true` only for [Write] permission. */
    fun canWrite(): Boolean = this == Write
}

/**
 * Lightweight read model for a collection returned in list and search responses.
 *
 * [bookCount] is computed at query time via `LEFT JOIN COUNT(*)` — no denormalization.
 * [callerPermission] reflects the effective permission of the authenticated caller:
 * owners receive [SharePermission.Write] implicitly; share recipients receive whatever
 * was granted. [isOwner] lets the UI show owner-only actions (rename, delete, share)
 * without a separate API call.
 */
@Serializable
@SerialName("CollectionSummary")
data class CollectionSummary(
    /** Stable identifier for this collection. */
    @SerialName("id") val id: CollectionId,
    /** Display name of the collection. */
    @SerialName("name") val name: String,
    /** User who owns (created) this collection. */
    @SerialName("ownerId") val ownerId: UserId,
    /** Whether this is the user's auto-created inbox collection. */
    @SerialName("isInbox") val isInbox: Boolean,
    /** Number of live books currently in this collection (no tombstones). */
    @SerialName("bookCount") val bookCount: Long,
    /** Effective permission of the authenticated caller on this collection. */
    @SerialName("callerPermission") val callerPermission: SharePermission,
    /** Whether the authenticated caller is the owner of this collection. */
    @SerialName("isOwner") val isOwner: Boolean,
)

/**
 * Read model for a single share grant on a collection.
 *
 * Surfaces the details of one `(collection, user, permission)` triple so the
 * collection owner can inspect and revoke individual shares.
 */
@Serializable
@SerialName("CollectionShareDto")
data class CollectionShareDto(
    /** Stable identifier for this share record (UUIDv7). */
    @SerialName("id") val id: String,
    /** The collection that was shared. */
    @SerialName("collectionId") val collectionId: CollectionId,
    /** The user who received access. */
    @SerialName("sharedWithUserId") val sharedWithUserId: UserId,
    /** The permission level granted to the recipient. */
    @SerialName("permission") val permission: SharePermission,
)

/**
 * Request body for creating a new collection.
 *
 * [name] must be between 1 and 200 characters. [libraryId] scopes the collection
 * to a specific library — each collection belongs to exactly one library.
 */
@Serializable
@SerialName("CreateCollectionBody")
data class CreateCollectionBody(
    /** The library to create the collection in. */
    @SerialName("libraryId") val libraryId: String,
    /** Display name for the new collection (1..200 characters). */
    @SerialName("name") val name: String,
) {
    init {
        require(name.isNotBlank() && name.length <= MAX_COLLECTION_NAME_LENGTH) { "name must be 1..200 chars" }
    }
}

/**
 * Request body for sharing a collection with another user.
 *
 * [sharedWithUserId] must be a different user than the caller — sharing with yourself
 * is rejected with `CollectionError.SelfShare`. [permission] defaults to [SharePermission.Read].
 */
@Serializable
@SerialName("ShareCollectionBody")
data class ShareCollectionBody(
    /** The user to share the collection with. Must not be the caller. */
    @SerialName("sharedWithUserId") val sharedWithUserId: String,
    /** The permission level to grant. Defaults to [SharePermission.Read]. */
    @SerialName("permission") val permission: SharePermission = SharePermission.Read,
)
