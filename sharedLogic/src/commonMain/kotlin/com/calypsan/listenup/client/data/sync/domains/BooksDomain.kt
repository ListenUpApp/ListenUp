package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.local.db.BookEntityMapper
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.documents.DocumentStorage
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.Timestamp

/**
 * The `books` domain: atomic aggregate apply (see [BookMirrorApply]), echo-shielded
 * server-wins, soft-delete tombstones, full digest, access-gated, outbox updates.
 *
 * **Echo no-flicker:** when an event echoes this client's own write, the visible
 * fields are already correct locally — the shield advances only `revision` and
 * `updatedAt` on the root row, leaving title, cover, and child rows untouched
 * (repainting them would flicker the UI). An echo for a book not yet mirrored falls
 * through to a full apply: there is nothing on screen to flicker, and the payload is
 * the freshest full snapshot of a book this client has never seen.
 *
 * **Why server-wins is safe for offline edits:** a queued outbox edit carries
 * per-field `userEditedFields` provenance, so the server folds the user's fields
 * before the resulting event ever echoes back — an inbound snapshot never reverts a
 * user edit that reached the server, and one that hasn't yet is replayed by the
 * outbox after this apply. `WriteTier.Outbox(OutboxChannels.Books)` declares that
 * posture (books edits queue as [OpKind.Update] ops on the `books` channel).
 *
 * **Access gate:** the server's `pullSince` for books is filtered to the caller's
 * accessible set, so an `AccessChanged` reconcile must prune local rows the user can
 * no longer see. [AccessGate.pruneTo] tombstones (not hard-deletes) every live row
 * outside the accessible set — revoked books disappear from view but restore
 * losslessly if access is re-granted.
 *
 * **Digest:** full participation — books are revision-fingerprintable, and
 * `digestRows` includes soft-deleted rows, matching the server's digest exactly.
 */
internal fun booksDomain(
    database: ListenUpDatabase,
    mapper: BookEntityMapper,
    imageStorage: ImageStorage,
    documentStorage: DocumentStorage? = null,
): MirroredDomain<BookSyncPayload> {
    val apply =
        BookMirrorApply(
            database = database,
            mapper = mapper,
            imageStorage = imageStorage,
            documentStorage = documentStorage,
        )
    return MirroredDomain(
        key = SyncDomains.BOOKS,
        apply = apply,
        conflict =
            ConflictPolicy.EchoShielded(
                onOwnEcho = { id, payload ->
                    val bookId = BookId(id)
                    if (database.bookDao().getById(bookId) == null) {
                        false
                    } else {
                        database.bookDao().updateRevisionAndTimestamp(
                            id = bookId,
                            revision = payload.revision,
                            updatedAt = Timestamp(payload.updatedAt),
                        )
                        true
                    }
                },
                revisionGuard = RevisionGuard { id -> database.bookDao().revisionOf(BookId(id)) },
            ),
        deletes = DeleteSemantics.SoftDelete(apply::tombstoneById),
        digest = fullDigest(database.bookDao()::digestRows),
        writes = WriteTier.Outbox(OutboxChannels.Books),
        accessGate =
            AccessGate(
                localLiveIds = { database.bookDao().liveIds().toSet() },
                pruneTo = { accessibleIds, now ->
                    database.bookDao().tombstoneNotIn(accessibleIds, now)
                    database.bookReadershipDao().deleteWhereBookNotLive()
                },
            ),
    )
}
