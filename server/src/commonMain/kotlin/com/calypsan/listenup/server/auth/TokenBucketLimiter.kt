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
 * In-memory token buckets, one per ([K], subject). Each starts full and refills continuously at
 * `capacity / refillPeriod`, so a caller can burst to the limit and then proceeds at the steady rate.
 *
 * [capacityOf] gives a key's ceiling per [refillPeriod]; one limiter may mix keys with different
 * ceilings. The typed rate limiters ([LoginRateLimiter], [InviteRateLimiter],
 * [MetadataRateLimiter]) are thin fronts over this class.
 */
internal class TokenBucketLimiter<K>(
    private val clock: Clock,
    private val refillPeriod: Duration = 1.minutes,
    private val capacityOf: (K) -> Int,
) {
    // Mutable token-bucket state updated in place under the mutex; a data class adds nothing.
    @Suppress("UseDataClass")
    private class Bucket(
        var tokens: Double,
        var lastRefillMillis: Long,
    )

    private val mutex = Mutex()
    private val buckets = mutableMapOf<Pair<K, String>, Bucket>()

    /**
     * Consume one token for ([key], [subject]). Returns [RateDecision.Allowed] when a token was
     * available, or [RateDecision.Throttled] with the whole seconds until the next token otherwise.
     */
    suspend fun check(
        key: K,
        subject: String,
    ): RateDecision =
        mutex.withLock {
            val capacity = capacityOf(key).toDouble()
            val tokensPerMillis = capacity / refillPeriod.inWholeMilliseconds
            val now = clock.now().toEpochMilliseconds()

            val entry = buckets.getOrPut(key to subject) { Bucket(tokens = capacity, lastRefillMillis = now) }
            val elapsed = (now - entry.lastRefillMillis).coerceAtLeast(0)
            entry.tokens = (entry.tokens + elapsed * tokensPerMillis).coerceAtMost(capacity)
            entry.lastRefillMillis = now

            val decision =
                if (entry.tokens >= 1.0) {
                    entry.tokens -= 1.0
                    RateDecision.Allowed
                } else {
                    val deficit = 1.0 - entry.tokens
                    val retryAfter = ceil(deficit / tokensPerMillis / 1000.0).toInt().coerceAtLeast(1)
                    RateDecision.Throttled(retryAfterSeconds = retryAfter)
                }
            // AFTER this call's token is spent, never before: sweeping first could drop the very
            // entry the decision above is about, throwing the decrement away with it.
            sweep(now)
            decision
        }

    /** Live (unswept) bucket count — for tests that pin the sweep. */
    suspend fun liveBucketCount(): Int =
        mutex.withLock {
            sweep(clock.now().toEpochMilliseconds())
            buckets.size
        }

    /**
     * Drops entries that have refilled to capacity, so a long-lived process does not accumulate one
     * map entry per subject it has ever seen. A full bucket is indistinguishable from a subject
     * never seen before — [getOrPut] recreates it full — so removing it changes no decision.
     *
     * Only a *full* one. A partially drained entry still owes its subject the tokens it has spent;
     * dropping it would hand a caller a fresh full bucket mid-burst, which is an under-throttle that
     * no burst test would catch.
     */
    private fun sweep(nowMillis: Long) {
        buckets.entries.removeAll { (key, entry) -> refilledToCapacity(key.first, entry, nowMillis) }
    }

    /**
     * Whether [entry] would refill to its key's full capacity by [nowMillis]. Capacity is read
     * from the entry's OWN key rather than the caller's — the map mixes keys with different
     * ceilings, and reusing the caller's would sweep entries that are still in debt.
     */
    private fun refilledToCapacity(
        key: K,
        entry: Bucket,
        nowMillis: Long,
    ): Boolean {
        val capacity = capacityOf(key).toDouble()
        val tokensPerMillis = capacity / refillPeriod.inWholeMilliseconds
        val elapsed = (nowMillis - entry.lastRefillMillis).coerceAtLeast(0)
        return entry.tokens + elapsed * tokensPerMillis >= capacity
    }
}
