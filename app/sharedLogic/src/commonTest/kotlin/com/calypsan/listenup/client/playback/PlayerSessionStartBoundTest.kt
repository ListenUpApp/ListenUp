package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.test.fake.preparedPlaybackFor
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A start the engine never settles ends in a failure the listener can retry, on every engine: never a spinner forever. */
class PlayerSessionStartBoundTest :
    FunSpec({
        /** A rig over a real [NativePlayerEngine] (the iOS engine) whose prepares all succeed, recorded in [prepared]. */
        fun TestScope.nativeRig(
            prepared: MutableList<Pair<BookId, PlaybackStart>> = mutableListOf(),
        ): Pair<PlayerSessionRig, () -> NativePlayerEngine> {
            var engine: NativePlayerEngine? = null
            val rig =
                PlayerSessionRig(this, playerEngine = { scope ->
                    NativePlayerEngine(
                        prepare = { id, start ->
                            prepared += id to start
                            AppResult.Success(preparedPlaybackFor(id.value, durationMs = 300_000))
                        },
                        scope = scope,
                    ).also { engine = it }
                })
            return rig to { checkNotNull(engine) }
        }

        test("a book Swift never starts is Couldn't start once the start bound runs out, and Buffering until then") {
            runTest {
                val (rig, _) = nativeRig()
                try {
                    rig.withBook("a")
                    runCurrent()
                    rig.session.playBook(BookId("a"), null)
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Buffering
                    advanceTimeBy(ENGINE_START_BOUND - 1.milliseconds)
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Buffering
                    advanceTimeBy(2.milliseconds)
                    runCurrent()
                    rig.loaded.phase
                        .shouldBeInstanceOf<PlayerPhase.Failed>()
                        .error
                        .shouldBeInstanceOf<PlaybackError.CouldNotStart>()
                } finally {
                    rig.close()
                }
            }
        }

        test("a start that reaches playing inside the bound is never failed by it") {
            runTest {
                val (rig, engine) = nativeRig()
                try {
                    rig.withBook("a")
                    runCurrent()
                    rig.session.playBook(BookId("a"), null)
                    runCurrent()
                    advanceTimeBy(ENGINE_START_BOUND - 5.seconds)
                    engine().onPhase(1, EnginePhase.PLAYING)
                    runCurrent()
                    advanceTimeBy(ENGINE_START_BOUND * 2)
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Playing
                } finally {
                    rig.close()
                }
            }
        }

        test("Play after a start that never settled prepares the book again and hands Swift a fresh load") {
            runTest {
                val prepared = mutableListOf<Pair<BookId, PlaybackStart>>()
                val (rig, engine) = nativeRig(prepared)
                try {
                    rig.withBook("a")
                    runCurrent()
                    rig.session.playBook(BookId("a"), null)
                    runCurrent()
                    advanceTimeBy(ENGINE_START_BOUND + 1.seconds)
                    runCurrent()
                    rig.loaded.phase.shouldBeInstanceOf<PlayerPhase.Failed>()

                    rig.session.playPause()
                    runCurrent()

                    prepared.size shouldBe 2
                    rig.loaded.phase shouldBe PlayerPhase.Buffering
                    engine().onPhase(2, EnginePhase.PLAYING)
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Playing
                } finally {
                    rig.close()
                }
            }
        }

        test("tapping the book again after a start that never settled reloads it, never resumes the stuck load") {
            runTest {
                val prepared = mutableListOf<Pair<BookId, PlaybackStart>>()
                val (rig, _) = nativeRig(prepared)
                try {
                    rig.withBook("a")
                    runCurrent()
                    rig.session.playBook(BookId("a"), null)
                    runCurrent()
                    advanceTimeBy(ENGINE_START_BOUND + 1.seconds)
                    runCurrent()

                    rig.session.playBook(BookId("a"), null)
                    runCurrent()

                    prepared.size shouldBe 2
                } finally {
                    rig.close()
                }
            }
        }

        test("the bound holds on every engine, not only the iOS one") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    rig.withBook("a")
                    rig.engine.phaseAfterLoad = EnginePhase.BUFFERING
                    runCurrent()
                    rig.session.playBook(BookId("a"), null)
                    runCurrent()
                    advanceTimeBy(ENGINE_START_BOUND + 1.seconds)
                    runCurrent()
                    rig.loaded.phase
                        .shouldBeInstanceOf<PlayerPhase.Failed>()
                        .error
                        .shouldBeInstanceOf<PlaybackError.CouldNotStart>()
                    rig.engine.calls.last() shouldBe "pause"
                } finally {
                    rig.close()
                }
            }
        }

        test("a stall in the middle of a listen is left to the engine's own recovery, not failed as a start") {
            runTest {
                val (rig, engine) = nativeRig()
                try {
                    rig.withBook("a")
                    runCurrent()
                    rig.session.playBook(BookId("a"), null)
                    runCurrent()
                    engine().onPhase(1, EnginePhase.PLAYING)
                    runCurrent()
                    engine().onPhase(1, EnginePhase.BUFFERING)
                    runCurrent()
                    advanceTimeBy(ENGINE_START_BOUND * 2)
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Buffering
                } finally {
                    rig.close()
                }
            }
        }
    })
