package com.calypsan.listenup.server.document

import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlinx.io.files.Path

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
     * when the document row or its parent book/library row is absent. Does not check
     * whether the file exists on disk — the caller handles a missing file as 404.
     */
    suspend fun locate(
        bookId: String,
        docId: String,
    ): DocumentLocation? =
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

            DocumentLocation(
                path = Path(folderRoot, bookRow.root_rel_path, fileRow.filename),
                format = fileRow.format,
                sizeBytes = fileRow.size,
                hash = fileRow.hash,
            )
        }
}
