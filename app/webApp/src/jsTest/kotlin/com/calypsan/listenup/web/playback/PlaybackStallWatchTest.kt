package com.calypsan.listenup.web.playback

import com.calypsan.listenup.client.playback.PLAYBACK_DROP_GRACE
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** Comfortably inside the grace: a slow stream lands a fragment this often. */
private const val SLOW_FRAGMENT_EVERY_MS = 4_000L

/** Long enough that a watch which should have fired several times over would have. */
private const val SLOW_STREAM_RUNS = 15

private const val PART_WAY_MS = 5_000L

/**
 * The browser's half of "a dead connection fails at the grace" (Simon, 2026-10-14): a network that silently drops
 * every packet gives hls.js and the element nothing to report for about 30 s, so the web engine watches for progress
 * itself. A stall that moves no bytes and no clock for a whole [PLAYBACK_DROP_GRACE] fails, as Media3 does on
 * Android; a slow stream that keeps landing fragments never does. Driven on virtual time.
 */
class PlaybackStallWatchTest :
    FunSpec({
        val graceMs = PLAYBACK_DROP_GRACE.inWholeMilliseconds

        test("a stall with no progress fails at the grace, and not before") {
            runTest {
                var failures = 0
                val watch = PlaybackStallWatch(backgroundScope) { failures++ }
                watch.stalled()
                advanceTimeBy(graceMs - 1)
                runCurrent()
                failures shouldBe 0
                advanceTimeBy(1)
                runCurrent()
                failures shouldBe 1
            }
        }

        test("a slow stream that keeps making progress never fails") {
            runTest {
                var failures = 0
                val watch = PlaybackStallWatch(backgroundScope) { failures++ }
                watch.stalled()
                repeat(SLOW_STREAM_RUNS) {
                    advanceTimeBy(SLOW_FRAGMENT_EVERY_MS)
                    runCurrent()
                    watch.progressed()
                    // The element reports the stall again between fragments: the same stall, not a new window.
                    watch.stalled()
                }
                failures shouldBe 0
                // Once the bytes stop, the grace counts from the last progress.
                advanceTimeBy(graceMs - 1)
                runCurrent()
                failures shouldBe 0
                advanceTimeBy(1)
                runCurrent()
                failures shouldBe 1
            }
        }

        test("audio moving again, or a pause, ends the watch") {
            runTest {
                var failures = 0
                val watch = PlaybackStallWatch(backgroundScope) { failures++ }
                watch.stalled()
                advanceTimeBy(PART_WAY_MS)
                runCurrent()
                watch.stop()
                advanceTimeBy(graceMs * 3)
                runCurrent()
                failures shouldBe 0
                // A later stall gets a whole grace of its own.
                watch.stalled()
                advanceTimeBy(graceMs - 1)
                runCurrent()
                failures shouldBe 0
                advanceTimeBy(1)
                runCurrent()
                failures shouldBe 1
            }
        }

        test("progress outside a stall arms nothing") {
            runTest {
                var failures = 0
                val watch = PlaybackStallWatch(backgroundScope) { failures++ }
                watch.progressed()
                advanceTimeBy(graceMs * 3)
                runCurrent()
                failures shouldBe 0
            }
        }
    })
