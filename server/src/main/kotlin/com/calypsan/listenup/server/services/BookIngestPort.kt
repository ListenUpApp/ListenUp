package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId

/**
 * The narrow slice of [BookRepository] that [BookPersister] depends on.
 *
 * Exists so the persister's orchestration logic (per-book error containment,
 * full-scan-only tombstone sweep, inbox auto-add for new books) can be tested
 * against an in-memory fake without standing up a database. [BookRepository] is
 * the sole production implementation.
 */
interface BookIngestPort {
    /** Resolve-or-insert a scanned book; see [BookRepository.resolveOrInsert]. */
    suspend fun resolveOrInsert(
        libraryId: LibraryId,
        folderId: FolderId,
        analyzed: AnalyzedBook,
    ): AppResult<IngestOutcome>

    /** Soft-delete library books absent from [seenIds]; see [BookRepository.softDeleteAbsent]. */
    suspend fun softDeleteAbsent(
        libraryId: LibraryId,
        seenIds: Set<BookId>,
    )
}

/**
 * The result of a resolve-or-insert: the stable [bookId] the aggregate landed
 * under, plus whether this scan minted it fresh ([wasNew]) versus refreshed an
 * existing book in place (a plain rescan or a move).
 *
 * [BookPersister] uses [wasNew] to gate the inbox auto-add: only genuinely new
 * books are inboxed, so a released book stays released across rescans.
 */
data class IngestOutcome(
    val bookId: BookId,
    val wasNew: Boolean,
)
