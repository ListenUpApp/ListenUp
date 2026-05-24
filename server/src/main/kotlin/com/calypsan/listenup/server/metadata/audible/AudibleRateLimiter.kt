package com.calypsan.listenup.server.metadata.audible

import com.calypsan.listenup.api.metadata.AudibleRegion
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Per-region cooperative rate limiter for outbound Audible API calls.
 *
 * Maintains an independent token bucket per [AudibleRegion] — a US call does
 * not consume a UK token and vice versa. Each region allows one request per
 * [perRegionInterval], matching the Go reference's `defaultRPS = 1.0` (with
 * Go's burst-of-3 folded into the design: the first call is always free, the
 * delay only applies to rapid successive calls).
 *
 * [await] is cooperative: it uses [delay] rather than blocking, so cancellation
 * propagates immediately through the waiting period.
 *
 * Ported from Go's `internal/ratelimit.KeyedRateLimiter`. The Go version uses
 * `golang.org/x/time/rate` (token bucket, 1 rps, burst 3). This Kotlin version
 * matches the steady-state behaviour: 1 request per second per region, with the
 * first call per region always returning immediately.
 */
open class AudibleRateLimiter(
    /** Minimum gap between successive calls to the same region. */
    private val perRegionInterval: Duration = 1.seconds,
    private val clock: Clock = Clock.System,
) {
    private val mutex = Mutex()
    private val nextAllowedAt = mutableMapOf<AudibleRegion, Instant>()

    /**
     * Waits until a call to [region] is permitted, then returns.
     *
     * If the bucket for [region] is fresh (no prior call), returns immediately.
     * If the bucket was recently consumed, suspends via [delay] until
     * [perRegionInterval] has elapsed since the last call.
     *
     * Cancellation of the calling coroutine propagates through the [delay].
     */
    open suspend fun await(region: AudibleRegion) {
        val waitFor =
            mutex.withLock {
                val now = clock.now()
                val next = nextAllowedAt[region] ?: now
                val remaining = next - now
                // Advance the bucket: next permitted time is whichever is later —
                // the scheduled slot or now + interval (avoids drift on slow calls).
                nextAllowedAt[region] = maxOf(next, now) + perRegionInterval
                remaining
            }
        if (waitFor > Duration.ZERO) {
            delay(waitFor)
        }
    }
}
