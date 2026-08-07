package com.calypsan.listenup.client.playback

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Tests for [taskRemovedActionFor] — what swiping the app away should do to the service.
 *
 * The bug that motivates this: the service decided by checking whether an idle timer happened
 * to be armed. Swiping away first sets `playWhenReady = false`, but Media3 posts the resulting
 * `onIsPlayingChanged` asynchronously, so the timer that pause arms had *not* been armed yet
 * when the check ran. Swiping away mid-listen therefore stopped the service outright — killing
 * the very notification the code was trying to leave behind — while swiping away when already
 * paused (timer already armed) correctly survived. Exactly inverted.
 */
class TaskRemovedActionTest :
    FunSpec({

        test("swiping away while a player exists leaves the notification and arms the timer") {
            // The regression: this case used to stop the service immediately, because the pause
            // it had just requested had not yet been delivered.
            taskRemovedActionFor(hasPlayer = true, casting = false) shouldBe
                TaskRemovedAction.ARM_IDLE_TIMER
        }

        test("swiping away with no player stops the service") {
            taskRemovedActionFor(hasPlayer = false, casting = false) shouldBe
                TaskRemovedAction.STOP_SERVICE
        }

        test("swiping away during a cast session leaves the session alone") {
            // Audio is playing on another device. Arming a teardown timer — or stopping outright —
            // would end someone's listening because they tidied up their recent-apps list.
            taskRemovedActionFor(hasPlayer = true, casting = true) shouldBe
                TaskRemovedAction.KEEP_ALIVE
        }

        test("a cast session with no local player still keeps the service alive") {
            taskRemovedActionFor(hasPlayer = false, casting = true) shouldBe
                TaskRemovedAction.KEEP_ALIVE
        }
    })
