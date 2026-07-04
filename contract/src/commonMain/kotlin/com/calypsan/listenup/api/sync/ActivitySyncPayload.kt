package com.calypsan.listenup.api.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTO for one social-activity-feed row — a started/finished book, a streak or
 * listening milestone, a shelf creation, a listening session. Written server-side
 * (never by a client) as an append-only event and mirrored to every client on the
 * revision-cursored data channel.
 *
 * Carries only the RAW activity fields the server persists — NOT the display
 * projection (`userDisplayName`, `bookCoverPath`, …). Identity and book-card
 * display are enriched at read time on the client by joining the local
 * `public_profiles` and book mirrors, so a later rename is reflected everywhere
 * instead of frozen at record time.
 *
 * Access is book-gated: a row with a non-null [bookId] is visible only to callers
 * who can access that book; [bookId] == null rows are public. The server enforces
 * this on all three sync surfaces (catch-up, digest, firehose).
 *
 * Implements [Tombstoned] for uniform soft-delete routing; the feed is append-only
 * so tombstones are rare (e.g. a cascaded user/book deletion), but the substrate's
 * machinery still requires the shape.
 */
@Serializable
@SerialName("ActivitySyncPayload")
data class ActivitySyncPayload(
    @SerialName("id") override val id: String,
    @SerialName("userId") val userId: String,
    @SerialName("type") val type: String,
    @SerialName("bookId") val bookId: String?,
    @SerialName("isReread") val isReread: Boolean,
    @SerialName("durationMs") val durationMs: Long,
    @SerialName("milestoneValue") val milestoneValue: Int,
    @SerialName("milestoneUnit") val milestoneUnit: String?,
    @SerialName("shelfId") val shelfId: String?,
    @SerialName("shelfName") val shelfName: String?,
    @SerialName("occurredAt") val occurredAt: Long,
    @SerialName("revision") override val revision: Long,
    @SerialName("createdAt") val createdAt: Long,
    @SerialName("updatedAt") val updatedAt: Long,
    @SerialName("deletedAt") override val deletedAt: Long?,
) : SyncPayload
