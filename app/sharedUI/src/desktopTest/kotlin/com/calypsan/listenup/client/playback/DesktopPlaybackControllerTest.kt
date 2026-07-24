package com.calypsan.listenup.client.playback

import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest

/**
 * Delegation tests for [DesktopPlaybackController].
 *
 * These tests only exercise direct delegation to [AudioPlayer] (play/pause/seek/etc.);
 * none invoke `startPlayback(prepareResult)`, so the [PlaybackManager] dependency is
 * supplied as a never-invoked mokkery mock via [newController].
 *
 * Uses an inline [FakeAudioPlayer] (local to this test file) because [FakePlayer]
 * from shared's commonTest is not on the desktopTest classpath.
 */
class DesktopPlaybackControllerTest :
    FunSpec({
        // ---------------------------------------------------------------------------
        // acquire / release are no-ops
        // ---------------------------------------------------------------------------

        test("acquire is a no-op") {
            val player = FakeAudioPlayer()
            val sut = newController(player)

            sut.acquire() // must not throw or delegate to player

            player.calls shouldBe emptyList()
        }

        test("release is a no-op") {
            val player = FakeAudioPlayer()
            val sut = newController(player)

            sut.releasePlayer()

            player.calls shouldBe emptyList()
        }

        // ---------------------------------------------------------------------------
        // isReady is constant true
        // ---------------------------------------------------------------------------

        test("isReady is always true") {
            val sut = newController(FakeAudioPlayer())

            sut.isReady.value shouldBe true
        }

        // ---------------------------------------------------------------------------
        // play / pause / seekTo / setPlaybackSpeed delegate to AudioPlayer
        // ---------------------------------------------------------------------------

        test("play delegates to audioPlayer") {
            val player = FakeAudioPlayer()
            val sut = newController(player)

            sut.play()

            player.calls shouldBe listOf(PlayerCall.Play)
        }

        test("pause delegates to audioPlayer") {
            val player = FakeAudioPlayer()
            val sut = newController(player)

            sut.pause()

            player.calls shouldBe listOf(PlayerCall.Pause)
        }

        test("seekTo delegates to audioPlayer") {
            val player = FakeAudioPlayer()
            val sut = newController(player)

            sut.seekTo(45_000L)

            player.calls shouldBe listOf(PlayerCall.SeekTo(45_000L))
        }

        test("setPlaybackSpeed delegates to audioPlayer setSpeed") {
            val player = FakeAudioPlayer()
            val sut = newController(player)

            sut.setPlaybackSpeed(1.5f)

            player.calls shouldBe listOf(PlayerCall.SetSpeed(1.5f))
        }

        // ---------------------------------------------------------------------------
        // setMediaQueue
        // ---------------------------------------------------------------------------

        test("setMediaQueue maps items to AudioSegments and calls load") {
            runTest {
                val player = FakeAudioPlayer()
                val sut = newController(player)

                val items =
                    listOf(
                        PlaybackMediaItem("id-0", "url0", "/local/0", 60_000L, 0L, "Ch 1", "Author", "Book", null),
                        PlaybackMediaItem("id-1", "url1", null, 60_000L, 60_000L, "Ch 2", "Author", "Book", null),
                    )

                sut.setMediaQueue(items, 0L)

                val expectedSegments =
                    listOf(
                        AudioSegment(url = "url0", localPath = "/local/0", durationMs = 60_000L, offsetMs = 0L),
                        AudioSegment(url = "url1", localPath = null, durationMs = 60_000L, offsetMs = 60_000L),
                    )
                player.calls shouldBe listOf(PlayerCall.Load(expectedSegments))
            }
        }

        test("setMediaQueue seeks after load when startPositionMs is positive") {
            runTest {
                val player = FakeAudioPlayer()
                val sut = newController(player)

                val items =
                    listOf(
                        PlaybackMediaItem("id-0", "url0", null, 120_000L, 0L, "Ch 1", null, null, null),
                    )

                sut.setMediaQueue(items, 30_000L)

                player.calls.size shouldBe 2
                (player.calls[0] is PlayerCall.Load) shouldBe true
                player.calls[1] shouldBe PlayerCall.SeekTo(30_000L)
            }
        }

        test("setMediaQueue does not seek when startPositionMs is zero") {
            runTest {
                val player = FakeAudioPlayer()
                val sut = newController(player)

                val items =
                    listOf(
                        PlaybackMediaItem("id-0", "url0", null, 60_000L, 0L, "Ch 1", null, null, null),
                    )

                sut.setMediaQueue(items, 0L)

                player.calls.size shouldBe 1
                (player.calls[0] is PlayerCall.Load) shouldBe true
            }
        }

        // ---------------------------------------------------------------------------
        // stop / setVolume
        // ---------------------------------------------------------------------------

        test("stop pauses and seeks to zero") {
            val player = FakeAudioPlayer()
            val sut = newController(player)

            sut.stop()

            player.calls shouldBe listOf(PlayerCall.Pause, PlayerCall.SeekTo(0L))
        }

        test("setVolume is a no-op") {
            val player = FakeAudioPlayer()
            val sut = newController(player)

            sut.setVolume(0.5f)

            player.calls shouldBe emptyList()
        }
    })

// ---------------------------------------------------------------------------
// File-private helpers — sealed interface cannot be declared inside a lambda
// ---------------------------------------------------------------------------

/** Tracks calls made to [FakeAudioPlayer]. */
private sealed interface PlayerCall {
    data object Play : PlayerCall

    data object Pause : PlayerCall

    data class SeekTo(
        val positionMs: Long,
    ) : PlayerCall

    data class SetSpeed(
        val speed: Float,
    ) : PlayerCall

    data class Load(
        val segments: List<AudioSegment>,
    ) : PlayerCall
}

private class FakeAudioPlayer : AudioPlayer {
    override val state: StateFlow<PlaybackState> = MutableStateFlow(PlaybackState.Idle)
    override val positionMs: StateFlow<Long> = MutableStateFlow(0L)
    override val durationMs: StateFlow<Long> = MutableStateFlow(0L)

    private val _calls = mutableListOf<PlayerCall>()
    val calls: List<PlayerCall> get() = _calls.toList()

    override fun play() {
        _calls += PlayerCall.Play
    }

    override fun pause() {
        _calls += PlayerCall.Pause
    }

    override fun seekTo(positionMs: Long) {
        _calls += PlayerCall.SeekTo(positionMs)
    }

    override fun setSpeed(speed: Float) {
        _calls += PlayerCall.SetSpeed(speed)
    }

    override suspend fun load(segments: List<AudioSegment>) {
        _calls += PlayerCall.Load(segments)
    }

    override fun releasePlayer() {}
}

/** Constructs a [DesktopPlaybackController] with a never-invoked [PlaybackManager] mock. */
private fun newController(player: AudioPlayer): DesktopPlaybackController = DesktopPlaybackController(audioPlayer = player, playbackManager = mock())
