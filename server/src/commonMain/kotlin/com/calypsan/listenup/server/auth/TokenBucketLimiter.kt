package com.calypsan.listenup.server.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.ceil
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * In-memory token buckets, one per ([K], subject). Each starts full and refills continuously at
 * `capacity / refillPeriod`, so a caller can burst to the limit and then proceeds at the steady rate.
 *
 * [capacityOf] gives a key's ceiling per [refillPeriod]; one limiter may mix keys with different
 * ceilings. The typed rate limiters ([LoginRateLimiter], [InviteRateLimiter],
 * [MetadataRateLimiter]) are thin fronts over this class.
 *
 * Memory is bounded twice over. Buckets are kept in least-recently-used order, and every call drops
 * the oldest ones that have refilled to capacity — a full bucket is indistinguishable from a subject
 * never seen, so that changes no decision. Every bucket refills completely within [refillPeriod] of
 * its last use, so the map holds at most the subjects seen in the last [refillPeriod], and the sweep
 * costs amortised O(1) per call instead of a walk of the whole map under the lock. On top of that,
 * at most [maxBuckets] are kept: a new subject arriving at the cap evicts the least recently used
 * bucket. Evicting, rather than refusing the new subject, is the safe failure: refusing would let
 * anyone who can present [maxBuckets] distinct subjects inside one refill period lock every new
 * caller out, while evicting only forgives the oldest bucket's remaining debt — and a caller with
 * that many subjects already has a fresh bucket for each of them. A subject in active use is always
 * among the most recently used, so it keeps its debt.
 */
internal class TokenBucketLimiter<K>(
    private val clock: Clock,
    private val refillPeriod: Duration = 1.minutes,
    private val maxBuckets: Int = DEFAULT_MAX_BUCKETS,
    private val capacityOf: (K) -> Int,
) {
    init {
        require(maxBuckets > 0) { "maxBuckets must be positive" }
    }

    // Mutable token-bucket state, updated in place under the mutex and linked in recency order. A
    // list node compared by identity: a data class's value equality would be wrong here.
    @Suppress("UseDataClass")
    private class Bucket<K>(
        val id: Pair<K, String>,
        var tokens: Double,
        var lastRefillMillis: Long,
    ) {
        var older: Bucket<K>? = null
        var newer: Bucket<K>? = null
    }

    private val mutex = Mutex()
    private val buckets = HashMap<Pair<K, String>, Bucket<K>>()
    private var oldest: Bucket<K>? = null
    private var newest: Bucket<K>? = null

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

            val id = key to subject
            val entry = buckets[id]?.also(::unlink) ?: admit(id, capacity, now)
            append(entry)
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

    /** Live (unswept) bucket count — for tests that pin the sweep and the cap. */
    suspend fun liveBucketCount(): Int =
        mutex.withLock {
            sweep(clock.now().toEpochMilliseconds())
            buckets.size
        }

    /**
     * A new, full bucket for [id]. At [maxBuckets] it first sweeps whatever has refilled since the
     * last call, and if the limiter is still full evicts the least recently used bucket.
     */
    private fun admit(
        id: Pair<K, String>,
        capacity: Double,
        nowMillis: Long,
    ): Bucket<K> {
        if (buckets.size >= maxBuckets) sweep(nowMillis)
        if (buckets.size >= maxBuckets) oldest?.let(::remove)
        return Bucket(id, tokens = capacity, lastRefillMillis = nowMillis).also { buckets[id] = it }
    }

    /**
     * Drops the least recently used buckets while they have refilled to capacity, so a long-lived
     * process does not accumulate one entry per subject it has ever seen. A full bucket is
     * indistinguishable from a subject never seen before — [admit] recreates it full — so removing
     * it changes no decision.
     *
     * Only a *full* one. A partially drained entry still owes its subject the tokens it has spent;
     * dropping it would hand a caller a fresh full bucket mid-burst, which is an under-throttle that
     * no burst test would catch. The walk stops at the first bucket still in debt: everything newer
     * was used within the last [refillPeriod], so what it leaves behind is bounded, and each bucket
     * is removed at most once, which is what makes the sweep amortised O(1).
     */
    private fun sweep(nowMillis: Long) {
        while (true) {
            val candidate = oldest ?: return
            if (!refilledToCapacity(candidate, nowMillis)) return
            remove(candidate)
        }
    }

    /**
     * Whether [entry] would refill to its key's full capacity by [nowMillis]. Capacity is read
     * from the entry's OWN key rather than the caller's — the map mixes keys with different
     * ceilings, and reusing the caller's would sweep entries that are still in debt.
     */
    private fun refilledToCapacity(
        entry: Bucket<K>,
        nowMillis: Long,
    ): Boolean {
        val capacity = capacityOf(entry.id.first).toDouble()
        val tokensPerMillis = capacity / refillPeriod.inWholeMilliseconds
        val elapsed = (nowMillis - entry.lastRefillMillis).coerceAtLeast(0)
        return entry.tokens + elapsed * tokensPerMillis >= capacity
    }

    private fun remove(entry: Bucket<K>) {
        unlink(entry)
        buckets.remove(entry.id)
    }

    /** Links [entry] in as the most recently used bucket. */
    private fun append(entry: Bucket<K>) {
        entry.older = newest
        entry.newer = null
        newest?.newer = entry
        newest = entry
        if (oldest == null) oldest = entry
    }

    private fun unlink(entry: Bucket<K>) {
        entry.older?.newer = entry.newer
        entry.newer?.older = entry.older
        if (oldest === entry) oldest = entry.newer
        if (newest === entry) newest = entry.older
        entry.older = null
        entry.newer = null
    }

    internal companion object {
        /**
         * Buckets kept per limiter. Legitimate traffic on a self-hosted server is a few hosts or
         * users a minute; ten thousand is far above that and still only a few megabytes.
         */
        const val DEFAULT_MAX_BUCKETS = 10_000
    }
}
