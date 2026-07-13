package com.calypsan.listenup.client.playback

import android.content.ContextWrapper
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class MediaControllerHolderTest :
    FunSpec({
        /**
         * Context is only used inside [MediaControllerHolder.connect], which is not called
         * by any test here. A ContextWrapper(null) stub is safe: it will NPE only if accessed,
         * and none of these tests trigger connect().
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
                        scope = this.backgroundScope,
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
                        scope = this.backgroundScope,
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
                        scope = this.backgroundScope,
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
                        scope = this.backgroundScope,
                    )

                holder.playerListener.onPlaybackParametersChanged(PlaybackParameters(1.5f))

                writer.speedHistory shouldBe listOf(1.5f)
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
