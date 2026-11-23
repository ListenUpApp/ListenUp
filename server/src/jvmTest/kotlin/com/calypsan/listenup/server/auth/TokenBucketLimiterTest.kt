package com.calypsan.listenup.server.auth

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Advanceable clock so refill, and therefore the sweep, is driven deterministically. */
private class BucketClock(
    var now: Instant,
) : Clock {
    override fun now(): Instant = now
}

/** The key is its own per-minute capacity, so one limiter can mix buckets of different sizes. */
private fun limiterOver(clock: Clock) = TokenBucketLimiter<Int>(clock) { it }

/**
 * The one token-bucket algorithm behind every rate limiter: burst to capacity, refill at
 * `capacity / refillPeriod`, sweep only a bucket that has refilled to its own capacity, and keep
 * at most the cap's worth of buckets by evicting the least recently used.
 */
class TokenBucketLimiterTest :
    FunSpec({
        test("a burst up to capacity is allowed and the next call is throttled with a retry hint") {
            runTest {
                val limiter = limiterOver(BucketClock(Clock.System.now()))

                repeat(3) { limiter.check(3, "host") shouldBe RateDecision.Allowed }

                limiter
                    .check(3, "host")
                    .shouldBeInstanceOf<RateDecision.Throttled>()
                    .retryAfterSeconds
                    .let { (it >= 1) shouldBe true }
            }
        }

        test("tokens refill with time") {
            runTest {
                val clock = BucketClock(Clock.System.now())
                val limiter = limiterOver(clock)
                repeat(2) { limiter.check(2, "host") shouldBe RateDecision.Allowed }
                limiter.check(2, "host").shouldBeInstanceOf<RateDecision.Throttled>()

                // Two per minute is one token per thirty seconds.
                clock.now += 30.seconds

                limiter.check(2, "host") shouldBe RateDecision.Allowed
            }
        }

        test("buckets of different capacities are swept by their own capacity, not the caller's") {
            runTest {
                val clock = BucketClock(Clock.System.now())
                val limiter = limiterOver(clock)
                limiter.check(2, "small") shouldBe RateDecision.Allowed
                repeat(10) { limiter.check(10, "big") shouldBe RateDecision.Allowed }
                limiter.liveBucketCount() shouldBe 2

                // Thirty seconds repays the small bucket's single token but only half the big one's debt.
                clock.now += 30.seconds

                limiter.liveBucketCount() shouldBe 1
            }
        }

        test("every bucket is swept once a whole refill period has passed") {
            runTest {
                val clock = BucketClock(Clock.System.now())
                val limiter = limiterOver(clock)
                repeat(10) { limiter.check(10, "big") shouldBe RateDecision.Allowed }
                limiter.check(2, "small") shouldBe RateDecision.Allowed

                clock.now += 60.seconds

                limiter.liveBucketCount() shouldBe 0
            }
        }

        test("the limiter never holds more than its cap, however many subjects it sees") {
            runTest {
                val limiter = TokenBucketLimiter<Int>(BucketClock(Clock.System.now()), maxBuckets = 3) { it }

                // Every subject is left in debt, so nothing is swept: only the cap bounds the map.
                repeat(50) { limiter.check(5, "subject-$it") shouldBe RateDecision.Allowed }

                limiter.liveBucketCount() shouldBe 3
            }
        }

        test("a subject in active use keeps its debt while new subjects flood past the cap") {
            runTest {
                val limiter = TokenBucketLimiter<Int>(BucketClock(Clock.System.now()), maxBuckets = 3) { it }
                repeat(2) { limiter.check(2, "hot") shouldBe RateDecision.Allowed }

                repeat(50) { round ->
                    limiter.check(2, "flood-$round")
                    // The flood evicts the least recently used buckets, never the one being hammered.
                    limiter.check(2, "hot").shouldBeInstanceOf<RateDecision.Throttled>()
                }
            }
        }

        test("at the cap the least recently used bucket is the one evicted") {
            runTest {
                val limiter = TokenBucketLimiter<Int>(BucketClock(Clock.System.now()), maxBuckets = 2) { it }
                limiter.check(1, "first") shouldBe RateDecision.Allowed
                limiter.check(1, "second") shouldBe RateDecision.Allowed
                limiter.check(1, "first").shouldBeInstanceOf<RateDecision.Throttled>()

                // "second" is now the least recently used, so the newcomer evicts it and "first" stays in debt.
                limiter.check(1, "third") shouldBe RateDecision.Allowed

                limiter.check(1, "first").shouldBeInstanceOf<RateDecision.Throttled>()
                limiter.check(1, "second") shouldBe RateDecision.Allowed
            }
        }

        test("a partially drained bucket is never swept") {
            runTest {
                val clock = BucketClock(Clock.System.now())
                val limiter = limiterOver(clock)
                limiter.check(5, "host") shouldBe RateDecision.Allowed

                limiter.liveBucketCount() shouldBe 1
            }
        }
    })
