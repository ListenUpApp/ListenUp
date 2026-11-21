package com.calypsan.listenup.client.playback

import com.calypsan.listenup.client.test.fake.positionFor
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private const val HOUR = 3_600_000L
private const val BOOK_MS = 10 * HOUR

/** The stamp this device's own last save carried, before it paused. */
private const val OWN_STAMP = 1_000L

/** A later stamp: another device listened after this one paused. */
private const val LATER_STAMP = 2_000L

/** [rowAt] [positionMs] for "b1", stamped [lastPlayedAtMs]. */
private fun rowAt(
    positionMs: Long,
    lastPlayedAtMs: Long,
    isFinished: Boolean = false,
) = positionFor("b1", positionMs = positionMs, isFinished = isFinished).copy(lastPlayedAtMs = lastPlayedAtMs)

/** Play "b1" from 1 h (this device's own save), then pause it, leaving the engine holding the book at 1 h. */
private fun TestScope.pausedAtOneHour(rig: PlayerSessionRig) {
    rig.withBook("b1", chapters = emptyList(), durationMs = BOOK_MS)
    rig.engine.preparedPositionMs = HOUR
    rig.positions.setPosition(rowAt(HOUR, OWN_STAMP))
    runCurrent()
    rig.session.playBook(BookId("b1"), startAtMs = null)
    runCurrent()
    rig.engine.pause()
    runCurrent()
}

class PlayerSessionRemotePositionTest :
    FunSpec({
        test("a paused engine adopts a newer row from another device: Play seeks to it, then plays") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    pausedAtOneHour(rig)
                    rig.positions.setPosition(rowAt(3 * HOUR, LATER_STAMP))
                    runCurrent()
                    rig.session.playBook(BookId("b1"), startAtMs = null)
                    rig.engine.loads.size shouldBe 1
                    rig.engine.calls.takeLast(3) shouldContainExactly listOf("seekTo:${3 * HOUR}", "clearError", "play")
                } finally {
                    rig.close()
                }
            }
        }

        test("a row this device already accounts for (same stamp) is not adopted, however far it is") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    pausedAtOneHour(rig)
                    rig.positions.setPosition(rowAt(3 * HOUR, OWN_STAMP))
                    runCurrent()
                    rig.session.playBook(BookId("b1"), startAtMs = null)
                    rig.engine.calls.takeLast(2) shouldContainExactly listOf("clearError", "play")
                    rig.engine.calls.filter { it.startsWith("seekTo") } shouldBe emptyList()
                } finally {
                    rig.close()
                }
            }
        }

        test("a newer row within 5 s of the playhead (this device's own pause save) is not a reason to seek") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    pausedAtOneHour(rig)
                    rig.positions.setPosition(rowAt(HOUR + 4_000, LATER_STAMP))
                    runCurrent()
                    rig.session.playBook(BookId("b1"), startAtMs = null)
                    rig.engine.calls.takeLast(2) shouldContainExactly listOf("clearError", "play")
                    rig.engine.calls.filter { it.startsWith("seekTo") } shouldBe emptyList()
                } finally {
                    rig.close()
                }
            }
        }

        test("the headset's play/pause adopts the newer row the same way") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    pausedAtOneHour(rig)
                    rig.positions.setPosition(rowAt(3 * HOUR, LATER_STAMP))
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Paused
                    rig.session.playPause()
                    rig.engine.loads.size shouldBe 1
                    rig.engine.calls.takeLast(3) shouldContainExactly listOf("seekTo:${3 * HOUR}", "clearError", "play")
                } finally {
                    rig.close()
                }
            }
        }

        test("a newer row that finished the book is not resumed from the old place: Play decides from the finish") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    pausedAtOneHour(rig)
                    rig.positions.setPosition(rowAt(BOOK_MS, LATER_STAMP, isFinished = true))
                    runCurrent()
                    rig.session.playBook(BookId("b1"), startAtMs = null)
                    runCurrent()
                    rig.engine.calls shouldNotContain "seekTo:$HOUR"
                    rig.engine.loads.map { it.second } shouldContainExactly
                        listOf(PlaybackStart.Resume, PlaybackStart.Resume)
                } finally {
                    rig.close()
                }
            }
        }
    })
