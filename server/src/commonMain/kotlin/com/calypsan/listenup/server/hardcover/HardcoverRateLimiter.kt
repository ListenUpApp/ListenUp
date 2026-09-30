package com.calypsan.listenup.server.hardcover

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Cooperative rate limiter for outbound Hardcover GraphQL lookups: one request per [interval],
 * shared by every caller because Hardcover throttles per token, not per lookup kind. The first call
 * is free; [await] suspends with [delay], so cancellation propagates through the wait.
 */
open class HardcoverRateLimiter(
    private val interval: Duration = 1.seconds,
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
