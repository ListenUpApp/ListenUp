package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlin.time.Clock
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Where a live book can be known to sit while an inode match is judged: [pathsInThisIngest] are the
 * paths in the batch being written, and [folderRoot] (null when unknown) is the directory the disk is
 * asked about for the paths a batch cannot speak for.
 */
internal data class MoveOccupancy(
    val pathsInThisIngest: Set<String>,
    val folderRoot: String?,
)

/**
 * Path resolution, inode move detection, and DB-side path-move operations for [BookRepository].
 *
 * Consolidates the disk-occupancy checks shared by single-book and batched scan ingest
 * ([findTrustedMove], [trustedMoveOnDisk]), the post-walk move exemption used by the full-scan
 * absence sweep ([movedAfterTheWalk]), and the organizer's `root_rel_path` update
 * ([moveRootRelPath]).
 */
internal class BookLocationManager(
    private val db: ListenUpDatabase,
    private val clock: Clock,
    private val bookFinder: BookFinder,
    private val sync: BookSyncBridge,
) {
    /**
     * The existing book a lone [analyzed] book may be the moved folder of, or null. A single ingest
     * sees one book, so "the matched book's own path is also being ingested" cannot be read from the
     * batch; instead a candidate whose recorded folder is still a directory on disk is occupied by
     * that book and so cannot be what moved here.
     */
    suspend fun findTrustedMove(
        folderId: FolderId,
        analyzed: AnalyzedBook,
    ): BookId? {
        val identityFile = analyzed.candidate.identityFile() ?: return null
        val inode = identityFile.inode ?: return null
        val candidates = bookFinder.findInodeCandidates(folderId, listOf(inode))[inode].orEmpty()
        if (candidates.isEmpty()) return null
        val occupancy = MoveOccupancy(setOf(analyzed.candidate.rootRelPath), folderRootPath(folderId))
        return trustedMoveOnDisk(candidates, identityFile.size, occupancy)
    }

    /**
     * [trustedMove] with the disk consulted: a candidate that passes the size witness but whose
     * recorded folder is still a directory under [MoveOccupancy.folderRoot] is occupied by that book,
     * so it cannot be what moved. The batch alone cannot say so — a lone or incremental ingest carries
     * only the changed books, never the unchanged original. The disk is probed only for size-corroborated
     * candidates, so an ingest with no inode matches does no file I/O. An unknown folder root probes
     * nothing.
     */
    fun trustedMoveOnDisk(
        candidates: List<BookFinder.InodeCandidate>,
        identityFileSize: Long,
        occupancy: MoveOccupancy,
    ): BookId? {
        val folderRoot = occupancy.folderRoot
        val occupiedOnDisk =
            candidates
                .filter { identityFileSize in it.audioFileSizes }
                .map { it.rootRelPath }
                .filter { relPath -> folderRoot != null && isDirectoryOnDisk(folderRoot, relPath) }
        return trustedMove(candidates, identityFileSize, occupancy.pathsInThisIngest + occupiedOnDisk)
    }

    suspend fun folderRootPath(folderId: FolderId): String? =
        suspendTransaction(db) {
            db.libraryFoldersQueries
                .selectById(folderId.value)
                .executeAsOneOrNull()
                ?.root_path
        }

    /**
     * True when the walk saw [bookId] under an older path and the organizer has moved it since: the
     * walk recorded the book's identity file (same folder, same inode) at a path that file has left,
     * and the book's first audio file is on disk where its row now says it lives. A book the walk
     * genuinely missed (deleted, ignored, or regrouped into another book) fails one of those tests and
     * is swept as before.
     */
    suspend fun movedAfterTheWalk(
        bookId: String,
        folderId: FolderId,
        rootRelPath: String,
        walkedIdentityFiles: Map<FolderScopedInode, String>,
    ): Boolean {
        if (walkedIdentityFiles.isEmpty()) return false
        val stored =
            suspendTransaction<Pair<Long, String>?>(db) {
                val inode =
                    db.booksQueries
                        .selectById(bookId)
                        .executeAsOneOrNull()
                        ?.inode
                val filename = db.bookAudioFilesQueries.selectPrimaryFilenameForBook(bookId).executeAsOneOrNull()
                if (inode == null || filename == null) null else inode to filename
            } ?: return false
        val (inode, filename) = stored
        val walkedAt = walkedIdentityFiles[FolderScopedInode(folderId, inode)] ?: return false
        val folderRoot = folderRootPath(folderId) ?: return false
        return !SystemFileSystem.exists(Path(walkedAt)) &&
            SystemFileSystem.exists(Path(folderRoot, rootRelPath, filename))
    }

    /**
     * Rewrites [id]'s `root_rel_path` — the organizer's DB-side move step (see
     * `com.calypsan.listenup.server.organize.MoveManifestExecutor`). Called only AFTER the
     * corresponding files have already landed at their new on-disk location via the
     * `LibraryWriteBroker` — this never touches the filesystem itself, and never touches any
     * content column beyond the path pair, so a rescan's tombstone sweep and the book's identity
     * are otherwise untouched. Bumps revision and publishes [com.calypsan.listenup.api.sync.SyncEvent.Updated]
     * like [BookRepository.touchRevision], since `rootRelPath` is part of the syncable
     * [com.calypsan.listenup.api.sync.BookSyncPayload].
     *
     * [renamedAudioFilename], when non-null, renames the book's SINGLE audio file row in the same
     * transaction — the organizer aligns a single-file book's filename with its folder, and the two
     * writes must land together or a lookup resolves `<root>/<newPath>/<oldName>`, which is nothing.
     * Only ever passed for a book the plan established has exactly one audio file.
     */
    suspend fun moveRootRelPath(
        id: BookId,
        newRootRelPath: String,
        renamedAudioFilename: String? = null,
    ): AppResult<Unit> {
        val idStr = id.value
        return suspendTransaction(db) {
            val rev = sync.nextRevision()
            val now = clock.now().toEpochMilliseconds()
            db.booksQueries.updateRootRelPath(
                root_rel_path = newRootRelPath,
                revision = rev,
                updated_at = now,
                id = idStr,
            )
            // Read `changes()` before any further statement — SQLite reports the most recent one.
            val bookRowsUpdated = db.booksQueries.changes().executeAsOne()
            if (bookRowsUpdated == 0L) {
                AppResult.Failure(SyncError.NotFound(domain = sync.domainName, entityId = idStr))
            } else {
                if (renamedAudioFilename != null) {
                    db.bookAudioFilesQueries.updateFilenameForSingleFileBook(
                        filename = renamedAudioFilename,
                        book_id = idStr,
                    )
                }
                with(sync) { emitBookUpdatedAfterCommit(idStr = idStr, rev = rev, now = now) }
                AppResult.Success(Unit)
            }
        }
    }

    private fun isDirectoryOnDisk(
        folderRoot: String,
        rootRelPath: String,
    ): Boolean = SystemFileSystem.metadataOrNull(Path(folderRoot, rootRelPath))?.isDirectory == true

    /**
     * The existing book this inode match may be trusted to mean, or null. An inode number is reused
     * after a delete and shared by hardlinks, so it is believed only when the file is the same size
     * as one recorded for that book and the book's own path is not occupied — [pathsInThisIngest]
     * carries every path the caller already knows is held by a live book, in this batch or on disk
     * (see [trustedMoveOnDisk]).
     * Two stored books that both corroborate are ambiguous, so neither is believed.
     */
    private fun trustedMove(
        candidates: List<BookFinder.InodeCandidate>,
        identityFileSize: Long,
        pathsInThisIngest: Set<String>,
    ): BookId? =
        candidates
            .singleOrNull { identityFileSize in it.audioFileSizes && it.rootRelPath !in pathsInThisIngest }
            ?.bookId
}
