package com.calypsan.listenup.api.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Validation-domain DTO for the Sync Foundation phase. Tags has no public write API
 * in this phase — the repository is exercised by integration tests only. Future phases
 * (likely Books-C, when users tag books) add the user-facing API.
 *
 * Carries the canonical sync-discipline fields every domain DTO will surface from
 * Books-A onwards: `revision`, `updatedAt`, `deletedAt`. `clientOpId` is NOT on the
 * payload — it lives on the wrapping [SyncEvent] for echo matching.
 */
@Serializable
@SerialName("Tag")
data class Tag(
    val id: String,
    val name: String,
    val revision: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)
