package com.calypsan.listenup.server.scanner

import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.error.ScanError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.LibraryId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Per-library single-flight coordinator for full scans and incremental re-analysis.
 *
 * Each [ScanCoordinator] instance owns exactly one library (identified by
 * [libraryId]). The [ScanOrchestrator] manages one coordinator per library,
 * allowing concurrent scans of different libraries while serialising concurrent
 * scans of the same library.
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
 * **Plan deviation:** the plan literal sketched `Channel.CONFLATED` for the
 * incremental queue. That collapses *all* traffic globally — when two
 * distinct book roots fire in quick succession the second clobbers the
 * first. We use [ConcurrentHashMap.newKeySet] for per-path dedup with an
 * unbounded queue, which gives the test-stated semantics ("100 triggers
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
    scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val pendingPaths: MutableSet<Path> = ConcurrentHashMap.newKeySet()
    private val incrementalChannel = Channel<Path>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (path in incrementalChannel) {
                // Remove BEFORE processing so a subsequent reanalyze(path)
                // arriving during this run is re-enqueued — the file may
                // have changed again while we were working on it.
                pendingPaths.remove(path)
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

    fun reanalyze(bookRoot: Path) {
        if (pendingPaths.add(bookRoot)) {
            // trySend on UNLIMITED never fails for legitimate sends; the
            // only failure mode is a closed channel, in which case we've
            // been cancelled and dropping is correct.
            val sent = incrementalChannel.trySend(bookRoot).isSuccess
            if (!sent) pendingPaths.remove(bookRoot)
        }
    }
}
