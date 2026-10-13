package com.calypsan.listenup.client.playback

import app.cash.turbine.test
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldNotBeInstanceOf
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.test.fake.positionFor
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class PlayerSessionPlayTest :
    FunSpec({
        test("a successful load: Preparing names the new book at once, then Playing, and the key is written") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    rig.withBook("b1")
                    val gate = CompletableDeferred<Unit>()
                    rig.engine.loadSuspension = { gate.await() }
                    runCurrent()
                    rig.session.playBook(BookId("b1"), startAtMs = null)
                    rig.session.preparingBookId.value shouldBe BookId("b1")
                    // `state` is a stateIn: let it observe the stage. The gate keeps the load suspended, so this
                    // still sees the Preparing frame, not the result.
                    runCurrent()
                    rig.loaded.bookId shouldBe BookId("b1")
                    rig.loaded.phase shouldBe PlayerPhase.Preparing
                    rig.session.preparingBookId.value shouldBe BookId("b1")
                    gate.complete(Unit)
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Playing
                    rig.loaded.isEngineBound shouldBe true
                    rig.store.stored shouldBe "b1"
                    rig.session.preparingBookId.value shouldBe null
                } finally {
                    rig.close()
                }
            }
        }

        test("the engine's beginLoad runs inside the call, before any suspension (the web's gesture rule)") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    rig.withBook("b1")
                    rig.engine.loadSuspension = { CompletableDeferred<Unit>().await() }
                    runCurrent()
                    rig.session.playBook(BookId("b1"), null)
                    // No runCurrent(): a browser's activation would be gone by the next dispatch.
                    rig.engine.calls.first() shouldBe "beginLoad:b1"
                } finally {
                    rig.close()
                }
            }
        }

        test("playBook(B) while A plays names B immediately (§3.6)") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    rig.withBook("a")
                    rig.withBook("b")
                    runCurrent()
                    rig.session.playBook(BookId("a"), null)
                    runCurrent()
                    rig.engine.loadSuspension = { CompletableDeferred<Unit>().await() }
                    rig.session.playBook(BookId("b"), null)
                    // The load of B never completes here, so this observes the stage, not a result.
                    runCurrent()
                    rig.loaded.bookId shouldBe BookId("b")
                    rig.loaded.phase shouldBe PlayerPhase.Preparing
                } finally {
                    rig.close()
                }
            }
        }

        test("A → B → A: only the last load lands, and the superseded ones withdraw their prime") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    rig.withBook("a")
                    rig.withBook("b")
                    runCurrent()
                    val gates = ArrayDeque<CompletableDeferred<Unit>>()
                    rig.engine.loadSuspension = { CompletableDeferred<Unit>().also(gates::addLast).await() }
                    rig.session.playBook(BookId("a"), null)
                    rig.session.playBook(BookId("b"), null)
                    rig.session.playBook(BookId("a"), null)
                    runCurrent()
                    gates.last().complete(Unit)
                    runCurrent()
                    rig.loaded.bookId shouldBe BookId("a")
                    rig.loaded.phase shouldBe PlayerPhase.Playing
                    rig.engine.calls.count { it == "abandonLoad" } shouldBe 2
                } finally {
                    rig.close()
                }
            }
        }

        test("a repeated plain tap while preparing is swallowed; a chapter tap supersedes") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    rig.withBook("b1")
                    runCurrent()
                    rig.engine.loadSuspension = { CompletableDeferred<Unit>().await() }
                    rig.session.playBook(BookId("b1"), null)
                    rig.session.playBook(BookId("b1"), null)
                    runCurrent()
                    rig.engine.loads.size shouldBe 1
                    rig.session.playBook(BookId("b1"), 100_000)
                    runCurrent()
                    rig.engine.loads.map { it.second } shouldContainExactly listOf(PlaybackStart.Resume, PlaybackStart.At(100_000))
                } finally {
                    rig.close()
                }
            }
        }

        test("Play from Book Detail on the restored book keeps the place the listener moved it to") {
            runTest {
                val rig = PlayerSessionRig(this, storedBookId = "b1")
                try {
                    rig.withBook("b1")
                    rig.positions.setPosition(positionFor("b1", positionMs = 42_000))
                    runCurrent()
                    rig.session.skipBack()
                    rig.session.playBook(BookId("b1"), null)
                    runCurrent()
                    rig.engine.loads.single() shouldBe (BookId("b1") to PlaybackStart.Moved(listOf(PlaybackMove.Skip(-10_000))))
                } finally {
                    rig.close()
                }
            }
        }

        test("the book the engine already holds resumes, and a start position seeks; no re-prepare") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    rig.withBook("b1")
                    runCurrent()
                    rig.session.playBook(BookId("b1"), null)
                    runCurrent()
                    rig.engine.pause()
                    runCurrent()
                    rig.session.playBook(BookId("b1"), 200_000)
                    rig.engine.loads.size shouldBe 1
                    rig.engine.calls.takeLast(3) shouldContainExactly listOf("seekTo:200000", "clearError", "play")
                } finally {
                    rig.close()
                }
            }
        }

        test("a failed prepare leaves the requested book loaded, Failed with the typed error, and withdraws the prime") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    rig.withBook("b1")
                    rig.engine.loadResult = AppResult.Failure(PlaybackError.CouldNotStart())
                    runCurrent()
                    rig.session.playBook(BookId("b1"), null)
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Failed(PlaybackError.CouldNotStart())
                    rig.store.stored shouldBe "b1"
                    rig.engine.calls.last() shouldBe "abandonLoad"
                } finally {
                    rig.close()
                }
            }
        }

        test("Listen again on a finished book re-prepares from the choke point (it starts at 0 and re-listens)") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    rig.withBook("b1")
                    runCurrent()
                    rig.session.playBook(BookId("b1"), null)
                    runCurrent()
                    rig.engine.phase.value = EnginePhase.ENDED
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Finished
                    rig.session.playBook(BookId("b1"), null)
                    runCurrent()
                    rig.engine.loads.size shouldBe 2
                } finally {
                    rig.close()
                }
            }
        }

        test("a load that throws is Failed(CouldNotStart), not swallowed, and withdraws the prime") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    rig.withBook("b1")
                    rig.engine.loadSuspension = { error("prepare failed") }
                    runCurrent()
                    rig.session.playBook(BookId("b1"), null)
                    runCurrent()
                    rig.loaded.phase
                        .shouldBeInstanceOf<PlayerPhase.Failed>()
                        .error
                        .shouldBeInstanceOf<PlaybackError.CouldNotStart>()
                    rig.engine.calls.last() shouldBe "abandonLoad"
                    rig.session.preparingBookId.value shouldBe null
                } finally {
                    rig.close()
                }
            }
        }

        test("preparingBookIdUi is a cold flow: the session parks no collector for it until someone subscribes") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    // A `.stateIn`/`.shareIn` here would park a never-ending collector in the session's scope
                    // for every session built, subscribed or not (the bug PlaybackManagerPreparingSharingTest
                    // guarded on the old manager). Hot state is the consumer's job.
                    rig.session.preparingBookIdUi.shouldNotBeInstanceOf<SharedFlow<*>>()
                    rig.withBook("b1")
                    rig.engine.loadSuspension = { CompletableDeferred<Unit>().await() }
                    runCurrent()
                    rig.session.preparingBookIdUi.test {
                        awaitItem() shouldBe null
                        rig.session.playBook(BookId("b1"), null)
                        advanceTimeBy(PREPARING_UI_DELAY.inWholeMilliseconds + 1)
                        runCurrent()
                        awaitItem() shouldBe BookId("b1")
                    }
                } finally {
                    rig.close()
                }
            }
        }

        test("a start position on another book prepares that book there, not where the engine is") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    rig.withBook("b1")
                    rig.withBook("b2")
                    runCurrent()
                    rig.session.playBook(BookId("b1"), null)
                    runCurrent()
                    rig.session.playBook(BookId("b2"), 50_000)
                    runCurrent()
                    rig.engine.loads.last() shouldBe (BookId("b2") to PlaybackStart.At(50_000))
                    rig.loaded.bookId shouldBe BookId("b2")
                } finally {
                    rig.close()
                }
            }
        }
    })
