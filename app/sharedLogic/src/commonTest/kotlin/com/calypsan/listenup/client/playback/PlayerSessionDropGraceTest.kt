package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.test.fake.preparedPlaybackFor
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * A network drop mid-play recovers for [PLAYBACK_DROP_GRACE] while the player says Reconnecting; only then does the
 * error show, and once it shows the engine is halted (Simon, 2026-10-14). A stall with no sign of the network behind
 * it (a start, a seek, a slow stream) stays Buffering. Driven on virtual time: every boundary is exact.
 */
class PlayerSessionDropGraceTest :
    FunSpec({
        val graceMs = PLAYBACK_DROP_GRACE.inWholeMilliseconds

        /** b1 is playing. */
        fun TestScope.playing(rig: PlayerSessionRig) {
            rig.withBook("b1")
            runCurrent()
            rig.session.playBook(BookId("b1"), null)
            runCurrent()
            rig.loaded.phase shouldBe PlayerPhase.Playing
        }

        /** The server goes away under the stream: the audio stalls, then the engine gives up on its own retries. */
        fun TestScope.drop(
            rig: PlayerSessionRig,
            failsAfterMs: Long = 6_000,
        ) {
            rig.engine.phase.value = EnginePhase.BUFFERING
            runCurrent()
            advanceTimeBy(failsAfterMs)
            runCurrent()
            rig.engine.phase.value = EnginePhase.IDLE
            rig.engine.error.value = PlaybackError.ConnectionLost()
            runCurrent()
        }

        /** The engine's own recovery got through: audio is moving again and the engine cleared its failure. */
        fun TestScope.recover(rig: PlayerSessionRig) {
            rig.engine.error.value = null
            rig.engine.phase.value = EnginePhase.PLAYING
            runCurrent()
        }

        test("a 3 s drop recovers silently: no error, and the engine is never stopped") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    playing(rig)
                    val callsBeforeDrop = rig.engine.calls.size
                    rig.engine.phase.value = EnginePhase.BUFFERING
                    runCurrent()
                    advanceTimeBy(1_000)
                    runCurrent()
                    rig.engine.error.value = PlaybackError.ConnectionLost()
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Reconnecting
                    advanceTimeBy(2_000)
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Reconnecting
                    recover(rig)
                    rig.loaded.phase shouldBe PlayerPhase.Playing
                    advanceTimeBy(graceMs * 3)
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Playing
                    rig.engine.calls.drop(callsBeforeDrop) shouldNotContain "pause"
                } finally {
                    rig.close()
                }
            }
        }

        test("a drop longer than the grace shows its error at the grace boundary, not before") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    playing(rig)
                    val callsBeforeDrop = rig.engine.calls.size
                    drop(rig, failsAfterMs = 6_000)
                    rig.loaded.phase shouldBe PlayerPhase.Reconnecting
                    // 6 s already passed since the audio stalled; one millisecond short of the grace is still quiet.
                    advanceTimeBy(graceMs - 6_000 - 1)
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Reconnecting
                    rig.engine.calls.drop(callsBeforeDrop) shouldNotContain "pause"
                    advanceTimeBy(1)
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Failed(PlaybackError.ConnectionLost())
                    rig.engine.calls.last() shouldBe "pause"
                } finally {
                    rig.close()
                }
            }
        }

        test("a failure the engine reports after the grace has run out shows at once") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    playing(rig)
                    drop(rig, failsAfterMs = graceMs + 20_000)
                    rig.loaded.phase shouldBe PlayerPhase.Failed(PlaybackError.ConnectionLost())
                } finally {
                    rig.close()
                }
            }
        }

        test("once shown, the error halts playback: the server coming back does not resume it") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    playing(rig)
                    drop(rig)
                    advanceTimeBy(graceMs)
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Failed(PlaybackError.ConnectionLost())
                    val callsAtFailure = rig.engine.calls.size
                    val loadsAtFailure = rig.engine.loads.size
                    // The server returns. The session neither plays nor prepares anything by itself.
                    advanceTimeBy(graceMs * 6)
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Failed(PlaybackError.ConnectionLost())
                    rig.engine.calls.drop(callsAtFailure) shouldBe emptyList()
                    rig.engine.loads.size shouldBe loadsAtFailure
                    // Retry is the way back.
                    rig.session.retry()
                    runCurrent()
                    rig.engine.loads.size shouldBe loadsAtFailure + 1
                    rig.loaded.phase shouldBe PlayerPhase.Playing
                } finally {
                    rig.close()
                }
            }
        }

        test("a recovery just before the boundary shows no error at all") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    playing(rig)
                    val seen = mutableListOf<PlayerPhase>()
                    val watcher =
                        rig.scope.launch {
                            rig.session.state.collect {
                                (it as? PlayerSessionState.Loaded)?.let { loaded -> seen += loaded.phase }
                            }
                        }
                    drop(rig, failsAfterMs = 6_000)
                    advanceTimeBy(graceMs - 6_000 - 1)
                    runCurrent()
                    recover(rig)
                    advanceTimeBy(graceMs * 2)
                    runCurrent()
                    watcher.cancel()
                    seen.filterIsInstance<PlayerPhase.Failed>() shouldBe emptyList()
                    rig.loaded.phase shouldBe PlayerPhase.Playing
                } finally {
                    rig.close()
                }
            }
        }

        test("audio heard moving again is the recovery, even before the engine clears its failure") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    playing(rig)
                    val callsBeforeDrop = rig.engine.calls.size
                    drop(rig, failsAfterMs = 6_000)
                    advanceTimeBy(2_000)
                    runCurrent()
                    // Media3 is READY and playing again; the stale failure has not been cleared yet.
                    rig.engine.phase.value = EnginePhase.PLAYING
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Playing
                    advanceTimeBy(graceMs * 2)
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Playing
                    rig.engine.calls.drop(callsBeforeDrop) shouldNotContain "pause"
                } finally {
                    rig.close()
                }
            }
        }

        test("a second drop after a recovery gets a whole grace of its own") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    playing(rig)
                    drop(rig, failsAfterMs = 6_000)
                    advanceTimeBy(3_000)
                    runCurrent()
                    recover(rig)
                    advanceTimeBy(5_000)
                    runCurrent()
                    drop(rig, failsAfterMs = 6_000)
                    advanceTimeBy(graceMs - 6_000 - 1)
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Reconnecting
                    advanceTimeBy(1)
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Failed(PlaybackError.ConnectionLost())
                } finally {
                    rig.close()
                }
            }
        }

        test("a stall with no engine error says the server can't be reached once the grace runs out, if it can't") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    playing(rig)
                    // The browser keeps retrying for ~30 s before it reports anything; the server is already gone.
                    rig.engine.phase.value = EnginePhase.BUFFERING
                    rig.availability.stateFlow.value =
                        rig.availability.stateFlow.value
                            .copy(canPlay = false)
                    runCurrent()
                    // The server is known to be out of reach: that is a network drop, not a slow stream.
                    rig.loaded.phase shouldBe PlayerPhase.Reconnecting
                    advanceTimeBy(graceMs - 1)
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Reconnecting
                    advanceTimeBy(1)
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Failed(PlaybackError.ServerUnreachable())
                    rig.engine.calls.last() shouldBe "pause"
                    // Stopped, it stays the error: the server returning does not clear it, Retry does.
                    rig.engine.phase.value = EnginePhase.PAUSED
                    rig.availability.stateFlow.value =
                        rig.availability.stateFlow.value
                            .copy(canPlay = true)
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Failed(PlaybackError.ServerUnreachable())
                    rig.session.dismissError()
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Paused
                } finally {
                    rig.close()
                }
            }
        }

        test("a network drop says Reconnecting from the moment the engine reports it until the audio returns") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    playing(rig)
                    rig.engine.phase.value = EnginePhase.BUFFERING
                    runCurrent()
                    // Nothing yet says the network went away: an ordinary stall.
                    rig.loaded.phase shouldBe PlayerPhase.Buffering
                    advanceTimeBy(2_000)
                    rig.engine.error.value = PlaybackError.ConnectionLost()
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Reconnecting
                    // The engine retries: it clears its failure and buffers again. Still the same drop.
                    advanceTimeBy(2_000)
                    rig.engine.error.value = null
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Reconnecting
                    recover(rig)
                    rig.loaded.phase shouldBe PlayerPhase.Playing
                    // The next stall is a fresh one, with nothing yet to say it is the network.
                    rig.engine.phase.value = EnginePhase.BUFFERING
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Buffering
                } finally {
                    rig.close()
                }
            }
        }

        test("a start or a seek buffers as Buffering, never Reconnecting") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    playing(rig)
                    rig.session.playPause()
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Paused
                    // Play from a pause: the engine buffers before the first audio.
                    rig.engine.phase.value = EnginePhase.BUFFERING
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Buffering
                    rig.engine.phase.value = EnginePhase.PLAYING
                    runCurrent()
                    // A seek mid-listen: the engine buffers the new position.
                    rig.session.seekTo(120_000)
                    rig.engine.phase.value = EnginePhase.BUFFERING
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Buffering
                    advanceTimeBy(graceMs * 2)
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Buffering
                } finally {
                    rig.close()
                }
            }
        }

        test("a stall the engine finds no progress in fails at the grace, and not before") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    playing(rig)
                    // The web engine's own watch: a silently dead network moves no bytes and no clock, so the
                    // engine names the dropped connection once a whole grace has passed without progress.
                    rig.engine.phase.value = EnginePhase.BUFFERING
                    runCurrent()
                    advanceTimeBy(graceMs - 1)
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Buffering
                    advanceTimeBy(1)
                    rig.engine.error.value = PlaybackError.ConnectionLost()
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Failed(PlaybackError.ConnectionLost())
                    rig.engine.calls.last() shouldBe "pause"
                } finally {
                    rig.close()
                }
            }
        }

        test("a slow stream on a reachable server is never turned into an error") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    playing(rig)
                    rig.engine.phase.value = EnginePhase.BUFFERING
                    runCurrent()
                    advanceTimeBy(graceMs * 4)
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Buffering
                } finally {
                    rig.close()
                }
            }
        }

        test("a failure that is not a network drop shows at once") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    playing(rig)
                    rig.engine.error.value = PlaybackError.UnsupportedFormat()
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Failed(PlaybackError.UnsupportedFormat())
                } finally {
                    rig.close()
                }
            }
        }

        test("a drop while paused is not an interrupted listen: it shows at once") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    playing(rig)
                    rig.session.playPause()
                    runCurrent()
                    rig.engine.error.value = PlaybackError.ConnectionLost()
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Failed(PlaybackError.ConnectionLost())
                } finally {
                    rig.close()
                }
            }
        }

        test("pausing while Reconnecting is a pause: no error later, and Play prepares the book again") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    playing(rig)
                    drop(rig, failsAfterMs = 2_000)
                    rig.loaded.phase shouldBe PlayerPhase.Reconnecting
                    rig.session.playPause()
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Paused
                    advanceTimeBy(graceMs * 3)
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Paused
                    rig.session.playPause()
                    runCurrent()
                    rig.engine.loads.size shouldBe 2
                    rig.loaded.phase shouldBe PlayerPhase.Playing
                } finally {
                    rig.close()
                }
            }
        }

        /** A session over the iOS engine, whose prepare always succeeds; [prepares] counts them. */
        fun TestScope.nativeRig(prepares: MutableList<BookId>): Pair<PlayerSessionRig, () -> NativePlayerEngine> {
            var engine: NativePlayerEngine? = null
            val rig =
                PlayerSessionRig(this, playerEngine = { scope ->
                    NativePlayerEngine(
                        prepare = { id, _ ->
                            prepares += id
                            AppResult.Success(preparedPlaybackFor(id.value, durationMs = 300_000))
                        },
                        scope = scope,
                    ).also { engine = it }
                })
            return rig to { engine!! }
        }

        test("iOS: a 3 s drop that fails the item recovers silently through a fresh prepare") {
            runTest {
                val prepares = mutableListOf<BookId>()
                val (rig, engine) = nativeRig(prepares)
                try {
                    rig.withBook("a")
                    runCurrent()
                    rig.session.playBook(BookId("a"), null)
                    runCurrent()
                    engine().onPhase(1, EnginePhase.PLAYING)
                    runCurrent()
                    // AVFoundation fails the item; Swift drops the book and pauses.
                    engine().reportConnectionLost(1, "NSURLErrorDomain -1004")
                    engine().onPhase(1, EnginePhase.PAUSED)
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Reconnecting
                    // The first reload, a second later, fails under a server still down. While Swift loads it
                    // the engine reports no failure, and the player still says Reconnecting.
                    advanceTimeBy(1_000)
                    runCurrent()
                    prepares.size shouldBe 2
                    rig.loaded.phase shouldBe PlayerPhase.Reconnecting
                    engine().reportCouldNotStart(2, "engine load failed")
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Reconnecting
                    // The server is back for the second reload, two seconds on.
                    advanceTimeBy(2_000)
                    runCurrent()
                    prepares.size shouldBe 3
                    engine().onPhase(3, EnginePhase.PLAYING)
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Playing
                    advanceTimeBy(graceMs * 2)
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Playing
                } finally {
                    rig.close()
                }
            }
        }

        test("iOS: a drop that outlasts the grace shows at the boundary and stops the engine's retries") {
            runTest {
                val prepares = mutableListOf<BookId>()
                val (rig, engine) = nativeRig(prepares)
                try {
                    rig.withBook("a")
                    runCurrent()
                    rig.session.playBook(BookId("a"), null)
                    runCurrent()
                    engine().onPhase(1, EnginePhase.PLAYING)
                    runCurrent()
                    engine().reportConnectionLost(1, "NSURLErrorDomain -1004")
                    engine().onPhase(1, EnginePhase.PAUSED)
                    runCurrent()
                    // Every reload fails as soon as Swift tries it.
                    var generation = 1
                    repeat(3) { attempt ->
                        advanceTimeBy(1_000L shl attempt)
                        runCurrent()
                        generation++
                        engine().reportCouldNotStart(generation, "engine load failed")
                        runCurrent()
                        rig.loaded.phase shouldBe PlayerPhase.Reconnecting
                    }
                    // 7 s in. One millisecond short of the grace is still quiet.
                    advanceTimeBy(graceMs - 7_000 - 1)
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Reconnecting
                    advanceTimeBy(1)
                    runCurrent()
                    rig.loaded.phase
                        .shouldBeInstanceOf<PlayerPhase.Failed>()
                        .error
                        .shouldBeInstanceOf<PlaybackError.ConnectionLost>()
                    val preparesAtFailure = prepares.size
                    advanceTimeBy(graceMs * 6)
                    runCurrent()
                    prepares.size shouldBe preparesAtFailure
                    rig.loaded.phase.shouldBeInstanceOf<PlayerPhase.Failed>()
                } finally {
                    rig.close()
                }
            }
        }
    })
