package com.calypsan.listenup.client.playback.cast

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SpeedAwareCastPlayerTest :
    FunSpec({
        test("setPlaybackParameters routes the speed to the rate setter and reports it back") {
            var lastRate = -1.0
            val rateSetter = CastRateSetter { rate -> lastRate = rate }
            val player = SpeedAwareCastPlayer(mock<Player>(), rateSetter)

            player.setPlaybackParameters(PlaybackParameters(1.5f))

            lastRate shouldBe 1.5
            player.playbackParameters.speed shouldBe 1.5f
        }

        test("setPlaybackSpeed also routes through the rate setter") {
            var lastRate = -1.0
            val player = SpeedAwareCastPlayer(mock<Player>(), CastRateSetter { lastRate = it })
            player.setPlaybackSpeed(2.0f)
            lastRate shouldBe 2.0
            player.playbackParameters.speed shouldBe 2.0f
        }

        // Note: getAvailableCommands() can't be unit-tested in this source set — it builds a
        // Player.Commands via buildUpon(), whose FlagSet touches android.util.SparseBooleanArray,
        // which is "not mocked" here (androidHostTest runs without Robolectric). isCommandAvailable
        // is the primary query path the UI uses, and it IS covered below.
        test("advertises COMMAND_SET_SPEED_AND_PITCH so the UI speed control stays enabled") {
            val player = SpeedAwareCastPlayer(mock<Player>(), CastRateSetter { })
            player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH) shouldBe true
        }

        test("isCommandAvailable still delegates for non-speed commands") {
            val delegate = mock<Player>()
            every { delegate.isCommandAvailable(Player.COMMAND_PLAY_PAUSE) } returns true
            val player = SpeedAwareCastPlayer(delegate, CastRateSetter { })
            player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE) shouldBe true
        }

        test("reports DEFAULT (1.0x) before any speed is set") {
            val player = SpeedAwareCastPlayer(mock<Player>(), CastRateSetter { })
            player.playbackParameters shouldBe PlaybackParameters.DEFAULT
        }
    })
