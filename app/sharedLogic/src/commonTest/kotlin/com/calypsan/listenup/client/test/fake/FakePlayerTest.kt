package com.calypsan.listenup.client.test.fake

import com.calypsan.listenup.client.playback.AudioSegment
import com.calypsan.listenup.client.playback.PlaybackState
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class FakePlayerTest :
    FunSpec({
        test("loadRecordsCallAndDuration") {
            runTest {
                val player = FakePlayer()
                val segments =
                    listOf(
                        AudioSegment(url = "s1", localPath = null, durationMs = 1_000L, offsetMs = 0L),
                        AudioSegment(url = "s2", localPath = null, durationMs = 2_000L, offsetMs = 1_000L),
                    )

                player.load(segments)

                player.calls shouldBe listOf(FakePlayer.Call.Load(segments))
                withClue("durationMs must sum segment durations") { player.durationMs.value shouldBe 3_000L }
            }
        }

        test("playSetsStateAndRecordsCall") {
            runTest {
                val player = FakePlayer()

                player.play()

                player.state.value shouldBe PlaybackState.Playing
                player.calls.contains(FakePlayer.Call.Play) shouldBe true
            }
        }

        test("pauseSetsState") {
            runTest {
                val player = FakePlayer()
                player.play()

                player.pause()

                player.state.value shouldBe PlaybackState.Paused
                player.calls.contains(FakePlayer.Call.Pause) shouldBe true
            }
        }

        test("seekToUpdatesPositionAndRecordsCall") {
            runTest {
                val player = FakePlayer()

                player.seekTo(5_000L)

                player.positionMs.value shouldBe 5_000L
                player.calls.contains(FakePlayer.Call.SeekTo(5_000L)) shouldBe true
            }
        }

        test("setSpeedRecordsCall") {
            runTest {
                val player = FakePlayer()

                player.setSpeed(1.5f)

                player.calls.contains(FakePlayer.Call.SetSpeed(1.5f)) shouldBe true
            }
        }

        test("releaseResetsStateAndRecordsCall") {
            runTest {
                val player = FakePlayer()
                player.play()

                player.releasePlayer()

                player.state.value shouldBe PlaybackState.Idle
                player.calls.contains(FakePlayer.Call.Release) shouldBe true
            }
        }
    })
