package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId

/**
 * The narrow slice of [BookRepository] that [BookPersister] depends on.
 *
 * Exists so the persister's orchestration logic (per-book error containment,
 * full-scan-only tombstone sweep) can be tested against an in-memory fake
 * without standing up a database. [BookRepository] is the sole production
 * implementation.
 */
interface BookIngestPort {
    /**
     * Resolve-or-insert a scanned book; see [BookRepository.resolveOrInsert].
     *
     * [pendingCover] carries the pre-validated cover bytes + provenance extracted
     * from the [AnalyzedBook] before the call — the image file has NOT yet been
     * written to the managed store. The implementation stores the bytes to
     * [com.calypsan.listenup.server.cover.CoverImageStore] after resolving the
     * stable [BookId], so the file is always named after the book it belongs to.
     * Null means the scanned book has no cover to store (or cover storage is not
     * configured).
     */
    suspend fun resolveOrInsert(
        libraryId: LibraryId,
        folderId: FolderId,
        analyzed: AnalyzedBook,
        pendingCover: PendingCover? = null,
    ): AppResult<IngestOutcome>

    /** Soft-delete library books absent from [seenIds]; see [BookRepository.softDeleteAbsent]. */
    suspend fun softDeleteAbsent(
        libraryId: LibraryId,
        seenIds: Set<BookId>,
    )
}

/**
 * Cover bytes extracted from an [AnalyzedBook] before the DB upsert, ready to
 * be stored into the managed cover store once the stable [BookId] is known.
 *
 * [bytes] are the raw image bytes; [mime] is the MIME type declared by the
 * source (e.g. `"image/jpeg"`); [source] is the sync-layer provenance tag
 * ([CoverSource.FILESYSTEM] or [CoverSource.EMBEDDED]).
 */
data class PendingCover(
    val bytes: ByteArray,
    val mime: String,
    val source: CoverSource,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PendingCover) return false
        return mime == other.mime && source == other.source && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = mime.hashCode()
        result = 31 * result + source.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

/**
 * The result of a resolve-or-insert: the stable [bookId] the aggregate landed
 * under, plus whether this scan minted it fresh ([wasNew]) versus refreshed an
 * existing book in place (a plain rescan or a move).
 *
 * [wasNew] reports a genuine fact about the operation that resolve-or-insert
 * knows for free. The scan-time inbox auto-populate that once consumed it was
 * reverted (TOCTOU firehose leak — see [BookPersister]); the flag is retained
 * for the future scan-auto-populate phase, which needs exactly this "only inbox
 * genuinely-new books" discrimination once it can land the membership atomically.
 */
data class IngestOutcome(
    val bookId: BookId,
    val wasNew: Boolean,
)
