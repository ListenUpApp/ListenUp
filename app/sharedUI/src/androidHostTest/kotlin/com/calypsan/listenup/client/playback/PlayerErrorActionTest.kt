package com.calypsan.listenup.client.playback

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Tests for [playerErrorActionFor] — what a reported playback error can still act on.
 *
 * The bug that motivates this: the recovery block read `player!!` from inside the coroutine it
 * launched. `serviceScope` runs on plain `Dispatchers.Main`, so that body executes on a later
 * looper turn — and `onDestroy` nulls `player` one statement before it cancels the scope. A
 * player error arriving as the service tore down could therefore dereference null and take the
 * playback process with it.
 */
class PlayerErrorActionTest :
    FunSpec({

        test("an attached player is handed to the recovery handler") {
            playerErrorActionFor(hasPlayer = true) shouldBe PlayerErrorAction.RECOVER
        }

        test("a released player leaves nothing to recover onto") {
            // The regression: this case used to be a null dereference rather than a decision.
            playerErrorActionFor(hasPlayer = false) shouldBe PlayerErrorAction.NOTHING_TO_RECOVER
        }
    })
