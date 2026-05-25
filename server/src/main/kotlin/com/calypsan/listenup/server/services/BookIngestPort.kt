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
 * full-scan-only tombstone sweep) can be tested against an in-memory fake
 * without standing up a database. [BookRepository] is the sole production
 * implementation.
 */
interface BookIngestPort {
    /** Resolve-or-insert a scanned book; see [BookRepository.resolveOrInsert]. */
    suspend fun resolveOrInsert(
        libraryId: LibraryId,
        folderId: FolderId,
        analyzed: AnalyzedBook,
    ): AppResult<BookId>

    /** Soft-delete library books absent from [seenIds]; see [BookRepository.softDeleteAbsent]. */
    suspend fun softDeleteAbsent(
        libraryId: LibraryId,
        seenIds: Set<BookId>,
    )
}
