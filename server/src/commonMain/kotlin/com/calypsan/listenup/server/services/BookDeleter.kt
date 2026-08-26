package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.error.LibraryWriteError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.failure
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.io.isUnder
import com.calypsan.listenup.server.librarywrite.LibraryWriteBroker
import com.calypsan.listenup.server.librarywrite.WriteManifest
import com.calypsan.listenup.server.librarywrite.WriteOp
import com.calypsan.listenup.server.librarywrite.resolvedForContainment
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.sidecar.SidecarWriteStateRepository
import kotlinx.io.files.Path

private val logger = loggerFor<BookDeleter>()

/**
 * Removes a book from the library **and from the disk** — its whole directory, every file in it,
 * and any now-empty author / series directories left standing above it.
 *
 * This is the most destructive operation the server performs, and the only one that deletes bytes
 * the caller never enumerated. 66 book folders in a real 1,198-book library carry bonus PDFs;
 * deleting only the audio would strand them in directories that no longer correspond to anything
 * the app can show, so the folder goes as a unit — and that is precisely why the guards below are
 * the feature rather than paperwork around it.
 *
 * **Three refusals, all re-checked at delete time.** Each was measured at zero against that live
 * library:
 * 1. **Another live book in (or under) the same directory.** Refused with
 *    [BookError.FolderNotExclusive], naming the blocking book; a silent skip would delete the
 *    requested book's files and orphan the other book's row. Two books at the *same*
 *    `(folder_id, root_rel_path)` are in fact impossible — `idx_book_natural_key` is UNIQUE — but
 *    that index is the whole of the schema's protection, and it does not reach the two shapes that
 *    matter here: a book **nested** under another's directory, and two library **folders**
 *    overlapping on disk so that distinct stored pairs resolve to one absolute directory. Which is
 *    why this compares resolved absolute paths across every live book on the server, not stored
 *    pairs within one library.
 * 2. **The directory is a library folder root.** A `root_rel_path` that came out empty resolves
 *    straight to the root, and deleting it would erase the library. Refused before anything is
 *    touched — and refused a second time inside [LibraryWriteBroker], which cannot be reached
 *    around.
 * 3. **Containment.** The broker's own guard, on resolved paths: a `..` escape, or a symlinked book
 *    directory whose target leaves the library. A link pointing *inside* the library slips past
 *    containment by construction — it resolves to a legitimate path — so [WriteOp.DeleteDir]
 *    refuses a symlinked directory outright as well. `DeleteDirOpTest` pins both cases.
 *
 * **Ordering is deliberate: files first, then the tombstone.** A crash between them leaves the
 * files gone and the book row live — which the next scan reconciles, because the sweep tombstones
 * every live book it no longer finds on disk. The reverse order would leave a tombstoned row over
 * files nothing in the app can reach any more, and no pass converges that.
 */
class BookDeleter(
    private val sql: ListenUpDatabase,
    private val bookRepository: BookRepository,
    private val broker: LibraryWriteBroker,
    private val sidecarWriteState: SidecarWriteStateRepository? = null,
) {
    /**
     * Deletes [id]'s directory and then tombstones the book. Returns the typed refusal — with
     * **nothing removed** — when any guard trips, and [BookError.NotFound] when the book is absent
     * or already tombstoned (its files may still be on disk from an interrupted earlier attempt,
     * so reporting success here would be a lie).
     */
    suspend fun delete(id: BookId): AppResult<Unit> {
        val book = bookRepository.findById(id) ?: return bookNotFound(id)
        if (book.deletedAt != null) return bookNotFound(id)

        val folderRoot =
            liveFolderRoot(book.folderId.value)
                ?: return failure(
                    LibraryWriteError.Unavailable(
                        debugInfo = "no live library folder ${book.folderId.value} for book ${id.value}",
                    ),
                )
        val bookDir = Path(folderRoot, book.rootRelPath)

        refuseIfLibraryRoot(bookDir, id, book.rootRelPath)?.let { return it }
        refuseIfSharedWithAnotherBook(bookDir, id)?.let { return it }

        val deleted =
            broker.executeManifest(
                // Deterministic per book, matching MoveManifestExecutor: a retry after a failure
                // reuses the previous attempt's journal entry rather than orphaning it.
                WriteManifest(
                    opId = "delete-book-${id.value}",
                    ops = listOf(WriteOp.DeleteDir(bookDir)) + ancestorPruneOps(bookDir, book.rootRelPath),
                    // A delete the admin was told had failed must not run itself later. See the
                    // property's KDoc — the book row is untouched by a partial delete, so the next
                    // scan reconciles disk reality either way.
                    resumeAfterReportedFailure = false,
                ),
            )
        if (deleted is AppResult.Failure) {
            logger.warn { "delete of book ${id.value} refused or failed at the broker: ${deleted.error.debugInfo}" }
            return deleted
        }

        val tombstoned = bookRepository.softDelete(id, clientOpId = null)
        if (tombstoned is AppResult.Failure) return tombstoned

        // The registry marks `sidecar_write_state` a HARD_CHILD — inert under a tombstoned parent,
        // because the recorded hash still describes a `listenup.json` that is still on disk. Here it
        // does not: the file went with the directory. The read-side skip check is content-keyed and
        // global (`existsByContentHash`), so leaving the row would let a later identical sidecar
        // elsewhere be mistaken for one of our own writes and skipped.
        sidecarWriteState?.deleteForBook(id.value)
        return AppResult.Success(Unit)
    }

    /**
     * The empty-directory cleanup for everything between the book and its library folder root —
     * one [WriteOp.DeleteDirIfEmpty] per level, deepest first.
     *
     * Deleting `Aleron Kong/Chaos Seeds/Book 1` leaves two directories behind that now describe
     * nothing; the walk removes the series folder, finds the author folder empty too, and removes
     * that. It needs no knowledge of what a "series folder" is — the shape of the path is the only
     * input, so any hierarchy the organizer can produce is pruned by the same code.
     *
     * Nothing here decides *whether* a directory should go: [WriteOp.DeleteDirIfEmpty] is
     * best-effort, so the first ancestor still holding a sibling book (or a stray file the user
     * put there) stops the chain on its own, and the ops above it become no-ops.
     *
     * The walk is bounded by **segment count, not path comparison**: a book stored at a
     * `root_rel_path` of N segments has exactly N-1 ancestors below the root, so the root is
     * unreachable by construction rather than by a string compare that a trailing slash could
     * defeat. The broker refuses a root-targeted op regardless — belt and braces on the one
     * mistake that would take the library with it.
     */
    private fun ancestorPruneOps(
        bookDir: Path,
        rootRelPath: String,
    ): List<WriteOp> {
        val depth = rootRelPath.split('/').count { it.isNotEmpty() }
        val ops = mutableListOf<WriteOp>()
        var dir = bookDir.parent
        repeat(maxOf(depth - 1, 0)) {
            val current = dir ?: return ops
            ops.add(WriteOp.DeleteDirIfEmpty(current))
            dir = current.parent
        }
        return ops
    }

    /** The live `library_folders.root_path` for [folderId], or null when the folder is gone. */
    private suspend fun liveFolderRoot(folderId: String): String? =
        suspendTransaction(sql) {
            sql.libraryFoldersQueries
                .selectById(folderId)
                .executeAsOneOrNull()
                ?.takeIf { it.deleted_at == null }
                ?.root_path
        }

    /**
     * Guard 2 — [bookDir] must not itself be a library folder root. Compared on *resolved* paths so
     * an empty, `.`, `/` or `..`-bearing `root_rel_path` cannot dress the root up as something else.
     */
    private suspend fun refuseIfLibraryRoot(
        bookDir: Path,
        id: BookId,
        rootRelPath: String,
    ): AppResult.Failure? {
        val resolved = resolvedForContainment(bookDir)
        val isRoot =
            suspendTransaction(sql) {
                sql.libraryFoldersQueries.selectLiveRootPaths().executeAsList()
            }.any { resolvedForContainment(Path(it)) == resolved }
        if (!isRoot) return null
        logger.warn { "refused deleting book ${id.value}: its directory IS a library folder root ($bookDir)" }
        return AppResult.Failure(
            LibraryWriteError.ProtectedPath(
                debugInfo = "book ${id.value} rootRelPath='$rootRelPath' resolves to the library folder root $bookDir",
            ),
        )
    }

    /**
     * Guard 1 — no OTHER live book may sit in [bookDir] or anywhere beneath it.
     *
     * Compares resolved absolute directories rather than `(folder_id, root_rel_path)` pairs, and
     * sweeps every live book on the server rather than only this library's: two library folders can
     * be nested on disk, and a symlinked directory can put two books in one place without their
     * stored paths ever looking alike.
     */
    private suspend fun refuseIfSharedWithAnotherBook(
        bookDir: Path,
        id: BookId,
    ): AppResult.Failure? {
        val target = resolvedForContainment(bookDir)
        val rootsByFolderId =
            suspendTransaction(sql) {
                sql.libraryFoldersQueries
                    .selectLiveFolderRoots()
                    .executeAsList()
                    .associate { it.id to it.root_path }
            }
        val others =
            suspendTransaction(sql) {
                sql.booksQueries.selectLiveIdsTitlesAndPaths().executeAsList()
            }.filterNot { it.id == id.value }
        for (other in others) {
            val otherRoot = rootsByFolderId[other.folder_id] ?: continue
            val otherDir = resolvedForContainment(Path(otherRoot, other.root_rel_path))
            // BOTH directions. The obvious one is another book beneath the target — delete the
            // target and its files go too. The other is the target sitting beneath ANOTHER book's
            // directory: deleting it then removes a subtree of a live book, whose row is left
            // pointing at missing files until the next scan tombstones it. One book quietly
            // destroying another is the same fault whichever way the nesting runs, and checking
            // only downward caught only half of it.
            val overlaps = otherDir.isUnder(target) || target.isUnder(otherDir)
            if (!overlaps) continue
            logger.warn {
                "refused deleting book ${id.value}: book ${other.id} shares this directory tree with $bookDir"
            }
            return AppResult.Failure(
                BookError.FolderNotExclusive(
                    otherBookId = other.id,
                    otherBookTitle = other.title,
                    debugInfo = "book ${id.value} at $bookDir also holds book ${other.id} at ${other.root_rel_path}",
                ),
            )
        }
        return null
    }

    private fun bookNotFound(id: BookId): AppResult.Failure =
        AppResult.Failure(BookError.NotFound(debugInfo = "bookId=${id.value}"))
}
