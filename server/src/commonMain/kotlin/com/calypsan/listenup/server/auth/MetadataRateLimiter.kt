@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.server.auth

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
    clock: Clock,
    refillPeriod: Duration = 1.minutes,
) {
    private val buckets = TokenBucketLimiter<MetadataRateBucket>(clock, refillPeriod) { it.perMinuteLimit }

    /**
     * Consume one token for ([bucket], [userId]). Returns [RateDecision.Allowed] when a token was
     * available, or [RateDecision.Throttled] with the whole seconds until the next token otherwise.
     */
    suspend fun check(
        bucket: MetadataRateBucket,
        userId: String,
    ): RateDecision = buckets.check(bucket, userId)

    /** Live (unswept) bucket count — for tests that pin the sweep. */
    internal suspend fun liveBucketCount(): Int = buckets.liveBucketCount()
}
