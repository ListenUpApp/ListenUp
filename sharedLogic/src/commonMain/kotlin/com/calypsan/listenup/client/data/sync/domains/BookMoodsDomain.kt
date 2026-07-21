package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.BookMoodSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.local.db.BookMoodEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * The `book_moods` junction domain: composite `(bookId, moodId)` primary key, mirrored under the
 * server's opaque per-row wire id (SERVER-SYNC-04 — stored as [BookMoodEntity.syncId], matched by
 * identity, never parsed). Server-wins apply, soft tombstones, full digest participation,
 * outbox-backed writes. Structurally identical to [bookTagsDomain] — the same junction rules
 * apply: the DAO tombstone keeps the row (advancing its own revision, ignoring the event's
 * revision) so digest reconciliation stays faithful, and re-adds arrive as Created/Updated with
 * `deletedAt = null`.
 *
 * **Outbox writes.** Removing a mood from a book writes the junction tombstone optimistically and
 * queues a durable op on [OutboxChannels.BookMoods]; the in-flight shield defers the junction's
 * own echo until that op drains. Adding a mood stays an online RPC (find-or-create may mint a new
 * server-side mood id).
 *
 * No FK constraints on `book_moods` — sync is responsible for integrity.
 */
internal fun bookMoodsDomain(database: ListenUpDatabase): MirroredDomain<BookMoodSyncPayload> {
    val apply = BookMoodMirrorApply(database)
    return MirroredDomain(
        key = SyncDomains.BOOK_MOODS,
        apply = apply,
        conflict =
            ConflictPolicy.ServerWins(
                RevisionGuard { syncId ->
                    database.bookMoodDao().revisionOfSyncId(syncId)
                },
            ),
        // The DAO advances its own revision on tombstone, so the event revision is dropped (`_`).
        deletes = DeleteSemantics.SoftDelete { id, deletedAt, _ -> apply.tombstoneById(id, deletedAt) },
        digest = fullDigest(database.bookMoodDao()::digestRows),
        writes = WriteTier.Outbox(OutboxChannels.BookMoods),
    )
}

/** Room mapping for [BookMoodSyncPayload] junction payloads. */
internal class BookMoodMirrorApply(
    private val database: ListenUpDatabase,
) : MirrorApply<BookMoodSyncPayload> {
    override suspend fun upsert(payload: BookMoodSyncPayload) {
        database.bookMoodDao().upsert(
            BookMoodEntity(
                bookId = payload.bookId,
                moodId = payload.moodId,
                syncId = payload.id,
                createdAt = payload.createdAt,
                revision = payload.revision,
                deletedAt = payload.deletedAt,
            ),
        )
    }

    /**
     * Tombstone from a firehose `Deleted` frame by the opaque wire [id] (SERVER-SYNC-04) — a graceful
     * no-op if [id] matches no local row (nothing to reconcile locally). The DAO advances its own
     * revision, so the event revision is not taken here.
     */
    suspend fun tombstoneById(
        id: String,
        deletedAt: Long,
    ) {
        val affected = database.bookMoodDao().tombstoneBySyncId(id, deletedAt)
        if (affected == 0) {
            logger.debug { "book_moods Deleted event matched no local row for id='$id' — graceful no-op" }
        }
    }

    /**
     * Tombstone from a catch-up item. The pull path blanks [item]'s natural pair on a tombstone
     * (SERVER-SYNC-04 — junction tombstones ship identity only), so this applies by [item]'s
     * opaque wire id, never by `bookId`/`moodId`.
     */
    override suspend fun tombstoneFromItem(item: BookMoodSyncPayload) {
        database.bookMoodDao().tombstoneBySyncId(
            syncId = item.id,
            deletedAt = item.deletedAt ?: item.createdAt,
        )
    }
}
