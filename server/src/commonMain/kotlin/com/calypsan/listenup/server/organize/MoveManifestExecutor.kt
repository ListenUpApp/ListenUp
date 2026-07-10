package com.calypsan.listenup.server.organize

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.librarywrite.LibraryWriteBroker
import com.calypsan.listenup.server.librarywrite.WriteManifest
import com.calypsan.listenup.server.librarywrite.WriteOp
import com.calypsan.listenup.server.services.BookRepository

/**
 * Executes one [MovePlanEntry]: moves every file in [MovePlanEntry.files] via
 * [LibraryWriteBroker] (journaled, watcher-suppressed, crash-resumable), deletes the now-empty
 * source folder, and — only once the broker reports the manifest fully applied — rewrites the
 * book's `root_rel_path` in a single narrow DB transaction ([BookRepository.moveRootRelPath]).
 *
 * The scanner's disk-diff is never involved: because the DB write happens in the same logical
 * step as the disk move (not via a rescan), the book's identity — positions, collections,
 * provenance — is untouched by design (spec §5). [execute] is safe to call again on the same
 * [MovePlanEntry] after a prior failure: every [WriteOp] the manifest is built from is
 * individually idempotent to re-apply (see their KDoc on [WriteOp]), so a retry naturally skips
 * whatever already landed and finishes the rest. The manifest's `opId` is deterministic per book
 * (not re-minted per call), so a retry reuses — rather than orphans — the previous attempt's
 * journal entry.
 *
 * Crash window: if the process dies between the broker reporting success and the DB write
 * landing, the files are already at their new location but `books.root_rel_path` still points at
 * the old one. This window is narrow (a fraction of a single transaction) and **self-heals at the
 * next scan via the Differ inode fallback**: a same-filesystem move preserves inode, so when the
 * scanner's path match misses, the `(folder_id, inode)` lookup — the existing moved-book
 * detection in `BookRepository.resolveOrInsert` — re-links the book to its new path under its
 * original id, no operator intervention required.
 */
class MoveManifestExecutor(
    private val broker: LibraryWriteBroker,
    private val bookRepository: BookRepository,
) {
    /** Moves [entry]'s files on disk, then rewrites the book's stored path. See the class KDoc for the ordering guarantee. */
    suspend fun execute(entry: MovePlanEntry): AppResult<Unit> {
        val moveResult = broker.executeManifest(manifestFor(entry))
        if (moveResult is AppResult.Failure) return moveResult

        return bookRepository.moveRootRelPath(BookId(entry.bookId), entry.toRootRelPath)
    }

    private fun manifestFor(entry: MovePlanEntry): WriteManifest =
        WriteManifest(
            opId = "organize-move-${entry.bookId}",
            ops =
                buildList {
                    add(WriteOp.EnsureDir(entry.toDir))
                    entry.files.forEach { add(WriteOp.MoveFile(it.from, it.to)) }
                    add(WriteOp.DeleteDirIfEmpty(entry.fromDir))
                },
        )
}
