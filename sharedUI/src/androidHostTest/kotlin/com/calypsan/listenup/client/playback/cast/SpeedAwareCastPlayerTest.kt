package com.calypsan.listenup.client.playback.cast

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SpeedAwareCastPlayerTest : FunSpec({
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
})
