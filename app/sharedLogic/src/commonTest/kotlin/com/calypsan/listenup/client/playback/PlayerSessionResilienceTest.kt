package com.calypsan.listenup.client.playback

import com.calypsan.listenup.client.test.fake.positionFor
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** One failed read upstream of the player's eager pipelines must not freeze them for the rest of the process. */
class PlayerSessionResilienceTest :
    FunSpec({
        test("one failed position read does not freeze the readout") {
            runTest {
                val rig = PlayerSessionRig(this, storedBookId = "b")
                try {
                    rig.withBook("b")
                    rig.positions.failNextObserve = IllegalStateException("database busy")
                    runCurrent()
                    advanceTimeBy(5_000)
                    runCurrent()

                    rig.positions.setPosition(positionFor("b", positionMs = 42_000))
                    runCurrent()

                    rig.session.positionMs.value shouldBe 42_000
                } finally {
                    rig.close()
                }
            }
        }

        test("one failed position read while switching books does not freeze the state") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    rig.withBook("a")
                    rig.withBook("b")
                    runCurrent()
                    rig.session.playBook(BookId("a"), null)
                    runCurrent()

                    rig.positions.failNextObserve = IllegalStateException("database busy")
                    rig.session.playBook(BookId("b"), null)
                    runCurrent()
                    advanceTimeBy(5_000)
                    runCurrent()
                    rig.loaded.bookId shouldBe BookId("b")

                    rig.session.playPause()
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Paused
                } finally {
                    rig.close()
                }
            }
        }

        test("switching books never shows or uses the last book's chapters") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    // The engine prepares the last book given, so "a" goes last: it is the one that plays first.
                    rig.withBook("b", chapters = threeChapters.take(2).map { it.copy(id = "b-" + it.id) })
                    rig.withBook("a")
                    runCurrent()
                    rig.session.playBook(BookId("a"), null)
                    runCurrent()
                    rig.session.chapters.value.size shouldBe 3

                    rig.books.holdChapters("b")
                    rig.engine.loadSuspension = { CompletableDeferred<Unit>().await() }
                    rig.session.playBook(BookId("b"), null)
                    runCurrent()
                    rig.session.chapters.value shouldBe emptyList()

                    rig.session.seekToChapter(2)
                    runCurrent()
                    rig.session.pendingStartMs.value shouldBe null

                    rig.books.releaseChapters("b")
                    runCurrent()
                    rig.session.chapters.value
                        .map { it.id } shouldBe listOf("b-c1", "b-c2")
                } finally {
                    rig.close()
                }
            }
        }

        test("a failed save of the loaded book does not fail Play") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    rig.withBook("a")
                    rig.store.failSaves = true
                    runCurrent()
                    rig.session.playBook(BookId("a"), null)
                    runCurrent()

                    rig.loaded.bookId shouldBe BookId("a")
                    rig.loaded.phase shouldBe PlayerPhase.Playing
                } finally {
                    rig.close()
                }
            }
        }
    })
