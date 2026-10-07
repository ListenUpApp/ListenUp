package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.entity.EntityChange
import com.calypsan.listenup.api.dto.entity.StoryWorldOp
import com.calypsan.listenup.api.sync.EntitySyncPayload
import com.calypsan.listenup.core.EntityId
import com.calypsan.listenup.core.StoryWorldHistoryId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.Story_world_history
import kotlin.uuid.Uuid

/**
 * The `story_world_history` table, as the entity writer uses it. [record] must run inside the
 * transaction that makes the change — that is what makes history atomic with the write: if either
 * fails, both roll back. Snapshots are the full [EntitySyncPayload], as JSON.
 */
internal class StoryWorldHistory(
    private val db: ListenUpDatabase,
) {
    /** Writes one history row and returns it as the wire entry. Call inside the write's open transaction. */
    fun record(
        entityId: String,
        op: StoryWorldOp,
        actor: UserId?,
        occurredAt: Long,
        revision: Long,
        before: EntitySyncPayload?,
        after: EntitySyncPayload?,
    ): EntityChange {
        val id = Uuid.random().toString()
        db.storyWorldHistoryQueries.insert(
            id = id,
            target_type = ENTITY_TARGET,
            target_id = entityId,
            actor_id = actor?.value,
            occurred_at = occurredAt,
            revision = revision,
            op = op.name,
            before_json = before?.let { contractJson.encodeToString(EntitySyncPayload.serializer(), it) },
            after_json = after?.let { contractJson.encodeToString(EntitySyncPayload.serializer(), it) },
        )
        return EntityChange(StoryWorldHistoryId(id), EntityId(entityId), op, actor?.value, occurredAt, before, after)
    }

    /** [entityId]'s history, newest first. Call inside a transaction. */
    fun listFor(entityId: EntityId): List<EntityChange> =
        db.storyWorldHistoryQueries
            .selectForTarget(target_type = ENTITY_TARGET, target_id = entityId.value)
            .executeAsList()
            .map { it.toChange() }

    /** One entity history entry, or null. Call inside a transaction. */
    fun find(id: StoryWorldHistoryId): EntityChange? =
        db.storyWorldHistoryQueries
            .selectByIdAndType(id = id.value, target_type = ENTITY_TARGET)
            .executeAsOneOrNull()
            ?.toChange()

    private fun Story_world_history.toChange(): EntityChange =
        EntityChange(
            id = StoryWorldHistoryId(id),
            entityId = EntityId(target_id),
            op = StoryWorldOp.valueOf(op),
            actorId = actor_id,
            occurredAt = occurred_at,
            before = before_json?.let { contractJson.decodeFromString(EntitySyncPayload.serializer(), it) },
            after = after_json?.let { contractJson.decodeFromString(EntitySyncPayload.serializer(), it) },
        )

    private companion object {
        const val ENTITY_TARGET = "entity"
    }
}
