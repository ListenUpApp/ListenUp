package com.calypsan.listenup.server.metadata.custom

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Serializes one custom provider's outbound calls to at most one per [minInterval]
 * (default one request/second). One limiter per [CustomHttpProvider], so operators with
 * several custom endpoints pace each independently.
 *
 * Pacing an operator's own endpoint is courtesy rather than necessity, but keeping a limiter
 * matches every other provider ([com.calypsan.listenup.server.metadata.audnexus.AudnexusRateLimiter],
 * `ITunesRateLimiter`) — one polite shape across the SPI. [await] is cooperative: it uses
 * [delay], so cancellation propagates immediately through the wait.
 */
open class CustomRateLimiter(
    private val minInterval: Duration = 1.seconds,
    private val clock: Clock = Clock.System,
) {
    private val mutex = Mutex()
    private var nextAllowedAt: Instant? = null

    /** Waits until the next call is permitted, then returns. The first call is free. */
    open suspend fun await() {
        val waitFor =
            mutex.withLock {
                val now = clock.now()
                val next = nextAllowedAt ?: now
                val remaining = next - now
                nextAllowedAt = maxOf(next, now) + minInterval
                remaining
            }
        if (waitFor > Duration.ZERO) delay(waitFor)
    }
}
