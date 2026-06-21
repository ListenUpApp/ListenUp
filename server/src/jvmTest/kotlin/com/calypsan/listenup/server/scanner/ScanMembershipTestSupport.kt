package com.calypsan.listenup.server.scanner

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.dto.scanner.TrackEntry
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase

/*
 * Shared test utilities for ScannerInboxIngestTest and ScanAllBooksMembershipTest.
 *
 * Provides setInboxEnabled (toggling the hold gate on a test library) and
 * buildAnalyzedBook (minimal AnalyzedBook factory). Both helpers are stable
 * across the two test files; keep them here rather than duplicating.
 */

/**
 * Toggles the `inbox_enabled` gate on the given [libraryId] row — used by tests that run inside
 * [com.calypsan.listenup.server.testing.withSqlDatabase].
 */
internal fun ListenUpDatabase.setInboxEnabled(
    libraryId: String,
    enabled: Boolean,
) {
    librariesQueries.setInboxEnabled(
        inbox_enabled = if (enabled) 1L else 0L,
        revision = 1L,
        updated_at = System.currentTimeMillis(),
        client_op_id = null,
        id = libraryId,
    )
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
