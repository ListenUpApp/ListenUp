package com.calypsan.listenup.client.playback

import app.cash.turbine.test
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.test.fake.preparedPlaybackFor
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Swift lets go of a book whose stream fails (AVFoundation fails the item). Mid-listen, the iOS engine then recovers
 * the way Media3 and the browser do: it prepares the book afresh, backing off, until audio moves again or it is told
 * to stop (the session's halt is a pause).
 */
class NativePlayerEngineDropRecoveryTest :
    FunSpec({
        test("a drop mid-listen prepares the book again after a second, from its saved place") {
            runTest {
                val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
                try {
                    val starts = mutableListOf<PlaybackStart>()
                    val engine =
                        NativePlayerEngine(prepare = { id, start ->
                            starts += start
                            AppResult.Success(preparedPlaybackFor(id.value))
                        }, scope = scope)
                    engine.commands.test {
                        engine.load(BookId("a"), start = PlaybackStart.Resume)
                        awaitItem().shouldBeInstanceOf<EngineCommand.Load>()
                        engine.onPhase(1, EnginePhase.PLAYING)
                        engine.reportConnectionLost(1, "NSURLErrorDomain -1004")
                        engine.onPhase(1, EnginePhase.PAUSED)
                        advanceTimeBy(999)
                        runCurrent()
                        expectNoEvents()
                        advanceTimeBy(1)
                        runCurrent()
                        awaitItem().shouldBeInstanceOf<EngineCommand.Load>().generation shouldBe 2
                        starts shouldBe listOf(PlaybackStart.Resume, PlaybackStart.Resume)
                        engine.error.value shouldBe null
                        engine.phase.value shouldBe EnginePhase.BUFFERING
                    }
                } finally {
                    scope.cancel()
                }
            }
        }

        test("a reload that fails while the drop lasts is still the drop, and the engine keeps backing off") {
            runTest {
                val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
                try {
                    val engine =
                        NativePlayerEngine(prepare = { id, _ -> AppResult.Success(preparedPlaybackFor(id.value)) }, scope = scope)
                    engine.commands.test {
                        engine.load(BookId("a"), start = PlaybackStart.Resume)
                        awaitItem()
                        engine.onPhase(1, EnginePhase.PLAYING)
                        engine.reportConnectionLost(1, null)
                        advanceTimeBy(1_000)
                        runCurrent()
                        awaitItem().shouldBeInstanceOf<EngineCommand.Load>().generation shouldBe 2
                        // Swift's load of the reloaded book fails too: its untyped "load failed" is the same drop.
                        engine.reportCouldNotStart(2, "engine load failed")
                        engine.error.value.shouldBeInstanceOf<PlaybackError.ConnectionLost>()
                        advanceTimeBy(1_999)
                        runCurrent()
                        expectNoEvents()
                        advanceTimeBy(1)
                        runCurrent()
                        awaitItem().shouldBeInstanceOf<EngineCommand.Load>().generation shouldBe 3
                        // Through: audio moves, and the next drop starts its backoff from the beginning.
                        engine.onPhase(3, EnginePhase.PLAYING)
                        engine.error.value shouldBe null
                        engine.reportConnectionLost(3, null)
                        advanceTimeBy(1_000)
                        runCurrent()
                        awaitItem().shouldBeInstanceOf<EngineCommand.Load>().generation shouldBe 4
                    }
                } finally {
                    scope.cancel()
                }
            }
        }

        test("a pause stops the recovery: nothing is prepared or played behind it") {
            runTest {
                val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
                try {
                    var prepares = 0
                    val engine =
                        NativePlayerEngine(prepare = { id, _ ->
                            prepares++
                            AppResult.Success(preparedPlaybackFor(id.value))
                        }, scope = scope)
                    engine.commands.test {
                        engine.load(BookId("a"), start = PlaybackStart.Resume)
                        awaitItem()
                        engine.onPhase(1, EnginePhase.PLAYING)
                        engine.reportConnectionLost(1, null)
                        engine.pause()
                        awaitItem() shouldBe EngineCommand.Pause
                        advanceTimeBy(60_000)
                        runCurrent()
                        expectNoEvents()
                        prepares shouldBe 1
                    }
                } finally {
                    scope.cancel()
                }
            }
        }

        test("a drop on a book that never played, or one the listener paused, is not recovered by the engine") {
            runTest {
                val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
                try {
                    var prepares = 0
                    val engine =
                        NativePlayerEngine(prepare = { id, _ ->
                            prepares++
                            AppResult.Success(preparedPlaybackFor(id.value))
                        }, scope = scope)
                    engine.commands.test {
                        engine.load(BookId("a"), start = PlaybackStart.Resume)
                        awaitItem()
                        engine.reportConnectionLost(1, null)
                        advanceTimeBy(60_000)
                        runCurrent()
                        expectNoEvents()
                        prepares shouldBe 1
                    }
                } finally {
                    scope.cancel()
                }
            }
        }

        test("a fresh prepare that fails for a network reason during the drop backs off and tries again") {
            runTest {
                val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
                try {
                    var prepares = 0
                    val engine =
                        NativePlayerEngine(prepare = { id, _ ->
                            prepares++
                            if (prepares == 2) {
                                AppResult.Failure(PlaybackError.ServerUnreachable())
                            } else {
                                AppResult.Success(preparedPlaybackFor(id.value))
                            }
                        }, scope = scope)
                    engine.commands.test {
                        engine.load(BookId("a"), start = PlaybackStart.Resume)
                        awaitItem()
                        engine.onPhase(1, EnginePhase.PLAYING)
                        engine.reportConnectionLost(1, null)
                        advanceTimeBy(1_000)
                        runCurrent()
                        prepares shouldBe 2
                        engine.error.value shouldBe PlaybackError.ServerUnreachable()
                        expectNoEvents()
                        advanceTimeBy(2_000)
                        runCurrent()
                        prepares shouldBe 3
                        awaitItem().shouldBeInstanceOf<EngineCommand.Load>().generation shouldBe 2
                    }
                } finally {
                    scope.cancel()
                }
            }
        }
    })
