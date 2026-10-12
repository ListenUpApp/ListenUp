package com.calypsan.listenup.client.playback

import io.kotest.matchers.types.shouldBeInstanceOf
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.api.result.AppResult
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
                    rig.engine.loads.map { it.second } shouldContainExactly listOf(null, 100_000L)
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
    })
