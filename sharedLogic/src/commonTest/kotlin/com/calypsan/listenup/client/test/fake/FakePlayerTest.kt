package com.calypsan.listenup.client.test.fake

import com.calypsan.listenup.client.playback.AudioSegment
import com.calypsan.listenup.client.playback.PlaybackState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakePlayerTest {
    @Test
    fun loadRecordsCallAndDuration() =
        runTest {
            val player = FakePlayer()
            val segments =
                listOf(
                    AudioSegment(url = "s1", localPath = null, durationMs = 1_000L, offsetMs = 0L),
                    AudioSegment(url = "s2", localPath = null, durationMs = 2_000L, offsetMs = 1_000L),
                )

            player.load(segments)

            assertEquals(listOf(FakePlayer.Call.Load(segments)), player.calls)
            assertEquals(3_000L, player.durationMs.value, "durationMs must sum segment durations")
        }

    @Test
    fun playSetsStateAndRecordsCall() =
        runTest {
            val player = FakePlayer()

            player.play()

            assertEquals(PlaybackState.Playing, player.state.value)
            assertTrue(player.calls.contains(FakePlayer.Call.Play))
        }

    @Test
    fun pauseSetsState() =
        runTest {
            val player = FakePlayer()
            player.play()

            player.pause()

            assertEquals(PlaybackState.Paused, player.state.value)
            assertTrue(player.calls.contains(FakePlayer.Call.Pause))
        }

    @Test
    fun seekToUpdatesPositionAndRecordsCall() =
        runTest {
            val player = FakePlayer()

            player.seekTo(5_000L)

            assertEquals(5_000L, player.positionMs.value)
            assertTrue(player.calls.contains(FakePlayer.Call.SeekTo(5_000L)))
        }

    @Test
    fun setSpeedRecordsCall() =
        runTest {
            val player = FakePlayer()

            player.setSpeed(1.5f)

            assertTrue(player.calls.contains(FakePlayer.Call.SetSpeed(1.5f)))
        }

    @Test
    fun releaseResetsStateAndRecordsCall() =
        runTest {
            val player = FakePlayer()
            player.play()

            player.release()

            assertEquals(PlaybackState.Idle, player.state.value)
            assertTrue(player.calls.contains(FakePlayer.Call.Release))
        }
}
