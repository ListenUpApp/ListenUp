package com.calypsan.listenup.api.dto.entity

import com.calypsan.listenup.api.sync.EntitySyncPayload
import com.calypsan.listenup.core.EntityId
import com.calypsan.listenup.core.StoryWorldHistoryId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What one Story World history row did. */
@Serializable
enum class StoryWorldOp {
    CREATE,
    UPDATE,
    DELETE,
    MERGE,
    REVERT,
}

/**
 * One entry of an entity's edit history. Every write records one, in the same transaction as the
 * write. History is kept forever.
 *
 * Reverting an entry is a new forward write that restores [before]; it is itself recorded (op
 * [StoryWorldOp.REVERT]) and can be reverted in turn.
 *
 * @property id the handle [com.calypsan.listenup.api.EntityService.revert] takes.
 * @property entityId the entity changed.
 * @property op what happened.
 * @property actorId who did it; null for a change the server made itself (a book removal).
 * @property occurredAt epoch ms.
 * @property before the entity before the change; null for a CREATE.
 * @property after the entity after the change; a tombstone (deletedAt set) for a DELETE or MERGE.
 */
@Serializable
@SerialName("EntityChange")
data class EntityChange(
    @SerialName("id") val id: StoryWorldHistoryId,
    @SerialName("entityId") val entityId: EntityId,
    @SerialName("op") val op: StoryWorldOp,
    @SerialName("actorId") val actorId: String? = null,
    @SerialName("occurredAt") val occurredAt: Long,
    @SerialName("before") val before: EntitySyncPayload? = null,
    @SerialName("after") val after: EntitySyncPayload? = null,
)
