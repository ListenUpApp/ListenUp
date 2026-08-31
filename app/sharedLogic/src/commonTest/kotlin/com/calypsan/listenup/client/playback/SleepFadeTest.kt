package com.calypsan.listenup.client.playback

import com.calypsan.listenup.client.test.fake.FakePlaybackController
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeSorted
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * What a sleep timer firing sounds like, pinned on the virtual clock.
 *
 * The ordering is the whole point and it is invisible from the outside: a fade that paused first
 * would cut the book off mid-word, and one that restored the volume before pausing would let the
 * last instant come back at full volume — both of which end with a paused player at volume 1,
 * which is to say both look identical to any assertion made after the fact. Stepping the clock is
 * what tells them apart.
 *
 * Every step is an explicit [advanceTimeBy]. `advanceUntilIdle` does NOT drive work launched on
 * `backgroundScope` — it would never reach idle if it did — so reaching for it here reports a fade
 * that made no calls at all rather than one that failed.
 */
class SleepFadeTest :
    FunSpec({

        /** Comfortably past the restore that follows the pause, without depending on its length. */
        val wellPastTheEnd = SLEEP_FADE_DURATION_MS + 500

        test("the volume ramps down over the whole fade rather than simply muting") {
            runTest {
                val controller = FakePlaybackController()
                backgroundScope.launch { fadeOutAndPause(controller) }

                advanceTimeBy(SLEEP_FADE_DURATION_MS / 2)
                runCurrent()

                // Halfway: quieter, still audible, still playing. A fade that jumped straight to
                // silence would satisfy every end-state assertion and fail this one.
                val partway = controller.volumeCalls
                partway.last() shouldBeLessThan 1f
                partway.last() shouldBeGreaterThan 0f
                controller.pauseCount shouldBe 0
                // Descending throughout, never a step back up — the ramp a listener hears.
                partway.map { -it }.shouldBeSorted()
            }
        }

        test("silence lands before the pause, so the book is never cut off mid-word") {
            runTest {
                val controller = FakePlaybackController()
                backgroundScope.launch { fadeOutAndPause(controller) }

                // Past the ramp, short of the restore: the one moment where the ordering shows.
                advanceTimeBy(SLEEP_FADE_DURATION_MS + 1)
                runCurrent()

                controller.volumeCalls.last() shouldBe 0f
                controller.pauseCount shouldBe 1
            }
        }

        test("the volume is put back, so the next book does not start silent") {
            runTest {
                val controller = FakePlaybackController()
                backgroundScope.launch { fadeOutAndPause(controller) }

                advanceTimeBy(wellPastTheEnd)
                runCurrent()

                // ⛔ The bug this exists for: the controller outlives the fade, so a volume left at
                // zero makes the NEXT book play with a working transport bar and no sound at all,
                // and nothing on screen to explain it.
                controller.volumeCalls.last() shouldBe 1f
                controller.pauseCount shouldBe 1
            }
        }
    })
