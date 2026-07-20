package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.BookTagSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.local.db.BookTagEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * The `book_tags` junction domain (Tags phase — Room v22): composite `(bookId, tagId)` primary
 * key, mirrored under the server's opaque per-row wire id (SERVER-SYNC-04 — stored as
 * [BookTagEntity.syncId], matched by identity, never parsed). Server-wins apply, soft tombstones,
 * full digest participation, outbox-backed writes.
 *
 * **Junctions soft-tombstone.** The DAO's `tombstone` keeps the row with `deletedAt`
 * set so [DigestParticipation.Full] still covers it — literally deleting the row
 * would break digest reconciliation (spec correction 2026-07-02).
 *
 * **Re-add semantics.** Re-applying a tag after removal arrives as Created/Updated
 * with `deletedAt = null`; the upsert clears the tombstone.
 *
 * **Outbox writes.** Removing a tag from a book writes the junction tombstone optimistically and
 * queues a durable op on [OutboxChannels.BookTags]; the in-flight shield defers the junction's own
 * echo until that op drains. Adding a tag stays an online RPC (find-or-create may mint a new
 * server-side tag id, which can't be mirrored optimistically).
 *
 * No FK constraints on `book_tags` — sync is responsible for integrity.
 */
internal fun bookTagsDomain(database: ListenUpDatabase): MirroredDomain<BookTagSyncPayload> {
    val apply = BookTagMirrorApply(database)
    return MirroredDomain(
        key = SyncDomains.BOOK_TAGS,
        apply = apply,
        conflict =
            ConflictPolicy.ServerWins(
                RevisionGuard { syncId ->
                    database.bookTagDao().revisionOfSyncId(syncId)
                },
            ),
        // Write the event's own revision so a replayed Deleted frame is a true no-op (matches
        // collection_books). The revision is the server-authoritative value from the frame.
        deletes =
            DeleteSemantics.SoftDelete {
                id,
                deletedAt,
                revision,
                ->
                apply.tombstoneById(id, deletedAt, revision)
            },
        digest = fullDigest(database.bookTagDao()::digestRows),
        writes = WriteTier.Outbox(OutboxChannels.BookTags),
    )
}

/** Room mapping for [BookTagSyncPayload] junction payloads. */
internal class BookTagMirrorApply(
    private val database: ListenUpDatabase,
) : MirrorApply<BookTagSyncPayload> {
    override suspend fun upsert(payload: BookTagSyncPayload) {
        database.bookTagDao().upsert(
            BookTagEntity(
                bookId = payload.bookId,
                tagId = payload.tagId,
                syncId = payload.id,
                createdAt = payload.createdAt,
                revision = payload.revision,
                deletedAt = payload.deletedAt,
            ),
        )
    }

    /**
     * Tombstone from an SSE `Deleted` frame by the opaque wire [id] (SERVER-SYNC-04) — a graceful
     * no-op if [id] matches no local row (nothing to reconcile locally). The event's own
     * [revision] is written (not `revision + 1`) so a replay is a no-op.
     */
    suspend fun tombstoneById(
        id: String,
        deletedAt: Long,
        revision: Long,
    ) {
        val affected = database.bookTagDao().tombstoneBySyncId(id, deletedAt, revision)
        if (affected == 0) {
            logger.debug { "book_tags Deleted event matched no local row for id='$id' — graceful no-op" }
        }
    }

    /**
     * Tombstone from a catch-up item. The pull path blanks [item]'s natural pair on a tombstone
     * (SERVER-SYNC-04 — junction tombstones ship identity only), so this applies by [item]'s
     * opaque wire id, never by `bookId`/`tagId`.
     */
    override suspend fun tombstoneFromItem(item: BookTagSyncPayload) {
        database.bookTagDao().tombstoneBySyncId(
            syncId = item.id,
            deletedAt = item.deletedAt ?: item.createdAt,
            revision = item.revision,
        )
    }
}
