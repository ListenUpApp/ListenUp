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
 * The throttled invite operations reachable over the RPC public mount, with their per-IP,
 * per-minute ceilings. The values mirror the REST `RateLimitBuckets.InviteClaim` /
 * `RateLimitBuckets.InviteLookup` limits so a first-party client gets the same protection whether
 * it claims or looks up an invite over REST or RPC.
 */
enum class InviteRateBucket(
    val perMinuteLimit: Int,
) {
    CLAIM(5),
    LOOKUP(20),
}

/**
 * In-memory, per-IP token-bucket throttle for the RPC invite surface (SEC-02) — the [InviteService]
 * sibling of [LoginRateLimiter].
 *
 * Ktor's `RateLimit` plugin throttles the REST invite routes, but first-party clients reach
 * [claimInvite]/[lookupInvite] over the RPC public mount too — many messages ride a single
 * WebSocket, so throttling the upgrade is useless. This limiter runs per-call inside the invite
 * service instead, keyed by the caller's remote host, so an anonymous claim/lookup burst — and the
 * Argon2 CPU/memory amplification behind a claim burst — is capped on the RPC path too.
 *
 * In-memory keying by host is acceptable for the self-hosted, single-process deployment — the same
 * rationale as [LoginRateLimiter] and the REST `RateLimiting` plugin.
 */
class InviteRateLimiter(
    private val clock: Clock,
    private val refillPeriod: Duration = 1.minutes,
) {
    private class Bucket(
        var tokens: Double,
        var lastRefillMillis: Long,
    )

    private val mutex = Mutex()
    private val buckets = mutableMapOf<Pair<InviteRateBucket, String>, Bucket>()

    /**
     * Consume one token for ([bucket], [host]). Returns [RateDecision.Allowed] when a token was
     * available, or [RateDecision.Throttled] with the whole seconds until the next token otherwise.
     */
    suspend fun check(
        bucket: InviteRateBucket,
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
