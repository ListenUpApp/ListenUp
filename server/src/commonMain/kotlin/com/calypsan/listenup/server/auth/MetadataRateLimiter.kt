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
 * The throttled metadata-lookup operations, with their per-**user**, per-minute ceilings.
 *
 * Keyed per user rather than per IP: these are authenticated reads, and the upstream provider
 * limiters (e.g. `AudibleClient`'s `rateLimiter.await`) are process-wide and *blocking* — so one
 * member's burst queues in front of every other caller, including an admin running the match
 * wizard. The per-user bucket is what keeps that a local problem.
 */
enum class MetadataRateBucket(
    val perMinuteLimit: Int,
) {
    /**
     * `searchBooks` / `searchContributorMetadata` — the wizard's opening move. A match wizard issues
     * ONE search and then fetches per candidate, so a search is the rarer of the two calls; twenty a
     * minute covers a user retyping a title several times over, and typing is what bounds this in
     * practice.
     */
    SEARCH(20),

    /**
     * `getBookMetadata` / `getBookChapters` / `getContributorMetadata` — the per-candidate fetches
     * that follow a search. The wizard issues several per search (core, chapters, contributor
     * profile) and a user may preview a handful of candidates, so this sits well above [SEARCH]:
     * sixty a minute leaves room for a whole wizard session without a legitimate user meeting it.
     */
    FETCH(60),
}

/**
 * In-memory, per-user token-bucket throttle for the authenticated metadata-lookup reads — the
 * [LoginRateLimiter] sibling for a surface whose callers all have a user id.
 *
 * The five read methods on `MetadataLookupService` carry no `canEdit` gate, so any member reaches
 * them, and every one of them fans out to an external catalog through a provider limiter that
 * *waits* rather than rejects. That makes an unthrottled burst a head-of-line block on everyone
 * else's metadata work rather than only the burster's own. This caps it per user, in front of that
 * shared queue.
 *
 * In-memory keying is acceptable for the self-hosted, single-process deployment — the same rationale
 * as [LoginRateLimiter] and [InviteRateLimiter]. The bucket starts full and refills continuously at
 * `limit / refillPeriod`, so a caller can burst up to the limit and then proceeds at the steady rate.
 */
class MetadataRateLimiter(
    private val clock: Clock,
    private val refillPeriod: Duration = 1.minutes,
) {
    private class Bucket(
        var tokens: Double,
        var lastRefillMillis: Long,
    )

    private val mutex = Mutex()
    private val buckets = mutableMapOf<Pair<MetadataRateBucket, String>, Bucket>()

    /**
     * Consume one token for ([bucket], [userId]). Returns [RateDecision.Allowed] when a token was
     * available, or [RateDecision.Throttled] with the whole seconds until the next token otherwise.
     */
    suspend fun check(
        bucket: MetadataRateBucket,
        userId: String,
    ): RateDecision =
        mutex.withLock {
            val capacity = bucket.perMinuteLimit.toDouble()
            val tokensPerMillis = capacity / refillPeriod.inWholeMilliseconds
            val now = clock.now().toEpochMilliseconds()

            val entry = buckets.getOrPut(bucket to userId) { Bucket(tokens = capacity, lastRefillMillis = now) }
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
    internal suspend fun liveBucketCount(): Int =
        mutex.withLock {
            sweep(clock.now().toEpochMilliseconds())
            buckets.size
        }

    /**
     * Drops entries that have refilled to capacity, so a long-lived process does not accumulate one
     * map entry per user it has ever served. A full bucket is indistinguishable from a user never
     * seen before — [getOrPut] recreates it full — so removing it changes no decision.
     *
     * Only a *full* one. A partially drained entry still owes its user the tokens it has spent;
     * dropping it would hand a caller a fresh full bucket mid-burst, which is an under-throttle that
     * no burst test would catch.
     */
    private fun sweep(nowMillis: Long) {
        buckets.entries.removeAll { (key, entry) -> refilledToCapacity(key.first, entry, nowMillis) }
    }

    /**
     * Whether [entry] would refill to its bucket's full capacity by [nowMillis]. Capacity is read
     * from the entry's OWN bucket rather than the caller's — the map mixes buckets with different
     * ceilings, and reusing the caller's would sweep entries that are still in debt.
     */
    private fun refilledToCapacity(
        bucket: MetadataRateBucket,
        entry: Bucket,
        nowMillis: Long,
    ): Boolean {
        val capacity = bucket.perMinuteLimit.toDouble()
        val tokensPerMillis = capacity / refillPeriod.inWholeMilliseconds
        val elapsed = (nowMillis - entry.lastRefillMillis).coerceAtLeast(0)
        return entry.tokens + elapsed * tokensPerMillis >= capacity
    }
}
