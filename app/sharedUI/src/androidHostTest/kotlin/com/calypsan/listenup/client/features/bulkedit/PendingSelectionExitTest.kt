package com.calypsan.listenup.client.features.bulkedit

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The handover that ends a selection after the editor that consumed it has already popped.
 *
 * The second test is the one that matters: the same instance outlives every editor visit, so an
 * exit that fired for one selection must not fire again over whatever the user picks next.
 */
class PendingSelectionExitTest :
    FunSpec({
        test("firing ends the armed selection") {
            var ended = 0
            val pending = PendingSelectionExit()
            pending.arm { ended++ }

            pending.fireAndDisarm()

            ended shouldBe 1
        }

        test("firing twice ends only the selection that was armed") {
            var ended = 0
            val pending = PendingSelectionExit()
            pending.arm { ended++ }

            pending.fireAndDisarm()
            pending.fireAndDisarm()

            ended shouldBe 1
        }

        test("firing with nothing armed leaves the selection alone") {
            PendingSelectionExit().fireAndDisarm()
        }
    })
