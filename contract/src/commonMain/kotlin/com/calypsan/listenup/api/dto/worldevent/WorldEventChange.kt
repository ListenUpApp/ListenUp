package com.calypsan.listenup.api.dto.worldevent

import com.calypsan.listenup.api.dto.entity.StoryWorldOp
import com.calypsan.listenup.api.sync.WorldEventSyncPayload
import com.calypsan.listenup.core.StoryWorldHistoryId
import com.calypsan.listenup.core.WorldEventId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One entry of a world event's history, recorded in the same transaction as the write and kept forever. The
 * event twin of [com.calypsan.listenup.api.dto.entity.EntityChange]; both live in one history table. An
 * event's op is never [StoryWorldOp.MERGE].
 *
 * Reverting an entry is a new forward write that restores [before]; it is itself recorded
 * ([StoryWorldOp.REVERT]) and can be reverted in turn.
 *
 * @property id the handle [com.calypsan.listenup.api.WorldEventService.revert] takes.
 * @property eventId the event changed.
 * @property op what happened.
 * @property actorId who did it; null for a change the server made itself (a book removal, a series merge).
 * @property occurredAt epoch ms.
 * @property before the event before the change; null for a CREATE, or when the viewer can't see this snapshot's
 *   home or anchor (an edit can move the anchor, so an earlier state may be out of the viewer's sight).
 * @property after the event after the change; a tombstone for a DELETE; null when the viewer can't see it.
 */
@Serializable
@SerialName("WorldEventChange")
data class WorldEventChange(
    @SerialName("id") val id: StoryWorldHistoryId,
    @SerialName("eventId") val eventId: WorldEventId,
    @SerialName("op") val op: StoryWorldOp,
    @SerialName("actorId") val actorId: String? = null,
    @SerialName("occurredAt") val occurredAt: Long,
    @SerialName("before") val before: WorldEventSyncPayload? = null,
    @SerialName("after") val after: WorldEventSyncPayload? = null,
)
