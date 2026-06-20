package com.calypsan.listenup.server.audio

import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlinx.io.files.Path

/**
 * Where one audio file lives on disk, plus the metadata the audio route needs
 * to serve it correctly.
 *
 * [path] is a [kotlinx.io.files.Path]; callers that need a [java.io.File]
 * (e.g. Ktor's `respondFile`) convert at the boundary via `File(path.toString())`.
 */
data class AudioFileLocation(
    val path: Path,
    val format: String,
    val sizeBytes: Long,
)

/**
 * Resolves `(bookId, fileId)` to an on-disk audio file location.
 *
 * The `book_audio_files` table stores no absolute path; the absolute path is
 * `<library rootPath>/<book rootRelPath>/<filename>` — the same three-table
 * join that `BookRepository.coverInfo` uses.
 */
class AudioFileLocator(
    private val sql: ListenUpDatabase,
) {
    /**
     * Returns the [AudioFileLocation] for the given `(bookId, fileId)` pair,
     * or null when either the audio file row or its parent book/library row
     * is absent. Does not check whether the file exists on disk — the caller
     * handles a missing file as 404.
     */
    suspend fun locate(
        bookId: String,
        fileId: String,
    ): AudioFileLocation? =
        suspendTransaction(sql) {
            val fileRow =
                sql.bookAudioFilesQueries
                    .selectFileForBook(book_id = bookId, id = fileId)
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

            AudioFileLocation(
                path = Path(folderRoot, bookRow.root_rel_path, fileRow.filename),
                format = fileRow.format,
                sizeBytes = fileRow.size,
            )
        }
}
