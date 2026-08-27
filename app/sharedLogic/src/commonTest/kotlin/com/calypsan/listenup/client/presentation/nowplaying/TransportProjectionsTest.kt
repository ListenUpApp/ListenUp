package com.calypsan.listenup.client.presentation.nowplaying

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The two transport rules every client shares.
 *
 * They live in one pure function each precisely so the browser and the native clients cannot drift:
 * before this, the speed multiplication and the speed ladder existed only inside
 * `NowPlayingViewModel`, so a web transport bar would have had to restate both from memory.
 */
class TransportProjectionsTest :
    FunSpec({

        context("skipTargetMs") {

            test("moves by the configured interval at normal speed") {
                skipTargetMs(300_000L, seconds = 45, speed = 1.0f, totalDurationMs = 600_000L, forward = true) shouldBe
                    345_000L
                skipTargetMs(300_000L, seconds = 20, speed = 1.0f, totalDurationMs = 600_000L, forward = false) shouldBe
                    280_000L
            }

            test("scales the distance by playback speed, so the gesture is worth the same listening time") {
                // At 1.5x, ten seconds of listening IS fifteen seconds of book. Skipping a fixed book
                // duration would quietly shrink the gesture the faster you listen.
                skipTargetMs(300_000L, seconds = 10, speed = 1.5f, totalDurationMs = 600_000L, forward = false) shouldBe
                    285_000L
                skipTargetMs(300_000L, seconds = 30, speed = 2.0f, totalDurationMs = 600_000L, forward = true) shouldBe
                    360_000L
            }

            test("never walks off either end of the book") {
                skipTargetMs(5_000L, seconds = 30, speed = 1.0f, totalDurationMs = 600_000L, forward = false) shouldBe 0L
                skipTargetMs(599_000L, seconds = 30, speed = 1.0f, totalDurationMs = 600_000L, forward = true) shouldBe
                    600_000L
            }

            test("a skip at the very edge is a no-op rather than an error") {
                skipTargetMs(0L, seconds = 30, speed = 1.0f, totalDurationMs = 600_000L, forward = false) shouldBe 0L
                skipTargetMs(600_000L, seconds = 30, speed = 1.0f, totalDurationMs = 600_000L, forward = true) shouldBe
                    600_000L
            }
        }

        context("nextPlaybackSpeed") {

            test("steps up through the ladder") {
                nextPlaybackSpeed(1.0f) shouldBe 1.25f
                nextPlaybackSpeed(1.25f) shouldBe 1.5f
                nextPlaybackSpeed(2.0f) shouldBe 2.5f
            }

            test("wraps from the top back to the slowest") {
                // A cycle control with a dead end is a control that stops working, so the ladder is a
                // ring: the listener who overshoots gets back by carrying on, not by hunting a reset.
                nextPlaybackSpeed(3.0f) shouldBe 0.5f
            }

            test("tolerates a float that is a hair off a rung") {
                // Speeds make a round trip through storage and the platform player, so 1.5 comes back
                // as 1.4999999. Matching exactly would silently drop such a listener to the bottom.
                nextPlaybackSpeed(1.4999999f) shouldBe 1.75f
                nextPlaybackSpeed(1.5000001f) shouldBe 1.75f
            }

            test("a speed off the ladder entirely climbs to the next rung above it") {
                nextPlaybackSpeed(1.1f) shouldBe 1.5f
            }
        }
    })
