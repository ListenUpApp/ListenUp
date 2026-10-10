package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.entity.StoryWorldOp
import com.calypsan.listenup.api.dto.worldevent.WorldEventChange
import com.calypsan.listenup.api.sync.WorldEventSyncPayload
import com.calypsan.listenup.core.StoryWorldHistoryId
import com.calypsan.listenup.core.WorldEventId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.Story_world_history
import kotlin.uuid.Uuid

/**
 * The `story_world_history` table as the world-event writer uses it (`target_type = 'world_event'`) — the
 * event twin of [StoryWorldHistory], over the same table. [record] must run inside the transaction that
 * makes the change, so a history row exists iff its change does. Snapshots are the full
 * [WorldEventSyncPayload], as JSON.
 */
internal class WorldEventHistory(
    private val db: ListenUpDatabase,
) {
    /** Writes one history row and returns it as the wire entry. Call inside the write's open transaction. */
    fun record(
        eventId: String,
        op: StoryWorldOp,
        actor: UserId?,
        occurredAt: Long,
        revision: Long,
        before: WorldEventSyncPayload?,
        after: WorldEventSyncPayload?,
    ): WorldEventChange {
        val id = Uuid.random().toString()
        db.storyWorldHistoryQueries.insert(
            id = id,
            target_type = WORLD_EVENT_TARGET,
            target_id = eventId,
            actor_id = actor?.value,
            occurred_at = occurredAt,
            revision = revision,
            op = op.name,
            before_json = before?.let { contractJson.encodeToString(WorldEventSyncPayload.serializer(), it) },
            after_json = after?.let { contractJson.encodeToString(WorldEventSyncPayload.serializer(), it) },
        )
        return WorldEventChange(
            id = StoryWorldHistoryId(id),
            eventId = WorldEventId(eventId),
            op = op,
            actorId = actor?.value,
            occurredAt = occurredAt,
            before = before,
            after = after,
        )
    }

    /** [eventId]'s history, newest first. Call inside a transaction. */
    fun listFor(eventId: WorldEventId): List<WorldEventChange> =
        db.storyWorldHistoryQueries
            .selectForTarget(target_type = WORLD_EVENT_TARGET, target_id = eventId.value)
            .executeAsList()
            .map { it.toChange() }

    /** One world-event history entry, or null (an entity's entry is not one). Call inside a transaction. */
    fun find(id: StoryWorldHistoryId): WorldEventChange? =
        db.storyWorldHistoryQueries
            .selectByIdAndType(id = id.value, target_type = WORLD_EVENT_TARGET)
            .executeAsOneOrNull()
            ?.toChange()

    private fun Story_world_history.toChange(): WorldEventChange =
        WorldEventChange(
            id = StoryWorldHistoryId(id),
            eventId = WorldEventId(target_id),
            op = StoryWorldOp.valueOf(op),
            actorId = actor_id,
            occurredAt = occurred_at,
            before = before_json?.let { contractJson.decodeFromString(WorldEventSyncPayload.serializer(), it) },
            after = after_json?.let { contractJson.decodeFromString(WorldEventSyncPayload.serializer(), it) },
        )

    private companion object {
        const val WORLD_EVENT_TARGET = "world_event"
    }
}
