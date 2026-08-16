package com.calypsan.listenup.server.transcode

import com.calypsan.listenup.server.io.deleteRecursively
import com.calypsan.listenup.server.io.fileIoDispatcher
import com.calypsan.listenup.server.io.listRegularFilesRecursively
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * On-disk home for transcoded segments, laid out so a whole file's worth can be evicted in one step:
 *
 * ```
 * $LISTENUP_HOME/transcode/{bookId}/{fileId}/seg/aac-64k/segNNNNN.aac
 * ```
 *
 * ⚠️ **Not under `covers/` or any directory `BackupArchive` walks** — transcoded audio is derivable
 * bytes and must never inflate a backup. `$LISTENUP_HOME/transcode/` is its own root for that reason.
 *
 * The profile segment of the path (`aac-64k`) means a future bitrate change lands beside the old one
 * rather than serving a mix of two.
 */
class SegmentCache(
    private val baseDir: Path,
    private val profile: String = DEFAULT_PROFILE,
) {
    /** Directory holding one file's segments. */
    fun fileDir(
        bookId: String,
        fileId: String,
    ): Path = Path(baseDir, "$bookId/$fileId/seg/$profile")

    /** Absolute path of segment [index]. */
    fun segmentPath(
        bookId: String,
        fileId: String,
        index: Int,
    ): Path = Path(fileDir(bookId, fileId), "seg${index.toString().padStart(INDEX_DIGITS, '0')}.aac")

    /** Creates the directory FFmpeg will write into. */
    suspend fun prepareDir(
        bookId: String,
        fileId: String,
    ) {
        withContext(fileIoDispatcher) { SystemFileSystem.createDirectories(fileDir(bookId, fileId)) }
    }

    /** True when segment [index] is fully on disk. */
    fun has(
        bookId: String,
        fileId: String,
        index: Int,
    ): Boolean = SystemFileSystem.metadataOrNull(segmentPath(bookId, fileId, index))?.isRegularFile == true

    /** Total bytes across the whole cache. */
    suspend fun totalBytes(): Long =
        withContext(fileIoDispatcher) {
            listRegularFilesRecursively(baseDir).sumOf { SystemFileSystem.metadataOrNull(it)?.size ?: 0L }
        }

    /** Removes every segment for one file. Idempotent. */
    suspend fun evict(
        bookId: String,
        fileId: String,
    ) {
        withContext(fileIoDispatcher) { deleteRecursively(Path(baseDir, "$bookId/$fileId")) }
    }

    private companion object {
        /** Encoder profile the segments under a path were produced by. */
        const val DEFAULT_PROFILE = "aac-64k"

        /** Zero-padding width of a segment index — 5 digits covers 99,999 segments, ~11 days of audio. */
        const val INDEX_DIGITS = 5
    }
}
