package com.calypsan.listenup.server.ratings

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.util.runCatchingCancellable
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
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
 * `ExternalRatingsFetcher`, so the catalogue token and the shared limiter apply. At most one fetch per
 * book is in flight; a failure is logged and dropped, never reaching the caller.
 */
class HardcoverRatingOnOpen(
    private val lastTried: suspend (bookId: String) -> Long?,
    private val fetch: suspend (BookId) -> Unit,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val staleAfter: Duration = RatingSourceSettings.STALE_AFTER,
) {
    private val lock = SynchronizedObject()
    private val inFlight = mutableSetOf<String>()

    /** Starts [bookId]'s fetch when its rating is missing or stale and none is in flight; null when nothing started. */
    suspend fun ensure(bookId: BookId): Job? {
        val tried = lastTried(bookId.value)
        if (tried != null && clock.now().toEpochMilliseconds() - tried < staleAfter.inWholeMilliseconds) return null
        if (!synchronized(lock) { inFlight.add(bookId.value) }) return null
        return scope.launch {
            try {
                runCatchingCancellable { fetch(bookId) }
                    .onFailure { log.warn(it) { "Hardcover rating on open failed for ${bookId.value}; the nightly sweep will retry" } }
            } finally {
                synchronized(lock) { inFlight.remove(bookId.value) }
            }
        }
    }
}
