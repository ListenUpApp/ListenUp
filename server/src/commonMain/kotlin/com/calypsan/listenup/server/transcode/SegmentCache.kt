package com.calypsan.listenup.server.transcode

import com.calypsan.listenup.server.io.deleteRecursively
import com.calypsan.listenup.server.io.fileIoDispatcher
import com.calypsan.listenup.server.io.listRegularFilesRecursively
import com.calypsan.listenup.server.io.readText
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

    /**
     * FFmpeg `-f segment` output pattern for one file — the printf form of [segmentPath].
     *
     * Derived from the same [INDEX_DIGITS] so the names FFmpeg writes and the names
     * [segmentPath] looks for cannot drift apart.
     */
    fun segmentPattern(
        bookId: String,
        fileId: String,
    ): String = Path(fileDir(bookId, fileId), "seg%0${INDEX_DIGITS}d.aac").toString()

    /**
     * Where one encoder run records the segments it has **finished** writing.
     *
     * One file per run rather than one per file, because a run seeked elsewhere would otherwise
     * truncate the record of everything an earlier run completed.
     */
    fun runListPath(
        bookId: String,
        fileId: String,
        startSegment: Int,
    ): Path = Path(runsDir(bookId, fileId), "from${startSegment.toString().padStart(INDEX_DIGITS, '0')}.list")

    /** Creates the directories FFmpeg will write into — segments, and the run list beside them. */
    suspend fun prepareDir(
        bookId: String,
        fileId: String,
    ) {
        withContext(fileIoDispatcher) {
            SystemFileSystem.createDirectories(fileDir(bookId, fileId))
            SystemFileSystem.createDirectories(runsDir(bookId, fileId))
        }
    }

    /** True when segment [index] exists on disk — which is **not** the same as being finished. */
    fun has(
        bookId: String,
        fileId: String,
        index: Int,
    ): Boolean = SystemFileSystem.metadataOrNull(segmentPath(bookId, fileId, index))?.isRegularFile == true

    /**
     * True when segment [index] is on disk **and done being written**.
     *
     * ⛔ [has] is not enough, and the difference is audible. FFmpeg's segment muxer creates a file
     * the moment it *opens* it, so a segment exists for as long as it takes to encode before it
     * holds all its frames. Answering a player from [has] hands it a truncated segment — at the
     * start of a book, that is a decode error on the listener's first tap.
     *
     * Two signals, cheapest first. A successor on disk means the muxer closed this one and moved
     * on, which covers every segment but the last of a run and costs one `stat`. The last segment
     * of a run has no successor, so its proof is the run list FFmpeg writes as each segment
     * completes — read only when the cheap check has already come back false.
     */
    suspend fun isComplete(
        bookId: String,
        fileId: String,
        index: Int,
    ): Boolean {
        if (!has(bookId, fileId, index)) return false
        if (has(bookId, fileId, index + 1)) return true
        return withContext(fileIoDispatcher) { isListedAsFinished(bookId, fileId, index) }
    }

    private fun isListedAsFinished(
        bookId: String,
        fileId: String,
        index: Int,
    ): Boolean {
        val runsDir = runsDir(bookId, fileId)
        if (SystemFileSystem.metadataOrNull(runsDir)?.isDirectory != true) return false
        val name = segmentPath(bookId, fileId, index).name
        return SystemFileSystem.list(runsDir).any { runList ->
            runList.readText().lineSequence().any { it.trim() == name }
        }
    }

    private fun runsDir(
        bookId: String,
        fileId: String,
    ): Path = Path(fileDir(bookId, fileId), RUNS_DIR)

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

        /** Holds the per-run completion lists, out of the way of the segments themselves. */
        const val RUNS_DIR = "runs"
    }
}
