package com.calypsan.listenup.api.dto.entity

import com.calypsan.listenup.api.sync.EntitySyncPayload
import com.calypsan.listenup.core.EntityId
import com.calypsan.listenup.core.StoryWorldHistoryId
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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

    /**
     * What an older app reads an operation a newer server added as. Never recorded by the server,
     * and never revertible: restoring the `before` of a change this build cannot read is a guess.
     */
    UNKNOWN,
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
 * @property op what happened; [StoryWorldOp.UNKNOWN] when this build cannot read it. Always encoded.
 * @property actorId who did it; null for a change the server made itself (a book removal).
 * @property occurredAt epoch ms.
 * @property before the entity before the change; null for a CREATE.
 * @property after the entity after the change; a tombstone (deletedAt set) for a DELETE or MERGE.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("EntityChange")
data class EntityChange(
    @SerialName("id") val id: StoryWorldHistoryId,
    @SerialName("entityId") val entityId: EntityId,
    @SerialName("op") @EncodeDefault(EncodeDefault.Mode.ALWAYS) val op: StoryWorldOp = StoryWorldOp.UNKNOWN,
    @SerialName("actorId") val actorId: String? = null,
    @SerialName("occurredAt") val occurredAt: Long,
    @SerialName("before") val before: EntitySyncPayload? = null,
    @SerialName("after") val after: EntitySyncPayload? = null,
)
