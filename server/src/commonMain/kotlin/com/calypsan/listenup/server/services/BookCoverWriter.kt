package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.FieldProvenance
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.CoverPayload
import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.cover.LiveCover
import com.calypsan.listenup.server.cover.StoredCoverInfo
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.sync.FrameCapture
import kotlin.time.Clock
import kotlinx.coroutines.currentCoroutineContext

/** The cover columns a match writes, or an undo restores; a null [source] means no cover. */
internal data class MatchCoverColumns(
    val source: CoverSource?,
    val path: String?,
    val hash: String?,
)

/** Resolved cover columns to write on an INSERT or non-sticky UPDATE. */
internal data class CoverColumns(
    val source: String?,
    val path: String?,
    val hash: String?,
)

/** The stored cover columns read before a write, so [BookCoverWriter.resolveCoverColumns] can decide the path. */
internal data class ExistingCoverColumns(
    val source: String?,
    val path: String?,
    val hash: String?,
)

/**
 * The `cover` field a scan-time write should actually carry, for both batched and single-book
 * scan ingest.
 *
 * A scan-built [BookSyncPayload.cover] is always null — [AnalyzedBookMapper] never fills it
 * in; the scan-derived cover reaches [BookCoverWriter.resolveCoverColumns] only via [storedCover]
 * ([BookWriteExtras.managedCover]). That's harmless while [storedCover] is non-null:
 * [BookCoverWriter.resolveCoverColumns] takes source/path/hash from it and ignores the payload's own `cover`
 * entirely. It stops being harmless when [storedCover] is null AND [analyzed]'s files DID
 * carry cover art (`analyzed.cover != null`) — [BookCoverWriter.resolveCoverColumns] then has nothing but a
 * bare null to read, and a bare null means "the files lost their cover" to it. But a null
 * [storedCover] alongside a non-null `analyzed.cover` isn't that — it's one of three failure
 * modes that are nobody's fault (an unconfigured cover store, a cover file that couldn't be
 * read, or a store that threw) and none of them should cost the book its existing cover.
 * Handing back [existing]'s own cover in that situation is what lets [BookCoverWriter.resolveCoverColumns]'s
 * stored-path rule (matching source + hash) carry all three columns forward unchanged, with
 * no new write path.
 *
 * [existing] null (a genuinely new book) always yields a null cover — there is nothing to
 * carry forward, and a new book with no stored cover is exactly what a no-art scan produces
 * anyway.
 */
internal fun scanCoverForWrite(
    analyzed: AnalyzedBook,
    storedCover: StoredCoverInfo?,
    existing: BookSyncPayload?,
): CoverPayload? {
    val couldNotStoreFoundArt = analyzed.cover != null && storedCover == null
    return if (couldNotStoreFoundArt) existing?.cover else null
}

/**
 * Cover column resolution and targeted cover-row mutations for [BookRepository].
 *
 * Keeps cover-specific SQL mutations ([setManagedCover], [clearManagedCover], [listLiveCovers])
 * and scan/aggregate cover column preservation ([resolveCoverColumns], [scanCoverForWrite])
 * cohesive and out of [BookRepository], while delegating revision allocation and post-commit
 * event publication through [sync].
 */
internal class BookCoverWriter(
    private val db: ListenUpDatabase,
    private val clock: Clock,
    private val sync: BookSyncBridge,
) {
    /**
     * The cover columns to write for [value] on an INSERT or non-sticky UPDATE.
     *
     * When [managedCover] is non-null, the scan-time managed cover lands in the same
     * statement (source + relPath + hash). Otherwise the source/hash come from the wire
     * payload's cover — [CoverPayload] carries no path, only source + hash, so this method
     * can never learn a path from `value` alone.
     *
     * In that no-[managedCover] case, [existing] (the row's cover columns before this write,
     * `null` on INSERT) decides the path: if [value]'s cover has the SAME source + hash as the
     * stored row, the write is a re-read-and-write of an aggregate that already has a managed
     * cover on disk (a series/genre/contributor merge's re-upsert, or a user metadata edit both
     * do `upsert(findById(id))` under the hood) — the path is carried forward unchanged rather
     * than nulled, or the cover would silently stop resolving ([com.calypsan.listenup.server.cover.ManagedCoverFiles]
     * needs the path for every source but UPLOADED). If the source or hash differs, the incoming payload
     * describes cover bytes this write does not know the path to (a fresh scan or upload that
     * hasn't called [setManagedCover] yet), so the path is null exactly as before — the same
     * applies when [value]'s cover is null (the cover is being removed).
     */
    fun resolveCoverColumns(
        value: BookSyncPayload,
        managedCover: StoredCoverInfo?,
        existing: ExistingCoverColumns?,
    ): CoverColumns =
        if (managedCover != null) {
            CoverColumns(
                source = managedCover.source.name.lowercase(),
                path = managedCover.relPath,
                hash = managedCover.hash,
            )
        } else {
            val incoming = value.cover
            val incomingSource = incoming?.source?.run { name.lowercase() }
            val coverUnchangedFromStored =
                incoming != null &&
                    existing != null &&
                    existing.source == incomingSource &&
                    existing.hash == incoming.hash
            CoverColumns(
                source = incomingSource,
                path = if (coverUnchangedFromStored) existing.path else null,
                hash = incoming?.hash,
            )
        }

    /**
     * Sets the managed-cover columns (provenance + relative path + sha256 hash) and bumps the
     * row's revision so the change propagates to clients via the sync bus.
     *
     * [provenance], when given, is recorded as the book's `COVER` field provenance in the same
     * transaction: USER for a cover a person chose by hand, ENRICHMENT for one a match applied.
     * Null leaves it as it is.
     *
     * Opens its own transaction. The `SyncEvent.Updated` is published after the transaction
     * commits, carrying the full aggregate.
     */
    suspend fun setManagedCover(
        id: BookId,
        relPath: String,
        hash: String,
        source: CoverSource,
        provenance: FieldProvenance? = null,
    ): AppResult<Unit> {
        val idStr = id.value
        val capture = currentCoroutineContext()[FrameCapture.Key]
        return suspendTransaction(db) {
            val rev = sync.nextRevision()
            val now = clock.now().toEpochMilliseconds()
            db.booksQueries.updateManagedCover(
                cover_source = source.name.lowercase(),
                cover_path = relPath,
                cover_hash = hash,
                revision = rev,
                updated_at = now,
                id = idStr,
            )
            if (db.booksQueries.changes().executeAsOne() == 0L) {
                AppResult.Failure(SyncError.NotFound(domain = sync.domainName, entityId = idStr))
            } else {
                provenance?.let { stamp -> rewriteFieldProvenance(idStr) { it + (BookField.COVER to stamp) } }
                with(sync) { emitBookUpdatedAfterCommit(idStr = idStr, rev = rev, now = now, capture = capture) }
                AppResult.Success(Unit)
            }
        }
    }

    /**
     * Nulls the managed-cover columns and bumps the row's revision so the change propagates.
     * Opens its own transaction.
     */
    suspend fun clearManagedCover(id: BookId): AppResult<Unit> {
        val idStr = id.value
        return suspendTransaction(db) {
            val rev = sync.nextRevision()
            val now = clock.now().toEpochMilliseconds()
            db.booksQueries.clearManagedCover(revision = rev, updated_at = now, id = idStr)
            if (db.booksQueries.changes().executeAsOne() == 0L) {
                AppResult.Failure(SyncError.NotFound(domain = sync.domainName, entityId = idStr))
            } else {
                // The cover is gone, so is who set it: a later match should propose one again.
                rewriteFieldProvenance(idStr) { it - BookField.COVER }
                with(sync) { emitBookUpdatedAfterCommit(idStr = idStr, rev = rev, now = now) }
                AppResult.Success(Unit)
            }
        }
    }

    /**
     * Every live book that has a cover, paired with its content hash — what
     * [com.calypsan.listenup.server.cover.CoverDerivativeMaintenance] warms from, and what tells it
     * which cached derivatives still belong to something.
     */
    suspend fun listLiveCovers(): List<LiveCover> =
        suspendTransaction(db) {
            db.booksQueries
                .selectLiveCoverHashes()
                .executeAsList()
                .map { row -> LiveCover(BookId(row.id), row.cover_hash) }
        }

    /**
     * Rewrites [idStr]'s provenance column through [transform]. Synchronous, for use inside an open
     * transaction by the targeted cover writes, which bypass writePayload.
     */
    private fun rewriteFieldProvenance(
        idStr: String,
        transform: (Map<BookField, FieldProvenance>) -> Map<BookField, FieldProvenance>,
    ) {
        val current = db.booksQueries.selectFieldProvenanceById(idStr).executeAsOneOrNull() ?: return
        db.booksQueries.updateFieldProvenance(
            field_provenance = transform(current.toFieldProvenance()).toFieldProvenanceColumn(),
            id = idStr,
        )
    }
}
