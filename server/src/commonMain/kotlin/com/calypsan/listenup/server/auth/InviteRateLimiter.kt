package com.calypsan.listenup.server.auth

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The throttled invite operations reachable over the RPC public mount, with their per-IP,
 * per-minute ceilings. This is the sole throttle for these operations — the invite claim/lookup
 * surface has no REST mirror.
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
    clock: Clock,
    refillPeriod: Duration = 1.minutes,
) {
    private val buckets = TokenBucketLimiter<InviteRateBucket>(clock, refillPeriod) { it.perMinuteLimit }

    /**
     * Consume one token for ([bucket], [host]). Returns [RateDecision.Allowed] when a token was
     * available, or [RateDecision.Throttled] with the whole seconds until the next token otherwise.
     */
    suspend fun check(
        bucket: InviteRateBucket,
        host: String,
    ): RateDecision = buckets.check(bucket, host)

    /** Live (unswept) bucket count — for tests that pin the sweep. */
    internal suspend fun liveBucketCount(): Int = buckets.liveBucketCount()
}
