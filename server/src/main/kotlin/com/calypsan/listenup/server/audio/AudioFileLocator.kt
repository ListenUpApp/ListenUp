package com.calypsan.listenup.server.audio

import com.calypsan.listenup.server.db.BookAudioFileTable
import com.calypsan.listenup.server.db.BookTable
import com.calypsan.listenup.server.db.LibraryTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import java.nio.file.Path

/**
 * Where one audio file lives on disk, plus the metadata the audio route needs
 * to serve it correctly.
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
class AudioFileLocator(private val db: Database) {

    /**
     * Returns the [AudioFileLocation] for the given `(bookId, fileId)` pair,
     * or null when either the audio file row or its parent book/library row
     * is absent. Does not check whether the file exists on disk — the caller
     * handles a missing file as 404.
     */
    suspend fun locate(bookId: String, fileId: String): AudioFileLocation? =
        suspendTransaction(db) {
            val fileRow =
                BookAudioFileTable
                    .selectAll()
                    .where {
                        (BookAudioFileTable.bookId eq bookId) and
                            (BookAudioFileTable.id eq fileId)
                    }
                    .firstOrNull() ?: return@suspendTransaction null

            val filename = fileRow[BookAudioFileTable.filename]
            val format = fileRow[BookAudioFileTable.format]
            val size = fileRow[BookAudioFileTable.size]

            val bookRow =
                BookTable
                    .selectAll()
                    .where { BookTable.id eq bookId }
                    .firstOrNull() ?: return@suspendTransaction null

            val rootRelPath = bookRow[BookTable.rootRelPath]
            val libraryId = bookRow[BookTable.libraryId]

            val libraryRoot =
                LibraryTable
                    .selectAll()
                    .where { LibraryTable.id eq libraryId }
                    .firstOrNull()
                    ?.get(LibraryTable.rootPath)
                    ?: return@suspendTransaction null

            AudioFileLocation(
                path = Path.of(libraryRoot, rootRelPath, filename),
                format = format,
                sizeBytes = size,
            )
        }
}
