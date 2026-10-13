package com.calypsan.listenup.client.playback

import com.calypsan.listenup.client.test.fake.positionFor
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class PlayerSessionTransportTest :
    FunSpec({
        fun TestScope.playing(
            rig: PlayerSessionRig,
            at: Long = 0,
        ) {
            rig.withBook("b1")
            runCurrent()
            rig.session.playBook(BookId("b1"), at)
            runCurrent()
        }

        test("playPause pauses a playing book and resumes a paused one") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    playing(rig)
                    rig.session.playPause()
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Paused
                    rig.session.playPause()
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Playing
                } finally {
                    rig.close()
                }
            }
        }

        test("playPause on a restored book prepares at the saved position and plays (S3)") {
            runTest {
                val rig = PlayerSessionRig(this, storedBookId = "b1")
                try {
                    rig.withBook("b1")
                    rig.positions.setPosition(positionFor("b1", positionMs = 42_000))
                    runCurrent()
                    rig.session.playPause()
                    runCurrent()
                    rig.engine.loads shouldContainExactly listOf(BookId("b1") to PlaybackStart.Resume)
                    rig.loaded.phase shouldBe PlayerPhase.Playing
                } finally {
                    rig.close()
                }
            }
        }

        test("a skip on a restored book moves the pending start, prepares nothing, and Play starts there (Decision 5)") {
            runTest {
                val rig = PlayerSessionRig(this, storedBookId = "b1")
                try {
                    rig.withBook("b1")
                    rig.positions.setPosition(positionFor("b1", positionMs = 42_000))
                    rig.engine.preparedPositionMs = 42_000
                    runCurrent()
                    rig.session.skipBack()
                    runCurrent()
                    rig.session.positionMs.value shouldBe 32_000
                    rig.engine.loads shouldBe emptyList()
                    rig.session.playPause()
                    runCurrent()
                    rig.engine.loads shouldContainExactly
                        listOf(BookId("b1") to PlaybackStart.Moved(listOf(PlaybackMove.Skip(-10_000))))
                    rig.session.positionMs.value shouldBe 32_000
                } finally {
                    rig.close()
                }
            }
        }

        test("a skip on a restored book is replayed from the reconciled place: local 2 h, server 5 h, back 10 s → 5 h − 10 s") {
            runTest {
                val hour = 3_600_000L
                val rig = PlayerSessionRig(this, storedBookId = "b1")
                try {
                    rig.withBook("b1", durationMs = 6 * hour)
                    rig.positions.setPosition(positionFor("b1", positionMs = 2 * hour))
                    // The preparer's reconcile finds the server's newer 5 h.
                    rig.engine.preparedPositionMs = 5 * hour
                    runCurrent()
                    rig.session.skipBack()
                    runCurrent()
                    rig.session.positionMs.value shouldBe 2 * hour - 10_000
                    rig.session.playPause()
                    runCurrent()
                    rig.engine.loads
                        .single()
                        .second shouldBe PlaybackStart.Moved(listOf(PlaybackMove.Skip(-10_000)))
                    rig.session.positionMs.value shouldBe 5 * hour - 10_000
                } finally {
                    rig.close()
                }
            }
        }

        test("chapter steps on a restored book are moves too; a chapter tap is a picked place") {
            runTest {
                val rig = PlayerSessionRig(this, storedBookId = "b1")
                try {
                    rig.withBook("b1")
                    rig.positions.setPosition(positionFor("b1", positionMs = 42_000))
                    runCurrent()
                    rig.session.nextChapter()
                    runCurrent()
                    rig.session.positionMs.value shouldBe 100_000
                    rig.session.previousChapter()
                    runCurrent()
                    rig.session.positionMs.value shouldBe 0
                    rig.session.playPause()
                    runCurrent()
                    rig.engine.loads
                        .last()
                        .second shouldBe
                        PlaybackStart.Moved(listOf(PlaybackMove.NextChapter, PlaybackMove.PreviousChapter))
                } finally {
                    rig.close()
                }
            }
        }

        test("a chapter tap on a restored book starts exactly there") {
            runTest {
                val rig = PlayerSessionRig(this, storedBookId = "b1")
                try {
                    rig.withBook("b1")
                    rig.positions.setPosition(positionFor("b1", positionMs = 42_000))
                    runCurrent()
                    rig.session.seekToChapter(2)
                    rig.session.skipBack()
                    runCurrent()
                    rig.session.positionMs.value shouldBe 190_000
                    rig.session.playPause()
                    runCurrent()
                    rig.engine.loads
                        .single()
                        .second shouldBe PlaybackStart.At(190_000)
                } finally {
                    rig.close()
                }
            }
        }

        // runCurrent() after each engine fact below: the session reads its own derived flows (stateIn), and a real
        // engine reports a speed or a tick asynchronously anyway. The command under test still runs after it.
        test("skips scale with speed and clamp to the book") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    playing(rig, at = 295_000)
                    rig.engine.speed.value = 1.5f
                    runCurrent()
                    rig.session.skipForward()
                    rig.engine.calls.last() shouldBe "seekTo:300000"
                    rig.engine.setPosition(5_000)
                    runCurrent()
                    rig.session.skipBack()
                    rig.engine.calls.last() shouldBe "seekTo:0"
                } finally {
                    rig.close()
                }
            }
        }

        test("previous chapter: 3.1 s in restarts, 2.9 s in goes back, chapter 1 restarts (§3.3)") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    playing(rig)
                    rig.engine.setPosition(103_100)
                    runCurrent()
                    rig.session.previousChapter()
                    rig.engine.calls.last() shouldBe "seekTo:100000"
                    rig.engine.setPosition(102_900)
                    runCurrent()
                    rig.session.previousChapter()
                    rig.engine.calls.last() shouldBe "seekTo:0"
                    rig.engine.setPosition(50_000)
                    runCurrent()
                    rig.session.previousChapter()
                    rig.engine.calls.last() shouldBe "seekTo:0"
                } finally {
                    rig.close()
                }
            }
        }

        test("next chapter is a no-op at the last chapter") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    playing(rig)
                    rig.engine.setPosition(250_000)
                    runCurrent()
                    val before = rig.engine.calls.size
                    rig.session.nextChapter()
                    rig.engine.calls.size shouldBe before
                } finally {
                    rig.close()
                }
            }
        }

        test("a chapter index the list no longer has is ignored, never clamped") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    playing(rig)
                    val before = rig.engine.calls.size
                    rig.session.seekToChapter(99)
                    rig.engine.calls.size shouldBe before
                } finally {
                    rig.close()
                }
            }
        }

        test("a chapter tap on a finished book plays from that chapter (§4.5)") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    playing(rig)
                    rig.engine.phase.value = EnginePhase.ENDED
                    runCurrent()
                    rig.session.seekToChapter(1)
                    runCurrent()
                    rig.engine.loads.last() shouldBe (BookId("b1") to PlaybackStart.At(100_000))
                } finally {
                    rig.close()
                }
            }
        }

        test("seekWithinChapter on a chapterless book seeks within the whole book") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    rig.withBook("b1", chapters = emptyList(), durationMs = 1_000_000)
                    runCurrent()
                    rig.session.playBook(BookId("b1"), null)
                    runCurrent()
                    rig.session.seekWithinChapter(0.25f)
                    rig.engine.calls.last() shouldBe "seekTo:250000"
                } finally {
                    rig.close()
                }
            }
        }

        test("stepSpeed steps 0.05 and clamps; reset uses the user's default") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    playing(rig)
                    rig.engine.speed.value = 2.95f
                    runCurrent()
                    rig.session.stepSpeed(up = true)
                    runCurrent()
                    rig.session.stepSpeed(up = true)
                    rig.engine.speed.value shouldBe 3.0f
                    rig.preferences.defaultSpeed.value = 1.25f
                    rig.session.resetSpeed()
                    runCurrent()
                    rig.engine.calls.last() shouldBe "resetSpeed:1.25"
                } finally {
                    rig.close()
                }
            }
        }

        test("a negative chapter index is ignored, never clamped to the first") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    playing(rig)
                    val before = rig.engine.calls.size
                    rig.session.seekToChapter(-1)
                    rig.engine.calls.size shouldBe before
                } finally {
                    rig.close()
                }
            }
        }

        test("a chapter index on a chapterless book is ignored") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    rig.withBook("b1", chapters = emptyList())
                    runCurrent()
                    rig.session.playBook(BookId("b1"), null)
                    runCurrent()
                    val before = rig.engine.calls.size
                    rig.session.seekToChapter(0)
                    rig.engine.calls.size shouldBe before
                } finally {
                    rig.close()
                }
            }
        }

        test("the listener's own skip intervals size the skips, not the stock pair") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    rig.preferences.skipBackwardSec.value = 15
                    rig.preferences.skipForwardSec.value = 45
                    playing(rig, at = 100_000)
                    rig.session.skipBackwardSec.value shouldBe 15
                    rig.session.skipForwardSec.value shouldBe 45
                    rig.session.skipForward()
                    rig.engine.calls.last() shouldBe "seekTo:145000"
                    runCurrent()
                    rig.session.skipBack()
                    rig.engine.calls.last() shouldBe "seekTo:130000"
                } finally {
                    rig.close()
                }
            }
        }

        test("reset boost goes back to the listener's default boost, not to off") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    playing(rig)
                    rig.session.setBoost(6f)
                    rig.preferences.defaultBoostDb.value = 3f
                    rig.session.resetBoost()
                    runCurrent()
                    rig.engine.calls.last() shouldBe "resetBoost:3.0"
                    rig.engine.boostDb.value shouldBe 3f
                } finally {
                    rig.close()
                }
            }
        }

        test("resuming a paused book clears a latched engine error before it plays") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    playing(rig)
                    rig.session.playPause()
                    runCurrent()
                    rig.session.playPause()
                    rig.engine.calls.takeLast(2) shouldContainExactly listOf("clearError", "play")
                } finally {
                    rig.close()
                }
            }
        }

        test("the stock 10 s back and 30 s forward apply when the listener never set an interval") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    playing(rig, at = 100_000)
                    rig.session.skipForward()
                    rig.engine.calls.last() shouldBe "seekTo:130000"
                    runCurrent()
                    rig.session.skipBack()
                    rig.engine.calls.last() shouldBe "seekTo:120000"
                } finally {
                    rig.close()
                }
            }
        }

        test("an interval changed mid-session is picked up on the very next skip") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    playing(rig, at = 100_000)
                    rig.session.skipForward()
                    rig.engine.calls.last() shouldBe "seekTo:130000"
                    rig.preferences.skipForwardSec.value = 60
                    runCurrent()
                    rig.session.skipForward()
                    rig.engine.calls.last() shouldBe "seekTo:190000"
                } finally {
                    rig.close()
                }
            }
        }
    })
