package com.calypsan.listenup.client.playback

import app.cash.turbine.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackBandwidthCoordinatorTest :
    FunSpec({

        test("does not yield until a stream buffers") {
            runTest {
                val coordinator = DefaultPlaybackBandwidthCoordinator(backgroundScope, releaseDelay = 2.seconds)
                coordinator.shouldYield.value shouldBe false
            }
        }

        test("buffering a stream yields bandwidth immediately") {
            runTest {
                val coordinator = DefaultPlaybackBandwidthCoordinator(backgroundScope, releaseDelay = 2.seconds)
                coordinator.shouldYield.test {
                    awaitItem() shouldBe false
                    coordinator.setStreamingBuffering(true)
                    awaitItem() shouldBe true
                }
            }
        }

        test("release is delayed by releaseDelay so downloads don't thrash") {
            runTest {
                val coordinator = DefaultPlaybackBandwidthCoordinator(backgroundScope, releaseDelay = 2.seconds)
                coordinator.shouldYield.test {
                    awaitItem() shouldBe false
                    coordinator.setStreamingBuffering(true)
                    awaitItem() shouldBe true
                    coordinator.setStreamingBuffering(false)
                    runCurrent()
                    expectNoEvents() // still yielding within the release window
                    advanceTimeBy(2.seconds)
                    runCurrent()
                    awaitItem() shouldBe false // released after the window
                }
            }
        }

        test("a re-buffer within the release window keeps yielding (no flap)") {
            runTest {
                val coordinator = DefaultPlaybackBandwidthCoordinator(backgroundScope, releaseDelay = 2.seconds)
                coordinator.shouldYield.test {
                    awaitItem() shouldBe false
                    coordinator.setStreamingBuffering(true)
                    awaitItem() shouldBe true
                    coordinator.setStreamingBuffering(false)
                    advanceTimeBy(1.seconds) // partway through the window
                    runCurrent()
                    coordinator.setStreamingBuffering(true) // re-buffer cancels the pending release
                    advanceTimeBy(5.seconds) // well past the original window
                    runCurrent()
                    expectNoEvents() // never flapped to false
                }
            }
        }

        test("yield is capped at maxYield so a stuck buffer never starves downloads") {
            runTest {
                val coordinator =
                    DefaultPlaybackBandwidthCoordinator(backgroundScope, releaseDelay = 2.seconds, maxYield = 60.seconds)
                coordinator.shouldYield.test {
                    awaitItem() shouldBe false
                    coordinator.setStreamingBuffering(true) // buffering, and it never clears
                    awaitItem() shouldBe true
                    advanceTimeBy(60.seconds)
                    runCurrent()
                    awaitItem() shouldBe false // cap released despite still "buffering"
                }
            }
        }
    })
