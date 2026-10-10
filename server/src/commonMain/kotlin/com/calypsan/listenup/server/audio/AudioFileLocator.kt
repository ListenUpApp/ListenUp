package com.calypsan.listenup.server.audio

import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.io.fileIoDispatcher
import com.calypsan.listenup.server.librarywrite.isRegularFileInsideAnyRoot
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

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
    /** Channel count, or null when unrecorded — raw PCM between pipeline stages needs it exact. */
    val channels: Int?,
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
     * is absent. A file that is not on disk is still returned — the caller
     * handles a missing file as 404 — but a path that exists and is not a regular
     * file inside a library folder (a link out of the library, a FIFO) yields null.
     */
    suspend fun locate(
        bookId: String,
        fileId: String,
    ): AudioFileLocation? {
        val (location, roots) = lookup(bookId, fileId) ?: return null
        return location.ifServable(roots)
    }

    private suspend fun lookup(
        bookId: String,
        fileId: String,
    ): Pair<AudioFileLocation, List<Path>>? =
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

            val location =
                AudioFileLocation(
                    path = Path(folderRoot, bookRow.root_rel_path, fileRow.filename),
                    format = fileRow.format,
                    sizeBytes = fileRow.size,
                )
            // Read the live roots in the same transaction so the containment check below judges
            // the file against the folders that existed when the row was resolved.
            location to sql.libraryFoldersQueries.selectLiveRootPaths().executeAsList().map { Path(it) }
        }

    /** Missing is the caller's 404. Present but not a contained regular file is refused here. */
    private suspend fun AudioFileLocation.ifServable(roots: List<Path>): AudioFileLocation? =
        withContext(fileIoDispatcher) {
            this@ifServable.takeIf { !SystemFileSystem.exists(it.path) || isRegularFileInsideAnyRoot(it.path, roots) }
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
                ?.let { row ->
                    TranscodeSourceInfo(
                        durationMs = row.duration,
                        sampleRate = row.sampleRate?.toInt(),
                        codec = row.codec,
                        codecProfile = row.codecProfile,
                        channels = row.channels?.toInt(),
                    )
                }
        }
}
