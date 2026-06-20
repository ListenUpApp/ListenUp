package com.calypsan.listenup.server.scanner

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.dto.scanner.TrackEntry
import com.calypsan.listenup.server.db.LibraryTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Shared test utilities for [ScannerInboxIngestTest] and [ScanAllBooksMembershipTest].
 *
 * Provides [setInboxEnabled] (toggling the hold gate on a test library) and
 * [buildAnalyzedBook] (minimal [AnalyzedBook] factory). Both helpers are stable
 * across the two test files; keep them here rather than duplicating.
 */

internal fun setInboxEnabled(
    db: Database,
    libraryId: String,
    enabled: Boolean,
) {
    transaction(db) {
        LibraryTable.update({ LibraryTable.id eq libraryId }) { it[inboxEnabled] = enabled }
    }
}

internal fun buildAnalyzedBook(
    rootRelPath: String,
    inode: Long?,
): AnalyzedBook {
    val file =
        FileEntry(
            relPath = "$rootRelPath/01.m4b",
            name = "01.m4b",
            ext = "m4b",
            size = 1024L,
            mtimeMs = 0L,
            inode = inode,
            fileType = FileType.AUDIO,
        )
    return AnalyzedBook(
        candidate = CandidateBook(rootRelPath = rootRelPath, isFile = false, files = listOf(file)),
        title = rootRelPath.substringAfterLast('/'),
        tracks = listOf(TrackEntry(file = file)),
    )
}
