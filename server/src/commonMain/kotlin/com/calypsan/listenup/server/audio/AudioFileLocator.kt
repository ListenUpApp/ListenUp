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
 * What transcoding needs to know about one source file, beyond where it lives.
 *
 * [sampleRate] is nullable because a real library has files with none recorded (257 of 1,455 rows
 * in the reference library). `HlsPlaylist` falls back rather than failing — but the fallback is a
 * *guess*, and encoding must still never resample, or the declared timeline stops describing the
 * bytes actually written.
 */
data class TranscodeSourceInfo(
    val durationMs: Long,
    val sampleRate: Int?,
    val codec: String,
    val codecProfile: String?,
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

    /**
     * Returns the [TranscodeSourceInfo] for the given `(bookId, fileId)` pair, or null when the
     * audio file row is absent. The caller treats null as 404, exactly as [locate] does.
     */
    suspend fun transcodeInfo(
        bookId: String,
        fileId: String,
    ): TranscodeSourceInfo? =
        suspendTransaction(sql) {
            sql.bookAudioFilesQueries
                .selectTranscodeInfoForBook(book_id = bookId, id = fileId)
                .executeAsOneOrNull()
                ?.let {
                    TranscodeSourceInfo(
                        durationMs = it.duration,
                        sampleRate = it.sampleRate?.toInt(),
                        codec = it.codec,
                        codecProfile = it.codecProfile,
                    )
                }
        }
}
