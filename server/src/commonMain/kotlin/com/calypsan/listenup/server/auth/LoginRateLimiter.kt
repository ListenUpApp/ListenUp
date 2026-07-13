@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.server.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.ceil
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime

/**
 * The throttled auth operations reachable over the RPC public mount, with their per-IP, per-minute
 * ceilings. The values mirror the REST `RateLimitBuckets` limits so a first-party client gets the
 * same protection whether it logs in over REST or RPC.
 */
enum class AuthRateBucket(
    val perMinuteLimit: Int,
) {
    LOGIN(10),
    REGISTER(5),
    REFRESH(30),
}

/** Outcome of a rate-limit probe: proceed, or reject with a client-surfaced `Retry-After`. */
sealed interface RateDecision {
    /** Under the ceiling — the caller may proceed. */
    data object Allowed : RateDecision

    /** Over the ceiling — reject and tell the caller to retry after [retryAfterSeconds]. */
    data class Throttled(
        val retryAfterSeconds: Int,
    ) : RateDecision
}

/**
 * In-memory, per-IP token-bucket throttle for the RPC auth surface (C3).
 *
 * Ktor's `RateLimit` plugin throttles the REST auth routes, but first-party clients log in over
 * RPC — many login messages ride a single WebSocket, so throttling the upgrade is useless. This
 * limiter runs per-call inside the auth service instead, keyed by the caller's remote host, so login
 * brute-force and the Argon2 CPU/memory amplification behind it are capped on the RPC path too.
 *
 * In-memory keying by host is acceptable for the self-hosted, single-process deployment — the same
 * rationale as the REST `RateLimiting` plugin; a distributed limiter would only matter beyond one
 * node. The bucket starts full and refills continuously at `limit / refillPeriod`, so a caller can
 * burst up to the limit and then proceeds at the steady rate.
 */
class LoginRateLimiter(
    private val clock: Clock,
    private val refillPeriod: Duration = 1.minutes,
) {
    private class Bucket(
        var tokens: Double,
        var lastRefillMillis: Long,
    )

    private val mutex = Mutex()
    private val buckets = mutableMapOf<Pair<AuthRateBucket, String>, Bucket>()

    /**
     * Consume one token for ([bucket], [host]). Returns [RateDecision.Allowed] when a token was
     * available, or [RateDecision.Throttled] with the whole seconds until the next token otherwise.
     */
    suspend fun check(
        bucket: AuthRateBucket,
        host: String,
    ): RateDecision =
        mutex.withLock {
            val capacity = bucket.perMinuteLimit.toDouble()
            val tokensPerMillis = capacity / refillPeriod.inWholeMilliseconds
            val now = clock.now().toEpochMilliseconds()

            val entry = buckets.getOrPut(bucket to host) { Bucket(tokens = capacity, lastRefillMillis = now) }
            val elapsed = (now - entry.lastRefillMillis).coerceAtLeast(0)
            entry.tokens = (entry.tokens + elapsed * tokensPerMillis).coerceAtMost(capacity)
            entry.lastRefillMillis = now

            if (entry.tokens >= 1.0) {
                entry.tokens -= 1.0
                RateDecision.Allowed
            } else {
                val deficit = 1.0 - entry.tokens
                val retryAfter = ceil(deficit / tokensPerMillis / 1000.0).toInt().coerceAtLeast(1)
                RateDecision.Throttled(retryAfterSeconds = retryAfter)
            }
        }
}
