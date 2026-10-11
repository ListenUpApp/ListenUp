package com.calypsan.listenup.api.dto.worldevent

import com.calypsan.listenup.core.WorldEventId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One change inside an [EventsBatch]. Both variants are safe to re-fire. */
@Serializable
sealed interface WorldEventOp {
    /** Create or edit with the full snapshot. */
    @Serializable
    @SerialName("WorldEventOp.Upsert")
    data class Upsert(
        @SerialName("upsert") val upsert: WorldEventUpsert,
    ) : WorldEventOp

    /** Soft-delete the event [id]. */
    @Serializable
    @SerialName("WorldEventOp.Delete")
    data class Delete(
        @SerialName("id") val id: WorldEventId,
    ) : WorldEventOp
}

/**
 * The `world_events` outbox payload and the argument to
 * [com.calypsan.listenup.api.WorldEventService.applyBatch]: ordered ops the server applies in **one**
 * transaction. One refused op refuses the batch and nothing in it lands, so a client never reconciles a torn
 * write. A single write is a batch of one.
 *
 * @property ops the changes, applied in order.
 */
@Serializable
@SerialName("EventsBatch")
data class EventsBatch(
    @SerialName("ops") val ops: List<WorldEventOp>,
)
