package com.calypsan.listenup.server.transcode

import com.calypsan.listenup.server.io.deleteRecursively
import com.calypsan.listenup.server.io.fileIoDispatcher
import com.calypsan.listenup.server.io.listRegularFilesRecursively
import com.calypsan.listenup.server.io.readBytes
import com.calypsan.listenup.server.io.readText
import com.calypsan.listenup.server.io.statFile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * One `{bookId}/{fileId}` directory as the eviction sweep sees it: how many bytes it holds and when
 * anything in it was last written.
 *
 * Recency is approximated by **write** time — the newest segment or run-list mtime, or the
 * directory's own mtime for one just prepared and not yet written into. A listener replaying
 * fully-cached segments does not refresh it; the sweep protects the files that matter most (a
 * running session's) by name instead, not by timestamp.
 */
internal data class CachedFile(
    val bookId: String,
    val fileId: String,
    val bytes: Long,
    val lastTouchedMs: Long,
)

/**
 * On-disk home for transcoded segments, laid out so a whole file's worth can be evicted in one step:
 *
 * ```
 * $LISTENUP_HOME/transcode/{bookId}/{fileId}/seg/aac-64k/segNNNNN.aac          finished segments only
 * $LISTENUP_HOME/transcode/{bookId}/{fileId}/seg/aac-64k/runs/fromSSSSS-N/      one encoder run's own output
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
    private val readFile: (Path) -> ByteArray = { it.readBytes() },
) {
    private val lock = SynchronizedObject()
    private val layoutChecked = HashSet<String>()

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
     * The private directory of one encoder run: the [run]th run started at [startSegment]. A run writes
     * its segments and its list here, never into [fileDir], so the cache directory only ever holds
     * segments some run finished ([promoteFinished]).
     *
     * ⛔ A run of its own, not one per start segment: a seek back to where an earlier run began would
     * otherwise rewrite, under the same names, files that run had finished — and a run killed in the
     * middle of that would leave a truncated file where a whole one had been.
     */
    fun runDir(
        bookId: String,
        fileId: String,
        startSegment: Int,
        run: Int,
    ): Path = Path(runsDir(bookId, fileId), "from${startSegment.toString().padStart(INDEX_DIGITS, '0')}-$run")

    /**
     * FFmpeg `-f segment` output pattern for the run in [runDir] — the printf form of [segmentPath]'s
     * file name, so the names FFmpeg writes and the names [segmentPath] looks for cannot drift apart.
     */
    fun segmentPattern(runDir: Path): String = Path(runDir, "seg%0${INDEX_DIGITS}d.aac").toString()

    /** Where the run in [runDir] records each segment it has **finished** writing, one name a line. */
    fun runListPath(runDir: Path): Path = Path(runDir, RUN_LIST)

    /** Creates the cache directory for one file, and the directory its runs live under. */
    suspend fun prepareDir(
        bookId: String,
        fileId: String,
    ) {
        withContext(fileIoDispatcher) {
            SystemFileSystem.createDirectories(fileDir(bookId, fileId))
            SystemFileSystem.createDirectories(runsDir(bookId, fileId))
        }
    }

    /** Creates [runDir] — and the cache directory it promotes into — before an encoder is handed it. */
    suspend fun prepareRun(
        bookId: String,
        fileId: String,
        runDir: Path,
    ) {
        prepareDir(bookId, fileId)
        withContext(fileIoDispatcher) { SystemFileSystem.createDirectories(runDir) }
    }

    /** True when segment [index] is in the cache directory — which, by construction, means finished. */
    fun has(
        bookId: String,
        fileId: String,
        index: Int,
    ): Boolean = SystemFileSystem.metadataOrNull(segmentPath(bookId, fileId, index))?.isRegularFile == true

    /**
     * The whole of segment [index], read once — small enough to hold (seconds of AAC), and needed
     * whole anyway to count its frames before it is served.
     */
    suspend fun readSegment(
        bookId: String,
        fileId: String,
        index: Int,
    ): ByteArray = withContext(fileIoDispatcher) { readFile(segmentPath(bookId, fileId, index)) }

    /**
     * True when segment [index] is in the cache and done being written.
     *
     * Completeness is a property of where the file is. FFmpeg's segment muxer creates a file the moment
     * it *opens* it, so a segment being encoded exists long before it holds all its frames — serving that
     * truncates the audio. Runs therefore write into their own [runDir], and only a segment its run has
     * listed as closed is moved into [fileDir]. Whatever is there is whole; whatever is not is not served.
     *
     * The first look at a file directory also clears one left by the shared-directory layout this
     * replaced ([dropSharedLayout]), whose files cannot be trusted.
     */
    suspend fun isComplete(
        bookId: String,
        fileId: String,
        index: Int,
    ): Boolean {
        dropSharedLayout(bookId, fileId)
        return has(bookId, fileId, index)
    }

    /**
     * Moves every segment the run in [runDir] has listed as finished into [fileDir]. The list is read
     * once; a segment already in the cache is kept and the run's copy deleted — it is finished too, and
     * encodes to the same frames. Safe to call while the run is still writing, and from two callers at
     * once: a file the other caller moved first is simply no longer there to move.
     */
    suspend fun promoteFinished(
        bookId: String,
        fileId: String,
        runDir: Path,
    ) {
        withContext(fileIoDispatcher) {
            val listed = finishedNames(runDir)
            for (name in listed) {
                val written = Path(runDir, name)
                val cached = Path(fileDir(bookId, fileId), name)
                try {
                    when {
                        SystemFileSystem.metadataOrNull(written)?.isRegularFile != true -> Unit
                        SystemFileSystem.exists(cached) -> SystemFileSystem.delete(written, mustExist = false)
                        else -> SystemFileSystem.atomicMove(written, cached)
                    }
                } catch (_: IOException) {
                    // Another caller promoted or discarded it first; the next look settles it.
                }
            }
        }
    }

    /** Deletes the run in [runDir] with whatever it was in the middle of writing. Call after [promoteFinished]. */
    suspend fun discardRun(runDir: Path) {
        withContext(fileIoDispatcher) { deleteRecursively(runDir) }
    }

    /** The segment names [runDir]'s list holds, or none when the run has listed nothing (or is gone). */
    private fun finishedNames(runDir: Path): List<String> {
        val list = runListPath(runDir)
        if (SystemFileSystem.metadataOrNull(list)?.isRegularFile != true) return emptyList()
        return try {
            list
                .readText()
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
        } catch (_: IOException) {
            emptyList()
        }
    }

    /**
     * Clears, once per file directory per process, a directory the shared layout wrote: every run wrote
     * into [fileDir] itself and kept a `from*.list` **file** in the runs directory, so a file there may be
     * one a killed run left half-written. The cache refills on demand.
     */
    private suspend fun dropSharedLayout(
        bookId: String,
        fileId: String,
    ) {
        val key = "$bookId/$fileId"
        if (synchronized(lock) { !layoutChecked.add(key) }) return
        withContext(fileIoDispatcher) {
            val runs = runsDir(bookId, fileId)
            if (SystemFileSystem.metadataOrNull(runs)?.isDirectory != true) return@withContext
            val shared =
                SystemFileSystem.list(runs).any { entry ->
                    entry.name.endsWith(".list") && SystemFileSystem.metadataOrNull(entry)?.isRegularFile == true
                }
            if (shared) deleteRecursively(fileDir(bookId, fileId))
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

    /**
     * Every `{bookId}/{fileId}` directory in the cache with its size and recency — the input to
     * [TranscodeSessionEngine.sweepCache]. One recursive walk; the sweep runs on a minute-scale
     * timer precisely because this is not cheap on a full cache.
     */
    internal suspend fun listCachedFiles(): List<CachedFile> =
        withContext(fileIoDispatcher) {
            if (!SystemFileSystem.exists(baseDir)) return@withContext emptyList()
            subdirectories(baseDir).flatMap { bookDir ->
                subdirectories(bookDir).map { fileDir -> cachedFile(bookDir.name, fileDir) }
            }
        }

    private fun cachedFile(
        bookId: String,
        fileDir: Path,
    ): CachedFile {
        val files = listRegularFilesRecursively(fileDir).mapNotNull { statFile(it) }
        val dirTouchedMs = statFile(fileDir)?.mtimeMs ?: 0L
        return CachedFile(
            bookId = bookId,
            fileId = fileDir.name,
            bytes = files.sumOf { it.size },
            lastTouchedMs = maxOf(dirTouchedMs, files.maxOfOrNull { it.mtimeMs } ?: 0L),
        )
    }

    private fun subdirectories(dir: Path): List<Path> =
        SystemFileSystem.list(dir).filter { SystemFileSystem.metadataOrNull(it)?.isDirectory == true }

    /** Removes every segment for one file. Idempotent. */
    suspend fun evict(
        bookId: String,
        fileId: String,
    ) {
        withContext(fileIoDispatcher) { deleteRecursively(Path(baseDir, "$bookId/$fileId")) }
    }

    /** Removes one segment so the next request re-encodes it. Idempotent. */
    suspend fun evictSegment(
        bookId: String,
        fileId: String,
        index: Int,
    ) {
        withContext(fileIoDispatcher) { SystemFileSystem.delete(segmentPath(bookId, fileId, index), mustExist = false) }
    }

    private companion object {
        /** Encoder profile the segments under a path were produced by. */
        const val DEFAULT_PROFILE = "aac-64k"

        /** Zero-padding width of a segment index — 5 digits covers 99,999 segments, ~11 days of audio. */
        const val INDEX_DIGITS = 5

        /** Holds each run's private directory, out of the way of the finished segments. */
        const val RUNS_DIR = "runs"

        /** The list FFmpeg appends each segment's name to as it closes it, inside the run's directory. */
        const val RUN_LIST = "finished.list"
    }
}
