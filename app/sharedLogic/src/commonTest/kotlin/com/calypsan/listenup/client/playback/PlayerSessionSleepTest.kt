package com.calypsan.listenup.client.playback

import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class PlayerSessionSleepTest :
    FunSpec({
        test("a custom 37-minute timer runs and fades, then pauses") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    rig.withBook("b1")
                    runCurrent()
                    rig.session.playBook(BookId("b1"), null)
                    runCurrent()
                    rig.session.setSleepTimer(SleepTimerMode.Duration(37))
                    (rig.sleep.state.value as SleepTimerState.Active).totalMs shouldBe 37 * 60_000L
                    advanceTimeBy(37 * 60_000L + 1_000)
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.SleepFading
                    advanceTimeBy(SLEEP_FADE_DURATION_MS + 200)
                    runCurrent()
                    rig.engine.calls shouldContain "pause"
                    rig.sleep.state.value shouldBe SleepTimerState.Inactive
                } finally {
                    rig.close()
                }
            }
        }

        test("minutes are bounded to 1..720") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    rig.session.setSleepTimer(SleepTimerMode.Duration(5_000))
                    (rig.sleep.state.value as SleepTimerState.Active).totalMs shouldBe 720 * 60_000L
                    rig.session.setSleepTimer(SleepTimerMode.Duration(0))
                    (rig.sleep.state.value as SleepTimerState.Active).totalMs shouldBe 60_000L
                } finally {
                    rig.close()
                }
            }
        }

        test("Keep listening during the fade restores full volume (PT7)") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    rig.withBook("b1")
                    runCurrent()
                    rig.session.playBook(BookId("b1"), null)
                    runCurrent()
                    rig.session.setSleepTimer(SleepTimerMode.Duration(1))
                    advanceTimeBy(61_000)
                    runCurrent()
                    advanceTimeBy(500)
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.SleepFading
                    rig.session.cancelSleepTimer()
                    runCurrent()
                    rig.engine.calls.last() shouldBe "setVolume:1.0"
                    rig.sleep.state.value shouldBe SleepTimerState.Inactive
                    rig.loaded.phase shouldBe PlayerPhase.Playing
                } finally {
                    rig.close()
                }
            }
        }

        test("a throwing fade still resets the timer, so it is never stuck fading") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    rig.withBook("b1")
                    runCurrent()
                    rig.session.playBook(BookId("b1"), null)
                    runCurrent()
                    rig.engine.volumeFailure = IllegalStateException("volume gone")
                    rig.session.setSleepTimer(SleepTimerMode.Duration(1))
                    advanceTimeBy(61_000)
                    runCurrent()
                    rig.sleep.state.value shouldBe SleepTimerState.Inactive
                    rig.loaded.phase shouldBe PlayerPhase.Playing
                } finally {
                    rig.close()
                }
            }
        }

        test("changing book cancels a running timer") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    rig.withBook("a")
                    rig.withBook("b")
                    runCurrent()
                    rig.session.playBook(BookId("a"), null)
                    runCurrent()
                    rig.session.setSleepTimer(SleepTimerMode.Duration(30))
                    rig.session.playBook(BookId("b"), null)
                    runCurrent()
                    rig.sleep.state.value shouldBe SleepTimerState.Inactive
                } finally {
                    rig.close()
                }
            }
        }

        test("end of chapter fires at the next chapter boundary") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    rig.withBook("b1")
                    runCurrent()
                    rig.session.playBook(BookId("b1"), 10_000)
                    runCurrent()
                    rig.session.setSleepTimer(SleepTimerMode.EndOfChapter)
                    rig.engine.setPosition(100_500)
                    runCurrent()
                    rig.sleep.state.value shouldBe SleepTimerState.FadingOut
                } finally {
                    rig.close()
                }
            }
        }
    })
