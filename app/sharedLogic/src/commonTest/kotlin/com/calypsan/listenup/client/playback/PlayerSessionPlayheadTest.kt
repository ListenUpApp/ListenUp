package com.calypsan.listenup.client.playback

import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The session's [BookPlayhead] names the book its position belongs to, and never pairs one book's position with
 * another: an engine reports the book it took before the position it starts that book at.
 */
class PlayerSessionPlayheadTest :
    FunSpec({
        test("the playhead carries the book the engine reported its position for") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    rig.withBook("b1")
                    rig.session.playBook(BookId("b1"), null)
                    runCurrent()
                    rig.engine.setPosition(50_000)
                    runCurrent()
                    rig.session.playhead.value shouldBe BookPlayhead(BookId("b1"), 50_000)
                } finally {
                    rig.close()
                }
            }
        }

        test("an engine that takes the next book before moving its position never lends the old position to it") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    rig.withBook("b1")
                    rig.session.playBook(BookId("b1"), null)
                    runCurrent()
                    rig.engine.setPosition(50_000)
                    runCurrent()

                    rig.withBook("b2")
                    // PlaybackManager's order: the engine names b2 first, and its playhead has not moved off b1's 50 s
                    // when the load returns (the same value, so the position flow says nothing new).
                    rig.engine.preparedPositionMs = 50_000
                    rig.engine.loadSuspension = { rig.engine.loadedBookId.value = BookId("b2") }
                    rig.session.playBook(BookId("b2"), null)
                    runCurrent()
                    rig.session.playhead.value shouldBe null

                    rig.engine.setPosition(1_000) // b2's own position arrives
                    runCurrent()
                    rig.session.playhead.value shouldBe BookPlayhead(BookId("b2"), 1_000)
                } finally {
                    rig.close()
                }
            }
        }
    })
