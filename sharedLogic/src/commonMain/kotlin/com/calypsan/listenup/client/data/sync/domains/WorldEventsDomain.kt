package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.core.MentionTokens
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.api.sync.WorldEventSyncPayload
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.WorldEventEntity

/**
 * The `world_events` domain (Story World unified event log): server-wins apply, soft-delete
 * tombstones, full digest, outbox-backed batch writes.
 *
 * **Outbox writes.** Every write — a brand-new (client-minted id) event, an edit to an existing
 * one, or a soft-delete — rides one [com.calypsan.listenup.api.dto.world.EventsBatch] on
 * [OutboxChannels.WorldEvents], keyed by the batch's own entity id (see
 * [com.calypsan.listenup.client.data.repository.WorldEventEditRepositoryImpl] for how a
 * multi-op batch picks that key). There is no online-only world-event RPC — the only write
 * entry point, [com.calypsan.listenup.api.WorldEventService.applyBatch], mints no server-side
 * identity the client couldn't already generate, exactly like [entitiesDomain].
 *
 * **Aggregate with a replaced child collection.** Unlike [EntityMirrorApply], an event's mention
 * set is a child collection ([com.calypsan.listenup.client.data.local.db.WorldEventMentionEntity])
 * replaced wholesale on every apply — [WorldEventMirrorApply.upsert] writes the root row AND the
 * mention junction inside the composed handler's IMMEDIATE write transaction, so a reader never
 * observes an event with a stale or partial mention set.
 */
internal fun worldEventsDomain(database: ListenUpDatabase): MirroredDomain<WorldEventSyncPayload> {
    val apply = WorldEventMirrorApply(database)
    return MirroredDomain(
        key = SyncDomains.WORLD_EVENTS,
        apply = apply,
        conflict = ConflictPolicy.ServerWins(RevisionGuard { id -> database.worldEventDao().revisionOf(id) }),
        deletes = DeleteSemantics.SoftDelete(apply::tombstoneById),
        digest = fullDigest(database.worldEventDao()::digestRows),
        writes = WriteTier.Outbox(OutboxChannels.WorldEvents),
    )
}

/** Room mapping for [WorldEventSyncPayload] payloads — root row plus replaced mention junction. */
internal class WorldEventMirrorApply(
    private val database: ListenUpDatabase,
) : MirrorApply<WorldEventSyncPayload> {
    override suspend fun upsert(payload: WorldEventSyncPayload) {
        database.worldEventDao().upsert(
            WorldEventEntity(
                id = payload.id,
                homeSeriesId = payload.homeSeriesId,
                homeBookId = payload.homeBookId,
                bookId = payload.bookId,
                positionMs = payload.positionMs,
                type = payload.type,
                text = payload.text,
                subjectEntityId = payload.subjectEntityId,
                objectEntityId = payload.objectEntityId,
                source = payload.source,
                trackId = payload.trackId,
                trackVersion = payload.trackVersion,
                revision = payload.revision,
                deletedAt = payload.deletedAt,
                createdAt = payload.createdAt,
                updatedAt = payload.updatedAt,
            ),
        )
        // Recomputed rather than trusted from payload.mentionIds so the inbound apply and the
        // optimistic local write (WorldEventEditRepositoryImpl) share the exact same client-side
        // computation — see worldEventMentionIds' KDoc.
        database.worldEventDao().replaceMentions(
            payload.id,
            worldEventMentionIds(payload.text, payload.subjectEntityId, payload.objectEntityId),
        )
    }

    suspend fun tombstoneById(
        id: String,
        deletedAt: Long,
        revision: Long,
    ) {
        database.worldEventDao().softDelete(id = id, deletedAt = deletedAt, revision = revision)
        database.worldEventDao().deleteMentionsForEvent(id)
    }

    override suspend fun tombstoneFromItem(item: WorldEventSyncPayload) {
        tombstoneById(
            id = item.id,
            deletedAt = item.deletedAt ?: item.updatedAt,
            revision = item.revision,
        )
    }
}

/**
 * Computes the mention set for an event's (`text`, `subjectEntityId`, `objectEntityId`) — the
 * exact recompute `WorldEventRepository.applyUpsert` performs server-side
 * (`server/.../sync/WorldEventRepository.kt`), so a client-authored optimistic write and the
 * server's authoritative echo always agree bit-for-bit. Shared by both [WorldEventMirrorApply.upsert]
 * (the inbound sync path) and
 * [com.calypsan.listenup.client.data.repository.WorldEventEditRepositoryImpl] (the optimistic
 * local write path) so there is exactly one client-side computation of "what does this event
 * mention" — no drift between how the two paths derive it.
 */
internal fun worldEventMentionIds(
    text: String,
    subjectEntityId: String?,
    objectEntityId: String?,
): Set<String> = MentionTokens.extractMentionIds(text) + setOfNotNull(subjectEntityId, objectEntityId)
