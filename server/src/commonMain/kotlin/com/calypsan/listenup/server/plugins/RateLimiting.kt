package com.calypsan.listenup.server.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import kotlin.time.Duration.Companion.minutes

/** Named buckets for the auth surface and books search — referenced by route handlers. */
object RateLimitBuckets {
    val Login = RateLimitName("auth-login")
    val Register = RateLimitName("auth-register")
    val Refresh = RateLimitName("auth-refresh")
    val Setup = RateLimitName("auth-setup")
    val BooksSearch = RateLimitName("books_search")

    // Public, anonymous invite surface. Claim creates an account and runs Argon2 (CPU) —
    // limited as tightly as registration. Lookup is a cheaper existence/inviter-name oracle
    // (mitigated by 128-bit codes) but still anonymous, so it gets a looser bucket.
    val InviteLookup = RateLimitName("invite_lookup")
    val InviteClaim = RateLimitName("invite_claim")

    /** The push-test send button — cheap to abuse, so kept tight (3/min). */
    val PushTest = RateLimitName("push-test")
}

/**
 * In-memory rate limiting keyed by remote host. Acceptable for the
 * self-hosted threat model — distributed/cluster-aware limits would only
 * matter beyond the single-process deployment.
 */
fun Application.installRateLimiting() {
    install(RateLimit) {
        register(RateLimitBuckets.Login) {
            rateLimiter(limit = LOGIN_LIMIT, refillPeriod = 1.minutes)
            requestKey { call -> call.request.origin.remoteHost }
        }
        register(RateLimitBuckets.Register) {
            rateLimiter(limit = REGISTER_LIMIT, refillPeriod = 1.minutes)
            requestKey { call -> call.request.origin.remoteHost }
        }
        register(RateLimitBuckets.Refresh) {
            rateLimiter(limit = REFRESH_LIMIT, refillPeriod = 1.minutes)
            requestKey { call -> call.request.origin.remoteHost }
        }
        register(RateLimitBuckets.Setup) {
            rateLimiter(limit = SETUP_LIMIT, refillPeriod = 1.minutes)
            requestKey { call -> call.request.origin.remoteHost }
        }
        register(RateLimitBuckets.BooksSearch) {
            rateLimiter(limit = BOOKS_SEARCH_LIMIT, refillPeriod = 1.minutes)
            requestKey { call -> call.request.origin.remoteHost }
        }
        register(RateLimitBuckets.InviteLookup) {
            rateLimiter(limit = INVITE_LOOKUP_LIMIT, refillPeriod = 1.minutes)
            requestKey { call -> call.request.origin.remoteHost }
        }
        register(RateLimitBuckets.InviteClaim) {
            rateLimiter(limit = INVITE_CLAIM_LIMIT, refillPeriod = 1.minutes)
            requestKey { call -> call.request.origin.remoteHost }
        }
        register(RateLimitBuckets.PushTest) {
            rateLimiter(limit = PUSH_TEST_LIMIT, refillPeriod = 1.minutes)
            requestKey { call -> call.request.origin.remoteHost }
        }
    }
}

private const val LOGIN_LIMIT = 10
private const val REGISTER_LIMIT = 5
private const val REFRESH_LIMIT = 30
private const val SETUP_LIMIT = 3
private const val BOOKS_SEARCH_LIMIT = 60
private const val INVITE_LOOKUP_LIMIT = 20
private const val INVITE_CLAIM_LIMIT = 5
private const val PUSH_TEST_LIMIT = 3
