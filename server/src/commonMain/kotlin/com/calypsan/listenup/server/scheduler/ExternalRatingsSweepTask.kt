package com.calypsan.listenup.server.scheduler

import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.ratings.ExternalRatingsBackfillRunner
import com.calypsan.listenup.server.ratings.ExternalRatingsFetcher
import com.calypsan.listenup.server.ratings.localeFor
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.sync.BookExternalRatingRepository
import com.calypsan.listenup.server.util.runCatchingCancellable
import kotlin.math.ceil
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val log = loggerFor<ExternalRatingsSweepTask>()

/**
 * Nightly sweep that refreshes roughly 1/30th of the library's outside ratings each run — every
 * ASIN-bearing book gets a fresh fetch about once a month without ever bursting requests at Audible
 * all at once. Mirrors [MetadataCacheCleanupTask]'s shape: jittered boot, a persisted last-run under
 * [LAST_RUN_KEY] so a nightly-restarted server sweeps once per [interval], never once per restart.
 */
internal class ExternalRatingsSweepTask(
    private val fetcher: ExternalRatingsFetcher,
    private val ratings: BookExternalRatingRepository,
    private val backfill: ExternalRatingsBackfillRunner,
    private val defaultLocale: MetadataLocale = MetadataLocale.DEFAULT,
    private val clock: Clock = Clock.System,
    private val interval: Duration = 24.hours,
    /** Nullable — without it the last run is not persisted and every boot sweeps at once. */
    private val settings: ServerSettingsRepository? = null,
    /** Upper bound on the random delay before the first sweep; zero in tests. */
    private val startJitter: Duration = START_JITTER,
) {
    /**
     * Start the sweep loop on [scope]. Returns the [Job] — cancel it to stop.
     *
     * Sweeps at boot — after a short random delay up to [startJitter], so tasks restarted together
     * do not hit the DB in the same instant — unless the persisted last run says one happened
     * within [interval], in which case it waits out the remainder.
     */
    fun start(scope: CoroutineScope): Job =
        scope.launch {
            delay(Random.nextLong(startJitter.inWholeMilliseconds + 1))
            delay(untilNextSweep())
            while (isActive) {
                runCatchingCancellable {
                    runOnce()
                    recordSweep()
                }.onFailure { log.warn(it) { "ExternalRatingsSweepTask sweep failed; will retry next interval" } }
                delay(interval)
            }
        }

    /**
     * Runs [backfill] first — every never-attempted book gets caught up before the rotation below
     * ever sees it, so a book that (for whatever reason) missed its scan-completion trigger still
     * gets a prompt fetch here instead of waiting for its turn in the ceil(n/30) rotation — then
     * refreshes the least-recently-touched ceil(n/30) of the library's ASIN-bearing books
     * (`n` = [BookExternalRatingRepository.countBooksWithAsin]; at least 1 whenever `n > 0`),
     * sequentially — one slow or permanently-failing book must never crowd out the rest of the
     * night's quota. Returns the number of candidates swept by the refresh step (the backfill's own
     * count is not part of this return value). A library with zero ASIN'd books does nothing.
     */
    suspend fun runOnce(): Int {
        backfill.run()
        val total = ratings.countBooksWithAsin()
        if (total <= 0) return 0
        val limit = ceil(total / SWEEP_FRACTION.toDouble()).toLong().coerceAtLeast(1)
        val candidates = ratings.sweepCandidates(limit)
        for (id in candidates) {
            runCatchingCancellable {
                fetcher.fetch(BookId(id), ratings.localeFor(id, defaultLocale), refresh = true)
            }.onFailure { log.warn(it) { "ExternalRatingsSweepTask: fetch failed for $id — continuing sweep" } }
        }
        return candidates.size
    }

    /** What is left of [interval] since the last recorded sweep; zero when unknown or unreadable. */
    private suspend fun untilNextSweep(): Duration =
        runCatchingCancellable {
            val lastRunMs =
                settings?.getValue(LAST_RUN_KEY)?.toLongOrNull() ?: return@runCatchingCancellable Duration.ZERO
            val elapsed = (clock.now().toEpochMilliseconds() - lastRunMs).milliseconds
            (interval - elapsed).coerceIn(Duration.ZERO, interval)
        }.onFailure { log.warn(it) { "ExternalRatingsSweepTask could not read its last run; sweeping now" } }
            .getOrDefault(Duration.ZERO)

    /** Recorded only after a sweep succeeded — a failed sweep must not push the next one out. */
    private suspend fun recordSweep() {
        settings?.setValue(LAST_RUN_KEY, clock.now().toEpochMilliseconds().toString())
    }

    internal companion object {
        /** `server_settings` key holding the epoch-millis of the last successful sweep. */
        const val LAST_RUN_KEY = "ratings.sweep.lastRun"

        /** Enough to spread the boot sweeps of the cleanup tasks apart; small enough to be invisible. */
        private val START_JITTER = 10.seconds

        /** The nightly quota's denominator — roughly a month's worth of nights per full library pass. */
        private const val SWEEP_FRACTION = 30
    }
}
