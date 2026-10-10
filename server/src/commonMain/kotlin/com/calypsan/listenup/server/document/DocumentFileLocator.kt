package com.calypsan.listenup.server.document

import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.io.fileIoDispatcher
import com.calypsan.listenup.server.librarywrite.isRegularFileInsideAnyRoot
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Where one supplementary document lives on disk, plus the metadata the document
 * route needs to serve it.
 *
 * [path] is a [kotlinx.io.files.Path]; callers that need a [java.io.File] (e.g. Ktor's
 * `respondFile`) convert at the boundary via `File(path.toString())`. [hash] is the
 * stored SHA-256 hex digest, used for the response `ETag`.
 */
data class DocumentLocation(
    val path: Path,
    val format: String,
    val sizeBytes: Long,
    val hash: String,
)

/**
 * Resolves `(bookId, docId)` to an on-disk document location.
 *
 * The `book_documents` table stores no absolute path; the absolute path is
 * `<library rootPath>/<book rootRelPath>/<filename>` — the same three-table join
 * [com.calypsan.listenup.server.audio.AudioFileLocator] uses. A document's `filename`
 * is itself book-root-relative and may carry a subfolder (e.g. `extras/map.pdf`),
 * which [Path] composition preserves.
 */
class DocumentFileLocator(
    private val sql: ListenUpDatabase,
) {
    /**
     * Returns the [DocumentLocation] for the given `(bookId, docId)` pair, or null
     * when the document row or its parent book/library row is absent. A file that is
     * not on disk is still returned — the caller handles a missing file as 404 — but a
     * path that exists and is not a regular file inside a library folder (a link out of
     * the library, a FIFO) yields null.
     */
    suspend fun locate(
        bookId: String,
        docId: String,
    ): DocumentLocation? {
        val (location, roots) = lookup(bookId, docId) ?: return null
        return location.ifServable(roots)
    }

    private suspend fun lookup(
        bookId: String,
        docId: String,
    ): Pair<DocumentLocation, List<Path>>? =
        suspendTransaction(sql) {
            val fileRow =
                sql.bookDocumentsQueries
                    .selectFileForBook(book_id = bookId, id = docId)
                    .executeAsOneOrNull() ?: return@suspendTransaction null

            val bookRow =
                sql.booksQueries
                    .selectById(bookId)
                    .executeAsOneOrNull() ?: return@suspendTransaction null

            // Resolve the folder root path via the book's folder_id column.
            val folderRoot =
                sql.libraryFoldersQueries
                    .selectById(bookRow.folder_id)
                    .executeAsOneOrNull()
                    ?.root_path ?: return@suspendTransaction null

            val location =
                DocumentLocation(
                    path = Path(folderRoot, bookRow.root_rel_path, fileRow.filename),
                    format = fileRow.format,
                    sizeBytes = fileRow.size,
                    hash = fileRow.hash,
                )
            // Read the live roots in the same transaction so the containment check below judges
            // the file against the folders that existed when the row was resolved.
            location to
                sql.libraryFoldersQueries
                    .selectLiveRootPaths()
                    .executeAsList()
                    .map { Path(it) }
        }

    /** Missing is the caller's 404. Present but not a contained regular file is refused here. */
    private suspend fun DocumentLocation.ifServable(roots: List<Path>): DocumentLocation? =
        withContext(fileIoDispatcher) {
            this@ifServable.takeIf { !SystemFileSystem.exists(it.path) || isRegularFileInsideAnyRoot(it.path, roots) }
        }
}
