package com.calypsan.listenup.api.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTO for a mood synced between server and client.
 *
 * Moods are global (cross-user, single server) — the affective axis of a book
 * ("Feel-Good", "Tense", "Scary"), independent of genre and tag. [slug] is the
 * canonical URL-safe identity — computed from [name] on first write and immutable
 * thereafter, so renames change [name] but never [slug]. [id] is the stable storage
 * row identity (UUIDv7).
 *
 * Carries the canonical sync-discipline fields: [revision], [updatedAt], [deletedAt].
 * [clientOpId] lives on the wrapping [SyncEvent], not here.
 */
@Serializable
@SerialName("Mood")
data class Mood(
    @SerialName("id") override val id: String,
    @SerialName("name") val name: String,
    /**
     * URL-safe slug derived from [name] at creation time (e.g. `"feel-good"` for `"Feel-Good"`).
     * Immutable — renames update [name] only; slug is stable URL identity.
     */
    @SerialName("slug") val slug: String,
    @SerialName("revision") override val revision: Long,
    @SerialName("updatedAt") val updatedAt: Long,
    @SerialName("deletedAt") override val deletedAt: Long? = null,
) : SyncPayload
