package com.calypsan.listenup.server.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import kotlin.time.Duration.Companion.minutes

/** Named buckets for the auth surface — referenced by route handlers. */
object RateLimitBuckets {
    val Login = RateLimitName("auth-login")
    val Register = RateLimitName("auth-register")
    val Refresh = RateLimitName("auth-refresh")
    val Setup = RateLimitName("auth-setup")
}

/**
 * Phase 1 in-memory rate limiting keyed by remote host. Acceptable for the
 * self-hosted threat model — distributed/cluster-aware limits would be a
 * later concern if we ever leave the single-process deployment.
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
    }
}

private const val LOGIN_LIMIT = 10
private const val REGISTER_LIMIT = 5
private const val REFRESH_LIMIT = 30
private const val SETUP_LIMIT = 3
