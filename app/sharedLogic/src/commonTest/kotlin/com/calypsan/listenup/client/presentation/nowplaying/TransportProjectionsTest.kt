package com.calypsan.listenup.client.presentation.nowplaying

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.plusOrMinus
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
    })

class PlaybackSpeedVocabularyTest :
    FunSpec({

        test("a dragged rate snaps to the nearest increment") {
            snapPlaybackSpeed(1.23f) shouldBe (1.25f plusOrMinus SNAP_SLACK)
            snapPlaybackSpeed(1.01f) shouldBe (1.0f plusOrMinus SNAP_SLACK)
            snapPlaybackSpeed(2.97f) shouldBe (2.95f plusOrMinus SNAP_SLACK)
        }

        test("snapping never leaves the range the players accept") {
            snapPlaybackSpeed(PLAYBACK_SPEED_MIN) shouldBe (PLAYBACK_SPEED_MIN plusOrMinus SNAP_SLACK)
            snapPlaybackSpeed(PLAYBACK_SPEED_MAX) shouldBe (PLAYBACK_SPEED_MAX plusOrMinus SNAP_SLACK)
        }

        test("every rung of the ladder sits inside the bounds") {
            // ⛔ The bounds and the ladder are separate declarations, so nothing but this stops a
            // rung being added outside the range the slider can reach — a preset the chips offer
            // and the slider cannot represent.
            PLAYBACK_SPEED_STEPS.forEach { rung ->
                (rung >= PLAYBACK_SPEED_MIN) shouldBe true
                (rung <= PLAYBACK_SPEED_MAX) shouldBe true
            }
        }

        test("a rate that made a round trip still matches the rung it came from") {
            // ⛔ The reason this is not `==`: a speed goes through storage and a platform player,
            // so 1.5 comes back as 1.4999999. Every caller comparing one has to survive that.
            isSamePlaybackSpeed(1.4999999f, 1.5f) shouldBe true
        }

        test("two different rungs are never the same rate") {
            PLAYBACK_SPEED_STEPS.zipWithNext().forEach { (lower, upper) ->
                isSamePlaybackSpeed(lower, upper) shouldBe false
            }
        }

        test("a rate between two rungs matches neither") {
            // What lets the browser's chips mark nothing rather than the nearest rung.
            isSamePlaybackSpeed(1.35f, 1.25f) shouldBe false
            isSamePlaybackSpeed(1.35f, 1.5f) shouldBe false
        }
    })

/** Float slack for a snapped value — far tighter than the increment being snapped to. */
private const val SNAP_SLACK = 0.001f
