package com.calypsan.listenup.api.dto

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.core.CollectionId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    /** Whether this is a server-managed system collection (All Books or Inbox) — read-only in the UI. */
    @SerialName("isSystem") val isSystem: Boolean = false,
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
