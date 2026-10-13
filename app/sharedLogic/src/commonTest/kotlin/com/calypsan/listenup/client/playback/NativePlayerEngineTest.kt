package com.calypsan.listenup.client.playback

import app.cash.turbine.test
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.test.fake.preparedPlaybackFor
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class NativePlayerEngineTest :
    FunSpec({
        val chapters =
            listOf(
                Chapter(id = "a", title = "A", duration = 100_000, startTime = 0),
                Chapter(id = "b", title = "B", duration = 100_000, startTime = 100_000),
            )

        test("a load prepares in Kotlin, then hands Swift one Load with the prepared book") {
            runTest {
                val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
                try {
                    val prepared = preparedPlaybackFor("b1", durationMs = 200_000, chapters = chapters, resumePositionMs = 150_000)
                    val engine = NativePlayerEngine(prepare = { _, _ -> AppResult.Success(prepared) }, scope = scope)
                    engine.commands.test {
                        engine.load(BookId("b1"), start = PlaybackStart.Resume) shouldBe AppResult.Success(Unit)
                        awaitItem() shouldBe EngineCommand.Load(bookId = "b1", prepared = prepared, generation = 1)
                    }
                    runCurrent()
                    engine.loadedBookId.value shouldBe BookId("b1")
                    engine.durationMs.value shouldBe 200_000
                    engine.positionMs.value shouldBe 150_000
                    engine.currentChapter.value!!.index shouldBe 1
                    engine.phase.value shouldBe EnginePhase.BUFFERING
                } finally {
                    scope.cancel()
                }
            }
        }

        test("a failed prepare sends nothing to Swift") {
            runTest {
                val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
                try {
                    val engine =
                        NativePlayerEngine(prepare = { _, _ -> AppResult.Failure(PlaybackError.CouldNotStart()) }, scope = scope)
                    engine.commands.test {
                        engine.load(BookId("b1"), start = PlaybackStart.Resume).shouldBeInstanceOf<AppResult.Failure>()
                        expectNoEvents()
                    }
                    engine.loadedBookId.value shouldBe null
                } finally {
                    scope.cancel()
                }
            }
        }

        test("commands arrive in the order they were issued") {
            runTest {
                val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
                try {
                    val prepared = preparedPlaybackFor("b1")
                    val engine = NativePlayerEngine(prepare = { _, _ -> AppResult.Success(prepared) }, scope = scope)
                    engine.commands.test {
                        engine.load(BookId("b1"), start = PlaybackStart.Resume)
                        engine.seekTo(30_000)
                        engine.play()
                        engine.setSpeed(1.5f)
                        engine.resetBoost(0f)
                        engine.setVolume(0.2f)
                        engine.pause()
                        engine.stop()
                        awaitItem().shouldBeInstanceOf<EngineCommand.Load>()
                        awaitItem() shouldBe EngineCommand.SeekTo(30_000)
                        awaitItem() shouldBe EngineCommand.Play
                        awaitItem() shouldBe EngineCommand.SetRate(1.5f, isReset = false)
                        awaitItem() shouldBe EngineCommand.SetBoostDb(0f, isReset = true)
                        awaitItem() shouldBe EngineCommand.SetVolume(0.2f)
                        awaitItem() shouldBe EngineCommand.Pause
                        awaitItem() shouldBe EngineCommand.Stop
                    }
                    engine.loadedBookId.value shouldBe null
                } finally {
                    scope.cancel()
                }
            }
        }

        test("switching books pauses the outgoing one inside the gesture") {
            runTest {
                val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
                try {
                    val engine = NativePlayerEngine(prepare = { id, _ -> AppResult.Success(preparedPlaybackFor(id.value)) }, scope = scope)
                    engine.commands.test {
                        engine.load(BookId("a"), start = PlaybackStart.Resume)
                        awaitItem()
                        engine.beginLoad(BookId("b"))
                        awaitItem() shouldBe EngineCommand.Pause
                        engine.beginLoad(BookId("a")) // the same book: nothing to silence
                        expectNoEvents()
                    }
                } finally {
                    scope.cancel()
                }
            }
        }

        test("Swift's facts become the engine's state") {
            runTest {
                val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
                try {
                    val engine =
                        NativePlayerEngine(prepare = {
                            _,
                            _,
                            ->
                            AppResult.Success(preparedPlaybackFor("b1", chapters = chapters, durationMs = 200_000))
                        }, scope = scope)
                    engine.load(BookId("b1"), start = PlaybackStart.Resume)
                    engine.onPhase(1, EnginePhase.PLAYING)
                    engine.onPosition(1, 120_000)
                    engine.onRate(1, 1.25f)
                    engine.onBoost(1, 3f)
                    engine.onRoute("Kitchen HomePod")
                    runCurrent()
                    engine.phase.value shouldBe EnginePhase.PLAYING
                    engine.currentChapter.value!!.index shouldBe 1
                    engine.speed.value shouldBe 1.25f
                    engine.boostDb.value shouldBe 3f
                    engine.outputDeviceName.value shouldBe "Kitchen HomePod"
                    engine.reportConnectionLost(1, "NSURLErrorDomain -1005")
                    engine.error.value shouldBe PlaybackError.ConnectionLost(debugInfo = "NSURLErrorDomain -1005")
                    engine.reportUnsupportedFormat(1, null)
                    engine.error.value shouldBe PlaybackError.UnsupportedFormat()
                    engine.reportCouldNotStart(1, "no audio")
                    engine.error.value shouldBe PlaybackError.CouldNotStart(debugInfo = "no audio")
                    engine.onUnloaded(1)
                    engine.loadedBookId.value shouldBe null
                    engine.phase.value shouldBe EnginePhase.IDLE
                } finally {
                    scope.cancel()
                }
            }
        }

        test("a fact from an earlier load never lands on the book loaded after it") {
            runTest {
                val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
                try {
                    val engine = NativePlayerEngine(prepare = { id, _ -> AppResult.Success(preparedPlaybackFor(id.value)) }, scope = scope)
                    engine.commands.test {
                        engine.load(BookId("a"), start = PlaybackStart.Resume)
                        val first = (awaitItem() as EngineCommand.Load).generation
                        engine.onPosition(first, 42_000)
                        engine.positionMs.value shouldBe 42_000
                        engine.load(BookId("b"), start = PlaybackStart.Resume)
                        val second = (awaitItem() as EngineCommand.Load).generation
                        (second == first) shouldBe false
                        // Book a's engine is still winding down while Swift has not yet run b's Load.
                        engine.reportCouldNotStart(first, "the old file is gone")
                        engine.onPhase(first, EnginePhase.PAUSED)
                        engine.onPosition(first, 43_000)
                        engine.onRate(first, 2f)
                        engine.onBoost(first, 6f)
                        engine.onUnloaded(first)
                        engine.error.value shouldBe null
                        engine.phase.value shouldBe EnginePhase.BUFFERING
                        engine.positionMs.value shouldBe 0
                        engine.speed.value shouldBe 1f
                        engine.boostDb.value shouldBe 0f
                        engine.loadedBookId.value shouldBe BookId("b")
                        engine.onPhase(second, EnginePhase.PLAYING)
                        engine.phase.value shouldBe EnginePhase.PLAYING
                    }
                } finally {
                    scope.cancel()
                }
            }
        }

        test("after a stop, the stopped load's late facts are dropped") {
            runTest {
                val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
                try {
                    val engine = NativePlayerEngine(prepare = { id, _ -> AppResult.Success(preparedPlaybackFor(id.value)) }, scope = scope)
                    engine.commands.test {
                        engine.load(BookId("a"), start = PlaybackStart.Resume)
                        val generation = (awaitItem() as EngineCommand.Load).generation
                        engine.stop()
                        awaitItem() shouldBe EngineCommand.Stop
                        engine.onPhase(generation, EnginePhase.PAUSED)
                        engine.reportConnectionLost(generation, null)
                        engine.phase.value shouldBe EnginePhase.IDLE
                        engine.error.value shouldBe null
                    }
                } finally {
                    scope.cancel()
                }
            }
        }

        test("audio playing again clears the engine's failure") {
            runTest {
                val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
                try {
                    val engine = NativePlayerEngine(prepare = { id, _ -> AppResult.Success(preparedPlaybackFor(id.value)) }, scope = scope)
                    engine.load(BookId("a"), start = PlaybackStart.Resume)
                    engine.reportConnectionLost(1, "NSURLErrorDomain -1005")
                    engine.onPhase(1, EnginePhase.BUFFERING)
                    engine.error.value shouldBe PlaybackError.ConnectionLost(debugInfo = "NSURLErrorDomain -1005")
                    engine.onPhase(1, EnginePhase.PLAYING)
                    engine.error.value shouldBe null
                } finally {
                    scope.cancel()
                }
            }
        }
    })
