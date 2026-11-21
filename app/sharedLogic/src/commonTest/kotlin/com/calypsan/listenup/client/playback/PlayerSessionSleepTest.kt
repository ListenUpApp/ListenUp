package com.calypsan.listenup.client.playback

import io.kotest.matchers.collections.shouldNotContain
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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

        test("closing the book during the fade puts the volume back") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    rig.withBook("b1")
                    runCurrent()
                    rig.session.playBook(BookId("b1"), null)
                    runCurrent()
                    rig.session.setSleepTimer(SleepTimerMode.Duration(1))
                    advanceTimeBy(61_500)
                    runCurrent()
                    rig.sleep.state.value shouldBe SleepTimerState.FadingOut
                    rig.session.closeBook()
                    runCurrent()
                    rig.engine.calls.last { it.startsWith("setVolume") } shouldBe "setVolume:1.0"
                    rig.sleep.state.value shouldBe SleepTimerState.Inactive
                } finally {
                    rig.close()
                }
            }
        }

        test("playing another book during the fade stops the fade, which never pauses the new book") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    rig.withBook("a")
                    rig.withBook("b")
                    runCurrent()
                    rig.session.playBook(BookId("a"), null)
                    runCurrent()
                    rig.session.setSleepTimer(SleepTimerMode.Duration(1))
                    advanceTimeBy(61_500)
                    runCurrent()
                    rig.sleep.state.value shouldBe SleepTimerState.FadingOut
                    rig.session.playBook(BookId("b"), null)
                    runCurrent()
                    val sinceSwitch = rig.engine.calls.size
                    advanceTimeBy(SLEEP_FADE_DURATION_MS + 500)
                    runCurrent()
                    rig.engine.calls.drop(sinceSwitch) shouldNotContain "pause"
                    rig.engine.calls.last { it.startsWith("setVolume") } shouldBe "setVolume:1.0"
                    rig.loaded.bookId shouldBe BookId("b")
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

        test("end of chapter fires at the next boundary after Close and reopening the book in the same chapter") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    rig.withBook("b1")
                    runCurrent()
                    rig.session.playBook(BookId("b1"), 10_000)
                    runCurrent()
                    rig.session.closeBook()
                    runCurrent()
                    rig.session.playBook(BookId("b1"), 20_000)
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

        test("end of chapter fires at the next boundary after switching to another book in the same chapter") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    rig.withBook("a")
                    rig.withBook("b")
                    runCurrent()
                    rig.session.playBook(BookId("a"), 10_000)
                    runCurrent()
                    rig.session.playBook(BookId("b"), 20_000)
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

        test("end of chapter survives Next chapter, and fires at the end of the chapter moved to") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    rig.withBook("b1")
                    runCurrent()
                    rig.session.playBook(BookId("b1"), 10_000)
                    runCurrent()
                    rig.session.setSleepTimer(SleepTimerMode.EndOfChapter)
                    rig.session.nextChapter()
                    runCurrent()
                    rig.sleep.state.value
                        .shouldBeInstanceOf<SleepTimerState.Active>()
                    // Listening on, unprompted, across the end of the chapter the listener is now in.
                    rig.engine.setPosition(200_500)
                    runCurrent()
                    rig.sleep.state.value shouldBe SleepTimerState.FadingOut
                } finally {
                    rig.close()
                }
            }
        }

        test("end of chapter survives a scrub across a boundary") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    rig.withBook("b1")
                    runCurrent()
                    rig.session.playBook(BookId("b1"), 10_000)
                    runCurrent()
                    rig.session.setSleepTimer(SleepTimerMode.EndOfChapter)
                    rig.session.seekTo(150_000)
                    runCurrent()
                    rig.sleep.state.value
                        .shouldBeInstanceOf<SleepTimerState.Active>()
                    // Listening on, unprompted, across the end of the chapter the listener is now in.
                    rig.engine.setPosition(200_500)
                    runCurrent()
                    rig.sleep.state.value shouldBe SleepTimerState.FadingOut
                } finally {
                    rig.close()
                }
            }
        }

        test("end of chapter survives a chapter tap") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    rig.withBook("b1")
                    runCurrent()
                    rig.session.playBook(BookId("b1"), 10_000)
                    runCurrent()
                    rig.session.setSleepTimer(SleepTimerMode.EndOfChapter)
                    rig.session.seekToChapter(1)
                    runCurrent()
                    rig.sleep.state.value
                        .shouldBeInstanceOf<SleepTimerState.Active>()
                    // Listening on, unprompted, across the end of the chapter the listener is now in.
                    rig.engine.setPosition(200_500)
                    runCurrent()
                    rig.sleep.state.value shouldBe SleepTimerState.FadingOut
                } finally {
                    rig.close()
                }
            }
        }

        test("end of chapter survives a skip forward across a boundary") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    rig.withBook("b1")
                    runCurrent()
                    rig.session.playBook(BookId("b1"), 95_000)
                    runCurrent()
                    rig.session.setSleepTimer(SleepTimerMode.EndOfChapter)
                    rig.session.skipForward()
                    runCurrent()
                    rig.sleep.state.value
                        .shouldBeInstanceOf<SleepTimerState.Active>()
                    // Listening on, unprompted, across the end of the chapter the listener is now in.
                    rig.engine.setPosition(200_500)
                    runCurrent()
                    rig.sleep.state.value shouldBe SleepTimerState.FadingOut
                } finally {
                    rig.close()
                }
            }
        }

        test("a move back re-baselines: re-crossing the boundary by listening fires once") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    rig.withBook("b1")
                    runCurrent()
                    rig.session.playBook(BookId("b1"), 150_000)
                    runCurrent()
                    rig.session.setSleepTimer(SleepTimerMode.EndOfChapter)
                    rig.session.seekToChapter(0)
                    runCurrent()
                    rig.sleep.state.value
                        .shouldBeInstanceOf<SleepTimerState.Active>()
                    // Listening on, unprompted, across the end of the chapter the listener is now in.
                    rig.engine.setPosition(100_500)
                    runCurrent()
                    rig.sleep.state.value shouldBe SleepTimerState.FadingOut
                } finally {
                    rig.close()
                }
            }
        }

        test("extending a running timer adds to what is left, and cancelling disarms it") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    rig.withBook("b1")
                    runCurrent()
                    rig.session.playBook(BookId("b1"), null)
                    runCurrent()
                    rig.session.setSleepTimer(SleepTimerMode.Duration(15))
                    val before = (rig.sleep.state.value as SleepTimerState.Active).remainingMs
                    rig.session.extendSleepTimer(10)
                    (rig.sleep.state.value as SleepTimerState.Active).remainingMs shouldBe before + 10 * 60_000L
                    rig.session.cancelSleepTimer()
                    rig.sleep.state.value shouldBe SleepTimerState.Inactive
                    rig.engine.calls shouldNotContain "pause"
                } finally {
                    rig.close()
                }
            }
        }
    })
