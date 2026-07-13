package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.BookMoodSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.local.db.BookMoodEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * The `book_moods` junction domain: composite `(bookId, moodId)` primary key mirrored
 * under the server's synthetic `"$bookId:$moodId"` envelope id. Server-wins apply,
 * soft tombstones, full digest participation, outbox-backed writes. Structurally
 * identical to [bookTagsDomain] — the same junction rules apply: the DAO tombstone
 * keeps the row (advancing its own revision, ignoring the event's revision) so digest
 * reconciliation stays faithful, and re-adds arrive as Created/Updated with
 * `deletedAt = null`.
 *
 * **Outbox writes.** Removing a mood from a book writes the junction tombstone
 * optimistically and queues a durable op on [OutboxChannels.BookMoods], keyed by the
 * same `"$bookId:$moodId"` envelope id; the in-flight shield defers the junction's own
 * echo until that op drains. Adding a mood stays an online RPC (find-or-create may mint
 * a new server-side mood id).
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
                RevisionGuard { id ->
                    val parts = id.split(":")
                    if (parts.size != 2) {
                        null
                    } else {
                        database.bookMoodDao().revisionOf(bookId = parts[0], moodId = parts[1])
                    }
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
                createdAt = payload.createdAt,
                revision = payload.revision,
                deletedAt = payload.deletedAt,
            ),
        )
    }

    /**
     * Tombstone from an SSE `Deleted` frame (`"$bookId:$moodId"` envelope id; `:` is
     * unambiguous — both parts are UUIDv7 strings). The DAO advances its own revision,
     * so the event revision is not taken here.
     */
    suspend fun tombstoneById(
        id: String,
        deletedAt: Long,
    ) {
        val parts = id.split(":")
        if (parts.size != 2) {
            logger.warn { "book_moods Deleted event has unexpected id format: '$id' — skipping tombstone" }
            return
        }
        database.bookMoodDao().tombstone(bookId = parts[0], moodId = parts[1], deletedAt = deletedAt)
    }

    override suspend fun tombstoneFromItem(item: BookMoodSyncPayload) {
        database.bookMoodDao().tombstone(
            bookId = item.bookId,
            moodId = item.moodId,
            deletedAt = item.deletedAt ?: item.createdAt,
        )
    }
}
