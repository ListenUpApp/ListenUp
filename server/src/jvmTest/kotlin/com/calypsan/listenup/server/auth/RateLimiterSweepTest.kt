@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.server.auth

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Advanceable clock so refill — and therefore the sweep — is driven deterministically. */
private class SweepClock(
    var now: Instant,
) : Clock {
    override fun now(): Instant = now
}

/**
 * Pins the bucket-map sweep on both per-IP limiters.
 *
 * A token bucket keyed by `(bucket, host)` is a map that only ever grows: one entry per remote host
 * the process has ever seen, held for its whole lifetime. `SocketTicketStore` already sweeps what
 * the clock has invalidated; these two did not.
 *
 * **The property that makes the sweep sound — and the one worth breaking a test over — is that an
 * entry may only be dropped once it has refilled to capacity.** Such an entry is indistinguishable
 * from a host never seen before, because `getOrPut` recreates it full; dropping a *partially
 * drained* one would hand a caller a fresh full bucket mid-burst, which is an under-throttle that a
 * passing burst test would never notice. The third case below is what pins it.
 */
class RateLimiterSweepTest :
    FunSpec({

        test("LoginRateLimiter holds one entry per host until they refill, then drops them") {
            runTest {
                val clock = SweepClock(Clock.System.now())
                val limiter = LoginRateLimiter(clock)
                val hosts = (1..HOST_COUNT).map { "10.0.0.$it" }

                hosts.forEach { limiter.check(AuthRateBucket.LOGIN, it) shouldBe RateDecision.Allowed }

                // One probe each: every host now owns a partially drained bucket, so nothing is
                // sweepable and the map carries them all.
                limiter.liveBucketCount() shouldBe HOST_COUNT

                // A full refill period restores every bucket to capacity. The next probe sweeps
                // them; only the host that just spent a token is left below capacity, and so kept.
                clock.now += 1.minutes
                limiter.check(AuthRateBucket.LOGIN, hosts.first()) shouldBe RateDecision.Allowed

                limiter.liveBucketCount() shouldBe 1
            }
        }

        test("LoginRateLimiter still throttles a burst that spans a sweep") {
            runTest {
                val clock = SweepClock(Clock.System.now())
                val limiter = LoginRateLimiter(clock)
                val burstHost = "10.0.1.1"

                // Drain the burst host's bucket, then make a hundred other hosts' entries sweepable
                // by advancing the clock — the sweep must take them and leave the drained one.
                repeat(AuthRateBucket.LOGIN.perMinuteLimit) {
                    limiter.check(AuthRateBucket.LOGIN, burstHost) shouldBe RateDecision.Allowed
                }
                limiter.check(AuthRateBucket.LOGIN, burstHost).shouldBeInstanceOf<RateDecision.Throttled>()

                (1..HOST_COUNT).forEach { limiter.check(AuthRateBucket.LOGIN, "10.0.2.$it") }

                // Far less than a refill period: the burst host has earned no whole token back.
                clock.now += SUB_REFILL_STEP
                limiter.check(AuthRateBucket.LOGIN, burstHost).shouldBeInstanceOf<RateDecision.Throttled>()
            }
        }

        test("InviteRateLimiter sweeps the same way") {
            runTest {
                val clock = SweepClock(Clock.System.now())
                val limiter = InviteRateLimiter(clock)
                val hosts = (1..HOST_COUNT).map { "10.0.3.$it" }

                hosts.forEach { limiter.check(InviteRateBucket.CLAIM, it) shouldBe RateDecision.Allowed }
                limiter.liveBucketCount() shouldBe HOST_COUNT

                clock.now += 1.minutes
                limiter.check(InviteRateBucket.CLAIM, hosts.first()) shouldBe RateDecision.Allowed

                limiter.liveBucketCount() shouldBe 1
            }
        }

        test("InviteRateLimiter still throttles a burst that spans a sweep") {
            runTest {
                val clock = SweepClock(Clock.System.now())
                val limiter = InviteRateLimiter(clock)
                val burstHost = "10.0.4.1"

                repeat(InviteRateBucket.CLAIM.perMinuteLimit) {
                    limiter.check(InviteRateBucket.CLAIM, burstHost) shouldBe RateDecision.Allowed
                }
                limiter.check(InviteRateBucket.CLAIM, burstHost).shouldBeInstanceOf<RateDecision.Throttled>()

                (1..HOST_COUNT).forEach { limiter.check(InviteRateBucket.CLAIM, "10.0.5.$it") }

                clock.now += SUB_REFILL_STEP
                limiter.check(InviteRateBucket.CLAIM, burstHost).shouldBeInstanceOf<RateDecision.Throttled>()
            }
        }
    })

/** Distinct hosts probed — enough that "the map grows per host" is unambiguous. */
private const val HOST_COUNT = 100

/**
 * Short enough that no bucket earns a whole token back: `LOGIN` refills one token every six
 * seconds and `CLAIM` one every twelve, so a single second can never lift a drained bucket over 1.0
 * — which is what makes the third and fourth cases a statement about the sweep, not about refill.
 */
private val SUB_REFILL_STEP = 1.seconds
