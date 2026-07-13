@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.server.auth

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Advanceable clock so the token-bucket refill is driven deterministically, not by wall time. */
private class MutableClock(
    var now: Instant,
) : Clock {
    override fun now(): Instant = now
}

/**
 * Pins the RPC-path per-IP auth throttle (C3): the first `limit` calls from a host pass, the next is
 * throttled, a different host is unaffected, and tokens refill over the window.
 */
class LoginRateLimiterTest :
    FunSpec({

        test("the (limit+1)th login from one host is throttled; another host is unaffected") {
            runTest {
                val clock = MutableClock(Clock.System.now())
                val limiter = LoginRateLimiter(clock)
                val host = "10.0.0.1"

                repeat(AuthRateBucket.LOGIN.perMinuteLimit) {
                    limiter.check(AuthRateBucket.LOGIN, host) shouldBe RateDecision.Allowed
                }
                val throttled =
                    limiter
                        .check(AuthRateBucket.LOGIN, host)
                        .shouldBeInstanceOf<RateDecision.Throttled>()
                (throttled.retryAfterSeconds >= 1) shouldBe true

                // A different host has its own bucket, still full.
                limiter.check(AuthRateBucket.LOGIN, "10.0.0.2") shouldBe RateDecision.Allowed
            }
        }

        test("tokens refill after the window elapses") {
            runTest {
                val clock = MutableClock(Clock.System.now())
                val limiter = LoginRateLimiter(clock)
                val host = "10.0.0.9"

                repeat(AuthRateBucket.LOGIN.perMinuteLimit) { limiter.check(AuthRateBucket.LOGIN, host) }
                limiter.check(AuthRateBucket.LOGIN, host).shouldBeInstanceOf<RateDecision.Throttled>()

                // A full refill period restores the whole bucket.
                clock.now += 1.minutes
                limiter.check(AuthRateBucket.LOGIN, host) shouldBe RateDecision.Allowed
            }
        }

        test("buckets are independent per operation") {
            runTest {
                val clock = MutableClock(Clock.System.now())
                val limiter = LoginRateLimiter(clock)
                val host = "10.0.0.3"

                // Exhaust REGISTER (limit 5)...
                repeat(AuthRateBucket.REGISTER.perMinuteLimit) { limiter.check(AuthRateBucket.REGISTER, host) }
                limiter.check(AuthRateBucket.REGISTER, host).shouldBeInstanceOf<RateDecision.Throttled>()

                // ...LOGIN from the same host is a separate bucket, still open.
                limiter.check(AuthRateBucket.LOGIN, host) shouldBe RateDecision.Allowed
            }
        }
    })
