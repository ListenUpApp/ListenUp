package com.calypsan.listenup.client.playback

import android.content.ContextWrapper
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import com.google.common.util.concurrent.SettableFuture
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class MediaControllerHolderTest :
    FunSpec({
        /**
         * Context is only used inside [MediaControllerHolder.connect], which is not called
         * by any test here (the reconnect tests below inject a [MediaControllerHolder]
         * `connectionFactory` instead of exercising the real Media3 `Builder`, so `connect()`
         * never touches this stub). A ContextWrapper(null) stub is safe: it will NPE only if
         * accessed.
         */
        val stubContext = object : ContextWrapper(null) {}

        // ---------------------------------------------------------------------------
        // Tests
        // ---------------------------------------------------------------------------

        test("onIsPlayingChanged forwards to playbackManager setPlaying") {
            runTest {
                val writer = FakePlaybackStateWriter()
                val holder =
                    MediaControllerHolder(
                        context = stubContext,
                        playbackManager = writer,
                    )

                holder.playerListener.onIsPlayingChanged(true)
                holder.playerListener.onIsPlayingChanged(false)

                writer.playingHistory shouldBe listOf(true, false)
            }
        }

        test("onPlaybackStateChanged STATE_BUFFERING forwards setBuffering(true) and Buffering state") {
            runTest {
                val writer = FakePlaybackStateWriter()
                val holder =
                    MediaControllerHolder(
                        context = stubContext,
                        playbackManager = writer,
                    )

                holder.playerListener.onPlaybackStateChanged(Player.STATE_BUFFERING)

                writer.bufferingHistory shouldBe listOf(true)
                writer.playbackStateHistory shouldBe listOf<PlaybackState>(PlaybackState.Buffering)
            }
        }

        test("onPlaybackStateChanged STATE_READY with idle controller forwards setBuffering(false) and Paused") {
            runTest {
                val writer = FakePlaybackStateWriter()
                val holder =
                    MediaControllerHolder(
                        context = stubContext,
                        playbackManager = writer,
                    )
                // _controller is null at this point — toCommonPlaybackState returns Paused
                holder.playerListener.onPlaybackStateChanged(Player.STATE_READY)

                writer.bufferingHistory shouldBe listOf(false)
                writer.playbackStateHistory shouldBe listOf<PlaybackState>(PlaybackState.Paused)
            }
        }

        // onPlayerError is not tested here: PlaybackException's constructor internally calls
        // android.os.SystemClock.elapsedRealtime(), which is not mocked in JVM host tests
        // (androidHostTest runs without Robolectric). The error-translation logic is covered
        // by integration testing on device / instrumented tests.

        test("onPlaybackParametersChanged forwards speed to updateSpeed") {
            runTest {
                val writer = FakePlaybackStateWriter()
                val holder =
                    MediaControllerHolder(
                        context = stubContext,
                        playbackManager = writer,
                    )

                holder.playerListener.onPlaybackParametersChanged(PlaybackParameters(1.5f))

                writer.speedHistory shouldBe listOf(1.5f)
            }
        }

        // ---------------------------------------------------------------------------
        // awaitReady — the generic bounded-wait mechanism behind awaitController().
        // Exercised directly against a plain MutableStateFlow<Boolean> because
        // MediaController itself cannot be constructed or mocked in a JVM host test
        // (package-private Media3 constructor — confirmed empirically, not assumed).
        // This is the actual new mechanism that fixes F6: a command issued before the
        // signal is ready still observes it once it flips, instead of reading a stale
        // "not ready" snapshot and giving up.
        // ---------------------------------------------------------------------------

        test("awaitReady: a signal that flips ready after the wait has started still unblocks the waiter (the F6 race)") {
            runTest {
                val ready = MutableStateFlow(false)
                var observed: String? = null

                val job =
                    launch {
                        if (awaitReady(ready, timeoutMs = 10_000L)) {
                            observed = "resolved"
                        }
                    }

                // The command was issued before the signal existed — prove it's genuinely
                // waiting rather than having already given up on a stale null/false read.
                testScheduler.runCurrent()
                observed shouldBe null

                ready.value = true // the controller "binds" here, after the wait already started
                job.join()

                observed shouldBe "resolved"
            }
        }

        test("awaitReady returns false once the bound elapses without the signal becoming ready") {
            runTest {
                val ready = MutableStateFlow(false)

                awaitReady(ready, timeoutMs = 1_000L) shouldBe false
            }
        }

        test("awaitReady stops waiting once cancelled, rather than swallowing the cancellation and completing later") {
            runTest {
                val ready = MutableStateFlow(false)
                var completedNormally = false

                val job =
                    launch {
                        awaitReady(ready, timeoutMs = 60_000L)
                        completedNormally = true
                    }

                testScheduler.runCurrent()
                job.cancel()
                job.join()

                // If the signal becomes ready after cancellation, a properly-cancelled waiter
                // must not resume and complete — that would mean CancellationException was
                // swallowed rather than re-thrown.
                ready.value = true
                testScheduler.runCurrent()

                completedNormally shouldBe false
                job.isCancelled shouldBe true
            }
        }

        // ---------------------------------------------------------------------------
        // awaitController — never binds within the bound surfaces a real, user-visible
        // failure via playbackManager.reportError, instead of a warn-log no-op.
        // ---------------------------------------------------------------------------

        test("awaitController reports a user-visible error and returns null when the controller never binds") {
            runTest {
                val writer = FakePlaybackStateWriter()
                val holder = MediaControllerHolder(context = stubContext, playbackManager = writer)

                val controller = holder.awaitController()

                controller shouldBe null
                writer.errorHistory.size shouldBe 1
                writer.errorHistory.first().isRecoverable shouldBe true
            }
        }

        // ---------------------------------------------------------------------------
        // Reconnect on disconnect — a dropped controller must not permanently brick
        // transport for the rest of the process, but must respect the refcounted
        // acquire()/release() lifecycle (no reconnect once fully released).
        // ---------------------------------------------------------------------------

        test("handleDisconnect reconnects while still acquired") {
            runTest {
                val writer = FakePlaybackStateWriter()
                var connectAttempts = 0
                val holder =
                    MediaControllerHolder(
                        context = stubContext,
                        playbackManager = writer,
                        connectionFactory = {
                            connectAttempts++
                            SettableFuture.create()
                        },
                    )

                holder.acquire()
                connectAttempts shouldBe 1

                holder.handleDisconnect()

                connectAttempts shouldBe 2
            }
        }

        test("handleDisconnect does not reconnect once fully released") {
            runTest {
                val writer = FakePlaybackStateWriter()
                var connectAttempts = 0
                val holder =
                    MediaControllerHolder(
                        context = stubContext,
                        playbackManager = writer,
                        connectionFactory = {
                            connectAttempts++
                            SettableFuture.create()
                        },
                    )

                holder.acquire()
                holder.release()
                connectAttempts shouldBe 1

                holder.handleDisconnect()

                connectAttempts shouldBe 1
            }
        }
    })

// ---------------------------------------------------------------------------
// File-private fake — nested data class cannot be declared inside a lambda
// ---------------------------------------------------------------------------

/** Records [PlaybackStateWriter] calls for assertion. */
private class FakePlaybackStateWriter : PlaybackStateWriter {
    val playingHistory = mutableListOf<Boolean>()
    val bufferingHistory = mutableListOf<Boolean>()
    val playbackStateHistory = mutableListOf<PlaybackState>()
    val speedHistory = mutableListOf<Float>()
    val positionHistory = mutableListOf<Long>()

    data class ErrorCall(
        val message: String,
        val isRecoverable: Boolean,
    )

    val errorHistory = mutableListOf<ErrorCall>()

    override fun setPlaying(playing: Boolean) {
        playingHistory += playing
    }

    override fun setBuffering(buffering: Boolean) {
        bufferingHistory += buffering
    }

    override fun setPlaybackState(state: PlaybackState) {
        playbackStateHistory += state
    }

    override fun updatePosition(positionMs: Long) {
        positionHistory += positionMs
    }

    val mediaItemPositionHistory = mutableListOf<Pair<Int, Long>>()

    override fun updatePositionFromMediaItem(
        mediaItemIndex: Int,
        positionInItemMs: Long,
    ) {
        mediaItemPositionHistory += mediaItemIndex to positionInItemMs
    }

    override fun updateSpeed(speed: Float) {
        speedHistory += speed
    }

    override fun reportError(
        message: String,
        isRecoverable: Boolean,
    ) {
        errorHistory += ErrorCall(message, isRecoverable)
    }
}
