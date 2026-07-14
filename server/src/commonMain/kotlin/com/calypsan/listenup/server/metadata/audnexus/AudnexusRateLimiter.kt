package com.calypsan.listenup.server.metadata.audnexus

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Serializes Audnexus API calls to at most one per [minInterval] (a default of one
 * request/second). Single-bucket — Audnexus has no per-region rate dimension.
 *
 * Audnexus is a free, community-run aggregator; pacing outbound calls is a courtesy
 * to that shared service (the same politeness reason `ITunesRateLimiter` exists).
 * [await] is cooperative — it uses [delay], so cancellation propagates immediately
 * through the waiting period. Mirrors `ITunesRateLimiter`.
 */
open class AudnexusRateLimiter(
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
