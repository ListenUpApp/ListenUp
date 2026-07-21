package com.calypsan.listenup.server.scanner

import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.error.ScanError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.logging.loggerFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path

private val logger = loggerFor<ScanCoordinator>()

/**
 * Single-flight coordinator for the library's full scans and incremental re-analysis.
 *
 * The [ScanOrchestrator] owns one [ScanCoordinator], bound to the library
 * (identified by [libraryId]), which serialises that library's scans.
 *
 * One [Mutex] serialises every scan operation — a full scan and an
 * incremental re-analysis cannot overlap, because both mutate the
 * Scanner's `lastResult` state.
 *
 * **Full scans.** [scanFull] returns [ScanError.AlreadyRunning] immediately
 * when another scan is in flight (mutex unavailable). The caller chooses
 * whether to retry; by design we never queue full scans.
 *
 * **Incremental re-analysis.** [reanalyze] is fire-and-forget and
 * non-suspending so the file watcher can call it freely. Per-path
 * coalescing keeps a hot folder from flooding the queue: a path that is
 * already pending will not be enqueued twice. A worker coroutine launched
 * by the constructor drains the queue, taking the mutex for each item.
 *
 * **Why not `Channel.CONFLATED`?** A conflated channel collapses *all* traffic
 * globally — when two distinct book roots fire in quick succession the second
 * clobbers the first. Instead, an atomicfu-synchronized set does per-path dedup
 * over an unbounded queue, which gives the wanted semantics ("100 triggers
 * for the same path collapse to ≤ 1") *without* dropping events for
 * unrelated paths.
 *
 * **Cancellation.** Cancelling [scope] cancels the worker coroutine and
 * propagates into any in-flight scan via structured concurrency.
 * `CancellationException` is always re-raised — the coordinator never
 * swallows it.
 */
internal class ScanCoordinator(
    val libraryId: LibraryId,
    private val runFullScan: suspend () -> ScanResult,
    private val runIncremental: suspend (Path) -> Unit,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val pendingLock = SynchronizedObject()
    private val pendingPaths = LinkedHashSet<Path>() // guarded by pendingLock
    private val incrementalChannel = Channel<Path>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (path in incrementalChannel) {
                // Remove BEFORE processing so a subsequent reanalyze(path)
                // arriving during this run is re-enqueued — the file may
                // have changed again while we were working on it.
                synchronized(pendingLock) { pendingPaths.remove(path) }
                try {
                    mutex.withLock { runIncremental(path) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logger.warn(e) { "incremental analysis failed for $path — continuing" }
                }
            }
        }
    }

    /** True while a scan currently holds this library's single-flight lock. */
    fun isScanning(): Boolean = mutex.isLocked

    /** True after [close] has been called. Used in tests to verify teardown. */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    fun isChannelClosed(): Boolean = incrementalChannel.isClosedForSend

    suspend fun scanFull(): AppResult<ScanResult> {
        if (!mutex.tryLock()) {
            return AppResult.Failure(ScanError.AlreadyRunning())
        }
        return try {
            AppResult.Success(runFullScan())
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Fire-and-forget full scan: acquire the single-flight lock synchronously (so the
     * caller learns [ScanError.AlreadyRunning] immediately), then run the scan on [scope]
     * and return [AppResult.Success] right away — "202 Accepted" semantics. The scan
     * outlives the triggering request; progress streams over the scanner RPC watch. Used by
     * the admin/wizard
     * `scanLibrary` trigger, which must not block on the whole walk.
     */
    fun scanFullAsync(): AppResult<Unit> {
        if (!mutex.tryLock()) {
            return AppResult.Failure(ScanError.AlreadyRunning())
        }
        scope.launch {
            try {
                val result = runFullScan()
                logger.info {
                    "background full scan completed for library ${libraryId.value}: " +
                        "${result.books.size} books, ${result.filesWalked} files in ${result.durationMs}ms"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.error(e) { "background full scan failed for library ${libraryId.value}" }
            } finally {
                mutex.unlock()
            }
        }
        return AppResult.Success(Unit)
    }

    fun reanalyze(bookRoot: Path) {
        if (synchronized(pendingLock) { pendingPaths.add(bookRoot) }) {
            // trySend on UNLIMITED never fails for legitimate sends; the
            // only failure mode is a closed channel, in which case we've
            // been cancelled and dropping is correct.
            val sent = incrementalChannel.trySend(bookRoot).isSuccess
            if (!sent) synchronized(pendingLock) { pendingPaths.remove(bookRoot) }
        }
    }

    /**
     * Releases this coordinator's own resources: closes [incrementalChannel], which
     * causes the `for (path in incrementalChannel)` worker to drain and exit its loop,
     * ending the coroutine launched in the constructor without cancelling the shared
     * application [scope].
     *
     * Safe to call more than once — [Channel.close] is idempotent.
     */
    fun close() {
        incrementalChannel.close()
    }
}
