package com.calypsan.listenup.server.goodreads

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Cooperative rate limiter for Goodreads page fetches: one request per [interval], shared by every
 * caller. Goodreads is scraped, not an API, so it is asked gently — half Hardcover's pace. The first
 * call is free; [await] suspends with [delay], so cancellation propagates through the wait.
 */
open class GoodreadsRateLimiter(
    private val interval: Duration = 2.seconds,
    private val clock: Clock = Clock.System,
) {
    private val mutex = Mutex()
    private var nextAllowedAt: Instant? = null

    /** Waits until one more request is permitted. */
    open suspend fun await() {
        val waitFor =
            mutex.withLock {
                val now = clock.now()
                val next = nextAllowedAt ?: now
                nextAllowedAt = maxOf(next, now) + interval
                next - now
            }
        if (waitFor > Duration.ZERO) delay(waitFor)
    }
}
