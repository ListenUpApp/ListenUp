package com.calypsan.listenup.server.metadata.itunes

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Serializes iTunes Search API calls to at most one per [minInterval] (3s ⇒ 20/min,
 * the documented iTunes limit). Mirrors `AudibleRateLimiter` but single-bucket (iTunes
 * has no region dimension).
 */
open class ITunesRateLimiter(
    private val minInterval: Duration = 3.seconds,
    private val clock: Clock = Clock.System,
) {
    private val mutex = Mutex()
    private var nextAllowedAt: Instant? = null

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
