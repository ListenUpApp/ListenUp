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
 * The throttled auth operations reachable over the RPC public mount, with their per-IP (or, for
 * [PUSH_TEST], per-user) per-minute ceilings. This — not a Ktor route-level plugin — is the sole
 * throttle for these operations: auth has no REST mirror, and the push-test send button is
 * authenticated RPC only.
 */
enum class AuthRateBucket(
    val perMinuteLimit: Int,
) {
    LOGIN(10),
    REGISTER(5),
    REFRESH(30),

    /**
     * `registerRegistrationWatchToken` — the pre-auth watch-token upsert (#1068). Idempotent
     * and cheap, but public: a modest ceiling above LOGIN's covers the pending screen's
     * register-on-open plus poll-tick refreshes without opening a write amplifier.
     */
    REGISTER_WATCH_TOKEN(20),

    /**
     * `observeRegistrationStatus` subscriptions. Each open subscription runs a poll loop for as
     * long as the registration stays pending, so an unbounded stream of subscribe attempts is a
     * resource-exhaustion vector distinct from the request-per-call auth buckets above — hence a
     * more generous ceiling than LOGIN/REGISTER (a legitimate client re-subscribes on every
     * reconnect-with-backoff attempt and every manual "Check Status" tap).
     */
    OBSERVE_REGISTRATION_STATUS(20),

    /**
     * `observeRegistrationPolicy` subscriptions — the login screen's live Sign Up toggle. The same
     * open-subscription-holds-a-poll-loop exhaustion vector as [OBSERVE_REGISTRATION_STATUS], with
     * the same ceiling: a legitimate client re-subscribes on every reconnect-with-backoff attempt.
     */
    OBSERVE_REGISTRATION_POLICY(20),

    /**
     * `requestPasswordReset`. Unauthenticated, and the known/unknown-account branches differ
     * measurably in cost (the known path runs an extra transaction plus an HMAC). That timing
     * delta is not a one-shot signal, but it is repeatable and free to sample — this bucket is
     * what makes averaging over many probes impractical, so it is the mitigation the
     * enumeration-oracle argument in `PasswordResetService.request` explicitly leans on.
     */
    REQUEST_PASSWORD_RESET(5),

    /**
     * `completePasswordReset`. Guessing the code is already bounded by the per-ticket attempt
     * counter; this bounds the *other* axis — an attacker spraying one guess each across many
     * tickets — and caps the Argon2 CPU/memory amplification behind each call.
     */
    COMPLETE_PASSWORD_RESET(5),

    /**
     * `resetRootPassword`. The 128-bit token makes guessing impractical and a wrong guess costs
     * only an in-memory comparison — but this is an unauthenticated method on the public mount,
     * and every sibling here is bucketed. Consistency is the point: an un-bucketed auth method is
     * an exception a reader has to go and verify, so it should not exist without a reason.
     */
    RESET_ROOT_PASSWORD(5),

    /**
     * `setupRoot` — the one-time bootstrap that creates the ROOT account. Unauthenticated (there
     * is no account yet) and runs Argon2 on every attempt like [REGISTER], so it gets the same
     * ceiling.
     */
    SETUP(3),

    /**
     * `PushService.sendTestNotification` — the push-test send button. Authenticated, but cheap to
     * loop: an unthrottled caller could drain the server's per-IP relay budget and the operator's
     * FCM/APNs quota. Keyed per-user (not per-IP, unlike every bucket above) since the caller is
     * always authenticated by the time this bucket is consulted.
     */
    PUSH_TEST(3),
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
