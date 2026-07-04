package com.calypsan.listenup.api.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTO for a tag synced between server and client.
 *
 * Tags are global (cross-user, single server). [slug] is the canonical URL-safe
 * identity — computed from [name] on first write and immutable thereafter, so
 * renames change [name] but never [slug]. [id] is the stable storage row identity
 * (UUIDv7).
 *
 * Carries the canonical sync-discipline fields: [revision], [updatedAt], [deletedAt].
 * [clientOpId] lives on the wrapping [SyncEvent], not here.
 */
@Serializable
@SerialName("Tag")
data class Tag(
    @SerialName("id") override val id: String,
    @SerialName("name") val name: String,
    /**
     * URL-safe slug derived from [name] at creation time (e.g. `"sci-fi"` for `"Sci-Fi"`).
     * Immutable — renames update [name] only; slug is stable URL identity.
     */
    @SerialName("slug") val slug: String,
    @SerialName("revision") override val revision: Long,
    @SerialName("updatedAt") val updatedAt: Long,
    @SerialName("deletedAt") override val deletedAt: Long? = null,
) : SyncPayload
