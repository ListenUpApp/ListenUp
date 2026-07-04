package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.BookTagSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.local.db.BookTagEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * The `book_tags` junction domain (Tags phase — Room v22): composite `(bookId, tagId)`
 * primary key mirrored under the server's synthetic `"$bookId:$tagId"` envelope id.
 * Server-wins apply, soft tombstones, full digest participation, online-only writes.
 *
 * **Junctions soft-tombstone.** The DAO's `tombstone` keeps the row with `deletedAt`
 * set so [DigestParticipation.Full] still covers it — literally deleting the row
 * would break digest reconciliation (spec correction 2026-07-02).
 *
 * **Re-add semantics.** Re-applying a tag after removal arrives as Created/Updated
 * with `deletedAt = null`; the upsert clears the tombstone.
 *
 * No FK constraints on `book_tags` — sync is responsible for integrity. `isOwnEcho`
 * needs no shield: `@Upsert` is idempotent.
 */
internal fun bookTagsDomain(database: ListenUpDatabase): MirroredDomain<BookTagSyncPayload> {
    val apply = BookTagMirrorApply(database)
    return MirroredDomain(
        key = SyncDomains.BOOK_TAGS,
        apply = apply,
        conflict =
            ConflictPolicy.ServerWins(
                RevisionGuard { id ->
                    val parts = id.split(":")
                    if (parts.size != 2) {
                        null
                    } else {
                        database.bookTagDao().revisionOf(bookId = parts[0], tagId = parts[1])
                    }
                },
            ),
        // The DAO advances its own revision on tombstone, so the event revision is dropped (`_`).
        deletes = DeleteSemantics.SoftDelete { id, deletedAt, _ -> apply.tombstoneById(id, deletedAt) },
        digest = fullDigest(database.bookTagDao()::digestRows),
        writes = WriteTier.OnlineOnly,
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
                createdAt = payload.createdAt,
                revision = payload.revision,
                deletedAt = payload.deletedAt,
            ),
        )
    }

    /**
     * Tombstone from an SSE `Deleted` frame. The server synthesises `"$bookId:$tagId"`
     * as the stable envelope id (see `BookTagId.asString()` server-side); the `:`
     * delimiter cannot appear in either part because book/tag ids are UUIDv7 strings.
     * The DAO advances its own revision, so the event revision is not taken here.
     */
    suspend fun tombstoneById(
        id: String,
        deletedAt: Long,
    ) {
        val parts = id.split(":")
        if (parts.size != 2) {
            logger.warn { "book_tags Deleted event has unexpected id format: '$id' — skipping tombstone" }
            return
        }
        database.bookTagDao().tombstone(bookId = parts[0], tagId = parts[1], deletedAt = deletedAt)
    }

    override suspend fun tombstoneFromItem(item: BookTagSyncPayload) {
        database.bookTagDao().tombstone(
            bookId = item.bookId,
            tagId = item.tagId,
            deletedAt = item.deletedAt ?: item.createdAt,
        )
    }
}
