package com.calypsan.listenup.server.ratings

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.util.runCatchingCancellable
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration

private val log = loggerFor<HardcoverRatingOnOpen>()

/**
 * Ratings on open (#1542): when someone opens a book whose Hardcover rating is missing or older than
 * [staleAfter], fetch it in the background, one book at a time, instead of sweeping the library in a
 * burst — Hardcover allows 60 requests a minute per token, and the shared rate limiter's one a second
 * also carries push and pull. The nightly sweep stays as the slow fill for books nobody opens.
 *
 * [lastTried] is when Hardcover last attempted the book (every fetch records one, a confident "no
 * rating" included, so that counts as fresh too). [fetch] runs the Hardcover source alone through
 * `ExternalRatingsFetcher`, so the catalogue token and the shared limiter apply. [canRun] says whether
 * Hardcover can be asked at all right now (enabled, unpaused, connected); when it can't, nothing
 * starts, so Book Detail never shows a check that cannot happen.
 *
 * At most one fetch per book is in flight, and a second open of the same book gets that same [Job]
 * back — so every device that opened it can follow it (`BookRatingService.checkExternalRatings`). A
 * failure is logged and dropped, never reaching the caller.
 */
class HardcoverRatingOnOpen(
    private val lastTried: suspend (bookId: String) -> Long?,
    private val fetch: suspend (BookId) -> Unit,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val staleAfter: Duration = RatingSourceSettings.STALE_AFTER,
    private val canRun: suspend () -> Boolean = { true },
) {
    private val lock = SynchronizedObject()
    private val inFlight = mutableMapOf<String, Job>()

    /**
     * [bookId]'s Hardcover fetch: the one already in flight, or a new one when its rating is missing or
     * stale and Hardcover can be asked. Null when there is nothing to fetch.
     */
    suspend fun ensure(bookId: BookId): Job? {
        synchronized(lock) { inFlight[bookId.value] }?.let { return it }
        val tried = lastTried(bookId.value)
        if (tried != null && clock.now().toEpochMilliseconds() - tried < staleAfter.inWholeMilliseconds) return null
        if (!canRun()) return null
        // Created lazily and registered under the lock, then started outside it: the body's own
        // `finally` takes the lock again, and an eager start on an unconfined dispatcher would run it
        // while this thread still holds it.
        val candidate = scope.launch(start = CoroutineStart.LAZY) { fetchContained(bookId) }
        val winner = synchronized(lock) { inFlight.getOrPut(bookId.value) { candidate } }
        if (winner !== candidate) {
            candidate.cancel()
            return winner
        }
        candidate.start()
        return candidate
    }

    private suspend fun fetchContained(bookId: BookId) {
        try {
            runCatchingCancellable { fetch(bookId) }
                .onFailure {
                    log.warn(it) { "Hardcover rating on open failed for ${bookId.value}; the nightly sweep will retry" }
                }
        } finally {
            synchronized(lock) { inFlight.remove(bookId.value) }
        }
    }
}
