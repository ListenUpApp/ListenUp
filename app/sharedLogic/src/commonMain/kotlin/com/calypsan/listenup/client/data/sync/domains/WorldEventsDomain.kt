package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.api.sync.WorldEventSyncPayload
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.WorldEventEntity
import com.calypsan.listenup.client.data.local.db.replaceMentions
import com.calypsan.listenup.client.data.sync.TargetedFetch

/**
 * The `world_events` domain (Story World): server-wins apply, soft tombstones, full digest, outbox writes, and
 * the book access gate — an event whose home or anchor book leaves the viewer's scope is pruned locally.
 *
 * **Targeted delta.** The scoped `AccessChanged` asks by book id; the server resolves those books to the events
 * they touch (`WorldEventRepository.pullByIds`) and answers only the visible ones, and
 * [com.calypsan.listenup.client.data.local.db.WorldEventDao.liveIdsTouchingBooks] is the same set locally.
 *
 * **Mentions** ride the payload: each frame replaces the row's local mention set with the server's.
 * **Tombstones** arrive minimised and apply by id only ([WorldEventMirrorApply.tombstoneById]), so the local row
 * keeps its content for undo.
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
        accessGate =
            AccessGate(
                liveIds = database.worldEventDao()::liveIds,
                tombstoneByIds = database.worldEventDao()::tombstoneByIds,
                delta =
                    AccessDeltaPolicy.Targeted(
                        order = WORLD_EVENTS_DELTA_ORDER,
                        axis = ScopeAxis.Books,
                        fetchFor = { TargetedFetch.ByBookIds(it) },
                        candidatesFor = { bookIds ->
                            bookIds
                                // The query binds each chunk three times (home, anchor, series membership).
                                .chunked(SQLITE_IN_CHUNK / BINDS_PER_TOUCHING_QUERY)
                                .flatMapTo(mutableSetOf()) { database.worldEventDao().liveIdsTouchingBooks(it, it, it) }
                        },
                    ),
            ),
    )
}

/** After every other book-keyed domain (reading_order_books is 8): events depend on book access only. */
private const val WORLD_EVENTS_DELTA_ORDER = 9

/** [com.calypsan.listenup.client.data.local.db.WorldEventDao.liveIdsTouchingBooks] binds each id list three times. */
private const val BINDS_PER_TOUCHING_QUERY = 3

/** Room mapping for [WorldEventSyncPayload]. */
internal class WorldEventMirrorApply(
    private val database: ListenUpDatabase,
) : MirrorApply<WorldEventSyncPayload> {
    override suspend fun upsert(payload: WorldEventSyncPayload) {
        database.worldEventDao().upsert(payload.toWorldEventRow())
        database.worldEventDao().replaceMentions(payload.id, payload.mentionIds)
    }

    /** Tombstone from a `Deleted` frame; a no-op when no local row matches. */
    suspend fun tombstoneById(
        id: String,
        deletedAt: Long,
        revision: Long,
    ) {
        database.worldEventDao().softDelete(id = id, deletedAt = deletedAt, revision = revision)
    }

    override suspend fun tombstoneFromItem(item: WorldEventSyncPayload) {
        tombstoneById(id = item.id, deletedAt = item.deletedAt ?: item.updatedAt, revision = item.revision)
    }
}

/** The Room row for a server [WorldEventSyncPayload], verbatim — the mirror's and an online undo's one mapping. */
internal fun WorldEventSyncPayload.toWorldEventRow(): WorldEventEntity =
    WorldEventEntity(
        id = id,
        homeSeriesId = homeSeriesId,
        homeBookId = homeBookId,
        bookId = bookId,
        positionMs = positionMs,
        type = type,
        text = text,
        detail = detail,
        subjectEntityId = subjectEntityId,
        objectEntityId = objectEntityId,
        createdBy = createdBy,
        updatedBy = updatedBy,
        revision = revision,
        deletedAt = deletedAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
