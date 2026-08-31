package com.calypsan.listenup.client.playback

import app.cash.turbine.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Deterministic coverage for [SleepTimerManager]'s duration timer, driven by the coroutines-test
 * virtual clock via the injected `nowMillis` seam. Pins the load-bearing "sleep timer actually
 * fires after the configured duration" behaviour (memory flags a historical "sleep timer no-op"
 * regression), and that a cancel before the deadline suppresses the fire entirely.
 */
class SleepTimerManagerTest :
    FunSpec({

        test("a one-minute duration timer fires exactly once when the minute elapses") {
            runTest {
                val manager = SleepTimerManager(scope = backgroundScope, nowMillis = testScheduler::currentTime)

                manager.sleepEvent.test {
                    manager.setTimer(SleepTimerMode.Duration(minutes = 1))

                    // Just short of the deadline: still counting down, nothing fired.
                    advanceTimeBy(59_000)
                    runCurrent()
                    expectNoEvents()
                    val active = manager.state.value
                    active.shouldBeInstanceOf<SleepTimerState.Active>()
                    active.remainingMs shouldBeGreaterThan 0L

                    // Cross the one-minute boundary: the timer fires and hands off to the fade-out.
                    advanceTimeBy(2_000)
                    runCurrent()
                    awaitItem() // exactly one sleep event
                    manager.state.value shouldBe SleepTimerState.FadingOut

                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("cancelling before the deadline suppresses the fire and resets to Inactive") {
            runTest {
                val manager = SleepTimerManager(scope = backgroundScope, nowMillis = testScheduler::currentTime)

                manager.sleepEvent.test {
                    manager.setTimer(SleepTimerMode.Duration(minutes = 1))
                    advanceTimeBy(30_000)
                    runCurrent()

                    manager.cancelTimer()
                    manager.state.value shouldBe SleepTimerState.Inactive

                    // Advance well past the original deadline — a cancelled timer must never fire.
                    advanceTimeBy(60_000)
                    runCurrent()
                    expectNoEvents()

                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("end of chapter fires at the end of THE chapter, not the one after it") {
            runTest {
                val manager = SleepTimerManager(scope = backgroundScope, nowMillis = testScheduler::currentTime)

                manager.sleepEvent.test {
                    // The listener has been in chapter 5 for a while — the consumer feeds this
                    // continuously from `currentChapter`, so the manager already knows where they
                    // are before they reach for the timer.
                    manager.onChapterChanged(4)

                    manager.setTimer(SleepTimerMode.EndOfChapter)

                    // ⛔ Chapter 5 just ended. THIS is the boundary the listener asked to stop at.
                    // Arming must not discard the baseline it already had, or the book plays on
                    // through a whole extra chapter — the one failure this feature cannot have,
                    // because the listener is asleep and cannot see it happen.
                    manager.onChapterChanged(5)
                    runCurrent()

                    awaitItem()
                    manager.state.value shouldBe SleepTimerState.FadingOut

                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("a cancelled end-of-chapter timer stays silent when the chapter turns over") {
            runTest {
                val manager = SleepTimerManager(scope = backgroundScope, nowMillis = testScheduler::currentTime)

                manager.sleepEvent.test {
                    manager.onChapterChanged(4)
                    manager.setTimer(SleepTimerMode.EndOfChapter)
                    manager.cancelTimer()

                    manager.onChapterChanged(5)
                    runCurrent()

                    expectNoEvents()
                    manager.state.value shouldBe SleepTimerState.Inactive

                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })
