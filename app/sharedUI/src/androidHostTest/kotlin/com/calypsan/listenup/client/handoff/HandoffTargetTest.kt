package com.calypsan.listenup.client.handoff

import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The handoff decision, tested where the platform cannot be.
 *
 * `MainActivity`'s `onHandoffActivityDataRequested` is unreachable from every lane this repo has —
 * Robolectric does not emulate an API 37 OS feature — so the Activity holds only glue and this
 * holds the judgement.
 */
class HandoffTargetTest :
    FunSpec({

        val playing = BookId("book-playing")
        val viewed = BookId("book-viewed")

        test("a book in the ears beats a book on the screen") {
            // ⛔ The ordering that matters. Browsing while listening is browsing WHILE listening —
            // handing over the glanced-at page would abandon what is actually playing.
            resolveHandoffTarget(playingBookId = playing, viewedBookId = viewed) shouldBe
                HandoffTarget.Book(playing)
        }

        test("what is on screen is offered when nothing is playing") {
            resolveHandoffTarget(playingBookId = null, viewedBookId = viewed) shouldBe
                HandoffTarget.Book(viewed)
        }

        test("a paused book still counts — the player is still holding it") {
            // Playback state is not consulted at all: `currentBookId` survives a pause, and someone
            // who paused to walk to another device is exactly who this feature is for.
            resolveHandoffTarget(playingBookId = playing, viewedBookId = null) shouldBe
                HandoffTarget.Book(playing)
        }

        test("nothing in either hand offers nothing, rather than the library") {
            // ⛔ Not a degenerate case — a deliberate refusal. A handoff that lands the receiver on
            // a generic library screen honours the system's promise emptily, which is worse than
            // withdrawing the affordance.
            resolveHandoffTarget(playingBookId = null, viewedBookId = null) shouldBe HandoffTarget.None
        }
    })
