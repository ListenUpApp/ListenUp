package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.test.fake.FakePlaybackController
import com.calypsan.listenup.client.test.fake.FakeDownloadRepository
import com.calypsan.listenup.client.test.fake.FakePlaybackManager
import com.calypsan.listenup.client.test.fake.FakePlaybackPositionRepository
import com.calypsan.listenup.core.error.ErrorBus
import com.calypsan.listenup.client.test.fake.prepareResultFor
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class ManagedPlayerEngineTest :
    FunSpec({
        class RecordingGate : PlaybackStartGate {
            val calls = mutableListOf<String>()
            var onPrime: () -> Unit = {}

            override fun prime() {
                onPrime()
                calls += "prime"
            }

            override fun withdraw() {
                calls += "withdraw"
            }

            override fun release() {
                calls += "release"
            }
        }

        class Rig(
            scope: CoroutineScope,
        ) {
            val manager = FakePlaybackManager()
            val controller = FakePlaybackController()
            val gate = RecordingGate()
            val route = MutableStateFlow<String?>(null)
            val positions = FakePlaybackPositionRepository()
            val tracker =
                ProgressTracker(
                    downloadRepository = FakeDownloadRepository(),
                    positionRepository = positions,
                    scope = scope,
                    errorBus = ErrorBus(),
                )
            val engine =
                ManagedPlayerEngine(
                    playbackManager = manager,
                    playbackController = controller,
                    startGate = gate,
                    progressTracker = tracker,
                    outputRoute =
                        object : OutputRoute {
                            override val deviceName = route
                        },
                    scope = scope,
                )
        }

        test("phase follows the manager: buffering, playing, paused, ended") {
            runTest {
                val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
                try {
                    val rig = Rig(scope)
                    runCurrent()
                    rig.engine.phase.value shouldBe EnginePhase.IDLE
                    rig.manager.bufferingFlow.value = true
                    runCurrent()
                    rig.engine.phase.value shouldBe EnginePhase.BUFFERING
                    rig.manager.bufferingFlow.value = false
                    rig.manager.playingFlow.value = true
                    rig.manager.playbackStateFlow.value = PlaybackState.Playing
                    runCurrent()
                    rig.engine.phase.value shouldBe EnginePhase.PLAYING
                    rig.manager.playingFlow.value = false
                    rig.manager.playbackStateFlow.value = PlaybackState.Paused
                    runCurrent()
                    rig.engine.phase.value shouldBe EnginePhase.PAUSED
                    rig.manager.playbackStateFlow.value = PlaybackState.Ended
                    runCurrent()
                    rig.engine.phase.value shouldBe EnginePhase.ENDED
                } finally {
                    scope.cancel()
                }
            }
        }

        test("Android: a pause flips only the playing flag, and the phase reads PAUSED") {
            runTest {
                val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
                try {
                    val rig = Rig(scope)
                    // MediaControllerHolder's real sequence: STATE_BUFFERING, then STATE_READY while
                    // isPlaying, then onIsPlayingChanged(true).
                    rig.manager.bufferingFlow.value = true
                    rig.manager.playbackStateFlow.value = PlaybackState.Buffering
                    runCurrent()
                    rig.engine.phase.value shouldBe EnginePhase.BUFFERING
                    rig.manager.bufferingFlow.value = false
                    rig.manager.playbackStateFlow.value = PlaybackState.Playing
                    rig.manager.playingFlow.value = true
                    runCurrent()
                    rig.engine.phase.value shouldBe EnginePhase.PLAYING
                    // Pause: Media3 stays STATE_READY, so onPlaybackStateChanged never fires; only
                    // onIsPlayingChanged(false) arrives and the manager's state stays Playing.
                    rig.manager.playingFlow.value = false
                    runCurrent()
                    rig.engine.phase.value shouldBe EnginePhase.PAUSED
                    rig.manager.playingFlow.value = true
                    runCurrent()
                    rig.engine.phase.value shouldBe EnginePhase.PLAYING
                } finally {
                    scope.cancel()
                }
            }
        }

        test("web and desktop: the manager moves state and the playing flag together") {
            runTest {
                val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
                try {
                    val rig = Rig(scope)
                    // PlaybackManagerImpl's AudioPlayer observation: state, buffering, then playing.
                    rig.manager.playbackStateFlow.value = PlaybackState.Buffering
                    rig.manager.bufferingFlow.value = true
                    rig.manager.playingFlow.value = false
                    runCurrent()
                    rig.engine.phase.value shouldBe EnginePhase.BUFFERING
                    rig.manager.playbackStateFlow.value = PlaybackState.Playing
                    rig.manager.bufferingFlow.value = false
                    rig.manager.playingFlow.value = true
                    runCurrent()
                    rig.engine.phase.value shouldBe EnginePhase.PLAYING
                    rig.manager.playbackStateFlow.value = PlaybackState.Paused
                    rig.manager.playingFlow.value = false
                    runCurrent()
                    rig.engine.phase.value shouldBe EnginePhase.PAUSED
                } finally {
                    scope.cancel()
                }
            }
        }

        test("a load prepares, activates and starts the book, and lands the prime") {
            runTest {
                val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
                try {
                    val rig = Rig(scope)
                    rig.manager.stubbedPrepareResult = prepareResultFor("b1")
                    rig.engine.beginLoad(BookId("b1"))
                    rig.engine.load(BookId("b1"), start = PlaybackStart.At(5_000)) shouldBe AppResult.Success(Unit)
                    rig.manager.prepareForPlaybackStarts shouldContainExactly listOf(PlaybackStart.At(5_000))
                    rig.manager.activatedBookIds shouldContainExactly listOf(BookId("b1"))
                    rig.controller.startPlaybackCalls.size shouldBe 1
                    rig.gate.calls shouldContainExactly listOf("prime")
                } finally {
                    scope.cancel()
                }
            }
        }

        test("another book pauses the playing one before the prime: a failed load leaves silence") {
            runTest {
                val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
                try {
                    val rig = Rig(scope)
                    rig.manager.currentBookIdFlow.value = BookId("a")
                    rig.manager.playingFlow.value = true
                    var pausesWhenPrimed = -1
                    rig.gate.onPrime = { pausesWhenPrimed = rig.controller.pauseCount }
                    rig.engine.beginLoad(BookId("b"))
                    rig.controller.pauseCount shouldBe 1
                    pausesWhenPrimed shouldBe 1
                    rig.gate.calls shouldContainExactly listOf("prime")
                } finally {
                    scope.cancel()
                }
            }
        }

        test("beginLoad pauses nothing for the same book, a paused book, or no book") {
            runTest {
                val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
                try {
                    val rig = Rig(scope)
                    rig.engine.beginLoad(BookId("a"))
                    rig.manager.currentBookIdFlow.value = BookId("a")
                    rig.engine.beginLoad(BookId("b"))
                    rig.manager.playingFlow.value = true
                    rig.engine.beginLoad(BookId("a"))
                    rig.controller.pauseCount shouldBe 0
                    rig.gate.calls shouldContainExactly listOf("prime", "prime", "prime")
                } finally {
                    scope.cancel()
                }
            }
        }

        test("a move made before Play reaches the preparer as a move, for it to replay from the reconciled place") {
            runTest {
                val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
                try {
                    val rig = Rig(scope)
                    rig.manager.stubbedPrepareResult = prepareResultFor("b1")
                    val moved = PlaybackStart.Moved(listOf(PlaybackMove.Skip(-10_000)))
                    rig.engine.load(BookId("b1"), start = moved) shouldBe AppResult.Success(Unit)
                    rig.manager.prepareForPlaybackStarts shouldContainExactly listOf(moved)
                } finally {
                    scope.cancel()
                }
            }
        }

        test("a failed prepare returns the typed failure and activates nothing") {
            runTest {
                val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
                try {
                    val rig = Rig(scope)
                    rig.manager.stubbedPrepareError = PlaybackError.CouldNotStart()
                    val result = rig.engine.load(BookId("b1"), start = PlaybackStart.Resume)
                    result.shouldBeInstanceOf<AppResult.Failure>().error shouldBe PlaybackError.CouldNotStart()
                    rig.manager.activatedBookIds shouldBe emptyList()
                } finally {
                    scope.cancel()
                }
            }
        }

        test("abandoning a load withdraws the prime; stop releases the player and clears the manager") {
            runTest {
                val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
                try {
                    val rig = Rig(scope)
                    rig.engine.beginLoad(BookId("b1"))
                    rig.engine.abandonLoad()
                    rig.manager.playingFlow.value = true
                    rig.engine.stop()
                    rig.gate.calls shouldContainExactly listOf("prime", "withdraw", "release")
                    rig.controller.stopCount shouldBe 1
                    rig.manager.clearPlaybackCalls shouldBe 1
                    rig.manager.setPlaybackStateCalls.last() shouldBe PlaybackState.Paused
                } finally {
                    scope.cancel()
                }
            }
        }

        test("stop while playing records the exact place before the manager forgets the book") {
            runTest {
                val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
                try {
                    val rig = Rig(scope)
                    rig.manager.currentBookIdFlow.value = BookId("b1")
                    rig.manager.currentPositionMsFlow.value = 4_014_944
                    rig.manager.playingFlow.value = true
                    rig.engine.stop()
                    runCurrent()
                    rig.manager.setPlaybackStateCalls.last() shouldBe PlaybackState.Paused
                    rig.manager.clearPlaybackCalls shouldBe 1
                    rig.positions
                        .observe(BookId("b1"))
                        .first()
                        ?.positionMs shouldBe 4_014_944
                } finally {
                    scope.cancel()
                }
            }
        }

        test("stop while paused still records the place: a move made while paused is never lost") {
            runTest {
                val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
                try {
                    val rig = Rig(scope)
                    rig.manager.currentBookIdFlow.value = BookId("b1")
                    rig.manager.currentPositionMsFlow.value = 2_000_000
                    rig.engine.stop()
                    runCurrent()
                    rig.positions
                        .observe(BookId("b1"))
                        .first()
                        ?.positionMs shouldBe 2_000_000
                } finally {
                    scope.cancel()
                }
            }
        }

        test("stop while another book prepares records the outgoing book's own place") {
            runTest {
                val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
                try {
                    val rig = Rig(scope)
                    rig.manager.currentBookIdFlow.value = BookId("a")
                    rig.manager.currentPositionMsFlow.value = 50_000
                    val held = CompletableDeferred<Unit>()
                    rig.manager.prepareForPlaybackSuspension = { held.await() }
                    val load = scope.launch { rig.engine.load(BookId("b"), PlaybackStart.Resume) }
                    runCurrent()
                    load.cancel()
                    rig.engine.stop()
                    runCurrent()
                    rig.positions
                        .observe(BookId("a"))
                        .first()
                        ?.positionMs shouldBe 50_000
                    rig.positions.observe(BookId("b")).first() shouldBe null
                } finally {
                    scope.cancel()
                }
            }
        }

        test("stop between activating a book and starting it never files the old place under the new book") {
            runTest {
                val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
                try {
                    val rig = Rig(scope)
                    rig.manager.currentBookIdFlow.value = BookId("a")
                    rig.manager.currentPositionMsFlow.value = 50_000
                    rig.manager.stubbedPrepareResult = prepareResultFor("b")
                    val held = CompletableDeferred<Unit>()
                    rig.controller.startPlaybackSuspension = { held.await() }
                    val load = scope.launch { rig.engine.load(BookId("b"), PlaybackStart.Resume) }
                    runCurrent()
                    rig.manager.currentBookId.value shouldBe BookId("b")
                    load.cancel()
                    rig.engine.stop()
                    runCurrent()
                    rig.positions.observeAll().first() shouldBe emptyMap()
                } finally {
                    scope.cancel()
                }
            }
        }

        test("stop with no book loaded records nothing") {
            runTest {
                val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
                try {
                    val rig = Rig(scope)
                    rig.engine.stop()
                    runCurrent()
                    rig.positions.observeAll().first() shouldBe emptyMap()
                } finally {
                    scope.cancel()
                }
            }
        }

        test("commands reach the controller and the manager together") {
            runTest {
                val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
                try {
                    val rig = Rig(scope)
                    rig.engine.seekTo(42_000)
                    rig.engine.setSpeed(1.25f)
                    rig.engine.resetSpeed(1.0f)
                    rig.engine.setBoost(6f)
                    rig.engine.resetBoost(0f)
                    rig.engine.setVolume(0.5f)
                    rig.controller.seekCalls shouldContainExactly listOf(42_000L)
                    rig.manager.updatedPositions shouldContainExactly listOf(42_000L)
                    rig.controller.speedCalls shouldContainExactly listOf(1.25f, 1.0f)
                    rig.manager.speedChanges shouldContainExactly listOf(1.25f)
                    rig.manager.speedResets shouldContainExactly listOf(1.0f)
                    rig.manager.boostChanges shouldContainExactly listOf(6f)
                    rig.manager.boostResets shouldContainExactly listOf(0f)
                    rig.controller.volumeCalls shouldContainExactly listOf(0.5f)
                } finally {
                    scope.cancel()
                }
            }
        }

        test("the session's stop on a surfaced failure reaches the platform controller (6c)") {
            runTest {
                var engineRig: Rig? = null
                val session = PlayerSessionRig(this, playerEngine = { scope -> Rig(scope).also { engineRig = it }.engine })
                try {
                    val rig = engineRig!!
                    session.withBook("b1")
                    rig.manager.stubbedPrepareResult = prepareResultFor("b1")
                    runCurrent()
                    session.session.playBook(BookId("b1"), null)
                    runCurrent()
                    rig.manager.playbackStateFlow.value = PlaybackState.Playing
                    rig.manager.playingFlow.value = true
                    runCurrent()
                    val pausesBefore = rig.controller.pauseCount
                    // Media3 reports the dropped stream and goes on re-buffering it, playWhenReady still true.
                    rig.manager.playingFlow.value = false
                    rig.manager.playbackStateFlow.value = PlaybackState.Buffering
                    rig.manager.reportError(PlaybackError.ConnectionLost())
                    runCurrent()
                    // Inside the drop's grace Media3 keeps recovering: nothing shows and nothing is stopped.
                    session.loaded.phase shouldBe PlayerPhase.Buffering
                    rig.controller.pauseCount shouldBe pausesBefore
                    advanceTimeBy(PLAYBACK_DROP_GRACE)
                    runCurrent()
                    session.loaded.phase shouldBe PlayerPhase.Failed(PlaybackError.ConnectionLost())
                    rig.controller.pauseCount shouldBe pausesBefore + 1
                } finally {
                    session.close()
                }
            }
        }

        test("the error and output route pass straight through") {
            runTest {
                val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
                try {
                    val rig = Rig(scope)
                    rig.manager.reportError(PlaybackError.ConnectionLost())
                    rig.engine.error.value shouldBe PlaybackError.ConnectionLost()
                    rig.engine.clearError()
                    rig.engine.error.value shouldBe null
                    rig.route.value = "Living Room"
                    rig.engine.outputDeviceName.value shouldBe "Living Room"
                } finally {
                    scope.cancel()
                }
            }
        }
    })
