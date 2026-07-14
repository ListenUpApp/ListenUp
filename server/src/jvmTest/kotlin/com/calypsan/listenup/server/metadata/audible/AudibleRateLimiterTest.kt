@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.metadata.audible

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A [kotlin.time.Clock] whose notion of "now" is controlled by a mutable
 * [epochMs] property. Lets the rate limiter see deterministic timestamps that
 * the test advances in step with [advanceTimeBy].
 */
private class MutableClock(
    initialEpochMs: Long = 0L,
) : kotlin.time.Clock {
    var epochMs: Long = initialEpochMs

    override fun now(): Instant = Instant.fromEpochMilliseconds(epochMs)
}

class AudibleRateLimiterTest :
    FunSpec({
        test("first await returns immediately when bucket is fresh") {
            runTest {
                val clock = MutableClock(currentTime)
                val limiter = AudibleRateLimiter(perRegionInterval = 1.seconds, clock = clock)

                val before = currentTime
                limiter.await(AudibleRegion.US)

                // Virtual time must not have advanced — no delay needed.
                currentTime shouldBe before
            }
        }

        test("second await in quick succession delays by the interval") {
            runTest {
                val clock = MutableClock(currentTime)
                val limiter = AudibleRateLimiter(perRegionInterval = 1.seconds, clock = clock)

                // First call consumes the bucket slot.
                limiter.await(AudibleRegion.US)
                clock.epochMs = currentTime

                val before = currentTime

                // Second call: bucket was just consumed so a 1-second delay is expected.
                // runTest auto-advances virtual time through `delay`.
                limiter.await(AudibleRegion.US)

                val elapsed = currentTime - before
                elapsed shouldBeGreaterThanOrEqual 1_000L
            }
        }

        test("different regions have independent buckets") {
            runTest {
                val clock = MutableClock(currentTime)
                val limiter = AudibleRateLimiter(perRegionInterval = 1.seconds, clock = clock)

                // Consume US bucket.
                limiter.await(AudibleRegion.US)

                // UK bucket is independent — must return without delay.
                val before = currentTime
                limiter.await(AudibleRegion.UK)
                currentTime shouldBe before
            }
        }

        test("cancellation propagates through the await delay") {
            runTest {
                val clock = MutableClock(currentTime)
                val limiter = AudibleRateLimiter(perRegionInterval = 10.seconds, clock = clock)

                // Consume the bucket so the next await needs to delay 10 seconds.
                limiter.await(AudibleRegion.US)
                clock.epochMs = currentTime

                var caughtCancellation = false
                val job =
                    launch {
                        try {
                            limiter.await(AudibleRegion.US)
                        } catch (e: CancellationException) {
                            caughtCancellation = true
                            throw e
                        }
                    }

                // Advance 500ms — not enough to unblock (needs 10s).
                advanceTimeBy(500.milliseconds)

                // Cancel the job mid-delay.
                job.cancel()
                job.join()

                caughtCancellation shouldBe true
            }
        }
    })
