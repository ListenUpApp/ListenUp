package com.calypsan.listenup.api.sync

import com.calypsan.listenup.api.dto.readingorder.ReadingOrderChoiceKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A user's choice of reading order on one series (#962). User-scoped: only its owner receives it. [id]
 * is the deterministic "<userId>:<seriesId>". A tombstone means "no choice here — inherit from the
 * nearest ancestor". [choice] defaults to SERIES so a kind a newer server adds coerces safely.
 */
@Serializable
@SerialName("ReadingOrderFollowSyncPayload")
data class ReadingOrderFollowSyncPayload(
    /** "<userId>:<seriesId>". */
    @SerialName("id") override val id: String,
    /** The series the choice is made on. */
    @SerialName("seriesId") val seriesId: String,
    /** Which kind of order is followed. */
    @SerialName("choice") val choice: ReadingOrderChoiceKind = ReadingOrderChoiceKind.SERIES,
    /** The followed user-made order; set iff [choice] is ORDER. */
    @SerialName("readingOrderId") val readingOrderId: String? = null,
    /** Sync revision counter — bumped on every write. */
    @SerialName("revision") override val revision: Long,
    /** Epoch millis of the last server-side write. */
    @SerialName("updatedAt") val updatedAt: Long,
    /** Epoch millis when the choice was first made. */
    @SerialName("createdAt") val createdAt: Long,
    @SerialName("deletedAt") override val deletedAt: Long? = null,
) : SyncPayload
