package com.calypsan.listenup.server.ratings

import com.calypsan.listenup.api.event.ScanEvent
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.sync.ExternalRatingSource
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.sync.BookExternalRatingRepository
import com.calypsan.listenup.server.util.runCatchingCancellable
import kotlin.time.Clock
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

private val log = loggerFor<ExternalRatingsBackfill>()

/**
 * What [ExternalRatingsSweepTask] needs from the backfill: run whatever has never been attempted,
 * then return, so the sweep can await it before its own oldest-first refresh runs. Narrowed to this
 * one method so the sweep's tests can inject a trivial fake instead of a real, DB-backed
 * [ExternalRatingsBackfill].
 */
internal fun interface ExternalRatingsBackfillRunner {
    suspend fun run()
}

/**
 * Catches a book up on its outside ratings the moment a source can reach it, instead of waiting for
 * [ExternalRatingsSweepTask]'s ceil(n/30)-a-night rotation. That rotation means an existing library
 * takes about a month to get scores on upgrade, and so does every book when a new source arrives;
 * this fetches, promptly, every live book that some runnable source (enabled, not paused, available
 * — see [ExternalRatingsFetcher.runnableSources]) has never once attempted (see
 * `ExternalRatingAttempts.sq`), asking only the sources that are missing. A source that cannot run
 * is not asked about at all, so an unconnected Hardcover never makes every book a candidate; the
 * pass after it becomes runnable finds exactly the books it has never tried.
 *
 * Two entry points, one guard: [run] is suspend and awaited (the nightly sweep calls this before its
 * own refresh); [trigger] is fire-and-forget (a completed scan — see
 * [triggerExternalRatingsBackfillOn] — never blocking on the fetch). A call that
 * arrives while a pass is running does not start a second one: it asks the running pass for one more
 * walk, which is how a book added mid-pass (by the very scan whose completion arrived while the pass
 * was running) is still picked up.
 */
internal class ExternalRatingsBackfill(
    private val fetcher: ExternalRatingsFetcher,
    private val ratings: BookExternalRatingRepository,
    private val scope: CoroutineScope,
    private val defaultLocale: MetadataLocale = MetadataLocale.DEFAULT,
    private val clock: Clock = Clock.System,
) : ExternalRatingsBackfillRunner {
    private val mutex = Mutex()
    private val walkRequested = atomic(false)

    /**
     * Walks the library until no walk has been requested since the last one began. A call that finds
     * a walk already in flight leaves its request for that walk's owner and returns immediately.
     */
    override suspend fun run() {
        walkRequested.value = true
        // Re-checked after unlocking: a request that lands between the owner's last check and its
        // unlock found the mutex held and returned, so the owner — or this loop — must serve it.
        while (walkRequested.value) {
            if (!mutex.tryLock()) return
            try {
                while (walkRequested.compareAndSet(expect = true, update = false)) walk()
            } finally {
                mutex.unlock()
            }
        }
    }

    /**
     * Launches [run] on [scope] and returns immediately — the caller never waits on a fetch. Returns
     * the [Job] so tests can await completion; production callers ignore it.
     */
    fun trigger(): Job = scope.launch { run() }

    /**
     * One walk through the library in id order, a page at a time. The runnable sources are re-read
     * for every page, so a source that pauses mid-walk stops being asked for, and one walk always
     * ends: the keyset cursor only moves forward.
     */
    private suspend fun walk() {
        var after = ""
        while (true) {
            val runnable = fetcher.runnableSources()
            val page = ratings.booksMissingAttempt(runnable, after, BATCH_LIMIT)
            if (page.isEmpty()) return
            for (id in page) catchUp(id, runnable)
            after = page.last()
        }
    }

    /** Fetches [id] from each of [runnable] that has never attempted it. */
    private suspend fun catchUp(
        id: String,
        runnable: Set<ExternalRatingSource>,
    ) {
        val missing = runnable - ratings.attemptedSources(id)
        if (missing.isEmpty()) return
        runCatchingCancellable {
            fetcher.fetch(BookId(id), ratings.localeFor(id, defaultLocale), refresh = true, sources = missing)
        }.onFailure { e ->
            log.warn(e) { "ExternalRatingsBackfill: fetch failed for $id — continuing pass" }
            // fetch() records its own attempts on every normal path, even a fully-failed one (see
            // ExternalRatingsFetcher.fetch) — but if it THROWS before reaching that, the book would
            // be offered to the same sources by every later pass. Recording here means a book leaves
            // the queue for these sources exactly once, however its fetch fails.
            val now = clock.now().toEpochMilliseconds()
            missing.forEach { source -> runCatchingCancellable { ratings.recordAttempt(id, source, now) } }
        }
    }

    private companion object {
        const val BATCH_LIMIT = 200L
    }
}

/**
 * [ExternalRatingsBackfill.trigger]s on every emission of [signals] — something happened that may
 * have given a source books it has never tried. Runs for the life of [this] scope, cancelled the same
 * way every other background task is, at application shutdown.
 */
internal fun CoroutineScope.triggerExternalRatingsBackfillOn(
    signals: Flow<*>,
    backfill: ExternalRatingsBackfill,
): Job = launch { signals.collect { backfill.trigger() } }

/**
 * Subscribes to [events] and [ExternalRatingsBackfill.trigger]s on every [ScanEvent.Completed] —
 * [com.calypsan.listenup.server.services.BookPersister] emits that event after a full scan AND
 * after an incremental re-analysis, so this one subscription covers both without this file (or
 * [ExternalRatingsBackfill] itself) knowing anything about the scanner. A book the scanner just
 * added gets its ratings without anyone matching it.
 */
internal fun CoroutineScope.triggerExternalRatingsBackfillOnScanCompletion(
    events: SharedFlow<ScanEvent>,
    backfill: ExternalRatingsBackfill,
): Job = triggerExternalRatingsBackfillOn(events.filterIsInstance<ScanEvent.Completed>(), backfill)
