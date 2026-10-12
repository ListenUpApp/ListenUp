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
                    rig.engine.loads shouldContainExactly listOf(BookId("b1") to null)
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
                    runCurrent()
                    rig.session.skipBack()
                    runCurrent()
                    rig.session.positionMs.value shouldBe 32_000
                    rig.engine.loads shouldBe emptyList()
                    rig.session.playPause()
                    runCurrent()
                    rig.engine.loads shouldContainExactly listOf(BookId("b1") to 32_000L)
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
                    rig.engine.loads.last() shouldBe (BookId("b1") to 100_000L)
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
    })
