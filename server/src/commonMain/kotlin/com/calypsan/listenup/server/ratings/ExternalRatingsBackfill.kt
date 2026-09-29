package com.calypsan.listenup.server.ratings

import com.calypsan.listenup.api.event.ScanEvent
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.sync.BookExternalRatingRepository
import com.calypsan.listenup.server.util.runCatchingCancellable
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
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
 * Catches a book up on its outside rating the moment it becomes eligible, instead of waiting for
 * [ExternalRatingsSweepTask]'s ceil(n/30)-a-night rotation to reach it. That rotation means an
 * existing library takes about a month to get scores on upgrade, and a freshly-scanned book waits
 * for its turn in the sweep; this runs once, promptly, for every live book with an ASIN that
 * [ExternalRatingsFetcher] has never once attempted (see `ExternalRatingAttempts.sq`) — paced by the
 * same per-region [com.calypsan.listenup.server.metadata.audible.AudibleRateLimiter] every other
 * fetch already goes through.
 *
 * Two entry points, one guard: [run] is suspend and awaited (the nightly sweep calls this before its
 * own refresh); [trigger] is fire-and-forget (a completed scan — full or incremental — calls this,
 * never blocking on the fetch). Both funnel through the same [Mutex.tryLock][kotlinx.coroutines.sync.Mutex.tryLock]
 * guard, so two overlapping calls never run a pass concurrently: whichever wins the lock loops until
 * [BookExternalRatingRepository.neverAttempted] comes back empty, which is what lets a book added
 * mid-pass (by the very scan whose completion arrived while a pass was already running) be picked up
 * by that same pass rather than dropped.
 */
internal class ExternalRatingsBackfill(
    private val fetcher: ExternalRatingsFetcher,
    private val ratings: BookExternalRatingRepository,
    private val scope: CoroutineScope,
    private val defaultLocale: MetadataLocale = MetadataLocale.DEFAULT,
    private val clock: Clock = Clock.System,
) : ExternalRatingsBackfillRunner {
    private val mutex = Mutex()

    /**
     * Runs a pass if none is already running; a call that loses the race returns immediately — the
     * pass already in flight will see whatever this call would have seen, since it loops until the
     * queue is empty.
     */
    override suspend fun run() {
        if (!mutex.tryLock()) return
        try {
            runPass()
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Launches [run] on [scope] and returns immediately — the caller (a completed scan) never waits
     * on a fetch. Returns the [Job] so tests can await completion; production callers ignore it.
     */
    fun trigger(): Job = scope.launch { run() }

    /**
     * Pages [BookExternalRatingRepository.neverAttempted] until it comes back empty, fetching each
     * candidate one at a time. A book added while this loop is running (by a scan that completes
     * mid-pass) is picked up by the next page — there is no separate "catch the newcomers" step.
     */
    private suspend fun runPass() {
        while (true) {
            val candidates = ratings.neverAttempted(BATCH_LIMIT)
            if (candidates.isEmpty()) break
            for (id in candidates) {
                runCatchingCancellable {
                    fetcher.fetch(BookId(id), ratings.localeFor(id, defaultLocale), refresh = true)
                }.onFailure { e ->
                    log.warn(e) { "ExternalRatingsBackfill: fetch failed for $id — continuing pass" }
                    // fetch() is expected to record its own attempt on every normal path, even a
                    // fully-failed one (see ExternalRatingsFetcher.fetch's KDoc) — but if fetch()
                    // itself THROWS before reaching that, the book would sort straight back into the
                    // very next neverAttempted() page and spin this pass forever. Recording here
                    // guarantees a never-attempted book leaves that state exactly once per pass,
                    // however its fetch fails.
                    runCatchingCancellable { ratings.recordAttempt(id, clock.now().toEpochMilliseconds()) }
                }
            }
        }
    }

    private companion object {
        const val BATCH_LIMIT = 200L
    }
}

/**
 * Subscribes to [events] and [ExternalRatingsBackfill.trigger]s on every [ScanEvent.Completed] —
 * [com.calypsan.listenup.server.services.BookPersister] emits that event after a full scan AND
 * after an incremental re-analysis, so this one subscription covers both without this file (or
 * [ExternalRatingsBackfill] itself) knowing anything about the scanner. A book the scanner just
 * added with an embedded ASIN gets its rating without anyone matching it. Runs for the life of
 * [this] scope — cancelled the same way every other background task is, at application shutdown.
 */
internal fun CoroutineScope.triggerExternalRatingsBackfillOnScanCompletion(
    events: SharedFlow<ScanEvent>,
    backfill: ExternalRatingsBackfill,
): Job =
    launch {
        events.collect { event ->
            if (event is ScanEvent.Completed) backfill.trigger()
        }
    }
